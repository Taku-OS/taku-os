#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out/host-prompts
python3 - <<'PY'
import hashlib,pathlib,urllib.request
target=pathlib.Path('out/host-prompts/json-20240303.jar')
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
    candidates=[pathlib.Path('../shopping-observer/out/test-deps/json-20240303.jar'),pathlib.Path('../surfaces/out/host-wire/json-20240303.jar')]
    cached=next((p for p in candidates if p.exists() and hashlib.sha256(p.read_bytes()).hexdigest()==digest),None)
    data=cached.read_bytes() if cached else urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
    assert hashlib.sha256(data).hexdigest()==digest
    target.write_bytes(data)
PY
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -cp out/host-prompts/json-20240303.jar -d out/host-prompts \
  contract/org/agentos/proactivity/api/*.java ../surfaces/contract/org/agentos/surfaces/SurfaceSpec.java \
  src/org/agentos/proactivity/PromptPresentation.java src/org/agentos/proactivity/RegistryStore.java \
  tests/host/android/content/Context.java tests/host/android/util/AtomicFile.java tests/PromptContractTest.java
"$task_java" -cp out/host-prompts:out/host-prompts/json-20240303.jar org.agentos.proactivity.PromptContractTest
