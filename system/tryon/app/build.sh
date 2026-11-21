#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
APP_ROOT="$(cd "$(dirname "$0")" && pwd)"
MODULE_ROOT="$(cd "$APP_ROOT/.." && pwd)"
REPO_ROOT="$(cd "$MODULE_ROOT/../.." && pwd)"
source "$REPO_ROOT/scripts/android-env.sh"
NATIVE_DEPS="${TAKU_TRYON_NATIVE_DEPS:-$REPO_ROOT/out/tryon-native-deps}"
python3 "$APP_ROOT/prepare-native-deps.py" --output "$NATIVE_DEPS" >/dev/null
BUILD_DIR="$REPO_ROOT/out/tryon/app"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/assets/native-dependencies"
find "$BUILD_DIR/classes" -type f -delete
cp "$NATIVE_DEPS/verified.json" "$BUILD_DIR/assets/native-dependencies/"
cp -R "$APP_ROOT/third-party" "$BUILD_DIR/assets/native-dependencies/"
"$TOOLS/aapt2" compile --dir "$APP_ROOT/res" -o "$BUILD_DIR/resources.zip"
"$TOOLS/aapt2" link -o "$BUILD_DIR/resources.apk" -I "$ANDROID_JAR" --manifest "$APP_ROOT/AndroidManifest.xml" -A "$BUILD_DIR/assets" "$BUILD_DIR/resources.zip"
find "$APP_ROOT/src" "$MODULE_ROOT/contract" "$MODULE_ROOT/common" -name '*.java' > "$BUILD_DIR/sources.txt"
NATIVE_CLASSPATH="$(cat "$NATIVE_DEPS/classpath.txt")"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$NATIVE_CLASSPATH" -d "$BUILD_DIR/classes" @"$BUILD_DIR/sources.txt"
"$JDK/bin/jar" --create --file "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .
NATIVE_JARS=()
while IFS= read -r NATIVE_JAR; do NATIVE_JARS+=("$NATIVE_JAR"); done < "$NATIVE_DEPS/dex-inputs.txt"
find "$BUILD_DIR/dex" -name 'classes*.dex' -delete
"$TOOLS/d8" --min-api 33 --lib "$ANDROID_JAR" --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar" "${NATIVE_JARS[@]}"
cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/unsigned.apk" classes*.dex)
(cd "$NATIVE_DEPS/native" && zip -q -0 -r "$BUILD_DIR/unsigned.apk" lib)
python3 - "$BUILD_DIR/unsigned.apk" "${NATIVE_JARS[@]}" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1], 'a') as output:
    for path in sys.argv[2:]:
        with zipfile.ZipFile(path) as jar:
            for name in jar.namelist():
                if name.startswith('META-INF/services/') and not name.endswith('/'):
                    output.writestr(name,jar.read(name))
PY
"$TOOLS/zipalign" -P 16 -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"
rm -f "$BUILD_DIR/unsigned.apk"
if [ -n "${TAKU_TRYON_KEYSTORE:-}" ]; then
  SIGNING="$TAKU_TRYON_KEYSTORE"
  [ -f "$SIGNING" ] || { echo 'Configured signing key is missing.' >&2; exit 1; }
  "$TOOLS/apksigner" sign --ks "$SIGNING" --ks-key-alias "${TAKU_TRYON_KEY_ALIAS:-tryon-development}" --ks-pass "${TAKU_TRYON_KEYSTORE_PASS_SPEC:-pass:android}" --out "$BUILD_DIR/TakuTryOn.apk" "$BUILD_DIR/aligned.apk"
else
  # Match Home's normal development build; the app keeps its ordinary UID.
  # This pinned public AOSP key is development material, never a production identity.
  SIGNING="$REPO_ROOT/out/development-signing"
  python3 "$REPO_ROOT/scripts/aosp-test-keys.py" "$SIGNING"
  "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" --out "$BUILD_DIR/TakuTryOn.apk" "$BUILD_DIR/aligned.apk"
fi
"$TOOLS/apksigner" verify "$BUILD_DIR/TakuTryOn.apk"
rm -f "$BUILD_DIR/aligned.apk"
echo "Built $BUILD_DIR/TakuTryOn.apk (build only; no device, camera or cloud request)"
