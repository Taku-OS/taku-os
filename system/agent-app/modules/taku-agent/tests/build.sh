#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../../../.."
source scripts/android-env.sh
module=system/agent-app/modules/taku-agent/tests
output=system/agent-app/out/conversation-host-probe
mkdir -p "$output/classes" "$output/dex"
"$JDK/bin/javac" --release 17 -classpath "$ANDROID_JAR" -d "$output/classes" "$module/ConversationHostProbe.java"
"$JDK/bin/jar" cf "$output/classes.jar" -C "$output/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$output/dex" "$output/classes.jar"
"$TOOLS/aapt2" link -o "$output/unsigned.apk" -I "$ANDROID_JAR" --manifest "$module/AndroidManifest.xml"
(cd "$output/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$output/unsigned.apk" "$output/aligned.apk"
"$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$output/ConversationHostTests.apk" "$output/aligned.apk"
echo "Built $output/ConversationHostTests.apk; no device changed."
