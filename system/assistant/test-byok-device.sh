#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
: "${TAKU_SERIAL:?Set TAKU_SERIAL to an authorized development emulator}"
[[ "$TAKU_SERIAL" == emulator-* ]] || { echo 'This test is for development emulators.' >&2; exit 2; }
[[ "$("$ADB" -s "$TAKU_SERIAL" shell id -u | tr -d '\r')" == 0 ]] || { echo 'Run adb root on the development emulator first.' >&2; exit 2; }
out="$PWD/out/byok-test"
mkdir -p "$out/classes" "$out/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:system/assistant/out/broker-classes.jar" -d "$out/classes" system/assistant/tests/org/agentos/lab/ByokProvidersTest.java
"$JDK/bin/jar" --create --file "$out/classes.jar" -C "$out/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --classpath system/assistant/out/broker-classes.jar --output "$out/dex" "$out/classes.jar"
"$JDK/bin/jar" --create --file "$out/test.jar" -C "$out/dex" classes.dex
"$ADB" -s "$TAKU_SERIAL" push "$out/test.jar" /data/local/tmp/taku-byok-test.jar
"$ADB" -s "$TAKU_SERIAL" push system/assistant/out/task-broker.jar /data/local/tmp/taku-byok-broker.jar
trap '"$ADB" -s "$TAKU_SERIAL" shell rm -f /data/local/tmp/taku-byok-test.jar /data/local/tmp/taku-byok-broker.jar' EXIT
"$ADB" -s "$TAKU_SERIAL" shell 'CLASSPATH=/data/local/tmp/taku-byok-test.jar:/data/local/tmp/taku-byok-broker.jar app_process /system/bin org.agentos.lab.ByokProvidersTest' | tee "$out/result.txt"
