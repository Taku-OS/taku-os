#!/bin/bash
# Pure native-record projection checks on the dedicated AVD. No model or user data.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
export ADB_SERVER_SOCKET=tcp:5037
SERIAL=emulator-5634
[[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" == Taku_ImageIntegration_20261010 ]] || exit 1
OUTPUT=out/native-conversation-tests
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" \
  system/assistant/src/org/agentos/assistant/AgentConversation.java \
  system/assistant/broker/org/agentos/lab/ComposerInput.java \
  system/assistant/src/org/agentos/assistant/NativeToolPresentation.java \
  system/assistant/tests/org/agentos/assistant/NativeConversationTest.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
REMOTE=/data/local/tmp/taku-native-conversation-tests.jar
trap '"$ADB" -s "$SERIAL" shell rm -f "$REMOTE"' EXIT
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" "$REMOTE"
"$ADB" -s "$SERIAL" shell "CLASSPATH=$REMOTE app_process /system/bin org.agentos.assistant.NativeConversationTest" | tee "$OUTPUT/result.txt"
grep -q '^PASS NativeConversationTest:' "$OUTPUT/result.txt"
