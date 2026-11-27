#!/bin/bash
# Host-only regression for Taku Account encryption store, auth state machine, and Broker loopback relay.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")/../.."
source scripts/android-env.sh
OUTPUT=out/taku-account-host
mkdir -p "$OUTPUT/classes"
JSON_JAR="${TAKU_TEST_JSON_JAR:-$PWD/out/jev-magpie-host/json-20240303.jar}"
if [ ! -f "$JSON_JAR" ]; then
  JSON_JAR="$OUTPUT/json-20240303.jar"
  python3 - <<'PY'
from pathlib import Path
import hashlib, urllib.request
p = Path('out/taku-account-host/json-20240303.jar')
expected = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest() != expected:
    with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30) as response:
        data = response.read()
    assert hashlib.sha256(data).hexdigest() == expected, 'Test-only org.json checksum mismatch'
    p.write_bytes(data)
PY
fi
CP="$JSON_JAR:$ANDROID_JAR"
"$JDK/bin/javac" --release 17 --add-modules jdk.httpserver -cp "$CP" -d "$OUTPUT/classes" \
  system/assistant/src/org/agentos/assistant/TakuAccountStore.java \
  system/assistant/src/org/agentos/assistant/TakuAccountClient.java \
  system/assistant/src/org/agentos/assistant/TakuAuthManager.java \
  system/assistant/tests/org/agentos/assistant/TakuAccountStoreTest.java \
  system/assistant/tests/org/agentos/assistant/TakuAuthManagerTest.java \
  system/assistant/broker/org/agentos/lab/AiPolicy.java \
  system/assistant/broker/org/agentos/lab/MagpieProviderSecrets.java \
  system/assistant/broker/org/agentos/lab/SystemAiSettings.java \
  system/assistant/broker/org/agentos/lab/TakuCreditsCatalog.java \
  system/assistant/broker/org/agentos/lab/TakuProxyRelay.java \
  system/assistant/tests/org/agentos/lab/TakuProxyRelayTest.java
for test in \
  org.agentos.assistant.TakuAccountStoreTest \
  org.agentos.assistant.TakuAuthManagerTest \
  org.agentos.lab.TakuProxyRelayTest; do
  "$JDK/bin/java" --add-modules jdk.httpserver -cp "$OUTPUT/classes:$CP" "$test"
done | tee "$OUTPUT/result.txt"
