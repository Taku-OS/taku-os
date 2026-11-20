#!/bin/bash
# Run the live Expo composer regression on the dedicated local AVD.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
PHASE=all
COMPILE_ONLY=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --phase) PHASE="${2:?--phase requires a value}"; shift 2 ;;
    --compile-only) COMPILE_ONLY=true; shift ;;
    *) echo 'Usage: test-composer.sh [--compile-only] [--phase all|failure-missing|failure-async|attachments|home-attachments|steer|stop|camera]' >&2; exit 2 ;;
  esac
done
case "$PHASE" in all|failure-missing|failure-async|attachments|home-attachments|steer|stop|camera) ;; *) echo 'Unknown phase' >&2; exit 2 ;; esac
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
OUTPUT="../../out/composer-reliability-tests/$RUN_ID"
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
OUTPUT="$(cd "$OUTPUT" && pwd)"
printf 'Composer regression output: %s\n' "$OUTPUT"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" \
  -d "$OUTPUT/classes" tests/org/agentos/tests/ComposerReliabilityTest.java tests/org/agentos/tests/NativeSocketProbe.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --lib "$SDK/platforms/android-36/uiautomator.jar" \
  --lib "$SDK/platforms/android-36/optional/android.test.base.jar" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
if "$COMPILE_ONLY"; then exit 0; fi

