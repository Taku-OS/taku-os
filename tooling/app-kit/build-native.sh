#!/system/bin/sh
# Native Android app compiler for the explicitly prepared development environment.
set -eu
LAB=/data/local/agentos/light-app-lab
TOOLS="$LAB/toolchain"
INSTALL_ONLY=false
if [ "${1:-}" = --install-only ]; then
  INSTALL_ONLY=true
  shift
fi
PROJECT="${1:?Pass the generated application project directory}"
if [ "$INSTALL_ONLY" = false ]; then
  DISPLAY="${TAKU_APP_DISPLAY:?Select the development display explicitly}"
  case "$DISPLAY" in ''|*[!0-9]*) echo 'Invalid display ID' >&2; exit 2;; esac
  cmd display get-displays --ids-only | grep -qx "$DISPLAY" || { echo 'Display unavailable' >&2; exit 1; }
fi
export PATH="/apex/com.android.art/bin:/system/bin:/system_ext/bin"
native_tool() {
  tool="$1"
  shift
  LD_LIBRARY_PATH="$LAB/native/lib" "$LAB/native/$tool" "$@"
}
cd "$PROJECT"
mkdir -p build/classes build/dex build/resources
set -- -I "$TOOLS/android.jar" --manifest AndroidManifest.xml -o build/unsigned.apk
if [ -d assets ]; then
  set -- "$@" -A assets
fi
if [ -d res ]; then
  native_tool aapt2 compile --dir res -o build/resources.zip
  set -- "$@" build/resources.zip
fi
native_tool aapt2 link "$@"
# Target the device's API level. Play Protect blocks apps "built for an older version of Android"
# (INSTALL_FAILED_VERIFICATION_FAILURE, BUG-0043), and raising the target silently would change
# behaviour the app's code was written for, so the manifest must state it. Read from the linked
# manifest, so any valid XML spelling (quotes, spacing, line breaks) yields the same number.
DEVICE_SDK=$(getprop ro.build.version.sdk)
TARGET_SDK=$(native_tool aapt2 dump xmltree --file AndroidManifest.xml build/unsigned.apk |
  sed -n 's/.*android:targetSdkVersion([^)]*)=\([0-9][0-9]*\).*/\1/p' | head -n 1)
case "$TARGET_SDK" in ''|*[!0-9]*)
  echo "BUILD_STATUS=target_sdk_missing DEVICE_SDK=$DEVICE_SDK: add <uses-sdk android:targetSdkVersion=\"$DEVICE_SDK\"/> to AndroidManifest.xml" >&2; exit 2;;
esac
if [ "$TARGET_SDK" -lt "$DEVICE_SDK" ]; then
  echo "BUILD_STATUS=target_sdk_too_old TARGET_SDK=$TARGET_SDK DEVICE_SDK=$DEVICE_SDK: set android:targetSdkVersion=\"$DEVICE_SDK\" and adapt to its permission and foreground-service rules" >&2
  exit 2
