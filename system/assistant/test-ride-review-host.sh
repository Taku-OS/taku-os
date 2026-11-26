#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/ride-review-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
test -f "$task_json"
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -nowarn -cp "$task_json:$ANDROID_JAR" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/SessionRemovals.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/PhoneTasks.java \
 system/assistant/broker/org/agentos/lab/DedicatedScreens.java \
 system/assistant/broker/org/agentos/lab/PhoneSubAgents.java \
 system/assistant/broker/org/agentos/lab/PhoneExecutionScreens.java \
 system/assistant/broker/org/agentos/lab/WorkspacePolicy.java \
 system/assistant/broker/org/agentos/lab/WorkspaceAdmissions.java \
 system/assistant/broker/org/agentos/lab/UberUpcomingPolicy.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/DedicatedScreensTest.java \
 system/assistant/tests/ride-review/org/agentos/lab/*.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.UberUpcomingPolicyTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.DedicatedScreensReviewTest
