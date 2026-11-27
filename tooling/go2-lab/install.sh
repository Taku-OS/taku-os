#!/bin/bash
# Install this app only; never flashes an OS or connects to a robot.
set -euo pipefail
cd "$(dirname "$0")/../.."
fail() { echo "$*" >&2; exit 1; }
serial=${TAKU_SERIAL:-}; avd=${TAKU_AVD:-}; ai=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial) [ "$#" -ge 2 ] || fail 'Missing serial'; serial=$2; shift 2;;
    --avd) [ "$#" -ge 2 ] || fail 'Missing AVD'; avd=$2; shift 2;;
    --with-ai-tools) ai=true; shift;;
    *) fail 'Usage: install.sh --serial SERIAL [--avd AVD] [--with-ai-tools]';;
  esac
done
[[ "$serial" =~ ^[a-zA-Z0-9_.:-]+$ ]] || fail 'Specify one adb serial explicitly.'
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
adb=${ADB:-"$sdk/platform-tools/adb"}
if [ ! -x "$adb" ]; then adb=$(command -v adb || true); fi
[ -n "$adb" ] || fail 'Install Android SDK platform-tools or set ADB.'
target() { "$adb" -s "$serial" "$@"; }
prop() { target shell getprop "$1" | tr -d '\r'; }
[ "$(target get-state)" = device ] || fail 'Target unavailable.'
api=$(prop ro.build.version.sdk)
[[ "$api" =~ ^[0-9]+$ ]] && [ "$api" -ge 36 ] || fail 'Android API 36+ required.'
if [[ "$serial" == emulator-* ]]; then
  [[ "$avd" =~ ^[a-zA-Z0-9_-]+$ ]] || fail 'Specify the expected --avd name.'
  [ "$(target emu avd name | head -1 | tr -d '\r')" = "$avd" ] || fail 'AVD mismatch; nothing installed.'
  [ "$(prop ro.kernel.qemu)" = 1 ] || fail 'Expected emulator.'
fi
if $ai; then
  [[ "$serial" == emulator-* ]] || fail 'AI tool installation is verified only on the development emulator.'
  [ "$(prop ro.build.type)" = userdebug ] || fail 'AI tools require a userdebug development emulator.'
  [ "$(target shell getenforce | tr -d '\r')" = Enforcing ] || fail 'SELinux must remain Enforcing.'
fi
artifacts=out/go2-lab
[ -f "$artifacts/TakuGo2.apk" ] || artifacts=artifacts
[ -f "$artifacts/TakuGo2.apk" ] || fail 'Build the app first or use the complete devkit ZIP.'
if $ai; then
  [ -f "$artifacts/client.jar" ] || fail 'Missing AI client.jar; nothing installed.'
  target root
  target wait-for-device
  [ "$(target shell id -u | tr -d '\r')" = 0 ] || fail 'adb root is unavailable.'
  remote=/data/local/agentos/go2-lab
  skill=/data/local/agentos/home/.codex/skills/taku-go2
  target shell "mkdir -p $remote $skill"
  target push "$artifacts/client.jar" "$remote/client.jar"
  target push tooling/go2-lab/go2 "$remote/go2"
  target push tooling/go2-lab/skill/taku-go2/SKILL.md "$skill/SKILL.md"
  target shell "chmod 755 $remote/go2; chmod 644 $remote/client.jar $skill/SKILL.md; restorecon -R $remote $skill"
fi
target install -r "$artifacts/TakuGo2.apk"
target reverse tcp:18845 tcp:18845
target reverse tcp:18846 tcp:18846
target shell am start -n org.taku.go2lab/.Go2Activity
echo 'Installed Taku Go2. Start the Mac service before using the app. No robot commands sent.'
