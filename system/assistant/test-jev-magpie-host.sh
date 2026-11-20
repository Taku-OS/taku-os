#!/bin/bash
# Host-only regression: real loopback HTTP with a synthetic gateway; no user keys or model calls.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")/../.."
source scripts/android-env.sh
OUTPUT=out/jev-magpie-host
mkdir -p "$OUTPUT/classes"
python3 - <<'PY'
from pathlib import Path
import hashlib,urllib.request
p=Path('out/jev-magpie-host/json-20240303.jar')
expected='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=expected:
 with urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30) as response:data=response.read()
 assert hashlib.sha256(data).hexdigest()==expected,'Test-only org.json checksum mismatch'
 p.write_bytes(data)
PY
CP="$OUTPUT/json-20240303.jar:$ANDROID_JAR"
"$JDK/bin/javac" --release 17 --add-modules jdk.httpserver -cp "$CP" -d "$OUTPUT/classes" \
 system/assistant/broker/org/agentos/lab/JevDecisionClient.java \
 system/assistant/broker/org/agentos/lab/MagpieDecisions.java \
 system/assistant/tests/host/MagpieSettingsStub.java \
 system/assistant/tests/org/agentos/lab/JevDecisionClientTest.java \
 system/assistant/tests/host/JevMagpieTest.java \
 system/assistant/src/org/agentos/assistant/JevCredentialStore.java \
 system/assistant/tests/org/agentos/assistant/JevCredentialStoreTest.java \
 system/assistant/src/org/agentos/assistant/SceneRoutes.java \
 system/assistant/tests/org/agentos/assistant/SceneRoutesTest.java \
 system/assistant/src/org/agentos/assistant/SystemAiCompatibility.java \
 system/assistant/tests/org/agentos/assistant/SystemAiCompatibilityTest.java \
 system/assistant/src/org/agentos/assistant/AgentConversation.java \
 system/assistant/src/org/agentos/assistant/NativeToolPresentation.java \
 system/assistant/tests/org/agentos/assistant/NativeConversationTest.java
for test in org.agentos.lab.JevDecisionClientTest org.agentos.lab.JevMagpieTest \
 org.agentos.assistant.JevCredentialStoreTest org.agentos.assistant.SceneRoutesTest org.agentos.assistant.SystemAiCompatibilityTest \
 org.agentos.assistant.NativeConversationTest; do
 "$JDK/bin/java" --add-modules jdk.httpserver -cp "$OUTPUT/classes:$CP" "$test"
done | tee "$OUTPUT/result.txt"
