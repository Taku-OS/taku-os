#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
out="$PWD/out/ime-replies-host"
mkdir -p "$out/classes"
python3 - "$out" <<'PY'
import hashlib,pathlib,sys,urllib.request
p=pathlib.Path(sys.argv[1])/'json-20240303.jar'
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:
 b=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
 assert hashlib.sha256(b).hexdigest()==digest
 p.write_bytes(b)
PY
"$JDK/bin/javac" --release 17 -cp "$out/json-20240303.jar" -d "$out/classes" \
 system/assistant/broker/org/agentos/lab/ImeReplyJson.java \
 system/assistant/broker/org/agentos/lab/ImeReplyError.java \
 system/assistant/broker/org/agentos/lab/ImeReplyReuse.java \
 system/assistant/tests/org/agentos/lab/ImeReplyJsonTest.java \
 system/assistant/tests/org/agentos/lab/ImeReplyErrorTest.java \
 system/assistant/tests/org/agentos/lab/ImeReplyReuseTest.java
{
 "$JDK/bin/java" -cp "$out/classes:$out/json-20240303.jar" org.agentos.lab.ImeReplyJsonTest
 "$JDK/bin/java" -cp "$out/classes:$out/json-20240303.jar" org.agentos.lab.ImeReplyErrorTest
 "$JDK/bin/java" -cp "$out/classes:$out/json-20240303.jar" org.agentos.lab.ImeReplyReuseTest
} | tee "$out/result.txt"
