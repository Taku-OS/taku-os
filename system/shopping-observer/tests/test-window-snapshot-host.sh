#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/window-host-tests
window_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
window_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
find tests/window-host-stubs tests/window-host -name '*.java' -print > out/window-host-tests/sources.txt
printf '%s\n' core/org/agentos/shopping/*.java \
  src/org/agentos/shopping/WindowSnapshot.java src/org/agentos/shopping/LiveFrameWindow.java \
  >> out/window-host-tests/sources.txt
"$window_javac" --release 17 -d out/window-host-tests @out/window-host-tests/sources.txt
"$window_java" -cp out/window-host-tests org.agentos.shopping.WindowSnapshotReadTest
