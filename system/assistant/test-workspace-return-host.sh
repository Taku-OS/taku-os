#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/workspace-return-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/SessionRemovals.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/WorkspaceExecution.java \
 system/assistant/broker/org/agentos/lab/WorkspaceExecutionRuntime.java \
 system/assistant/broker/org/agentos/lab/PhoneSubAgents.java \
 system/assistant/tests/workspace-return/org/agentos/lab/*.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.WorkspaceReturnRuntimeTest | tee "$task_out/result.txt"
