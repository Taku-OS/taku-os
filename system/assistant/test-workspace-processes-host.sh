#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/workspace-processes-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes" "$task_out/android-classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/TurnProcesses.java \
 system/assistant/tests/workspace-processes/android/system/*.java \
 system/assistant/tests/workspace-processes/android/os/*.java \
 system/assistant/tests/workspace-processes/org/agentos/lab/TurnProcessesTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.TurnProcessesTest | tee "$task_out/result.txt"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/android-classes" \
 system/assistant/broker/org/agentos/lab/TurnProcesses.java
