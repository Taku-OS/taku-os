#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
task_out="$PWD/out/confirmation-host"
task_json="$PWD/out/ime-replies-host/json-20240303.jar"
rm -rf "$task_out/classes"
mkdir -p "$task_out/classes" "$(dirname "$task_json")"
python3 - "$task_json" <<'PY'
import hashlib,pathlib,sys,urllib.request
path=pathlib.Path(sys.argv[1])
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:
    data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
    assert hashlib.sha256(data).hexdigest()==digest
    path.write_bytes(data)
PY
compile_jvm "$task_out/classes" "$task_json" \
 system/surfaces/contract/org/agentos/surfaces/SurfaceSpec.java \
 system/assistant/broker/org/agentos/lab/PhoneConfirmation.java \
 system/assistant/broker/org/agentos/lab/UiCardRequest.kt \
 system/assistant/tests/org/agentos/lab/UiCardRequestTest.java \
 system/assistant/src/org/agentos/assistant/BackgroundQuestion.java \
 system/assistant/src/org/agentos/assistant/BackgroundTaskResult.java \
 system/assistant/tests/org/agentos/lab/PhoneConfirmationTest.java \
 system/assistant/tests/org/agentos/assistant/BackgroundQuestionTest.java
{
 "$JDK/bin/java" -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.PhoneConfirmationTest
 "$JDK/bin/java" -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.lab.UiCardRequestTest
 "$JDK/bin/java" -cp "$task_out/classes:$task_json:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.assistant.BackgroundQuestionTest
} | tee "$task_out/result.txt"
