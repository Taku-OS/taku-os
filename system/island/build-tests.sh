#!/bin/bash
# Builds only the isolated parser instrumentation APK, not SystemUI or a ROM.
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
output=../../out/island-parser-tests
mkdir -p "$output/classes" "$output/dex"
"$TOOLS/aapt2" link -o "$output/resources.apk" -I "$ANDROID_JAR" --manifest tests/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$output/classes" \
    src/com/android/systemui/taku/island/IslandPayload.java tests/IslandParserTests.java
"$JDK/bin/jar" --create --file "$output/classes.jar" -C "$output/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$output/dex" "$output/classes.jar"
cp "$output/resources.apk" "$output/unsigned.apk"
(cd "$output/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$output/unsigned.apk" "$output/aligned.apk"
python3 ../../scripts/aosp-test-keys.py ../../out/development-signing
"$TOOLS/apksigner" sign --key ../../out/development-signing/platform.pk8 \
    --cert ../../out/development-signing/platform.x509.pem \
    --out "$output/TakuIslandParserTests.apk" "$output/aligned.apk"
"$TOOLS/apksigner" verify "$output/TakuIslandParserTests.apk"
echo "Built $output/TakuIslandParserTests.apk; no device was changed."
