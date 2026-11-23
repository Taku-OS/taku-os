#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/android-env.sh
source scripts/kotlin-env.sh
MODULE=system/audio-engine
mkdir -p "$MODULE/out/test-dex"
NETWORK_CP="$MODULE/out/network/okhttp-4.12.0.jar:$MODULE/out/network/okio-jvm-3.6.0.jar:$MODULE/out/network/msgpack-core-0.9.10.jar"
"$KOTLINC" "$MODULE"/tests/android/*.kt -jvm-target 17 -Xfriend-paths="$MODULE/out/classes.jar" -classpath "$ANDROID_JAR:$MODULE/out/classes.jar:$MODULE/out/sdk/classes.jar:system/assistant/out/classes.jar:$NETWORK_CP" -d "$MODULE/out/test-classes.jar"
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --classpath "$MODULE/out/classes.jar" --classpath system/assistant/out/classes.jar --classpath "$MODULE/out/network/okhttp-4.12.0.jar" --classpath "$MODULE/out/network/okio-jvm-3.6.0.jar" --output "$MODULE/out/test-dex" "$MODULE/out/test-classes.jar"
"$TOOLS/aapt2" link -o "$MODULE/out/test-unsigned.apk" -I "$ANDROID_JAR" --manifest "$MODULE/tests/AndroidManifest.xml"
(cd "$MODULE/out/test-dex" && zip -q ../test-unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$MODULE/out/test-unsigned.apk" "$MODULE/out/test-aligned.apk"
"$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$MODULE/out/AudioEngineTests.apk" "$MODULE/out/test-aligned.apk"
