#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Restore only already-deployed runtime assets. Never clears tasks or business data.
set -eu
. /data/local/agentos/dev-runtime/common.sh
load_config
# Android mksh opens exec redirections CLOEXEC. Explicit 9>&9 exports the FD
# to toybox flock; the parent retains the same open file description and lock.
# The kernel releases it on exit; no stale lock deletion is necessary.
exec 9>"$RUNTIME_ROOT/restore.lock"
attempt=0
until flock -n 9 9>&9; do
  attempt=$((attempt + 1)); [ "$attempt" -lt 30 ] || fail restore_busy
  sleep 1
done
check_assets
V="$STAGE/volatile"
mkdir -p "$V"
if ! awk -v p="$V" '$2==p && $3=="tmpfs"{found=1} END{exit !found}' /proc/mounts; then
  # Refuse a foreign mount at the owned volatile path.
  if awk -v p="$V" '$2==p{found=1} END{exit !found}' /proc/mounts; then fail volatile_foreign_mount; fi
  mount -t tmpfs -o size=16m,mode=755 agentos-runtime-repair "$V"
fi
if ! awk '$2=="/system_ext" && $3=="overlay"{found=1} END{exit !found}' /proc/mounts; then
  mkdir -p "$V/system-ext-upper/etc/codex" "$V/system-ext-upper/etc" "$V/system-ext-work"
  cp -R "$STAGE/system-ext-upper/bin" "$V/system-ext-upper/"
  cp -R "$STAGE/system-ext-upper/framework" "$V/system-ext-upper/"
  cp -R "$STAGE/system-ext-upper/etc/agentos" "$V/system-ext-upper/etc/"
  cp -R "$STAGE/system-ext-upper/etc/init" "$V/system-ext-upper/etc/"
  cp "$STAGE/system-ext-upper/etc/codex/cacert.pem" "$V/system-ext-upper/etc/codex/cacert.pem"
  ln -sf "$STAGE/system-ext-upper/etc/codex/codex.tar.gz" "$V/system-ext-upper/etc/codex/codex.tar.gz"
  ln -sf "$STAGE/system-ext-upper/etc/codex/code-mode-host.tar.gz" "$V/system-ext-upper/etc/codex/code-mode-host.tar.gz"
  chmod 755 "$V/system-ext-upper/bin/"*
  chcon -R u:object_r:system_file:s0 "$V/system-ext-upper"
  mount -t overlay agentos-runtime-repair -o lowerdir=/system_ext,upperdir="$V/system-ext-upper",workdir="$V/system-ext-work" /system_ext
fi
if ! awk '$2=="/system/etc" && $3=="overlay"{found=1} END{exit !found}' /proc/mounts; then
  mkdir -p "$V/system-etc-upper" "$V/system-etc-work"
  cp "$STAGE/system-etc-upper/resolv.conf" "$V/system-etc-upper/resolv.conf"
  chmod 644 "$V/system-etc-upper/resolv.conf"
  chcon -R u:object_r:system_file:s0 "$V/system-etc-upper"
  mount -t overlay agentos-dns-repair -o lowerdir=/system/etc,upperdir="$V/system-etc-upper",workdir="$V/system-etc-work" /system/etc
fi
check_mounts
# Only obsolete metadata belonging to a dead display host may be archived.
if [ -e "$DISPLAY_DIR/display.id" ] || [ -e "$DISPLAY_DIR/display.json" ]; then
  [ ! -L "$DISPLAY_DIR/display.id" ] && [ ! -L "$DISPLAY_DIR/display.json" ] || fail display_metadata_symlink
  prior_display_pid=''
  [ ! -f "$DISPLAY_DIR/display.json" ] || prior_display_pid=$(json_number "$DISPLAY_DIR/display.json" pid)
  if class_process "$prior_display_pid" org.agentos.lab.VirtualDisplayHost; then
    check_display
  else
    [ -z "$(class_pids org.agentos.lab.VirtualDisplayHost)" ] || fail live_display_host_without_matching_metadata
    archive="$RUNTIME_ROOT/archive/display-$(date +%s)-$$"
    mkdir -p "$archive"; chmod 700 "$RUNTIME_ROOT/archive" "$archive"
    for name in display.id display.json; do
      [ ! -e "$DISPLAY_DIR/$name" ] || mv "$DISPLAY_DIR/$name" "$archive/$name"
    done
  fi
fi
timeout 12 /system_ext/bin/phone-display start >/dev/null 9>&- || fail display_start
check_display
brokers=$(class_pids org.agentos.lab.TaskBroker)
if [ -n "$brokers" ]; then
  [ "$(printf '%s\n' "$brokers" | grep -c '^[0-9]')" = 1 ] || fail broker_count
  root_process "$brokers" || fail broker_not_root
  printf '%s\n' "$brokers" > "$STAGE/broker.pid"
else
  mkdir -p "$STAGE/logs"
  setsid /system/bin/sh -c 'CLASSPATH=/system_ext/framework/agentos-task-broker.jar exec /system/bin/app_process /system/bin org.agentos.lab.TaskBroker' </dev/null >"$STAGE/logs/broker.log" 2>&1 9>&- &
  printf '%s\n' "$!" > "$STAGE/broker.pid"
fi
# Refuse port collisions, including a listener unrelated to this kit.
listeners=$(port_lines)
if [ -n "$listeners" ]; then check_service_port; fi
MINI_APP_API_PORT="$SERVICE_PORT" timeout 15 /system/bin/sh "$SERVICE" start >/dev/null 9>&- || fail service_start
attempt=0
until /system/bin/sh "$RUNTIME_ROOT/health.sh"; do
  attempt=$((attempt + 1)); [ "$attempt" -lt 5 ] || fail final_health
  sleep 1
done
printf 'RESTORED\n'
