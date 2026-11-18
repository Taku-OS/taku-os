#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# kotlin-env.sh looks for the downloaded toolchain under $PWD and is pinned by the audio
# engine's rom-input-lock.json; builds that run from a subdirectory point it at the repo's copy.
task_jvm_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
if [ -z "${TAKU_KOTLINC:-}" ] && ! command -v kotlinc >/dev/null 2>&1 \
  && [ -x "$task_jvm_root/out/toolchains/kotlin-2.3.20/kotlinc/bin/kotlinc" ]; then
  TAKU_KOTLINC="$task_jvm_root/out/toolchains/kotlin-2.3.20/kotlinc/bin/kotlinc"
fi
source "$(dirname "${BASH_SOURCE[0]}")/kotlin-env.sh"

compile_jvm() {
  local task_classes="$1" task_classpath="$2"
  shift 2
  local task_source task_has_kotlin=false task_java_count=0
  local task_java_sources=()
  for task_source in "$@"; do
    case "$task_source" in
      *.kt) task_has_kotlin=true ;;
      *.java)
        task_java_sources[$task_java_count]="$task_source"
        task_java_count=$((task_java_count + 1))
        ;;
    esac
  done
  mkdir -p "$task_classes"
  if "$task_has_kotlin"; then
    JAVACMD="$JDK/bin/java" "$KOTLINC" -jvm-target 17 -no-stdlib -no-reflect \
      -classpath "$task_classpath:$KOTLIN_LIB/kotlin-stdlib.jar" -d "$task_classes" "$@"
  fi
  if [ "$task_java_count" -gt 0 ]; then
    "$JDK/bin/javac" --release 17 -cp "$task_classes:$task_classpath:$KOTLIN_LIB/kotlin-stdlib.jar" \
      -d "$task_classes" "${task_java_sources[@]}"
  fi
}
