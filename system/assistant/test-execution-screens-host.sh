#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/execution-screens-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/PhoneExecutionScreens.java \
 system/assistant/broker/org/agentos/lab/WorkspacePolicy.java \
 system/assistant/broker/org/agentos/lab/WorkspaceAdmissions.java \
 system/assistant/broker/org/agentos/lab/PhoneExecutionScreensPointers.java \
 system/assistant/broker/org/agentos/lab/PhoneGestureSession.java \
 system/assistant/tests/execution-screens/org/agentos/lab/PhoneExecutionScreensTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.PhoneExecutionScreensTest | tee "$task_out/result.txt"
