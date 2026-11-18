#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
: "${TAKU_SERIAL:?Set TAKU_SERIAL to the development emulator to install into}"
: "${TAKU_AVD:?Set TAKU_AVD to the development emulator AVD name}"
SERIAL="$TAKU_SERIAL"
HOME_COMPONENT=org.agentos.agent/org.agentos.agent.home.HomeActivity
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = "$TAKU_AVD" ] || { echo 'Wrong experiment emulator'; exit 1; }
"$ADB" -s "$SERIAL" root
"$ADB" -s "$SERIAL" wait-for-device
"$ADB" -s "$SERIAL" remount
# The backend no longer owns HOME/ASSIST; require the matching new frontend first.
"$ADB" -s "$SERIAL" shell cmd package query-services --brief -a android.service.voice.VoiceInteractionService -p org.agentos.agent | grep -q 'SystemAssistantService' || {
 echo 'Install the matching TakuAgent APK with SystemAssistantService first' >&2; exit 1;
}
"$ADB" -s "$SERIAL" install --no-incremental -r out/AgentOSAssistant.apk
"$ADB" -s "$SERIAL" shell 'mkdir -p /system_ext/priv-app/AgentOSAssistant /system_ext/framework /system_ext/etc/agentos /system_ext/etc/init'
"$ADB" -s "$SERIAL" push out/AgentOSAssistant.apk /system_ext/priv-app/AgentOSAssistant/AgentOSAssistant.apk
"$ADB" -s "$SERIAL" push out/task-broker.jar /system_ext/framework/agentos-task-broker.jar
"$ADB" -s "$SERIAL" push task-result.schema.json /system_ext/etc/agentos/task-result.schema.json
"$ADB" -s "$SERIAL" push agentos-codex.rc /system_ext/etc/init/agentos-codex.rc
"$ADB" -s "$SERIAL" shell 'chmod 644 /system_ext/priv-app/AgentOSAssistant/AgentOSAssistant.apk /system_ext/framework/agentos-task-broker.jar /system_ext/etc/agentos/task-result.schema.json /system_ext/etc/init/agentos-codex.rc; restorecon -R /system_ext/priv-app/AgentOSAssistant /system_ext/etc/agentos /system_ext/etc/init/agentos-codex.rc /system_ext/framework/agentos-task-broker.jar; cmd role add-role-holder --user 0 android.app.role.ASSISTANT org.agentos.agent; settings put global power_button_long_press 5; pm grant org.agentos.assistant android.permission.POST_NOTIFICATIONS'
# The desktop is the TakuAgent HomeActivity. Read it back so a missing or stale Agent APK
# fails here instead of leaving the legacy CodexHomeActivity as the HOME app.
"$ADB" -s "$SERIAL" shell "cmd package set-home-activity --user 0 $HOME_COMPONENT"
[ "$("$ADB" -s "$SERIAL" shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1' | tr -d '\r')" = "$HOME_COMPONENT" ] || { echo "HOME is not $HOME_COMPONENT; install the matching TakuAgent APK first"; exit 1; }
echo 'Installed. Reboot required to load the init service; authentication remains in device data.'
