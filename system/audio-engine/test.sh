#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/kotlin-env.sh
mkdir -p system/audio-engine/out/tests
"$KOTLINC" system/audio-engine/contract/org/agentos/audio/*.kt system/audio-engine/client/org/agentos/audio/SpeechChunks.kt system/audio-engine/src/org/agentos/audio/FileSpeechSegments.kt system/audio-engine/src/org/agentos/audio/RouteGate.kt system/audio-engine/src/org/agentos/audio/FeedGate.kt system/audio-engine/src/org/agentos/audio/Uninterruptible.kt system/audio-engine/src/org/agentos/audio/DictationPolicy.kt system/audio-engine/src/org/agentos/audio/PcmLevelMeter.kt system/audio-engine/src/org/agentos/audio/WhisperPcmRanges.kt system/audio-engine/src/org/agentos/audio/WhisperSignalGate.kt system/audio-engine/tests/RuntimeTest.kt system/audio-engine/tests/ConcurrencyTest.kt system/audio-engine/tests/DictationRuntimeTest.kt system/audio-engine/tests/DictationPolicyTest.kt system/audio-engine/tests/FalDictationRealtimeTest.kt -include-runtime -jvm-target 17 -d system/audio-engine/out/tests/runtime.jar
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.RuntimeTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.ConcurrencyTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.DictationRuntimeTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.DictationPolicyTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/runtime.jar org.agentos.audio.FalDictationRealtimeTestKt
# MOSS-Live wire format uses org.json; host runs pin the same JSON jar as the assistant host tests.
python3 - system/audio-engine/out/tests <<'PY'
import hashlib,pathlib,sys,urllib.request
p=pathlib.Path(sys.argv[1])/'json-20240303.jar'
digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:
 b=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
 assert hashlib.sha256(b).hexdigest()==digest
 p.write_bytes(b)
PY
"$KOTLINC" system/audio-engine/contract/org/agentos/audio/*.kt system/audio-engine/src/org/agentos/audio/MossWireProtocol.kt system/audio-engine/src/org/agentos/audio/AudioParameters.kt system/audio-engine/tests/MossWireProtocolTest.kt system/audio-engine/tests/AudioParametersLiveTest.kt -classpath system/audio-engine/out/tests/json-20240303.jar -include-runtime -jvm-target 17 -d system/audio-engine/out/tests/moss.jar
"$JDK/bin/java" -cp system/audio-engine/out/tests/moss.jar:system/audio-engine/out/tests/json-20240303.jar org.agentos.audio.MossWireProtocolTestKt
"$JDK/bin/java" -cp system/audio-engine/out/tests/moss.jar:system/audio-engine/out/tests/json-20240303.jar org.agentos.audio.AudioParametersLiveTestKt
