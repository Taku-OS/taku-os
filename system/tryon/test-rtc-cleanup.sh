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
OUT="$REPO/out/tryon/rtc-cleanup-tests"
mkdir -p "$OUT"
SRC="$MODULE/app/src/org/taku/tryon/realtime"
SOURCES=()
for name in AttemptOwner RealtimeCallTransport CallTryOnRtc CallTryOnRtcDiagnostics FalSignalingSocketFactory FalRealtimeProfile FalSignalCodec FalSignalPolicy FalModelUpdate RtcPixelConversion;do SOURCES+=("$SRC/$name.java");done
find "$MODULE/tests/rtc-cleanup" -name '*.java' > "$OUT/fixtures.txt"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$CP" -d "$OUT" "${SOURCES[@]}" @"$OUT/fixtures.txt"
"$JDK/bin/java" -cp "$OUT:$CP:$ANDROID_JAR" org.taku.tryon.realtime.LucyCleanupTest
