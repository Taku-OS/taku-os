#!/bin/bash
# Local filesystem and Expo acceptance on an isolated emulator with installed components.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
source scripts/android-env.sh
source scripts/compile-jvm.sh
serial=${TAKU_SERIAL:-emulator-5634}
avd=${TAKU_AVD:-Taku_ImageIntegration_20261010}
[[ "$serial" =~ ^emulator-[0-9]+$ && "$("$ADB" -s "$serial" emu avd name | head -1 | tr -d '\r')" == "$avd" ]] || {
 echo 'Dedicated integration AVD required.' >&2; exit 1;
}
thread=${1:-}
[[ "$thread" =~ ^[a-f0-9-]{36}$ ]] || { echo 'Provide an existing native Harness test thread ID.' >&2; exit 1; }
[[ "$#" == 1 ]] || { echo 'Usage: test-harness.sh THREAD_UUID' >&2; exit 2; }
test_source=system/assistant/tests/org/agentos/tests
result=out/harness-regression
rm -rf "$result/store/classes" "$result/store/dex"
mkdir -p "$result/store/classes" "$result/store/dex" "$result/panel/classes" "$result/panel/dex"
compile_jvm "$result/store/classes" "$ANDROID_JAR" \
  system/assistant/broker/org/agentos/lab/*.java system/assistant/broker/org/agentos/lab/*.kt \
  system/ai/client/org/agentos/ai/NotebookSocialSource.java \
  system/assistant/tests/org/agentos/lab/HarnessStoreTest.java
"$JDK/bin/jar" cf "$result/store/classes.jar" -C "$result/store/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$result/store/dex" "$result/store/classes.jar" "$KOTLIN_LIB/kotlin-stdlib.jar"
"$JDK/bin/jar" cf "$result/store/test.jar" -C "$result/store/dex" classes.dex
"$ADB" -s "$serial" push "$result/store/test.jar" /data/local/tmp/taku-harness-store-tests.jar
"$ADB" -s "$serial" shell 'CLASSPATH=/data/local/tmp/taku-harness-store-tests.jar app_process /system/bin org.agentos.lab.HarnessStoreTest /data/local/agentos/home/.codex/skills/taku-app-development/SKILL.md' > "$result/store/result.txt"
grep -q '^PASS:' "$result/store/result.txt"
"$JDK/bin/javac" --release 17 -encoding UTF-8 -classpath "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" \
  -d "$result/panel/classes" "$test_source/AgentInterfaceTest.java" "$test_source/HarnessPanelTest.java"
"$JDK/bin/jar" cf "$result/panel/classes.jar" -C "$result/panel/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --lib "$SDK/platforms/android-36/uiautomator.jar" --lib "$SDK/platforms/android-36/optional/android.test.base.jar" --output "$result/panel/dex" "$result/panel/classes.jar"
"$JDK/bin/jar" cf "$result/panel/test.jar" -C "$result/panel/dex" classes.dex
"$ADB" -s "$serial" push "$result/panel/test.jar" /data/local/tmp/taku-harness-panel-tests.jar
"$ADB" -s "$serial" shell mkdir -p /data/local/tmp/taku-agent-interface
"$ADB" -s "$serial" shell timeout 180 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-harness-panel-tests.jar \
 -c org.agentos.tests.HarnessPanelTest -e threadId "$thread" > "$result/panel/result.txt"
grep -Fq 'OK (2 tests)' "$result/panel/result.txt"
for part in harness-background installed-skill; do
  "$ADB" -s "$serial" pull "/data/local/tmp/taku-agent-interface/$part.png" "$result/panel/$part.png"
done
echo 'PASS: local filesystem invariants and Expo Harness and Skills.'
