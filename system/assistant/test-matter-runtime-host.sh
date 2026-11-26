#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
task_out="$PWD/out/matter-runtime-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then
  printf '%s\n' 'An existing org.json host JAR is required; set TAKU_TEST_JSON_JAR.' >&2
  exit 1
fi
rm -rf "$task_out/classes"
mkdir -p "$task_out/classes"
compile_jvm "$task_out/classes" "$task_json:$ANDROID_JAR" \
 system/assistant/broker/org/agentos/lab/UiCardRequest.kt \
 system/assistant/broker/org/agentos/lab/ComposerInput.java \
 system/assistant/src/org/agentos/assistant/AgentGatewayProtocol.kt \
 system/assistant/src/org/agentos/assistant/AgentGatewaySubmissions.kt \
 system/assistant/src/org/agentos/assistant/ComposerState.kt \
 system/assistant/src/org/agentos/assistant/ComposerPreferences.kt \
 system/assistant/src/org/agentos/assistant/ComposerPlugins.kt \
 system/assistant/src/org/agentos/assistant/PanelProtocol.kt \
 system/assistant/src/org/agentos/assistant/PanelRegistry.kt \
 system/assistant/src/org/agentos/assistant/CardViewStateStore.kt \
 system/assistant/src/org/agentos/assistant/MatterReceiptHistory.kt \
 system/assistant/src/org/agentos/assistant/MatterRetention.kt \
 system/assistant/src/org/agentos/assistant/MatterRuntime.kt \
 system/assistant/src/org/agentos/assistant/MatterProjection.kt \
 system/assistant/src/org/agentos/assistant/SourceAdapter.kt \
 system/assistant/src/org/agentos/assistant/WatchNotifications.kt \
 system/assistant/tests/agent-gateway/org/agentos/assistant/GatewayTestDoubles.java \
 system/assistant/tests/agent-gateway/org/agentos/assistant/MemoryPreferences.java \
 system/assistant/tests/matter-runtime/android/content/Context.java \
 system/assistant/tests/matter-runtime/android/app/KeyguardManager.java \
 system/assistant/tests/matter-runtime/android/os/UserManager.java \
 system/assistant/tests/matter-runtime/android/util/Log.java \
 system/assistant/tests/matter-runtime/org/agentos/assistant/*.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.assistant.MatterRuntimeConcurrencyTest
