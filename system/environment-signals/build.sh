#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
rm -rf out/classes
mkdir -p out/classes
"$JDK/bin/javac" --release 17 -encoding UTF-8 -cp "$ANDROID_JAR" -d out/classes src/org/agentos/environment/*.java
"$JDK/bin/jar" --create --file out/taku-environment-signals.jar -C out/classes .
echo 'Built system/environment-signals/out/taku-environment-signals.jar; no device was changed.'
