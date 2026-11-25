#!/system/bin/sh
set -eu

if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo 'Usage: sh /data/local/agentos/light-app-lab/menu-app/build.sh PATH_TO_MENU_JSON' >&2
  exit 2
fi

LAB=/data/local/agentos/light-app-lab
KIT="$LAB/menu-app"
TOOLS="$LAB/toolchain"
case "$1" in
  /*) INPUT="$1" ;;
  *) INPUT="$PWD/$1" ;;
esac
export PATH="$LAB/native:/apex/com.android.art/bin:/system/bin:/system_ext/bin"

for prerequisite in "$KIT/AndroidManifest.xml" "$KIT/template/index.html" \
  "$KIT/src/MenuData.java" "$KIT/src/MainActivity.java" "$KIT/src/RecommendationLinks.java" \
  "$LAB/build-native.sh" "$TOOLS/ecj.jar" "$TOOLS/d8.dex" "$TOOLS/android.jar" \
  "$TOOLS/android.classes.jar" "$TOOLS/apksigner.dex" "$TOOLS/debug.pk8" "$TOOLS/debug.x509.pem"; do
  if [ ! -r "$prerequisite" ]; then
    echo "Missing Menu App build prerequisite: $prerequisite" >&2
    exit 1
  fi
done
for command in dalvikvm app_process aapt aapt2 zipalign; do
  command -v "$command" >/dev/null || { echo "Missing Android build tool: $command" >&2; exit 1; }
done

ID=$(od -An -N16 -v -tx1 /dev/urandom | tr -d '[:space:]')
case "$ID" in ''|*[!0-9a-f]*) echo 'Could not generate a valid menu ID' >&2; exit 1;; esac
if [ "${#ID}" -ne 32 ]; then
  echo 'Could not generate a 128-bit menu ID' >&2
  exit 1
fi
PACKAGE="org.agentos.generated.menu.m$ID"
umask 077
mkdir -p "$LAB/generated"
PROJECT="$LAB/generated/$PACKAGE"
mkdir "$PROJECT"
CLEANUP_INSTALL=false
cleanup() {
  if [ "$CLEANUP_INSTALL" = true ]; then
    pm uninstall --user current "$PACKAGE" >&2 || true
  fi
  rm -rf "$PROJECT"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

mkdir -p "$PROJECT/assets" "$PROJECT/src" "$PROJECT/validation/classes" "$PROJECT/validation/dex"
cp -R "$KIT/template/." "$PROJECT/assets/"
dalvikvm -Xmx512m -Djava.io.tmpdir="$PROJECT/validation" \
  -cp "$TOOLS/ecj.jar" org.eclipse.jdt.internal.compiler.batch.Main -proc:none -16 \
  -cp "$TOOLS/android.classes.jar" -d "$PROJECT/validation/classes" \
  "$KIT/src/MenuData.java" "$KIT/src/RecommendationLinks.java" >&2
dalvikvm -Xmx512m -cp "$TOOLS/d8.dex" com.android.tools.r8.D8 --lib "$TOOLS/android.jar" \
  --min-api 36 --output "$PROJECT/validation/dex" \
  $(find "$PROJECT/validation/classes" -name '*.class') >&2
chmod a-w "$PROJECT/validation/dex/classes.dex"
CLASSPATH="$PROJECT/validation/dex/classes.dex" app_process /system/bin \
  org.agentos.generated.menu.MenuData "$INPUT" "$PROJECT" >&2

sed "s/package=\"org\.agentos\.generated\.menu\"/package=\"$PACKAGE\"/" \
  "$KIT/AndroidManifest.xml" > "$PROJECT/AndroidManifest.xml"
for source in MainActivity RecommendationLinks; do
  sed "s/^package org\.agentos\.generated\.menu;/package $PACKAGE;/" \
    "$KIT/src/$source.java" > "$PROJECT/src/$source.java"
done
sh "$LAB/build-native.sh" --install-only "$PROJECT" >&2
CLEANUP_INSTALL=true

APK_PACKAGE=$(LD_LIBRARY_PATH="$LAB/native/lib" aapt dump badging "$PROJECT/build/app.apk" | \
  sed -n "s/^package: name='\([^']*\)'.*/\1/p")
if [ "$APK_PACKAGE" != "$PACKAGE" ]; then
  echo 'The generated APK package does not match' >&2
  exit 1
fi
pm list packages --user current "$PACKAGE" | grep -Fxq "package:$PACKAGE" || {
  echo 'The generated menu APK was not found after installation' >&2
  exit 1
}
RESOLVED=$(cmd package resolve-activity --brief --user current \
  -a org.agentos.generated.menu.OPEN -c android.intent.category.DEFAULT -p "$PACKAGE" | tail -n 1)
case "$RESOLVED" in
  "$PACKAGE/.MainActivity"|"$PACKAGE/$PACKAGE.MainActivity") ;;
  *) echo 'The installed menu cannot be opened from Chat' >&2; exit 1;;
esac
CLEANUP_INSTALL=false
printf 'taku-menu-app:%s\n' "$PACKAGE"
