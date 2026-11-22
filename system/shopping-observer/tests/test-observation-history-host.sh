#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")/.."
history_json="${TAKU_TEST_JSON_JAR:-$PWD/out/test-deps/json-20240303.jar}"
python3 - "$history_json" <<'FETCH'
import hashlib, pathlib, sys, urllib.request
p=pathlib.Path(sys.argv[1]); digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not p.exists():
 data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
 assert hashlib.sha256(data).hexdigest()==digest
 p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(data)
assert hashlib.sha256(p.read_bytes()).hexdigest()==digest, 'JSON test dependency checksum mismatch'
FETCH
mkdir -p out/history-host-tests
history_javac="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
history_java="${JAVA_HOME:+$JAVA_HOME/bin/}java"
"$history_javac" --release 17 -cp "$history_json" -d out/history-host-tests \
  tests/history-host-stubs/android/content/Context.java \
  tests/history-host-stubs/android/content/SharedPreferences.java \
  tests/history-host-stubs/android/os/Bundle.java \
  core/org/agentos/shopping/LivePage.java src/org/agentos/shopping/ObservationHistory.java \
  src/org/agentos/shopping/Preferences.java \
  tests/history-host/org/agentos/shopping/ObservationHistoryTest.java \
  tests/history-host/org/agentos/shopping/PreferencesTest.java
"$history_java" -cp "out/history-host-tests:$history_json" org.agentos.shopping.ObservationHistoryTest

"$history_java" -cp "out/history-host-tests:$history_json" org.agentos.shopping.PreferencesTest
