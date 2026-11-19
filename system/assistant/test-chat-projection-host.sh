#!/bin/bash
# Pure projection/preview protocol fixtures; no device or model calls.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/chat-projection-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
  system/assistant/src/org/agentos/assistant/ChatWorkspacePreviewProtocol.java \
  system/assistant/tests/chat-preview/org/agentos/assistant/ChatWorkspacePreviewProtocolTest.java \
  system/assistant/src/org/agentos/assistant/AgentConversation.java \
  system/assistant/src/org/agentos/assistant/NativeToolPresentation.java \
  system/assistant/tests/org/agentos/assistant/NativeConversationTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.assistant.ChatWorkspacePreviewProtocolTest
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.assistant.NativeConversationTest
