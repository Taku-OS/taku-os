#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/dining-island-host"
task_json="${TAKU_TEST_JSON_JAR:-$task_out/json-20240303.jar}"
mkdir -p "$task_out/classes"
python3 - "$task_json" <<'PY'
import hashlib
import pathlib
import sys
import urllib.request

jar = pathlib.Path(sys.argv[1])
digest = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest() != digest:
    with urllib.request.urlopen(
        "https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar", timeout=30
    ) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != digest:
        sys.exit("Host org.json JAR integrity check failed")
    jar.write_bytes(data)
PY
main=system/assistant/src/org/agentos/assistant
tests=system/assistant/tests/dining-island/org/agentos/assistant
"$JDK/bin/javac" --release 17 -cp "$task_json:$ANDROID_JAR" -d "$task_out/classes" \
  "$main"/DiningPollConfirmationState.java "$main"/DiningPollIslandModel.java \
  "$main"/DiningPollReceiptTarget.java "$main"/DiningRestaurantIdentity.java "$main"/DiningPollBookingFlow.java \
  "$main"/DiningPollReceipt.java "$main"/DiningPollDateProof.java "$main"/DiningPollSchedule.java "$main"/DiningPollBookingForm.java \
  "$main"/DiningReservationFeedCard.java "$main"/DiningBookingCheckout.java "$main"/DiningPollUi.java \
  "$main"/DiningPollSendAutomation.java "$main"/DiningPollBookingCodec.java \
  "$main"/DiningBookingClaim.java "$main"/DiningBookingNavigation.java \
  "$tests"/DiningPollIslandTest.java "$tests"/DiningPollReceiptTargetTest.java "$tests"/DiningPollBookingFlowTest.java \
  "$tests"/DiningPollReceiptDateTest.java "$tests"/DiningBookingClaimTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.assistant.DiningPollIslandTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.assistant.DiningPollReceiptTargetTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.assistant.DiningPollBookingFlowTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.assistant.DiningPollReceiptDateTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.assistant.DiningBookingClaimTest
