#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Compile actual Lucy adapter against the Android SDK and already verified deps.
# Runtime uses fake Handler/Context actors only; startup/JNI/socket work never runs.
set -euo pipefail
MODULE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$MODULE/../.." && pwd)"
source "$REPO/scripts/android-env.sh"
DEPS="${TAKU_TRYON_NATIVE_DEPS:-$REPO/out/tryon-native-deps}"
python3 "$MODULE/app/prepare-native-deps.py" --output "$DEPS" >/dev/null
CP="$(cat "$DEPS/classpath.txt")"
JSON_JAR="${TAKU_TEST_JSON_JAR:-$REPO/out/tryon/test-deps/json-20240303.jar}"
python3 - "$JSON_JAR" <<'PY'
import hashlib,pathlib,sys,urllib.request
path=pathlib.Path(sys.argv[1]);digest='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not path.exists():
    data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',timeout=30).read()
    assert hashlib.sha256(data).hexdigest()==digest
    path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
assert hashlib.sha256(path.read_bytes()).hexdigest()==digest
PY
OUT="$REPO/out/tryon/rtc-cleanup-tests"
mkdir -p "$OUT"
SRC="$MODULE/app/src/org/taku/tryon/realtime"
SOURCES=()
for name in AttemptOwner RealtimeCallTransport CallTryOnRtc CallTryOnRtcDiagnostics CallTryOnDiagnosticStore CallTryOnFailures FalSignalingSocketFactory FalRealtimeProfile FalSignalCodec FalSignalPolicy FalModelUpdate RtcPixelConversion;do SOURCES+=("$SRC/$name.java");done
find "$MODULE/tests/rtc-cleanup" -name '*.java' > "$OUT/fixtures.txt"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$CP" -d "$OUT" "${SOURCES[@]}" @"$OUT/fixtures.txt"
"$JDK/bin/java" -cp "$OUT:$JSON_JAR:$CP:$ANDROID_JAR" org.taku.tryon.realtime.LucyCleanupTest
"$JDK/bin/java" -cp "$OUT:$JSON_JAR:$CP:$ANDROID_JAR" org.taku.tryon.realtime.RtcBackpressureTest
"$JDK/bin/java" -cp "$OUT:$JSON_JAR:$CP:$ANDROID_JAR" org.taku.tryon.realtime.RtcInformationalBurstTest
"$JDK/bin/java" -cp "$OUT:$JSON_JAR:$CP:$ANDROID_JAR" org.taku.tryon.realtime.SignalingCloseTest
