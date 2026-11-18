#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=emulator-5582
TARGET=/system_ext/overlay/AgentOSLabSettingsOverlay
"$ADB" -s "$SERIAL" root
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" remount
"$ADB" -s "$SERIAL" shell mkdir -p "$TARGET"
"$ADB" -s "$SERIAL" push out/AgentOSLabSettingsOverlay.apk "$TARGET/AgentOSLabSettingsOverlay.apk"
"$ADB" -s "$SERIAL" shell "chmod 644 $TARGET/AgentOSLabSettingsOverlay.apk; restorecon -RF $TARGET"
"$ADB" -s "$SERIAL" reboot
"$ADB" -s "$SERIAL" wait-for-device
for n in $(seq 1 90); do
  if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then
    "$ADB" -s "$SERIAL" shell cmd overlay lookup com.android.settings com.android.settings:string/about_settings
    exit 0
  fi
  sleep 2
done
echo 'Boot timeout' >&2
exit 1
