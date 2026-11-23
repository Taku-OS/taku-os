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
json_jar="$output/json-20240303.jar"
python3 - "$json_jar" <<'PY'
import hashlib, sys, urllib.request
from pathlib import Path
path = Path(sys.argv[1])
expected = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30) as response:
        data = response.read()
    assert hashlib.sha256(data).hexdigest() == expected, 'Test-only org.json checksum mismatch'
    path.write_bytes(data)
PY
"$task_javac" --release 17 -cp "$json_jar" -d "$output" tests/host-shims/android/util/*.java \
    src/com/android/systemui/taku/island/IslandPayload.java tests/IslandPayloadTest.java
"$task_java" -cp "$output:$json_jar" IslandPayloadTest
"$task_javac" --release 17 -d "$output" src/com/android/systemui/taku/island/IslandCapsuleGeometry.java tests/IslandCapsuleGeometryTest.java
"$task_java" -cp "$output" IslandCapsuleGeometryTest
"$task_javac" --release 17 -d "$output" src/org/agentos/island/DotMatrixLoader.java tests/DotMatrixLoaderTest.java
"$task_java" -cp "$output" DotMatrixLoaderTest design/dot-matrix-loader/thinking-golden.csv
