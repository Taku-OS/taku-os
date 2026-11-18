#!/bin/bash
# Real Android Codex/Magpie with a labelled local upstream protocol fixture.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
export ADB_SERVER_SOCKET="${ADB_SERVER_SOCKET:-tcp:5037}"
export ADB
: "${TAKU_SERIAL:?Select the development emulator with TAKU_SERIAL}"
[[ "$("$ADB" -s "$TAKU_SERIAL" emu avd name | head -1 | tr -d '\r')" == Taku_ImageIntegration_20261010 ]] || exit 1
"$ADB" -s "$TAKU_SERIAL" root
"$ADB" -s "$TAKU_SERIAL" wait-for-device
OUTPUT=out/magpie-tests/probe
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" system/assistant/tests/org/agentos/tests/NativeSocketProbe.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
REMOTE=/data/local/tmp/taku-magpie-probe.jar
trap '"$ADB" -s "$TAKU_SERIAL" shell rm -f "$REMOTE"' EXIT
"$ADB" -s "$TAKU_SERIAL" push "$OUTPUT/test.jar" "$REMOTE"
python3 scripts/test-magpie-runtime.py --serial "$TAKU_SERIAL" --probe "$REMOTE"
