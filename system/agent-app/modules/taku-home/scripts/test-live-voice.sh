#!/usr/bin/env bash
# Host-only: which Taku questions Live Talk may answer by voice.
set -euo pipefail
MODULE=$(cd "$(dirname "$0")/.." && pwd)
ROOT=$(cd "$MODULE/../../../.." && pwd)
source "$ROOT/scripts/android-env.sh"
source "$ROOT/scripts/compile-jvm.sh"
OUT="$ROOT/out/live-voice-tests"
rm -rf "$OUT/classes"; mkdir -p "$OUT/classes"
python3 - "$OUT" <<'PY'
import hashlib,pathlib,sys,urllib.request
p=pathlib.Path(sys.argv[1])/'json-20240303.jar'
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:
 b=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
 assert hashlib.sha256(b).hexdigest()==digest
 p.write_bytes(b)
PY
compile_jvm "$OUT/classes" "$OUT/json-20240303.jar" \
  "$MODULE/android/src/main/java/org/agentos/agent/home/assistant/LiveVoiceAnswer.kt" \
  "$MODULE/tests/live-voice/LiveVoiceAnswerTest.kt"
"$JDK/bin/java" -cp "$OUT/classes:$OUT/json-20240303.jar:$KOTLIN_LIB/kotlin-stdlib.jar" org.agentos.agent.home.LiveVoiceAnswerTestKt
