#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
mkdir -p apk-shell/out/classes apk-shell/out/dex
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d apk-shell/out/classes apk-shell/src/org/agentos/generated/MainActivity.java apk-shell/src/org/agentos/runtime/AppApiClient.java
"$JDK/bin/jar" --create --file apk-shell/out/classes.jar -C apk-shell/out/classes .
"$TOOLS/d8" --min-api 30 --lib "$ANDROID_JAR" --output apk-shell/out/dex apk-shell/out/classes.jar
# Device packager uses this precompiled shell; each app still contains real JSX/API code.
printf '%s\n' 'Compiled apk-shell/out/dex/classes.dex (not installed).'
