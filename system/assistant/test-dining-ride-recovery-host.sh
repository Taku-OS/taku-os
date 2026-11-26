#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="${TAKU_RIDE_RECOVERY_TEST_OUT:-$PWD/out/dining-ride-recovery-host}"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
[ -f "$task_json" ] || { echo 'Set TAKU_TEST_JSON_JAR to a cached org.json JAR.' >&2; exit 1; }
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json:$ANDROID_JAR" -d "$task_out/classes" \
 system/assistant/src/org/agentos/assistant/DiningRideInputRecovery.java \
 system/assistant/tests/dining-ride-recovery/org/agentos/assistant/*.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.assistant.DiningRideRecoveryTest
