#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/native-routing-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
# Android framework types are linked only; socket/UI methods are not called in this host test.
"$JDK/bin/javac" --release 17 -cp "$task_json:$ANDROID_JAR" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/PhoneSubAgents.java \
 system/assistant/broker/org/agentos/lab/SessionRemovalRuntime.java \
 system/assistant/broker/org/agentos/lab/SessionRemovals.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/NativeBridge.java \
 system/assistant/broker/org/agentos/lab/SessionEvents.java \
 system/assistant/broker/org/agentos/lab/WorkspaceAdmissions.java \
 system/assistant/broker/org/agentos/lab/PhoneConfirmation.java \
 system/assistant/broker/org/agentos/lab/PhoneAskUser.java \
 system/assistant/tests/native-routing/org/agentos/lab/NativeBridgeTestDoubles.java \
 system/assistant/tests/native-routing/org/agentos/lab/NativeBridgeHostServer.java \
 system/assistant/tests/native-routing/org/agentos/lab/AgentViewsTestDouble.java \
 system/assistant/tests/native-routing/org/agentos/lab/NativeBridgeRoutingTest.java \
 system/assistant/tests/native-routing/org/agentos/lab/NativeBridgeFamilyRoutingTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.NativeBridgeRoutingTest | tee "$task_out/result.txt"
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.lab.NativeBridgeFamilyRoutingTest | tee -a "$task_out/result.txt"
mkdir -p "$task_out/task-ownership"
"$JDK/bin/javac" --release 17 -cp "$task_json:$ANDROID_JAR" -d "$task_out/task-ownership" \
 system/assistant/broker/org/agentos/lab/CodexAppServer.java \
 system/assistant/broker/org/agentos/lab/PhoneSubAgents.java \
 system/assistant/broker/org/agentos/lab/SessionRemovalRuntime.java \
 system/assistant/broker/org/agentos/lab/SessionRemovals.java \
 system/assistant/broker/org/agentos/lab/CleanupWork.java \
 system/assistant/broker/org/agentos/lab/NativeBridge.java \
 system/assistant/broker/org/agentos/lab/SessionEvents.java \
 system/assistant/broker/org/agentos/lab/WorkspaceAdmissions.java \
 system/assistant/broker/org/agentos/lab/PhoneConfirmation.java \
 system/assistant/broker/org/agentos/lab/PhoneAskUser.java \
 system/assistant/tests/native-routing/org/agentos/lab/NativeBridgeTestDoubles.java \
 system/assistant/tests/native-routing/org/agentos/lab/AgentViewsTestDouble.java \
 system/assistant/tests/native-routing/org/agentos/lab/CodexTaskOwnershipTest.java
"$JDK/bin/java" -cp "$task_out/task-ownership:$task_json:$ANDROID_JAR" org.agentos.lab.CodexTaskOwnershipTest | tee "$task_out/task-ownership-result.txt"
