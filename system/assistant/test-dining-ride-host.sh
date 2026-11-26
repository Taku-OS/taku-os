#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/dining-ride-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
test -f "$task_json" || { echo 'Set TAKU_TEST_JSON_JAR to the host org.json jar from a host test run.' >&2; exit 1; }
mkdir -p "$task_out/classes"
main=system/assistant/src/org/agentos/assistant
tests=system/assistant/tests/dining-ride/org/agentos/assistant
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
  "$main"/DiningFeedDispatch.java "$main"/DiningBookingCalendarPolicy.java "$main"/DiningPollReceiptTarget.java "$main"/DiningBookingRidePolicy.java \
  "$main"/DiningPollBookingFlow.java "$main"/DiningPollBookingCodec.java "$main"/DiningRestaurantIdentity.java \
  "$main"/DiningRideInputState.java "$main"/DiningRidePlan.java "$main"/DiningRidePreparationHistory.java "$tests"/DiningRidePolicyTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.assistant.DiningRidePolicyTest
