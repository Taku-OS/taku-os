#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
javac_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$javac_cmd" --release 17 -d "$tmp" system/plugins/tests/dalvik/system/DexClassLoader.java system/plugins/tests/de/robv/android/xposed/*.java system/plugins/src/org/taku/plugins/*.java system/plugins/tests/LoaderTest.java examples/godmode/*/*.java
"$java_cmd" -cp "$tmp" LoaderTest