fi
# The app source is generated on the device; no precompiled business implementation.
dalvikvm -Xmx512m -Djava.io.tmpdir="$PROJECT/build" -cp "$TOOLS/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main -proc:none -16 -cp "$TOOLS/android.classes.jar" -d build/classes $(find src -name '*.java')
dalvikvm -Xmx512m -cp "$TOOLS/d8.dex" com.android.tools.r8.D8 --lib "$TOOLS/android.jar" --min-api 30 --output build/dex $(find build/classes -name '*.class')
cp build/dex/classes.dex build/classes.dex
(cd build && native_tool aapt add -f unsigned.apk classes.dex)
native_tool zipalign -f 4 build/unsigned.apk build/aligned.apk
dalvikvm -cp "$TOOLS/apksigner.dex" com.android.apksigner.ApkSignerTool sign --key "$TOOLS/debug.pk8" --cert "$TOOLS/debug.x509.pem" --out build/app.apk build/aligned.apk
dalvikvm -cp "$TOOLS/apksigner.dex" com.android.apksigner.ApkSignerTool verify --verbose build/app.apk
ACTIVITY=$(native_tool aapt dump badging build/app.apk | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p")
PACKAGE=$(native_tool aapt dump badging build/app.apk | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
VERSION=$(native_tool aapt dump badging build/app.apk | sed -n "s/^package: .*versionCode='\([0-9]*\)'.*/\1/p")
test -n "$ACTIVITY" && test -n "$PACKAGE"
# Install, reporting one machine-readable INSTALL_STATUS line (BUG-0043): waiting_for_user while a
# Play Protect / installer page has focus, then installed | cancelled | blocked | target_sdk_too_old
# | failed. Acting on that page is not an install: only a confirmed package with this version is.
# waiting_for_user needs evidence that the page belongs to this install: a verifier or installer
# window (not an ordinary Play Store page) that took focus after this install began, while it is
# still blocked.
focus() { dumpsys window 2>/dev/null | sed -n 's/.*mCurrentFocus=Window{[^ ]* [^ ]* \([^}]*\)}.*/\1/p' | head -n 1; }
BEFORE_FOCUS=$(focus)
if [ "$INSTALL_ONLY" = true ]; then
  set -- --user current
else
  set -- -r
fi
pm install "$@" build/app.apk > build/install.log 2>&1 &
INSTALLER=$!
WAITING=0
while kill -0 "$INSTALLER" 2>/dev/null; do
  NOW_FOCUS=$(focus)
  if [ "$WAITING" = 0 ] && [ -n "$NOW_FOCUS" ] && [ "$NOW_FOCUS" != "$BEFORE_FOCUS" ] &&
    printf '%s\n' "$NOW_FOCUS" | grep -Ei '^(com\.android\.vending/.*verif|com\.google\.android\.gms/.*(verif|security)|[a-z.]*packageinstaller/|[a-z.]*permissioncontroller/)' >/dev/null; then
    echo "INSTALL_STATUS=waiting_for_user PACKAGE=$PACKAGE: a Play Protect or installer page is on screen; ask the user to respond there" >&2
    WAITING=1
  fi
  sleep 1
done
INSTALL_RESULT=0
wait "$INSTALLER" || INSTALL_RESULT=$?
cat build/install.log
CODE=$(sed -n 's/.*\(INSTALL_[A-Z_]*\).*/\1/p' build/install.log | head -n 1)
if [ "$INSTALL_RESULT" = 0 ] && grep -q '^Success' build/install.log; then
  INSTALLED=$(dumpsys package "$PACKAGE" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)
  if [ -z "$VERSION" ] || [ "$INSTALLED" = "$VERSION" ]; then
    echo "INSTALL_STATUS=installed PACKAGE=$PACKAGE VERSION=$INSTALLED"
  else
    echo "INSTALL_STATUS=failed PACKAGE=$PACKAGE CODE=VERSION_MISMATCH EXPECTED=$VERSION INSTALLED=$INSTALLED" >&2; exit 1
  fi
else
  case "$CODE" in
    INSTALL_FAILED_ABORTED|INSTALL_FAILED_USER_RESTRICTED)
      echo "INSTALL_STATUS=cancelled PACKAGE=$PACKAGE CODE=$CODE: the user or a restriction stopped it; do not retry without asking" >&2; exit 4;;
    INSTALL_FAILED_VERIFICATION_FAILURE|INSTALL_FAILED_VERIFICATION_TIMEOUT)
      echo "INSTALL_STATUS=blocked PACKAGE=$PACKAGE CODE=$CODE: package verification (Play Protect) refused it; read its reason on screen, fix the cause (e.g. targetSdkVersion) and rebuild; an 'install anyway' answer needs a new install" >&2; exit 3;;
    INSTALL_FAILED_DEPRECATED_SDK_VERSION)
      echo "INSTALL_STATUS=target_sdk_too_old PACKAGE=$PACKAGE CODE=$CODE DEVICE_SDK=$DEVICE_SDK" >&2; exit 3;;
    *)
      echo "INSTALL_STATUS=failed PACKAGE=$PACKAGE CODE=${CODE:-UNKNOWN}" >&2; exit 1;;
  esac
fi
if [ "$INSTALL_ONLY" = false ]; then
  am start --display "$DISPLAY" -n "$PACKAGE/$ACTIVITY"
fi
sha256sum build/app.apk
