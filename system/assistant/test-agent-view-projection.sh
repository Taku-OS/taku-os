#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")/../.."
source scripts/android-env.sh
SERIAL="${TAKU_TEST_SERIAL:-}"
[[ -n "$SERIAL" ]] || { echo 'Set TAKU_TEST_SERIAL to the approved Android test device.' >&2; exit 2; }
OUTPUT=system/assistant/out/agent-view-projection-tests
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" \
  system/assistant/broker/org/agentos/lab/AgentViewProjection.java \
  system/assistant/tests/org/agentos/lab/AgentViewProjectionTest.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
REMOTE=/data/local/tmp/taku-agent-view-projection-tests.jar
trap '"$ADB" -s "$SERIAL" shell rm -f "$REMOTE"' EXIT
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" "$REMOTE"
"$ADB" -s "$SERIAL" shell "CLASSPATH=$REMOTE app_process /system/bin org.agentos.lab.AgentViewProjectionTest" \
  | tee "$OUTPUT/result.txt"
grep -q '^PASS AgentViewProjectionTest:' "$OUTPUT/result.txt"
