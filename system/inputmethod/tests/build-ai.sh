#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/android-env.sh
bash system/inputmethod/tests/build.sh
bash system/inputmethod/tests/build-chatcase.sh
out="$PWD/out/inputmethod/native-probe"
mkdir -p "$out/classes" "$out/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$out/classes" system/assistant/tests/org/agentos/tests/NativeSocketProbe.java
"$JDK/bin/jar" --create --file "$out/classes.jar" -C "$out/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/dex" "$out/classes.jar"
"$JDK/bin/jar" --create --file "$out/probe.jar" -C "$out/dex" classes.dex
