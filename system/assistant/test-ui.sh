#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
ADB="$SDK/platform-tools/adb"
mkdir -p out/test-classes out/test-dex
"$JDK/bin/javac" --release 17 -cp "$SDK/platforms/android-36/android.jar:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d out/test-classes tests/org/agentos/tests/PanelFlowTest.java
"$JDK/bin/jar" --create --file out/test-classes.jar -C out/test-classes .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/test-dex out/test-classes.jar
"$JDK/bin/jar" --create --file out/panel-flow-test.jar -C out/test-dex classes.dex
"$ADB" -s emulator-5582 push out/panel-flow-test.jar /data/local/tmp/panel-flow-test.jar
"$ADB" -s emulator-5582 shell 'cmd voiceinteraction show'
"$ADB" -s emulator-5582 shell 'uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/panel-flow-test.jar -c org.agentos.tests.PanelFlowTest' > evidence/ui-test.txt
python3 - <<'PY'
from pathlib import Path
s=Path('evidence/ui-test.txt').read_text()
assert 'OK (1 test)' in s and 'unexpected exception' not in s and 'FAILURES' not in s,s
print('PASS: actual assistant text entry and submit. Model task result still needs separate verification.')
PY
