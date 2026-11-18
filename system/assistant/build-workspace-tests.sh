#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_output="$PWD/out/workspaces-device-tests"
mkdir -p "$task_output/classes" "$task_output/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:system/assistant/out/broker-classes" -d "$task_output/classes" \
 system/assistant/tests/workspaces/org/agentos/lab/WorkspaceDiscoveryProbe.java \
 system/assistant/tests/workspaces/org/agentos/lab/WorkspaceLocaleProbe.java \
 system/assistant/tests/org/agentos/tests/NativeSocketProbe.java
"$JDK/bin/jar" --create --file "$task_output/classes.jar" -C "$task_output/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --classpath system/assistant/out/broker-classes.jar \
 --output "$task_output/dex" "$task_output/classes.jar"
"$JDK/bin/jar" --create --file "$task_output/tests.jar" -C "$task_output/dex" classes.dex
