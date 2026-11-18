#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/app-catalog-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
if [ ! -f "$task_json" ]; then bash system/assistant/test-confirmation-host.sh; fi
mkdir -p "$task_out/classes"
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/PhoneAppCatalog.java \
 system/assistant/tests/app-catalog/org/agentos/lab/PhoneAppCatalogTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.PhoneAppCatalogTest | tee "$task_out/result.txt"
# Compile the actual framework probe against the target SDK, not the JVM adapters.
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$task_out/classes" -d "$task_out/classes" \
 system/assistant/broker/org/agentos/lab/PhoneAppProbe.java
