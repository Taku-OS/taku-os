#!/bin/bash
# Offline Jev API contract checks. Uses a fake transport; never contacts OpenRouter.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=emulator-5634
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = Taku_ImageIntegration_20261010 ] || exit 1
OUTPUT=../../out/agent-jev-tests
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" \
  broker/org/agentos/lab/JevDecisionClient.java tests/org/agentos/lab/JevDecisionClientTest.java \
  src/org/agentos/assistant/JevCredentialStore.java tests/org/agentos/assistant/JevCredentialStoreTest.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" /data/local/tmp/taku-jev-tests.jar >/dev/null
"$ADB" -s "$SERIAL" shell 'CLASSPATH=/data/local/tmp/taku-jev-tests.jar app_process /system/bin org.agentos.lab.JevDecisionClientTest'
"$ADB" -s "$SERIAL" shell 'CLASSPATH=/data/local/tmp/taku-jev-tests.jar app_process /system/bin org.agentos.assistant.JevCredentialStoreTest'
