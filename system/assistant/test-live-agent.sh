#!/bin/bash
# Requires the user's already-connected device account. Never logs in, logs out, or changes auth.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=emulator-5634
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = Taku_ImageIntegration_20261010 ] || exit 1
OUTPUT=../../out/agent-live-ui
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$OUTPUT/classes" tests/org/agentos/tests/LiveAgentTest.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" /data/local/tmp/taku-live-agent-test.jar
"$ADB" -s "$SERIAL" shell timeout 300 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-live-agent-test.jar -c org.agentos.tests.LiveAgentTest > "$OUTPUT/ui-test.txt"
python3 - <<'PY'
from pathlib import Path
log=Path('../../out/agent-live-ui/ui-test.txt').read_text()
assert 'OK (1 test)' in log and 'FAILURES' not in log, log
print('PASS: real model reply, actual Android read-only commands, native completed turn, visible UI. No model results simulated.')
PY
for name in chat system-read connected-settings; do
 "$ADB" -s "$SERIAL" pull "/data/local/tmp/live-agent-$name.png" "$OUTPUT/$name.png"
done
"$ADB" -s "$SERIAL" pull /data/local/tmp/live-agent-acceptance.json "$OUTPUT/acceptance.json"
