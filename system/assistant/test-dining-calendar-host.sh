#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/dining-calendar-host"
task_json="${TAKU_TEST_JSON_JAR:-$task_out/json-20240303.jar}"
mkdir -p "$task_out/classes"
python3 - "$task_json" "$PWD/out/ime-replies-host/json-20240303.jar" "$PWD/out/dining-island-host/json-20240303.jar" <<'PYJSON'
import hashlib
from pathlib import Path
import sys
import urllib.request
expected="3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"
target=Path(sys.argv[1])
for candidate in map(Path,sys.argv[1:]):
    if candidate.is_file() and hashlib.sha256(candidate.read_bytes()).hexdigest()==expected:
        if candidate!=target: target.write_bytes(candidate.read_bytes())
        break
else:
    with urllib.request.urlopen("https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar",timeout=30) as response:
        data=response.read()
    if hashlib.sha256(data).hexdigest()!=expected: sys.exit("Host org.json JAR integrity check failed")
    target.write_bytes(data)
PYJSON
main=system/assistant/src/org/agentos/assistant
"$JDK/bin/javac" --release 17 -cp "$task_json:$ANDROID_JAR" -d "$task_out/classes" \
  "$main"/DiningRestaurantIdentity.java "$main"/DiningPollReceiptTarget.java "$main"/DiningPollBookingFlow.java "$main"/DiningPollBookingCodec.java \
  "$main"/DiningFeedDispatch.java "$main"/DiningBookingCalendarPolicy.java "$main"/DiningBookingCalendarCard.java "$main"/DiningCalendarSelection.java \
  "$main"/DiningReservationCalendar.java "$main"/DiningFollowupIsland.java system/assistant/tests/dining-calendar/org/agentos/assistant/DiningCalendarTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.assistant.DiningCalendarTest "$task_out/calendar-card.json" "$task_out/island-followup-cards.json"
