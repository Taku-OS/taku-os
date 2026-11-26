#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/kotlin-env.sh
module=system/audio-engine
mkdir -p "$module/out"
"$KOTLINC" "$module/contract/org/agentos/audio/AudioContract.kt" \
  "$module"/client/org/agentos/audio/*.kt -jvm-target 17 -include-runtime \
  -classpath "$ANDROID_JAR" -d "$module/out/audio-client.jar"
