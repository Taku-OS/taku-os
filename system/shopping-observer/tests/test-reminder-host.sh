#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/test-deps/json-20240303.jar}"
python3 - "$task_json" <<'PY'
import hashlib,pathlib,sys,urllib.request
path=pathlib.Path(sys.argv[1]);digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not path.exists():
    data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
    assert hashlib.sha256(data).hexdigest()==digest
    path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
assert hashlib.sha256(path.read_bytes()).hexdigest()==digest
PY
mkdir -p out/reminder-host-tests
task_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
task_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$task_javac" --release 17 -cp "$task_json" -d out/reminder-host-tests \
  ../proactivity/contract/org/agentos/proactivity/api/*.java \
  tests/reminder-host-stubs/android/content/Context.java tests/reminder-host-stubs/org/agentos/proactivity/api/ProactivityClient.java \
  src/org/agentos/shopping/ReminderConnection.java tests/reminder-host/org/agentos/shopping/ReminderAcceptanceTest.java
"$task_java" -cp "out/reminder-host-tests:$task_json" org.agentos.shopping.ReminderAcceptanceTest
