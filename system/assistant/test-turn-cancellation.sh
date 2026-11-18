#!/bin/bash
# Actual upstream model and device process acceptance, on the dedicated AVD only.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
serial=emulator-5634
[[ "$("$ADB" -s "$serial" emu avd name | head -1 | tr -d '\r')" == Taku_ImageIntegration_20261010 ]] || exit 1
"$ADB" -s "$serial" shell id | grep -q 'uid=0('
result=out/turn-cancellation
mkdir -p "$result/classes" "$result/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" \
  -d "$result/classes" system/assistant/tests/org/agentos/tests/NativeSocketProbe.java system/assistant/tests/org/agentos/tests/TurnCancellationTest.java
"$JDK/bin/jar" --create --file "$result/classes.jar" -C "$result/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$result/dex" "$result/classes.jar"
"$JDK/bin/jar" --create --file "$result/test.jar" -C "$result/dex" classes.dex
"$ADB" -s "$serial" push "$result/test.jar" /data/local/tmp/taku-cancellation-tests.jar
"$ADB" -s "$serial" shell timeout 220 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-cancellation-tests.jar \
  -c org.agentos.tests.TurnCancellationTest > "$result/result.txt" 2>&1
grep -Fq 'OK (1 test)' "$result/result.txt"
"$ADB" -s "$serial" pull /data/local/tmp/taku-turn-cancellation/acceptance.json "$result/acceptance.json"
echo 'PASS: live model, Stop button, process cleanup, sibling/prior-session preservation and follow-up response. Dedicated AVD only.'
