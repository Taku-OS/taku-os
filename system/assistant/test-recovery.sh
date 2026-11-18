#!/bin/bash
# Real UI/process-death recovery and upstream reply. Does not change login state.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
# Always use this host's isolated AVD, regardless of an inherited remote ADB environment.
unset ANDROID_ADB_SERVER_PORT ADB_SERVER_PORT ANDROID_SERIAL
export ADB_SERVER_SOCKET=tcp:5037
SERIAL=emulator-5634
[[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" == Taku_ImageIntegration_20261010 ]] || exit 1
[[ "$("$ADB" -s "$SERIAL" shell id -u | tr -d '\r')" == 0 ]] || { echo 'Root ADB required on isolated AVD.'; exit 1; }
OUTPUT=../../out/agent-recovery-tests
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
# Preserve only relevant fixture settings and the selected conversation, never auth material.
sound=$("$ADB" -s "$SERIAL" shell settings get system sound_effects_enabled | tr -d '\r')
stay=$("$ADB" -s "$SERIAL" shell settings get global stay_on_while_plugged_in | tr -d '\r')
conversation=$("$ADB" -s "$SERIAL" exec-out cat /data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml | python3 -c 'import sys,xml.etree.ElementTree as E;r=E.fromstring(sys.stdin.read());print(next((n.text or "" for n in r if n.get("name")=="conversation"),""))')
restore(){
 "$ADB" -s "$SERIAL" shell settings put system sound_effects_enabled "$sound" >/dev/null || true
 "$ADB" -s "$SERIAL" shell settings put global stay_on_while_plugged_in "$stay" >/dev/null || true
 if [[ "$conversation" =~ ^[a-zA-Z0-9-]+$ ]]; then "$ADB" -s "$SERIAL" shell am start -n org.agentos.assistant/.PanelActivity --es conversationId "$conversation" >/dev/null || true; fi
}
trap restore EXIT
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$OUTPUT/classes" tests/org/agentos/tests/DrawerRecoveryTest.java tests/org/agentos/tests/NativeSocketProbe.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --lib "$SDK/platforms/android-36/uiautomator.jar" --lib "$SDK/platforms/android-36/optional/android.test.base.jar" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" /data/local/tmp/taku-recovery-tests.jar
"$ADB" -s "$SERIAL" shell settings put system sound_effects_enabled 0
"$ADB" -s "$SERIAL" shell svc power stayon true
"$ADB" -s "$SERIAL" shell input keyevent 224
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
"$ADB" -s "$SERIAL" shell timeout 360 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-recovery-tests.jar -c org.agentos.tests.DrawerRecoveryTest > "$OUTPUT/ui-test.txt" 2>&1
python3 - "$OUTPUT/ui-test.txt" <<'PY'
from pathlib import Path
import re,sys
log=Path(sys.argv[1]).read_text()
assert 'OK (1 test)' in log and not re.search(r'FAILURES|aborted|shortMsg=|INSTRUMENTATION_STATUS_CODE: -2',log),log
print('PASS: real app-process death preserves draft, background draft and actual upstream task/result. No model success simulated.')
PY
# Reboot only after the task has completed; never replay phone writes automatically.
"$ADB" -s "$SERIAL" shell timeout 45 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-recovery-tests.jar -c org.agentos.tests.DrawerRecoveryTest -e phase prepareReboot > "$OUTPUT/reboot-before.txt" 2>&1
python3 - "$OUTPUT/reboot-before.txt" <<'PY'
from pathlib import Path
import re,sys
log=Path(sys.argv[1]).read_text();assert 'OK (1 test)' in log and not re.search(r'FAILURES|aborted|shortMsg=|INSTRUMENTATION_STATUS_CODE: -2',log),log
PY
printf '%s\n' 'Rebooting the isolated AVD; account and userdata remain in place.'
"$ADB" -s "$SERIAL" reboot
python3 - "$ADB" "$SERIAL" <<'PY'
import subprocess,sys,time
adb,serial=sys.argv[1:];until=time.monotonic()+180
while time.monotonic()<until:
 try:
  r=subprocess.run([adb,'-s',serial,'shell','getprop','sys.boot_completed'],capture_output=True,text=True,timeout=4)
  if r.returncode==0 and r.stdout.strip()=='1':break
 except subprocess.TimeoutExpired:pass
 time.sleep(1)
else:raise SystemExit('AVD reboot did not complete')
PY
"$ADB" -s "$SERIAL" root
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" shell input keyevent 224
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
python3 - "$ADB" "$SERIAL" "$OUTPUT/runtime-ready.json" <<'PY'
# Android's boot flag can precede the native conversation service. Gate the UI check on
# reading this test's existing thread; no model request or replacement draft is created.
import json,re,subprocess,sys,time
adb,serial,output=sys.argv[1:]
accepted=json.loads(subprocess.check_output([adb,'-s',serial,'exec-out','cat','/data/local/tmp/recovery-acceptance.json'],text=True))
thread=accepted['threadId'];assert re.fullmatch(r'[a-f0-9-]{36}',thread)
params=json.dumps({'threadId':thread,'includeTurns':True},separators=(',',':'))
command="CLASSPATH=/data/local/tmp/taku-recovery-tests.jar app_process /system/bin org.agentos.tests.NativeSocketProbe rpc thread/read '"+params+"'"
until=time.monotonic()+120;attempts=0
while time.monotonic()<until:
 attempts+=1
 try:
  result=subprocess.run([adb,'-s',serial,'shell',command],capture_output=True,text=True,timeout=min(50,max(1,until-time.monotonic())))
  if result.returncode==0 and json.loads(result.stdout)['thread']['id']==thread:break
 except (subprocess.TimeoutExpired,ValueError,KeyError):pass
 time.sleep(1)
else:raise SystemExit('Native conversation service did not become ready')
with open(output,'w') as out:json.dump({'nativeReadReady':True,'attempts':attempts,'threadId':thread},out,indent=2)
PY
"$ADB" -s "$SERIAL" shell timeout 120 uiautomator runtest /system/framework/android.test.base.jar /data/local/tmp/taku-recovery-tests.jar -c org.agentos.tests.DrawerRecoveryTest -e phase verifyReboot > "$OUTPUT/reboot-after.txt" 2>&1
python3 - "$OUTPUT/reboot-after.txt" <<'PY'
from pathlib import Path
import re,sys
log=Path(sys.argv[1]).read_text();assert 'OK (1 test)' in log and not re.search(r'FAILURES|aborted|shortMsg=|INSTRUMENTATION_STATUS_CODE: -2',log),log
print('PASS: real Android reboot preserves draft and completed upstream reply history.')
PY
for name in draft-before draft-after completed reboot-before reboot-after; do "$ADB" -s "$SERIAL" pull "/data/local/tmp/recovery-$name.png" "$OUTPUT/$name.png"; done
"$ADB" -s "$SERIAL" pull /data/local/tmp/recovery-acceptance.json "$OUTPUT/acceptance.json"
