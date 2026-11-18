#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/workspace-ui-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
rm -rf "$task_out/classes" "$task_out/generated"
mkdir -p "$task_out/classes" "$task_out/generated"
"$TOOLS/aapt2" compile --dir system/assistant/res -o "$task_out/resources.zip"
"$TOOLS/aapt2" link -o "$task_out/resources.apk" -I "$ANDROID_JAR" \
 --manifest system/assistant/AndroidManifest.xml --java "$task_out/generated" "$task_out/resources.zip"
"$JDK/bin/javac" --release 17 -cp "$task_json:$ANDROID_JAR" -d "$task_out/classes" \
 "$task_out/generated/org/agentos/assistant/R.java" \
 system/assistant/tests/workspace-ui-host/android/content/Context.java \
 system/assistant/tests/workspace-ui-host/android/os/*.java \
 system/assistant/tests/workspace-ui-host/org/agentos/assistant/*.java \
 system/assistant/src/org/agentos/assistant/WorkspaceUi.java \
 system/assistant/src/org/agentos/assistant/WorkspaceExecutionRequest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.assistant.WorkspaceUiLogicTest "$@" | tee "$task_out/result.txt"
if [ "$#" = 0 ]; then
 "$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR" org.agentos.assistant.WorkspaceReceiptRetentionTest | tee -a "$task_out/result.txt"
fi
