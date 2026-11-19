#!/bin/bash
# Exercise Expo process death and Android reboot on the dedicated local AVD.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
case "${1:-}" in
  '') [[ $# -eq 0 ]] || exit 2 ;;
  --compile-only) [[ $# -eq 1 ]] || exit 2 ;;
  *) echo 'Usage: test-recovery.sh [--compile-only]' >&2; exit 2 ;;
esac
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
OUTPUT="../../out/agent-recovery-tests/$RUN_ID"
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
OUTPUT="$(cd "$OUTPUT" && pwd)"
printf 'Recovery regression output: %s\n' "$OUTPUT"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$OUTPUT/classes" tests/org/agentos/tests/DrawerRecoveryTest.java tests/org/agentos/tests/NativeSocketProbe.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --lib "$SDK/platforms/android-36/uiautomator.jar" --lib "$SDK/platforms/android-36/optional/android.test.base.jar" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
if [[ ${1:-} == --compile-only ]]; then exit 0; fi

unset ANDROID_ADB_SERVER_PORT ADB_SERVER_PORT ANDROID_SERIAL
export ADB_SERVER_SOCKET=tcp:5037
SERIAL=${TAKU_SERIAL:-emulator-5634}
AVD=${TAKU_AVD:-Taku_ImageIntegration_20261010}
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Select an isolated emulator serial.' >&2; exit 1; }
[[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" == "$AVD" ]] || { echo 'Wrong AVD' >&2; exit 1; }
[[ "$("$ADB" -s "$SERIAL" shell id -u | tr -d '\r')" == 0 ]] || { echo 'Root ADB required on isolated AVD.' >&2; exit 1; }
"$ADB" -s "$SERIAL" shell pm path --user 0 org.agentos.agent | grep -q '^package:' || { echo 'Install the Expo Agent APK first.' >&2; exit 1; }
sound=$("$ADB" -s "$SERIAL" shell settings get system sound_effects_enabled | tr -d '\r')
stay=$("$ADB" -s "$SERIAL" shell settings get global stay_on_while_plugged_in | tr -d '\r')
# Read only the Kotlin backend's selection so the runner can reopen it on exit.
conversation=$("$ADB" -s "$SERIAL" exec-out cat /data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml | python3 -c 'import sys,xml.etree.ElementTree as E;r=E.fromstring(sys.stdin.read());print(next((n.text or "" for n in r if n.get("name")=="conversation"),""))')
[[ "$sound" =~ ^(null|[0-9]+)$ && "$stay" =~ ^(null|[0-9]+)$ && "$conversation" =~ ^[a-f0-9-]{36}$ ]] || { echo 'Cannot safely snapshot original settings/conversation.' >&2; exit 1; }
DEVICE_OUTPUT="/data/local/tmp/taku-recovery-tests/$RUN_ID"
DEVICE_JAR="/data/local/tmp/taku-recovery-$RUN_ID.jar"
restore() {
  original_exit=$?
  trap - EXIT
  set +e
  restore_failed=0
  if [[ "$sound" == null ]]; then "$ADB" -s "$SERIAL" shell settings delete system sound_effects_enabled; else "$ADB" -s "$SERIAL" shell settings put system sound_effects_enabled "$sound"; fi >/dev/null || restore_failed=1
  if [[ "$stay" == null ]]; then "$ADB" -s "$SERIAL" shell settings delete global stay_on_while_plugged_in; else "$ADB" -s "$SERIAL" shell settings put global stay_on_while_plugged_in "$stay"; fi >/dev/null || restore_failed=1
  "$ADB" -s "$SERIAL" shell am start -W --display 0 -a android.intent.action.MAIN -n org.agentos.agent/.MainActivity --es threadId "$conversation" > "$OUTPUT/restore-conversation.txt" 2>&1 || restore_failed=1
  if "$ADB" -s "$SERIAL" pull "$DEVICE_OUTPUT" "$OUTPUT/device" > "$OUTPUT/pull.txt" 2>&1; then
    "$ADB" -s "$SERIAL" shell rm -rf "$DEVICE_OUTPUT" >/dev/null || restore_failed=1
  else
    restore_failed=1
  fi
  "$ADB" -s "$SERIAL" shell rm -f "$DEVICE_JAR" >/dev/null || restore_failed=1
  if [[ "$restore_failed" != 0 ]]; then
    printf 'Recovery fixture restore failed; see %s\n' "$OUTPUT" >&2
    if [[ "$original_exit" == 0 ]]; then exit 1; fi
  fi
  exit "$original_exit"
}
trap restore EXIT
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" "$DEVICE_JAR"
"$ADB" -s "$SERIAL" shell settings put system sound_effects_enabled 0
"$ADB" -s "$SERIAL" shell svc power stayon true
"$ADB" -s "$SERIAL" shell input -d 0 keyevent 224
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
run_phase() {
  local log=$1 budget=$2
  shift 2
  "$ADB" -s "$SERIAL" shell timeout "$budget" uiautomator runtest /system/framework/android.test.base.jar "$DEVICE_JAR" \
    -c org.agentos.tests.DrawerRecoveryTest -e runId "$RUN_ID" "$@" > "$OUTPUT/$log" 2>&1
  python3 - "$OUTPUT/$log" <<'PY'
from pathlib import Path
import re,sys
log=Path(sys.argv[1]).read_text()
assert re.search(r'\bOK \(1 test\)',log) and not re.search(r'FAILURES|aborted|shortMsg=|INSTRUMENTATION_STATUS_CODE: -2',log),log
PY
}
run_phase ui-test.txt 360
run_phase reboot-before.txt 90 -e phase prepareReboot
previous_boot=$("$ADB" -s "$SERIAL" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
[[ "$previous_boot" =~ ^[a-f0-9-]{36}$ ]] || { echo 'Cannot read the original Android boot ID.' >&2; exit 1; }
printf '%s\n' 'Rebooting the isolated AVD; account and userdata remain in place.'
"$ADB" -s "$SERIAL" reboot
python3 - "$ADB" "$SERIAL" "$previous_boot" "$OUTPUT/boot.json" <<'PY'
import json,re,subprocess,sys,time
adb,serial,previous,output=sys.argv[1:]
until=time.monotonic()+180
while time.monotonic()<until:
    try:
        r=subprocess.run([adb,'-s',serial,'shell','cat /proc/sys/kernel/random/boot_id; getprop sys.boot_completed; id -u'],
                         capture_output=True,text=True,timeout=4)
        lines=r.stdout.splitlines()
        if r.returncode==0 and len(lines)==3 and lines[1]=='1' and re.fullmatch(r'[a-f0-9-]{36}',lines[0]) and lines[0]!=previous:
            if lines[2]!='0':
                subprocess.run([adb,'-s',serial,'root'],capture_output=True,timeout=4)
            else:
                with open(output,'w') as out:
                    json.dump({'before':previous,'after':lines[0]},out,indent=2)
                break
    except subprocess.TimeoutExpired:
        pass
    time.sleep(1)
else:
    raise SystemExit('AVD did not complete a new Android boot with root ADB')
PY
"$ADB" -s "$SERIAL" shell input -d 0 keyevent 224
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
python3 - "$ADB" "$SERIAL" "$DEVICE_OUTPUT" "$DEVICE_JAR" "$OUTPUT/runtime-ready.json" <<'PY'
# The Android boot flag can precede the native conversation service. Read the existing thread first.
import json,re,shlex,subprocess,sys,time
adb,serial,device_output,device_jar,output=sys.argv[1:]
accepted=json.loads(subprocess.check_output([adb,'-s',serial,'exec-out','cat',device_output+'/acceptance.json'],text=True))
thread=accepted['threadId'];assert re.fullmatch(r'[a-f0-9-]{36}',thread)
params=json.dumps({'threadId':thread,'includeTurns':True},separators=(',',':'))
command=shlex.join(['CLASSPATH='+device_jar,'app_process','/system/bin','org.agentos.tests.NativeSocketProbe','rpc','thread/read',params])
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
run_phase reboot-after.txt 120 -e phase verifyReboot
printf 'PASS: Expo process death and Android reboot recovery. Evidence: %s\n' "$OUTPUT"
