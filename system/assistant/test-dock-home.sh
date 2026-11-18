#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5582
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AgentOS_ImageCheck ]
EVIDENCE=evidence/layout-dock
TEST_DIR="$EVIDENCE/test-runtime"
mkdir -p "$TEST_DIR/classes" "$TEST_DIR/dex"
"$JDK/bin/javac" --release 17 -cp "$SDK/platforms/android-36/android.jar:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$TEST_DIR/classes" tests/org/agentos/tests/DockHomeTest.java
"$JDK/bin/jar" --create --file "$TEST_DIR/classes.jar" -C "$TEST_DIR/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$TEST_DIR/dex" "$TEST_DIR/classes.jar"
"$JDK/bin/jar" --create --file "$TEST_DIR/test.jar" -C "$TEST_DIR/dex" classes.dex
"$ADB" -s "$SERIAL" push "$TEST_DIR/test.jar" /data/local/tmp/dock-home-test.jar
"$ADB" -s "$SERIAL" shell timeout 70 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/dock-home-test.jar -c org.agentos.tests.DockHomeTest#testDockAndEntrypoints > "$EVIDENCE/ui-test.txt"
python3 - <<'PY'
from pathlib import Path
s=Path('evidence/layout-dock/ui-test.txt').read_text()
assert 'OK (1 test)' in s and 'FAILURES' not in s and 'unexpected exception' not in s,s
print('PASS: native four App Dock, real App launch, Make/Mirror/widget access; no model submit')
PY
for state in home app-0 app-1 app-2 app-3 make mirror drawer widget-added widget-removed; do
 "$ADB" -s "$SERIAL" pull "/data/local/tmp/taku-layout-$state.png" "$EVIDENCE/$state.png"
 "$ADB" -s "$SERIAL" pull "/data/local/tmp/taku-layout-$state.xml" "$EVIDENCE/$state.xml"
done
