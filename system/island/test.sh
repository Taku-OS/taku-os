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
import hashlib, os, sys, time, urllib.error, urllib.request
from pathlib import Path

path = Path(sys.argv[1])
expected = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
urls = [
    'https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar',
    'https://maven-central.storage-download.googleapis.com/maven2/org/json/json/20240303/json-20240303.jar',
    'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
]

def matches(candidate: Path) -> bool:
    return candidate.is_file() and hashlib.sha256(candidate.read_bytes()).hexdigest() == expected

if not matches(path):
    candidates = [
        Path('../surfaces/out/host-wire/json-20240303.jar'),
        Path('../../out/ime-replies-host/json-20240303.jar'),
    ]
    env_jar = os.environ.get('TAKU_TEST_JSON_JAR')
    if env_jar:
        candidates.insert(0, Path(env_jar))
    data = None
    for candidate in candidates:
        if matches(candidate):
            data = candidate.read_bytes()
            break
    if data is None:
        last_error = None
        for attempt in range(3):
            for url in urls:
                req = urllib.request.Request(url, headers={'User-Agent': 'taku-os-host-test/1.0'})
                try:
                    with urllib.request.urlopen(req, timeout=30) as response:
                        payload = response.read()
                    if hashlib.sha256(payload).hexdigest() != expected:
                        raise ValueError(f'Test-only org.json checksum mismatch from {url}')
                    data = payload
                    break
                except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as exc:
                    last_error = exc
            if data is not None:
                break
            time.sleep(2 ** attempt)
        if data is None:
            raise RuntimeError(f'Failed to fetch org.json test jar: {last_error}') from last_error
    path.write_bytes(data)
PY
"$task_javac" --release 17 -cp "$json_jar" -d "$output" tests/host-shims/android/util/*.java \
    src/com/android/systemui/taku/island/IslandPayload.java tests/IslandPayloadTest.java
"$task_java" -cp "$output:$json_jar" IslandPayloadTest
"$task_javac" --release 17 -d "$output" src/com/android/systemui/taku/island/IslandCapsuleGeometry.java tests/IslandCapsuleGeometryTest.java
"$task_java" -cp "$output" IslandCapsuleGeometryTest
"$task_javac" --release 17 -d "$output" src/org/agentos/island/DotMatrixLoader.java tests/DotMatrixLoaderTest.java
"$task_java" -cp "$output" DotMatrixLoaderTest design/dot-matrix-loader/thinking-golden.csv
