#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/host-tests
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -d out/host-tests contract/org/agentos/surfaces/*.java src/org/agentos/surfaces/SurfacePosition.java src/org/agentos/surfaces/SurfaceVisibility.java src/org/agentos/surfaces/SurfaceLayout.java tests/SurfaceStoreTest.java tests/SurfacePositionTest.java tests/SurfaceVisibilityTest.java tests/SurfaceLayoutTest.java
"$task_java" -cp out/host-tests SurfaceStoreTest
"$task_java" -cp out/host-tests org.agentos.surfaces.SurfacePositionTest
"$task_java" -cp out/host-tests org.agentos.surfaces.SurfaceVisibilityTest
"$task_java" -cp out/host-tests org.agentos.surfaces.SurfaceLayoutTest
