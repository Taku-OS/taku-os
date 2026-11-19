#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
bash system/assistant/test-ime-replies-host.sh
out="$PWD/out/system-ai-host"
rm -rf "$out/classes"
mkdir -p "$out/classes"
cp="$PWD/out/ime-replies-host/json-20240303.jar"
compile_jvm "$out/classes" "$cp" \
 system/assistant/broker/org/agentos/lab/AiPolicy.java \
 system/assistant/broker/org/agentos/lab/SystemAiSettings.java \
 system/assistant/broker/org/agentos/lab/MagpieProviderSecrets.java \
 system/assistant/tests/org/agentos/lab/MagpieProviderSecretsTest.java \
 system/assistant/broker/org/agentos/lab/MemoryDecisionJson.java \
 system/assistant/broker/org/agentos/lab/UiSseStream.kt \
 system/assistant/tests/org/agentos/lab/UiSseStreamTest.java \
 system/assistant/tests/org/agentos/lab/SystemAiTest.java \
 system/assistant/tests/org/agentos/lab/SystemAiSettingsTest.java
"$JDK/bin/java" -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.SystemAiTest | tee "$out/result.txt"
"$JDK/bin/java" -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.UiSseStreamTest | tee "$out/ui-sse-result.txt"
"$JDK/bin/java" -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.SystemAiSettingsTest
"$JDK/bin/java" -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.MagpieProviderSecretsTest

"$JDK/bin/javac" --release 17 -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" -d "$out/classes" \
 system/assistant/tests/magpie-host/android/os/*.java \
 system/assistant/tests/magpie-host/android/system/*.java \
 system/assistant/tests/magpie-host/org/agentos/lab/*.java \
 system/assistant/broker/org/agentos/lab/MagpieSettings.java \
 system/assistant/tests/org/agentos/lab/MagpieMigrationTest.java \
 system/assistant/tests/org/agentos/lab/MagpieProjectionRaceTest.java \
 system/assistant/tests/org/agentos/lab/MagpieSyncTest.java
"$JDK/bin/java" -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.MagpieMigrationTest
"$JDK/bin/java" -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.MagpieProjectionRaceTest

"$JDK/bin/java" -cp "$out/classes:$cp:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.MagpieSyncTest

perm_out="$PWD/out/app-perm-host"
rm -rf "$perm_out"
mkdir -p "$perm_out"
compile_jvm "$perm_out" "$cp:$ANDROID_JAR" \
 system/assistant/broker/org/agentos/lab/*.java \
 system/assistant/broker/org/agentos/lab/*.kt \
 tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java \
 system/assistant/tests/org/agentos/lab/AppPermissionPolicyTest.java
"$JDK/bin/java" -cp "$perm_out:$cp:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.AppPermissionPolicyTest
