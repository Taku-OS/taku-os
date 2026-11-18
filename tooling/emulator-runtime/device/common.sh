#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Shared checks for the opt-in, host-managed userdebug emulator runtime.
set -eu
RUNTIME_ROOT=/data/local/agentos/dev-runtime
fail() { echo "CHECK_FAIL:$1" >&2; exit 1; }
owned_file() {
  [ -f "$1" ] && [ ! -L "$1" ] || fail "not_regular:$1"
  [ "$(stat -c %u "$1")" = 0 ] || fail "not_root_owned:$1"
  case "$(stat -c %a "$1")" in 400|500|600|700) ;; *) fail "unsafe_mode:$1";; esac
}
load_config() {
  [ "$(id -u)" = 0 ] || fail root_required
  [ -d "$RUNTIME_ROOT" ] && [ ! -L "$RUNTIME_ROOT" ] || fail runtime_directory
  [ "$(stat -c %u "$RUNTIME_ROOT")" = 0 ] || fail runtime_owner
  [ "$(stat -c %a "$RUNTIME_ROOT")" = 700 ] || fail runtime_mode
  owned_file "$RUNTIME_ROOT/runtime.conf"
  owned_file "$RUNTIME_ROOT/self.sha256"
  (cd "$RUNTIME_ROOT" && sha256sum -c self.sha256 >/dev/null) || fail managed_file_hash
  . "$RUNTIME_ROOT/runtime.conf"
  [ "$OWNER" = taku-emulator-runtime-v1 ] || fail owner
  [ "$(getprop ro.kernel.qemu)" = 1 ] || fail emulator_required
  [ "$(getprop ro.build.type)" = userdebug ] || fail userdebug_required
  [ "$(getprop ro.boot.qemu.avd_name)" = "$EXPECTED_AVD" ] || fail avd_mismatch
  [ "$(getprop sys.boot_completed)" = 1 ] || fail boot_incomplete
  [ "$(am get-current-user)" = 0 ] || fail current_user_not_zero
  [ "$(cmd activity get-started-user-state 0)" = RUNNING_UNLOCKED ] || fail user_zero_locked
}
# All components must still match the root-owned deployment captured at install.
check_assets() {
  owned_file "$RUNTIME_ROOT/assets.sha256"
  sha256sum -c "$RUNTIME_ROOT/assets.sha256" >/dev/null || fail deployed_asset_hash
}
process_has_arg() {
  case "$1" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$1/cmdline" ] && tr '\000' '\n' < "/proc/$1/cmdline" | grep -Fxq -- "$2"
}
root_process() {
  [ -r "/proc/$1/status" ] && awk '/^Uid:/ {exit !($2 == 0 && $3 == 0)}' "/proc/$1/status"
}
# A Java class argument alone also occurs in this probe's grep/awk processes.
# Bind class discovery to the real system executable and the exact launch slots.
# Keep UID and deployment-hash checks separate so a foreign matching process is
# still rejected by the existing health/restore checks, never silently adopted.
class_process() {
  case "$1" in ''|*[!0-9]*) return 1;; esac
  executable=$(readlink "/proc/$1/exe") || return 1
  case "$executable" in
    /system/bin/app_process|/system/bin/app_process32|/system/bin/app_process64) ;;
    *) return 1;;
  esac
  [ -r "/proc/$1/cmdline" ] || return 1
  tr '\000' '\n' < "/proc/$1/cmdline" | awk -v expected="$2" '
    NR == 2 { directory = $0 }
    NR == 3 { matches = (directory == "/system/bin" && $0 == expected) }
    END { exit !matches }
  '
}
class_pids() {
  for p in /proc/[0-9]*; do
    pid=${p##*/}
    if class_process "$pid" "$1"; then printf '%s\n' "$pid"; fi
  done
}
json_number() { sed -n "s/.*\"$2\":[ ]*\([0-9][0-9]*\).*/\1/p" "$1" | head -1; }
check_mounts() {
  awk -v p="$STAGE/volatile" '$2==p && $3=="tmpfs"{found=1} END{exit !found}' /proc/mounts || fail volatile_mount
  awk -v upper="$STAGE/volatile/system-ext-upper" '$2=="/system_ext" && $3=="overlay" && index(","$4",",",upperdir="upper","){found=1} END{exit !found}' /proc/mounts || fail system_ext_overlay
  awk -v upper="$STAGE/volatile/system-etc-upper" '$2=="/system/etc" && $3=="overlay" && index(","$4",",",upperdir="upper","){found=1} END{exit !found}' /proc/mounts || fail system_etc_overlay
  sha256sum -c "$RUNTIME_ROOT/targets.sha256" >/dev/null || fail mounted_component_hash
}
check_broker() {
  brokers=$(class_pids org.agentos.lab.TaskBroker)
  [ "$(printf '%s\n' "$brokers" | grep -c '^[0-9]')" = 1 ] || fail broker_count
  root_process "$brokers" || fail broker_not_root
  process_has_arg "$brokers" /system/bin || fail broker_cmdline
  [ -s "$STAGE/broker.pid" ] && [ "$(cat "$STAGE/broker.pid")" = "$brokers" ] || fail broker_pidfile
}
check_display() {
  [ -s "$DISPLAY_DIR/display.id" ] && [ -s "$DISPLAY_DIR/display.json" ] || fail display_metadata
  display_id=$(cat "$DISPLAY_DIR/display.id")
  case "$display_id" in ''|*[!0-9]*|0) fail display_id;; esac
  [ "$(json_number "$DISPLAY_DIR/display.json" displayId)" = "$display_id" ] || fail display_metadata_id
  display_pid=$(json_number "$DISPLAY_DIR/display.json" pid)
  class_process "$display_pid" org.agentos.lab.VirtualDisplayHost || fail display_host
  root_process "$display_pid" || fail display_host_not_root
  cmd display get-displays --ids-only | grep -Fxq "$display_id" || fail display_missing
}
service_pid() {
  [ -s "$KIT/api/server.pid" ] || return 1
  service_pid_value=$(cat "$KIT/api/server.pid")
  process_has_arg "$service_pid_value" "$KIT/api/server.mjs" || return 1
  process_has_arg "$service_pid_value" "$NODE" || return 1
  root_process "$service_pid_value" || return 1
  printf '%s\n' "$service_pid_value"
}
port_lines() { ss -ltnp | awk -v p=":$SERVICE_PORT" '$1=="LISTEN" && substr($4,length($4)-length(p)+1)==p'; }
check_service_port() {
  actual_pid=$(service_pid) || fail service_pid
  listeners=$(port_lines)
  [ -n "$listeners" ] || fail service_no_listener
  while IFS= read -r line; do
    printf '%s\n' "$line" | grep -Fq "pid=$actual_pid," || fail port_owned_by_other_process
  done <<END_LISTENERS
$listeners
END_LISTENERS
}
check_api() {
  actual_pid=$(service_pid) || fail service_pid
  MINI_APP_API_PORT="$SERVICE_PORT" timeout 15 /system/bin/sh "$SERVICE" status >/dev/null || fail service_status
  LD_LIBRARY_PATH="$NODE_LIB" timeout 20 "$NODE" "$RUNTIME_ROOT/probe.mjs" "$SERVICE_PORT" "$APP_ID" "$STATE_METHOD" "$actual_pid" || fail service_api
}
check_all() { check_assets; check_mounts; check_broker; check_display; check_service_port; check_api; }
