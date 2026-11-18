#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5582
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AgentOS_ImageCheck ]
EVIDENCE_DIR="${TAKU_HOME_EVIDENCE_DIR:-evidence/video-ui}"
TEST_DIR="$EVIDENCE_DIR/test-runtime"
mkdir -p "$TEST_DIR/classes" "$TEST_DIR/dex"
"$JDK/bin/javac" --release 17 -cp "$SDK/platforms/android-36/android.jar:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$TEST_DIR/classes" tests/org/agentos/tests/VideoHomeTest.java
"$JDK/bin/jar" --create --file "$TEST_DIR/classes.jar" -C "$TEST_DIR/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$TEST_DIR/dex" "$TEST_DIR/classes.jar"
"$JDK/bin/jar" --create --file "$TEST_DIR/test.jar" -C "$TEST_DIR/dex" classes.dex
"$ADB" -s "$SERIAL" push "$TEST_DIR/test.jar" /data/local/tmp/video-home-test.jar
"$ADB" -s "$SERIAL" shell timeout 90 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/video-home-test.jar -c org.agentos.tests.VideoHomeTest#testHomeFlow > "$EVIDENCE_DIR/ui-test.txt"
python3 - "$EVIDENCE_DIR/ui-test.txt" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
assert 'OK (1 test)' in s and 'FAILURES' not in s and 'unexpected exception' not in s,s
print('PASS: native Home, direct editing, drawer, Make, focus timer, app switching')
PY
for state in home input drawer make; do
 "$ADB" -s "$SERIAL" pull "/data/local/tmp/taku-video-$state.png" "$EVIDENCE_DIR/$state.png"
 "$ADB" -s "$SERIAL" pull "/data/local/tmp/taku-video-$state.xml" "$EVIDENCE_DIR/$state.xml"
done
