#!/bin/bash
# Recorded-protocol reducers and bounded relay. No Android device or model calls.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/session-stream-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/SessionEvents.java \
 system/assistant/broker/org/agentos/lab/AgentSessionReducer.java \
 system/assistant/tests/session-stream/org/agentos/lab/*.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.SessionEventsTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.AgentSessionReducerTest
