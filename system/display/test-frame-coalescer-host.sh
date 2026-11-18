#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/frame-coalescer-host"
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -d "$task_out/classes" \
 system/display/src/org/agentos/lab/FrameCoalescer.java \
 system/display/tests/org/agentos/lab/FrameCoalescerTest.java
"$JDK/bin/java" -cp "$task_out/classes" org.agentos.lab.FrameCoalescerTest
