#!/bin/bash
# Synthetic host tests only. No device, deployment, model or source-video network calls.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/notebook-host"
task_json="${TAKU_TEST_JSON_JAR:-$PWD/out/ime-replies-host/json-20240303.jar}"
mkdir -p "$task_out/classes" "$(dirname "$task_json")"
# Same pinned test-only JSON dependency used by the existing assistant host suites.
python3 - "$task_json" <<'PY'
import hashlib, pathlib, sys, urllib.request
path = pathlib.Path(sys.argv[1])
digest = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not path.exists():
    data = urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar', timeout=30).read()
    if hashlib.sha256(data).hexdigest() != digest: raise SystemExit('Unexpected host JSON checksum')
    path.write_bytes(data)
if hashlib.sha256(path.read_bytes()).hexdigest() != digest: raise SystemExit('Unexpected host JSON checksum')
PY
"$JDK/bin/javac" --release 17 -cp "$task_json" -d "$task_out/classes" \
  system/ai/client/org/agentos/ai/NotebookSocialSource.java \
  system/notebook/src/org/agentos/notebook/NotebookRecall.java \
  system/assistant/broker/org/agentos/lab/NotebookVideoSource.java \
  system/assistant/broker/org/agentos/lab/NotebookXPostEvidence.java \
  system/assistant/broker/org/agentos/lab/AgentViewProjection.java \
  system/notebook/tests/org/agentos/notebook/NotebookRecallTest.java \
  system/notebook/tests/org/agentos/lab/*.java
for task_test in org.agentos.notebook.NotebookRecallTest org.agentos.lab.NotebookVideoSourceTest org.agentos.lab.NotebookXPostEvidenceTest org.agentos.lab.NotebookVideoProjectionProbe; do
  "$JDK/bin/java" -cp "$task_out/classes:$task_json" "$task_test"
done
node --check system/notebook/assets/library/library.js
