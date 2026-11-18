#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=emulator-5582
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = AgentOS_ImageCheck ] || { echo 'Wrong experiment emulator'; exit 1; }
mkdir -p evidence/desktop
if [ ! -f evidence/desktop/settings-before.txt ]; then
 "$ADB" -s "$SERIAL" shell 'settings get secure selected_search_engine; settings get secure search_global_search_activity' > evidence/desktop/settings-before.txt
 "$ADB" -s "$SERIAL" shell dumpsys appwidget > evidence/desktop/appwidget-before.txt
fi
if [ ! -f evidence/desktop/home-before.txt ]; then
 "$ADB" -s "$SERIAL" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME > evidence/desktop/home-before.txt
fi
"$ADB" -s "$SERIAL" root
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" remount
# The backend no longer owns HOME/ASSIST; require the matching new frontend first.
"$ADB" -s "$SERIAL" shell cmd package query-services --brief -a android.service.voice.VoiceInteractionService -p org.agentos.agent | grep -q 'SystemAssistantService' || {
 echo 'Install the matching TakuAgent APK with SystemAssistantService first' >&2; exit 1;
}
"$ADB" -s "$SERIAL" install --no-incremental -r out/AgentOSAssistant.apk
"$ADB" -s "$SERIAL" push out/AgentOSAssistant.apk /system_ext/priv-app/AgentOSAssistant/AgentOSAssistant.apk
"$ADB" -s "$SERIAL" shell 'chmod 644 /system_ext/priv-app/AgentOSAssistant/AgentOSAssistant.apk; restorecon /system_ext/priv-app/AgentOSAssistant/AgentOSAssistant.apk; settings delete secure selected_search_engine; settings delete secure search_global_search_activity; cmd package set-home-activity --user 0 org.agentos.agent/org.agentos.agent.home.HomeActivity; cmd role add-role-holder --user 0 android.app.role.ASSISTANT org.agentos.agent; input keyevent KEYCODE_HOME'
