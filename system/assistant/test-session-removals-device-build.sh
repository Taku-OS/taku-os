#!/bin/bash
# Build-only fixture. Device deployment is deliberately a separate, isolated-emulator step.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
task_out="$PWD/out/session-removals-device"
task_broker="$PWD/system/assistant/out/broker-classes.jar"
[ -f "$task_broker" ] || bash system/assistant/build.sh
mkdir -p "$task_out/probe-classes" "$task_out/probe-dex"
"$JDK/bin/javac" --release 17 -cp "$task_broker:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" \
 -d "$task_out/probe-classes" system/assistant/tests/session-removals-device/org/agentos/lab/SessionRemovalDeviceProbe.java
"$JDK/bin/jar" --create --file "$task_out/probe-classes.jar" -C "$task_out/probe-classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$task_out/probe-dex" \
 "$task_broker" "$task_out/probe-classes.jar" "$KOTLIN_LIB/kotlin-stdlib.jar"
"$JDK/bin/jar" --create --file "$task_out/probe.jar" -C "$task_out/probe-dex" classes.dex
echo 'Built probe.jar for an isolated emulator with the paired TakuAgent and backend APKs.'
