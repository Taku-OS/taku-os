#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
output=../../out/island-host-tests
mkdir -p "$output"
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -d "$output" src/com/android/systemui/taku/island/IslandStore.java tests/IslandStoreTest.java src/com/android/systemui/taku/island/IslandSourcePolicy.java tests/IslandSourcePolicyTest.java
"$task_java" -cp "$output" IslandStoreTest
"$task_java" -cp "$output" IslandSourcePolicyTest
"$task_javac" --release 17 -d "$output" src/com/android/systemui/taku/island/IslandAccessPolicy.java tests/IslandAccessPolicyTest.java
"$task_java" -cp "$output" IslandAccessPolicyTest
"$task_javac" --release 17 -d "$output" src/com/android/systemui/taku/island/IslandCapsuleGeometry.java tests/IslandCapsuleGeometryTest.java
"$task_java" -cp "$output" IslandCapsuleGeometryTest
"$task_javac" --release 17 -d "$output" src/org/agentos/island/DotMatrixLoader.java tests/DotMatrixLoaderTest.java
"$task_java" -cp "$output" DotMatrixLoaderTest design/dot-matrix-loader/thinking-golden.csv