# Explicitly pin this host's adb server; a remote inherited ADB socket must not redirect the test.
unset ANDROID_ADB_SERVER_PORT ADB_SERVER_PORT ANDROID_SERIAL
export ADB_SERVER_SOCKET=tcp:5037
SERIAL=${TAKU_SERIAL:-emulator-5634}
AVD=${TAKU_AVD:-Taku_ImageIntegration_20261010}
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Select an isolated emulator serial.' >&2; exit 1; }
[[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" == "$AVD" ]] || { echo 'Wrong AVD' >&2; exit 1; }
[[ "$("$ADB" -s "$SERIAL" shell id -u | tr -d '\r')" == 0 ]] || { echo 'Root ADB required on isolated AVD.' >&2; exit 1; }
"$ADB" -s "$SERIAL" shell pm path --user 0 org.agentos.agent | grep -q '^package:' || { echo 'Install the Expo Agent APK first.' >&2; exit 1; }
if [[ "$PHASE" == camera ]]; then
  routes=$("$ADB" -s "$SERIAL" shell 'ip route show table all && ip -6 route show table all')
  if grep -E '^default ' <<< "$routes" | grep -Ev '^default dev dummy0( |$)' >&2; then
    echo 'Disable external network access on the isolated camera AVD first.' >&2; exit 1
  fi
  "$ADB" -s "$SERIAL" shell dumpsys package org.agentos.agent | sed -n '/versionCode=/p; /versionName=/p' > "$OUTPUT/agent-version.txt"
fi
sound=$("$ADB" -s "$SERIAL" shell settings get system sound_effects_enabled | tr -d '\r')
stay=$("$ADB" -s "$SERIAL" shell settings get global stay_on_while_plugged_in | tr -d '\r')
# Read only the Kotlin backend's selection so the runner can reopen it on exit.
conversation=$("$ADB" -s "$SERIAL" exec-out cat /data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml | python3 -c 'import sys,xml.etree.ElementTree as E;r=E.fromstring(sys.stdin.read());print(next((n.text or "" for n in r if n.get("name")=="conversation"),""))')
[[ "$sound" =~ ^(null|[0-9]+)$ && "$stay" =~ ^(null|[0-9]+)$ && "$conversation" =~ ^[a-f0-9-]{36}$ ]] || { echo 'Cannot safely snapshot original settings/conversation.' >&2; exit 1; }
python3 - "$OUTPUT/original-settings.json" "$sound" "$stay" "$conversation" <<'PY'
import json,sys
with open(sys.argv[1],'w') as out: json.dump(dict(zip(['sound_effects_enabled','stay_on_while_plugged_in','conversation'],sys.argv[2:])),out,indent=2)
PY
DEVICE_OUTPUT="/data/local/tmp/taku-composer-tests/$RUN_ID"
DEVICE_JAR="/data/local/tmp/taku-composer-$RUN_ID.jar"
DEVICE_IMAGE="/data/local/tmp/taku-composer-$RUN_ID-image.png"
restore() {
  original_exit=$?
  trap - EXIT
  set +e
  restore_failed=0
  if [[ "$sound" == null ]]; then "$ADB" -s "$SERIAL" shell settings delete system sound_effects_enabled; else "$ADB" -s "$SERIAL" shell settings put system sound_effects_enabled "$sound"; fi >/dev/null || restore_failed=1
  if [[ "$stay" == null ]]; then "$ADB" -s "$SERIAL" shell settings delete global stay_on_while_plugged_in; else "$ADB" -s "$SERIAL" shell settings put global stay_on_while_plugged_in "$stay"; fi >/dev/null || restore_failed=1
  "$ADB" -s "$SERIAL" shell am start -W --display 0 -a android.intent.action.MAIN -n org.agentos.agent/.MainActivity --es threadId "$conversation" > "$OUTPUT/restore-conversation.txt" 2>&1 || restore_failed=1
  pulled=false
  if "$ADB" -s "$SERIAL" pull "$DEVICE_OUTPUT" "$OUTPUT/device" > "$OUTPUT/pull.txt" 2>&1; then pulled=true; else restore_failed=1; fi
  # Archive only IDs written by this run, after the original conversation has been reopened.
  python3 - "$OUTPUT/device" > "$OUTPUT/test-thread-ids.txt" <<'PY'
from pathlib import Path
import json,re,sys
ids=set()
for path in Path(sys.argv[1]).rglob('result.json'):
 try:
  result=json.loads(path.read_text())
  for name in ('threadId', 'otherThreadId'):
   value=result.get(name,'')
   if re.fullmatch(r'[a-f0-9-]{36}',value):ids.add(value)
 except (ValueError,OSError):pass
print('\n'.join(sorted(ids)))
PY
  while IFS= read -r thread; do
    [[ -n "$thread" ]] || continue
    "$ADB" -s "$SERIAL" shell "CLASSPATH=$DEVICE_JAR app_process /system/bin org.agentos.tests.NativeSocketProbe rpc thread/archive '{\"threadId\":\"$thread\"}'" >> "$OUTPUT/archive-test-threads.txt" 2>&1 || restore_failed=1
  done < "$OUTPUT/test-thread-ids.txt"
  "$ADB" -s "$SERIAL" shell rm -f "$DEVICE_JAR" >/dev/null 2>&1
  "$ADB" -s "$SERIAL" shell rm -f "$DEVICE_IMAGE" >/dev/null 2>&1
  # Remove only our unique public source fixtures. App-private copies remain with their test threads for diagnosis.
  for p in failure-missing failure-async attachments home-attachments steer stop; do
    "$ADB" -s "$SERIAL" shell rm -rf "/sdcard/Download/taku-composer-$RUN_ID-$p" >/dev/null 2>&1
  done
  if "$pulled"; then "$ADB" -s "$SERIAL" shell rm -rf "$DEVICE_OUTPUT" >/dev/null 2>&1; fi
  python3 - "$OUTPUT/run-result.json" "$original_exit" "$restore_failed" "$PHASE" <<'PY'
import json,sys
model_check='modelResponseVerified' if sys.argv[4]=='camera' else 'modelResponsesSimulated'
with open(sys.argv[1],'w') as out:json.dump({'testExitCode':int(sys.argv[2]),'restoreFailed':bool(int(sys.argv[3])),model_check:False},out,indent=2)
PY
  if [[ "$original_exit" == 0 && "$restore_failed" != 0 ]]; then exit 1; fi
  exit "$original_exit"
}
trap restore EXIT
"$ADB" -s "$SERIAL" push "$OUTPUT/test.jar" "$DEVICE_JAR"
IMAGE_CODE=none
if [[ "$PHASE" != camera ]]; then
  "$JDK/bin/java" -Djava.awt.headless=true tests/host/ComposerImageFixture.java "$OUTPUT/image.png" "$OUTPUT/image-code.txt"
  IMAGE_CODE=$(cat "$OUTPUT/image-code.txt")
  "$ADB" -s "$SERIAL" push "$OUTPUT/image.png" "$DEVICE_IMAGE"
fi
"$ADB" -s "$SERIAL" shell settings put system sound_effects_enabled 0
"$ADB" -s "$SERIAL" shell svc power stayon true
"$ADB" -s "$SERIAL" shell input keyevent 224
"$ADB" -s "$SERIAL" shell wm dismiss-keyguard
if [[ "$PHASE" == all ]]; then phases=(failure-missing failure-async attachments home-attachments steer stop); else phases=("$PHASE"); fi
for phase in "${phases[@]}"; do
  printf 'Running real composer phase: %s\n' "$phase"
  "$ADB" -s "$SERIAL" shell timeout 360 uiautomator runtest /system/framework/android.test.base.jar "$DEVICE_JAR" \
    -c org.agentos.tests.ComposerReliabilityTest -e runId "$RUN_ID" -e phase "$phase" -e imageCode "$IMAGE_CODE" > "$OUTPUT/$phase.log" 2>&1
  python3 - "$OUTPUT/$phase.log" <<'PY'
from pathlib import Path
import re,sys
log=Path(sys.argv[1]).read_text()
assert re.search(r'\bOK \(1 test\)',log) and not re.search(r'FAILURES|aborted|shortMsg=|INSTRUMENTATION_STATUS_CODE: -2',log),log
PY
  "$ADB" -s "$SERIAL" exec-out cat "$DEVICE_OUTPUT/$phase/result.json" > "$OUTPUT/$phase.json"
  python3 - "$OUTPUT/$phase.json" "$phase" <<'PY'
import json,sys
model_check='modelResponseVerified' if sys.argv[2]=='camera' else 'modelResponsesSimulated'
result=json.load(open(sys.argv[1]));assert result['passed'] is True and result[model_check] is False,result
PY
done
printf 'PASS: requested real composer phases; evidence: %s\n' "$OUTPUT"
