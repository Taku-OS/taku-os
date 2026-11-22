#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Actual compatibility bridge tests only; no device, camera, key or model access.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
APP="$ROOT/system/shopping-observer"
OUT="$ROOT/out/shopping-observer-pixel-compat-tests"
mkdir -p "$OUT" "$APP/out/test-deps"
python3 - "$APP/out/test-deps/json-20240303.jar" "$ROOT" <<'PY'
import hashlib,pathlib,sys,urllib.request
target=pathlib.Path(sys.argv[1]);root=pathlib.Path(sys.argv[2])
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
    candidates=[root/'system/proactivity/out/host-prompts/json-20240303.jar',root/'system/surfaces/out/host-wire/json-20240303.jar']
    cached=next((p for p in candidates if p.exists() and hashlib.sha256(p.read_bytes()).hexdigest()==digest),None)
    data=cached.read_bytes() if cached else urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
    assert hashlib.sha256(data).hexdigest()==digest
    target.write_bytes(data)
PY
python3 "$HERE/tests/test-phone-progress.py"
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
JSON="$APP/out/test-deps/json-20240303.jar"
"$task_javac" --release 17 -cp "$JSON" -d "$OUT" \
  "$ROOT"/system/proactivity/contract/org/agentos/proactivity/api/*.java \
  "$HERE/src/org/agentos/proactivity/api/PixelLegacyProactivity.java" \
  "$HERE/tests/PixelLegacyProactivityTest.java"
"$task_java" -cp "$OUT:$JSON" PixelLegacyProactivityTest
