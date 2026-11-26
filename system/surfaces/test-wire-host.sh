#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/host-wire
python3 - <<'PY'
import hashlib, os, pathlib, time, urllib.error, urllib.request

target = pathlib.Path('out/host-wire/json-20240303.jar')
digest = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
urls = [
    'https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar',
    'https://maven-central.storage-download.googleapis.com/maven2/org/json/json/20240303/json-20240303.jar',
    'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',
]

def matches(candidate: pathlib.Path) -> bool:
    return candidate.is_file() and hashlib.sha256(candidate.read_bytes()).hexdigest() == digest

if not matches(target):
    candidates = [
        pathlib.Path('../../out/ime-replies-host/json-20240303.jar'),
        pathlib.Path('../../out/island-host-tests/json-20240303.jar'),
    ]
    env_jar = os.environ.get('TAKU_TEST_JSON_JAR')
    if env_jar:
        candidates.insert(0, pathlib.Path(env_jar))
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
                    if hashlib.sha256(payload).hexdigest() != digest:
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
    target.write_bytes(data)
PY
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -cp out/host-wire/json-20240303.jar -d out/host-wire contract/org/agentos/surfaces/*.java wire/org/agentos/surfaces/*.java tests/SurfaceCodecTest.java
"$task_java" -cp out/host-wire:out/host-wire/json-20240303.jar org.agentos.surfaces.SurfaceCodecTest
