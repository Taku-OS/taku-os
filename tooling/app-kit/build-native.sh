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
# The app source is generated on the device; no precompiled business implementation.
dalvikvm -Xmx512m -Djava.io.tmpdir="$PROJECT/build" -cp "$TOOLS/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main -proc:none -16 -cp "$TOOLS/android.classes.jar" -d build/classes $(find src -name '*.java')
dalvikvm -Xmx512m -cp "$TOOLS/d8.dex" com.android.tools.r8.D8 --lib "$TOOLS/android.jar" --min-api 30 --output build/dex $(find build/classes -name '*.class')
set -- -I "$TOOLS/android.jar" --manifest AndroidManifest.xml -o build/unsigned.apk
if [ -d assets ]; then
  set -- "$@" -A assets
fi
if [ -d res ]; then
  native_tool aapt2 compile --dir res -o build/resources.zip
  set -- "$@" build/resources.zip
fi
native_tool aapt2 link "$@"
cp build/dex/classes.dex build/classes.dex
(cd build && native_tool aapt add -f unsigned.apk classes.dex)
native_tool zipalign -f 4 build/unsigned.apk build/aligned.apk
dalvikvm -cp "$TOOLS/apksigner.dex" com.android.apksigner.ApkSignerTool sign --key "$TOOLS/debug.pk8" --cert "$TOOLS/debug.x509.pem" --out build/app.apk build/aligned.apk
dalvikvm -cp "$TOOLS/apksigner.dex" com.android.apksigner.ApkSignerTool verify --verbose build/app.apk
ACTIVITY=$(native_tool aapt dump badging build/app.apk | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p")
PACKAGE=$(native_tool aapt dump badging build/app.apk | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
test -n "$ACTIVITY" && test -n "$PACKAGE"
if [ "$INSTALL_ONLY" = true ]; then
  pm install --user current build/app.apk
else
  pm install -r build/app.apk
  am start --display "$DISPLAY" -n "$PACKAGE/$ACTIVITY"
fi
sha256sum build/app.apk
