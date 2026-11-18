#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
export ADB_SERVER_SOCKET=tcp:5037
SERIAL=emulator-5634
TEST_CLASS=org.agentos.tests.AgentInterfaceTest
EXPECTED_TESTS=4
if [[ "$#" != 0 ]]; then
 [[ "$#" == 2 && "$1" == --test ]] || { echo 'Usage: test-agent-interface.sh [--test testNativeChineseStream|testNativeItems|testNavigationAndDraft|testNativeMarkdown|testModelConnection]' >&2; exit 2; }
 case "$2" in testNativeChineseStream|testNativeItems|testNavigationAndDraft|testNativeMarkdown|testModelConnection) ;; *) exit 2 ;; esac
 if [[ "$2" == testModelConnection ]]; then TEST_CLASS=org.agentos.tests.ModelConnectionUiTest; fi
 TEST_CLASS="$TEST_CLASS#$2"
 EXPECTED_TESTS=1
fi
[[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" == Taku_ImageIntegration_20261010 ]] || exit 1
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
OUTPUT="../../out/native-conversation-ui/$RUN_ID"
DEVICE_OUTPUT="/data/local/tmp/taku-agent-interface-$RUN_ID/"
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$OUTPUT/classes" tests/org/agentos/tests/AgentInterfaceTest.java tests/org/agentos/tests/ModelConnectionUiTest.java tests/org/agentos/tests/NativeSocketProbe.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --lib "$SDK/platforms/android-36/uiautomator.jar" --lib "$SDK/platforms/android-36/optional/android.test.base.jar" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
REMOTE=/data/local/tmp/taku-agent-interface.jar
cleanup() {
 if "$ADB" -s "$SERIAL" pull "$DEVICE_OUTPUT" "$OUTPUT/device" >/dev/null 2>&1; then
  "$ADB" -s "$SERIAL" shell rm -rf "$DEVICE_OUTPUT"
 fi
 "$ADB" -s "$SERIAL" shell rm -f "$REMOTE"
}
trap cleanup EXIT
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" "$REMOTE"
"$ADB" -s "$SERIAL" shell timeout 420 uiautomator runtest /system/framework/android.test.base.jar "$REMOTE" -c "$TEST_CLASS" -e output "$DEVICE_OUTPUT" > "$OUTPUT/ui-test.log"
python3 - "$OUTPUT/ui-test.log" "$EXPECTED_TESTS" <<'PY'
import sys
text=open(sys.argv[1]).read()
assert f'OK ({sys.argv[2]} test' in text and 'FAILURES' not in text,text
print(text)
PY
