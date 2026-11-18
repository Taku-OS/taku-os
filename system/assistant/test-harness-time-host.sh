#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/harness-time-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-ime-replies-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/HarnessTime.java \
 system/assistant/tests/org/agentos/lab/HarnessTimeTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.HarnessTimeTest
