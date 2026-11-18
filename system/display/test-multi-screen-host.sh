#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/system/display/out/multi-screen-host"
task_json="$task_out/json-20240303.jar"
mkdir -p "$task_out/classes"
python3 - "$task_json" <<'PY'
import hashlib,pathlib,sys,urllib.request
path=pathlib.Path(sys.argv[1])
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:
    data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
    assert hashlib.sha256(data).hexdigest()==digest
    path.write_bytes(data)
PY
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
 system/display/src/org/agentos/lab/DisplaySlot.java \
 system/display/tests/org/agentos/lab/DisplaySlotTest.java
"$JDK/bin/java" -cp "$task_out/classes:$task_json" org.agentos.lab.DisplaySlotTest
python3 system/display/tests/test_phone_display.py
