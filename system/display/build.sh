#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
mkdir -p out/classes out/dex
"$JAVA/bin/javac" --release 17 -cp "$ANDROID_JAR" -d out/classes src/org/agentos/lab/*.java
"$JAVA/bin/jar" --create --file out/classes.jar -C out/classes .
JAVA_HOME="$JAVA" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/dex out/classes.jar
"$JAVA/bin/jar" --create --file out/virtual-display.jar -C out/dex classes.dex
