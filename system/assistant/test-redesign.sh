#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5582
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AgentOS_ImageCheck ]
mkdir -p out/desktop-test-classes out/desktop-test-dex evidence/desktop-redesign
"$JDK/bin/javac" --release 17 -cp "$SDK/platforms/android-36/android.jar:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d out/desktop-test-classes tests/org/agentos/tests/DesktopEntryTest.java
"$JDK/bin/jar" --create --file out/desktop-test-classes.jar -C out/desktop-test-classes .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/desktop-test-dex out/desktop-test-classes.jar
"$JDK/bin/jar" --create --file out/desktop-entry-test.jar -C out/desktop-test-dex classes.dex
"$ADB" -s "$SERIAL" push out/desktop-entry-test.jar /data/local/tmp/desktop-entry-test.jar
for test_method in testRedesignedNavigation testDraftVisible; do
 "$ADB" -s "$SERIAL" shell uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/desktop-entry-test.jar -c "org.agentos.tests.DesktopEntryTest#$test_method" > "evidence/desktop-redesign/$test_method.txt"
 python3 - "$test_method" <<'PY'
from pathlib import Path
import sys
s=Path('evidence/desktop-redesign/'+sys.argv[1]+'.txt').read_text()
assert 'OK (1 test)' in s and 'FAILURES' not in s and 'unexpected exception' not in s,s
print('PASS:',sys.argv[1])
PY
done
"$ADB" -s "$SERIAL" pull /data/local/tmp/codex-redesign-drawer.png evidence/desktop-redesign/04-drawer.png
"$ADB" -s "$SERIAL" pull /data/local/tmp/codex-inline-draft.png evidence/desktop-redesign/03-input.png
"$ADB" -s "$SERIAL" exec-out screencap -p > evidence/desktop-redesign/02-home.png
