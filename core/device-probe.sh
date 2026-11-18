#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Single source for device-side probes. No agent prompts/results or identifiers.
set -eu
json_string() { printf '"'; printf '%s' "$1" | sed 's/\\/\\\\/g;s/"/\\"/g' | tr '\r\n\t' '   '; printf '"'; }
field() { json_string "$1"; printf ':'; json_string "$2"; }
case "${1:-}" in
 system.info)
  [ -x /system/bin/getprop ] || { printf '{"code":"UNSUPPORTED_PLATFORM","message":"Android system tools missing","retryable":false}\n'; exit 3; }
  printf '{'; field model "$(getprop ro.product.model)"; printf ','
  field manufacturer "$(getprop ro.product.manufacturer)"; printf ','
  field androidRelease "$(getprop ro.build.version.release)"; printf ','
  field apiLevel "$(getprop ro.build.version.sdk)"; printf ','
  field securityPatch "$(getprop ro.build.version.security_patch)"; printf ','
  field abi "$(getprop ro.product.cpu.abi)"; printf ','
  field buildType "$(getprop ro.build.type)"; printf ','
  field selinux "$(getenforce)"; printf ','
  field callerUid "$(id -u)"; printf '}\n';;
 displays.status)
  ids=$(cmd display get-displays --ids-only)
  printf '{"displayIds":['; first=1
  for item in $ids; do
   case "$item" in *[!0-9]*|'') continue;; esac
   [ "$first" = 1 ] || printf ','; printf '%s' "$item"; first=0
  done
  printf '],"background":'
  path=/data/local/agentos/virtual-display/display.id
  if [ -r "$path" ]; then
   item=$(cat "$path")
   case "$item" in *[!0-9]*|'') printf 'null';;
    *) if printf '%s\n' "$ids" | grep -qx "$item"; then printf '{"displayId":%s,"active":true}' "$item"; else printf '{"displayId":%s,"active":false}' "$item"; fi;;
   esac
  else printf 'null'; fi
  printf '}\n';;
 tasks.status)
  caller=$(id -u)
  case "$caller" in 0|1000) :;; *) printf '{"code":"PERMISSION_DENIED","message":"Task metadata requires root or system UID","retryable":false}\n'; exit 4;; esac
  root=/data/user/0/org.agentos.assistant/files/runtime/tasks
  printf '{"tasks":['; first=1
  for directory in "$root"/*; do
   [ -d "$directory" ] || continue
   task_id=${directory##*/}
   case "$task_id" in *[!0-9]*|'') continue;; esac
   [ -r "$directory/state.json" ] || continue
   grep -q '"kind":"agent_control"' "$directory/request.json" 2>/dev/null && continue
   state=$(sed -n 's/.*"state"[ ]*:[ ]*"\([a-z_]*\)".*/\1/p' "$directory/state.json")
   case "$state" in queued|running|completed|failed|needs_user|cancelled|error) :;; *) state=unknown;; esac
   [ "$first" = 1 ] || printf ','; first=0
   printf '{"id":'; json_string "$task_id"; printf ',"state":'; json_string "$state"; printf '}'
  done
  printf '],"source":"existing-task-broker","includesPrompts":false,"includesResults":false}\n';;
 *) printf '{"code":"METHOD_NOT_FOUND","message":"Unknown device method","retryable":false}\n'; exit 2;;
esac
