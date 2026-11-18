#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=emulator-5634
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = Taku_ImageIntegration_20261010 ] || { echo 'Wrong isolated AVD'; exit 1; }
EVIDENCE=../../out/agent-drawer-ui
mkdir -p "$EVIDENCE/classes" "$EVIDENCE/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$EVIDENCE/classes" tests/org/agentos/tests/SystemDrawerTest.java
"$JDK/bin/jar" --create --file "$EVIDENCE/classes.jar" -C "$EVIDENCE/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$EVIDENCE/dex" "$EVIDENCE/classes.jar"
"$JDK/bin/jar" --create --file "$EVIDENCE/test.jar" -C "$EVIDENCE/dex" classes.dex
"$ADB" -s "$SERIAL" push "$EVIDENCE/test.jar" /data/local/tmp/taku-system-drawer-test.jar
"$ADB" -s "$SERIAL" shell timeout 180 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-system-drawer-test.jar -c org.agentos.tests.SystemDrawerTest > "$EVIDENCE/ui-test.txt"
python3 - <<'PY'
from pathlib import Path
result=Path('../../out/agent-drawer-ui/ui-test.txt').read_text()
assert 'OK (2 tests)' in result and 'FAILURES' not in result, result
print('PASS: native system drawer, native thread titles/list, themes/drafts, IME, real missing-login path, dismiss to foreground app, invocation screenshot. No model success simulated.')
PY
for name in dark sessions settings-dark settings-light account-idle light keyboard needs-account restored-app invocation-context; do
 "$ADB" -s "$SERIAL" pull "/data/local/tmp/drawer-$name.png" "$EVIDENCE/$name.png"
done

"$ADB" -s "$SERIAL" pull /data/local/tmp/drawer-account-acceptance.json "$EVIDENCE/account-acceptance.json"
for name in account-cancelled account-error; do
 if "$ADB" -s "$SERIAL" shell test -f "/data/local/tmp/drawer-$name.png"; then "$ADB" -s "$SERIAL" pull "/data/local/tmp/drawer-$name.png" "$EVIDENCE/$name.png"; fi
done
