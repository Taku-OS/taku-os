#!/bin/bash
# Build only. The app_process test uses an in-memory preference store, never app data or model calls.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
OUTPUT=../../out/composer-state-tests
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" \
  src/org/agentos/assistant/ComposerDraft.java src/org/agentos/assistant/ComposerPreferences.java \
  tests/org/agentos/assistant/ComposerPreferencesTest.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
echo "Built $OUTPUT/test.jar; entry point: org.agentos.assistant.ComposerPreferencesTest"
