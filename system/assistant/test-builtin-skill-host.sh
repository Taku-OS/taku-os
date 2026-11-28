#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
task_out="${TAKU_BUILTIN_TEST_OUT:-$PWD/out/builtin-skill-host}"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
compile_jvm "$task_out/classes" "$ANDROID_JAR:$task_json" \
 system/assistant/broker/org/agentos/lab/*.java system/assistant/broker/org/agentos/lab/*.kt \
 system/ai/client/org/agentos/ai/NotebookSocialSource.java \
 tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java \
 system/assistant/tests/org/agentos/lab/BuiltinSkillUpdateTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.BuiltinSkillUpdateTest | tee "$task_out/result.txt"
