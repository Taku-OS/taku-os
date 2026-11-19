#!/bin/bash
# Production capsule renderer in an isolated instrumentation APK. No SystemUI installation.
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
: "${KOTLINC:?Set KOTLINC to the installed Kotlin compiler}"
: "${KOTLIN_STDLIB:?Set KOTLIN_STDLIB to the matching cached Kotlin stdlib jar}"
output=../../out/island-capsule-tests
mkdir -p "$output/classes" "$output/dex"
"$TOOLS/aapt2" link -o "$output/resources.apk" -I "$ANDROID_JAR" --manifest capsule-tests/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -d "$output/classes" src/org/agentos/island/DotMatrixLoader.java
"$KOTLINC" -jvm-target 17 -classpath "$ANDROID_JAR:$output/classes" -d "$output/classes"     src/com/android/systemui/taku/island/IslandActivity.kt     src/com/android/systemui/taku/island/IslandCapsuleView.kt \
    src/com/android/systemui/taku/island/IslandStatusGlyph.kt \
    src/com/android/systemui/taku/island/IslandProgressCard.kt capsule-tests/CapsuleRenderTests.kt
"$JDK/bin/jar" --create --file "$output/classes.jar" -C "$output/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$output/dex" "$output/classes.jar" "$KOTLIN_STDLIB"
cp "$output/resources.apk" "$output/unsigned.apk"
(cd "$output/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$output/unsigned.apk" "$output/aligned.apk"
python3 ../../scripts/aosp-test-keys.py ../../out/development-signing
"$TOOLS/apksigner" sign --key ../../out/development-signing/platform.pk8     --cert ../../out/development-signing/platform.x509.pem --out "$output/CapsuleRenderTests.apk" "$output/aligned.apk"
"$TOOLS/apksigner" verify "$output/CapsuleRenderTests.apk"
echo "Built $output/CapsuleRenderTests.apk; no device was changed."
