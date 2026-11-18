#!/bin/bash
# Local filesystem + native panel acceptance. Requires already installed components.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT"
source scripts/android-env.sh
source scripts/compile-jvm.sh
serial=emulator-5634
avd=$("$ADB" -s "$serial" emu avd name | head -1 | tr -d '\r')
[[ "$avd" == Taku_ImageIntegration_20261010 ]] || { echo 'Dedicated integration AVD required.' >&2; exit 1; }
thread=${1:-}
[[ "$thread" =~ ^[a-f0-9-]{36}$ ]] || { echo 'Provide an existing native Harness test thread ID.' >&2; exit 1; }
test_class=org.agentos.tests.HarnessPanelTest
test_source=system/assistant/tests/org/agentos/tests/HarnessPanelTest.java
expected_result='OK (2 tests)'
parts=(overview background environment skills catalog search detail installed-detail empty community-skill light category source-form skills-light)
result=out/harness-regression
if [[ "${2:-}" == --density ]]; then
  test_class=org.agentos.tests.HarnessDensityTest
  test_source=system/assistant/tests/org/agentos/tests/HarnessDensityTest.java
  expected_result='OK (1 test)'
  parts=(overview module background environment receipt light)
  result=out/harness-density-regression
elif [[ -n "${2:-}" ]]; then
  echo 'Only --density is supported as the second argument.' >&2; exit 1
fi
rm -rf "$result/store/classes" "$result/store/dex"
mkdir -p "$result/store/classes" "$result/store/dex" "$result/panel/classes" "$result/panel/dex"
compile_jvm "$result/store/classes" "$ANDROID_JAR" \
  system/assistant/broker/org/agentos/lab/*.java system/assistant/broker/org/agentos/lab/*.kt \
  system/assistant/tests/org/agentos/lab/HarnessStoreTest.java
"$JDK/bin/jar" cf "$result/store/classes.jar" -C "$result/store/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$result/store/dex" "$result/store/classes.jar" "$KOTLIN_LIB/kotlin-stdlib.jar"
"$JDK/bin/jar" cf "$result/store/test.jar" -C "$result/store/dex" classes.dex
"$ADB" -s "$serial" push "$result/store/test.jar" /data/local/tmp/taku-harness-store-tests.jar
"$ADB" -s "$serial" shell 'CLASSPATH=/data/local/tmp/taku-harness-store-tests.jar app_process /system/bin org.agentos.lab.HarnessStoreTest /data/local/agentos/home/.codex/skills/taku-app-development/SKILL.md' > "$result/store/result.txt"
grep -q '^PASS:' "$result/store/result.txt"
"$JDK/bin/javac" -encoding UTF-8 -classpath "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar:$SDK/platforms/android-36/optional/android.test.runner.jar" \
  -d "$result/panel/classes" "$test_source"
"$JDK/bin/jar" cf "$result/panel/classes.jar" -C "$result/panel/classes" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --lib "$SDK/platforms/android-36/uiautomator.jar" --lib "$SDK/platforms/android-36/optional/android.test.base.jar" --output "$result/panel/dex" "$result/panel/classes.jar"
"$JDK/bin/jar" cf "$result/panel/test.jar" -C "$result/panel/dex" classes.dex
"$ADB" -s "$serial" push "$result/panel/test.jar" /data/local/tmp/taku-harness-panel-tests.jar
"$ADB" -s "$serial" shell am start -n org.agentos.assistant/.PanelActivity --es conversationId "$thread"
"$ADB" -s "$serial" shell timeout 180 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-harness-panel-tests.jar -c "$test_class" > "$result/panel/result.txt"
grep -Fq "$expected_result" "$result/panel/result.txt"
prefix=harness-panel
[[ "${2:-}" != --density ]] || prefix=harness-compact
for part in "${parts[@]}"; do
  "$ADB" -s "$serial" pull "/data/local/tmp/$prefix-$part.png" "$result/panel/$part.png"
done
echo 'PASS: local filesystem invariants and native panels. No model request or ROM acceptance.'
