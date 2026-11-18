#!/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later
set -euo pipefail
cd "$(dirname "$0")/../../.."
export TAKU_BUILD_TOOLS="${TAKU_BUILD_TOOLS:-36.1.0}"
source scripts/android-env.sh
out="$PWD/out/inputmethod/fixture"
mkdir -p "$out/classes" "$out/dex"
"$TOOLS/aapt2" link -o "$out/resources.apk" -I "$ANDROID_JAR" --manifest system/inputmethod/tests/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$out/classes" system/inputmethod/tests/InputActivity.java
"$JDK/bin/jar" --create --file "$out/classes.jar" -C "$out/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/dex" "$out/classes.jar"
cp "$out/resources.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
# Test fixture only. Its local debug certificate is unrelated to product/ROM signing.
if [ ! -f "$out/fixture.jks" ]; then
 "$JDK/bin/keytool" -genkeypair -keystore "$out/fixture.jks" -storepass android -keypass android \
  -alias fixture -dname 'CN=Taku IME Fixture' -keyalg RSA -validity 365 >/dev/null 2>&1
fi
"$TOOLS/apksigner" sign --ks "$out/fixture.jks" --ks-pass pass:android --out "$out/fixture.apk" "$out/aligned.apk"

probe="$PWD/out/inputmethod/probe"
mkdir -p "$probe/classes" "$probe/dex"
"$TOOLS/aapt2" link -o "$probe/resources.apk" -I "$ANDROID_JAR" --manifest system/inputmethod/tests/probe/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$probe/classes" system/inputmethod/tests/probe/UiProbe.java
"$JDK/bin/jar" --create --file "$probe/classes.jar" -C "$probe/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$probe/dex" "$probe/classes.jar"
cp "$probe/resources.apk" "$probe/unsigned.apk"
(cd "$probe/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$probe/unsigned.apk" "$probe/aligned.apk"
"$TOOLS/apksigner" sign --ks "$out/fixture.jks" --ks-pass pass:android --out "$probe/probe.apk" "$probe/aligned.apk"
