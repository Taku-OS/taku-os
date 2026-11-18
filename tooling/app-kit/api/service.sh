#!/system/bin/sh
set -eu
KIT=/data/local/agentos/light-app-lab/free-app-kit
NODE=/data/local/agentos/light-app-lab/native/node
export LD_LIBRARY_PATH=/data/local/agentos/light-app-lab/native/lib
PIDFILE="$KIT/api/server.pid"
running(){ test -s "$PIDFILE" && test -r "/proc/$(cat "$PIDFILE")/cmdline" && tr '\000' ' ' < "/proc/$(cat "$PIDFILE")/cmdline" | grep -q "$KIT/api/server.mjs"; }
case "${1:-status}" in
start)
  if running; then "$KIT/mini-app" status;exit;fi
  nohup "$NODE" "$KIT/api/server.mjs" > "$KIT/api/server.log" 2>&1 </dev/null &
  echo $! > "$PIDFILE"
  for attempt in 1 2 3 4 5;do if "$KIT/mini-app" status 2>/dev/null;then exit 0;fi;sleep 0.2;done
  exit 1;;
stop) if running;then kill "$(cat "$PIDFILE")";fi;rm -f "$PIDFILE";;
status) "$KIT/mini-app" status;;
*) exit 2;;
esac
