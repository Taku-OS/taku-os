#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../../.."
source scripts/android-env.sh
source scripts/kotlin-env.sh
FIXTURE=system/audio-engine/tests/fixture
OUTPUT=system/audio-engine/out/call-fixture
mkdir -p "$OUTPUT/dex"
"$KOTLINC" "$FIXTURE/CallFixture.kt" -jvm-target 17 -include-runtime -classpath "$ANDROID_JAR" -d "$OUTPUT/classes.jar"
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$TOOLS/aapt2" link -o "$OUTPUT/unsigned.apk" -I "$ANDROID_JAR" --manifest "$FIXTURE/AndroidManifest.xml"
(cd "$OUTPUT/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUTPUT/unsigned.apk" "$OUTPUT/aligned.apk"
"$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$OUTPUT/CallFixture.apk" "$OUTPUT/aligned.apk"
