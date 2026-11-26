#!/bin/bash
# Dedicated screens: host tests (fake host, fake Android tasks, hand-moved clock, fake Codex). One run takes seconds.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/dedicated-screens-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
rm -rf "$task_out" && mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -nowarn -cp "$task_json:$ANDROID_JAR" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/SessionRemovals.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/PhoneTasks.java \
 system/assistant/broker/org/agentos/lab/DedicatedScreens.java \
 system/assistant/broker/org/agentos/lab/BackgroundPresentation.java \
 system/assistant/broker/org/agentos/lab/BoundedCommand.java \
 system/assistant/broker/org/agentos/lab/PhoneSubAgents.java \
 system/assistant/broker/org/agentos/lab/PhoneExecutionScreens.java \
 system/assistant/broker/org/agentos/lab/WorkspacePolicy.java \
 system/assistant/broker/org/agentos/lab/WorkspaceAdmissions.java \
 system/assistant/broker/org/agentos/lab/WorkspaceControl.java \
 system/assistant/broker/org/agentos/lab/PhoneExecutionScreensPointers.java \
 system/assistant/broker/org/agentos/lab/PhoneGestureSession.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/DedicatedScreensTest.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/DedicatedScreensLifecycleTest.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/DedicatedScreensCapacityTest.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/DedicatedScreensFamilyTest.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/DedicatedScreensRetentionTest.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/WorkspaceRevocationTest.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/BackgroundPresentationTest.java \
 system/assistant/tests/dedicated-screens/org/agentos/lab/PresentationRetentionTest.java
if [ "${1:-}" = family ]; then
 "$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.DedicatedScreensFamilyTest | tee "$task_out/result.txt"
 exit
fi
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.DedicatedScreensTest | tee "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.WorkspaceRevocationTest | tee -a "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.DedicatedScreensLifecycleTest | tee -a "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.DedicatedScreensCapacityTest | tee -a "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.DedicatedScreensFamilyTest | tee -a "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.DedicatedScreensRetentionTest | tee -a "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.BackgroundPresentationTest | tee -a "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.PresentationRetentionTest | tee -a "$task_out/result.txt"
