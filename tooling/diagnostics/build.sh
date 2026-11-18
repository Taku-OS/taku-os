#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
rm -rf out/classes out/dex
mkdir -p out/classes out/dex
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d out/classes src/org/agentos/lab/*.java
"$JDK/bin/jar" --create --file out/classes.jar -C out/classes .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/dex out/classes.jar
"$JDK/bin/jar" --create --file out/diagnostics.jar -C out/dex classes.dex
printf '%s\n' 'Built tooling/diagnostics/out/diagnostics.jar; no device operation performed.'
