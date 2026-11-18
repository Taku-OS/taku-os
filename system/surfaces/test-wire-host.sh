#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/host-wire
python3 - <<'PY'
import hashlib,pathlib,urllib.request
target=pathlib.Path('out/host-wire/json-20240303.jar')
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
cached=pathlib.Path('../../out/ime-replies-host/json-20240303.jar')
if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
    data=cached.read_bytes() if cached.exists() else urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
    assert hashlib.sha256(data).hexdigest()==digest
    target.write_bytes(data)
PY
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -cp out/host-wire/json-20240303.jar -d out/host-wire contract/org/agentos/surfaces/*.java wire/org/agentos/surfaces/*.java tests/SurfaceCodecTest.java
"$task_java" -cp out/host-wire:out/host-wire/json-20240303.jar org.agentos.surfaces.SurfaceCodecTest
