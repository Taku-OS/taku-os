#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
ADB="$SDK/platform-tools/adb"
PROMPT="在随手记保存一条内容为“桌面直接输入测试”的笔记，不改动已有笔记。通过 /data/local/agentos/light-app-lab/free-app-kit/mini-app 接口操作，使用幂等键 desktop-inline-20261009，并查询确认。"
PROMPT_BASE64=$(python3 -c 'import base64,sys; print(base64.b64encode(sys.argv[1].encode()).decode())' "$PROMPT")
mkdir -p out/desktop-test-classes out/desktop-test-dex evidence/desktop
"$JDK/bin/javac" --release 17 -cp "$SDK/platforms/android-36/android.jar:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d out/desktop-test-classes tests/org/agentos/tests/DesktopEntryTest.java
"$JDK/bin/jar" --create --file out/desktop-test-classes.jar -C out/desktop-test-classes .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/desktop-test-dex out/desktop-test-classes.jar
"$JDK/bin/jar" --create --file out/desktop-entry-test.jar -C out/desktop-test-dex classes.dex
"$ADB" -s emulator-5582 push out/desktop-entry-test.jar /data/local/tmp/desktop-entry-test.jar
"$ADB" -s emulator-5582 shell uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/desktop-entry-test.jar -c org.agentos.tests.DesktopEntryTest#testSubmitFromDesktop -e prompt_base64 "$PROMPT_BASE64" > evidence/desktop/ui-test.txt
python3 - <<'PY'
from pathlib import Path
s=Path('evidence/desktop/ui-test.txt').read_text()
assert 'OK (1 test)' in s and 'unexpected exception' not in s and 'FAILURES' not in s,s
print('PASS: native Home editor, exact Unicode text entry, inline submission, app launch and return. Backend result needs independent verification.')
PY
"$ADB" -s emulator-5582 pull /data/local/tmp/codex-desktop-typed.png evidence/desktop/typed.png
