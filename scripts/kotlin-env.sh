#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Source after android-env.sh. Build tooling is external; no IDE path is required.
if [ -n "${TAKU_KOTLINC:-}" ]; then
  KOTLINC="$TAKU_KOTLINC"
elif command -v kotlinc >/dev/null 2>&1; then
  KOTLINC=$(command -v kotlinc)
elif [ -x "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/out/toolchains/kotlin-2.3.20/kotlinc/bin/kotlinc" ]; then
  KOTLINC="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/out/toolchains/kotlin-2.3.20/kotlinc/bin/kotlinc"
elif [ -x '/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc' ]; then
  KOTLINC='/Applications/Android Studio.app/Contents/plugins/Kotlin/kotlinc/bin/kotlinc'
else
  echo 'Install the Kotlin JVM compiler or set TAKU_KOTLINC.' >&2
  return 1
fi
KOTLINC=$(python3 -c 'import os,sys;print(os.path.realpath(sys.argv[1]))' "$KOTLINC")
KOTLIN_LIB="${TAKU_KOTLIN_LIB:-$(cd "$(dirname "$KOTLINC")/../lib" && pwd)}"
test -f "$KOTLIN_LIB/kotlin-stdlib.jar" || { echo 'Set TAKU_KOTLIN_LIB to the compiler lib directory.' >&2; return 1; }
export JAVA_HOME="$JDK"
