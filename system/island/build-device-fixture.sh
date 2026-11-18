#!/bin/bash
# Explicit integration fixture APK only. No device operations or production packaging.
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
output=../../out/island-device-fixture
mkdir -p "$output/classes" "$output/dex"
"$TOOLS/aapt2" link -o "$output/resources.apk" -I "$ANDROID_JAR" --manifest device-tests/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$output/classes" \
  src/org/agentos/island/IslandContentProtocol.java device-tests/Fixture.java
"$JDK/bin/jar" --create --file "$output/classes.jar" -C "$output/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$output/dex" "$output/classes.jar"
cp "$output/resources.apk" "$output/unsigned.apk"
(cd "$output/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$output/unsigned.apk" "$output/aligned.apk"
python3 ../../scripts/aosp-test-keys.py ../../out/development-signing
"$TOOLS/apksigner" sign --key ../../out/development-signing/platform.pk8 \
  --cert ../../out/development-signing/platform.x509.pem --out "$output/IslandFixture.apk" "$output/aligned.apk"
"$TOOLS/apksigner" verify "$output/IslandFixture.apk"
echo "Built $output/IslandFixture.apk; no device was changed."
