#!/system/bin/sh
set -eu
stamp_ms() {
  read uptime_seconds rest < /proc/uptime
  whole=${uptime_seconds%%.*}
  fraction=${uptime_seconds#*.}
  echo "$((whole * 1000 + (1000$fraction % 100) * 10))"
}
KIT=/data/local/agentos/light-app-lab/free-app-kit
APP="$1"
case "$APP" in *[!a-zA-Z0-9_-]*|'') exit 2;; esac
PROJECT="$KIT/apps/$APP"
if [ -s "$PROJECT/api.mjs" ]; then
  /system/bin/sh "$KIT/api/service.sh" start >/dev/null
fi
START=$(stamp_ms)
"$KIT/runtime/esbuild" "$PROJECT/main.jsx" --bundle --format=iife --external:react --external:react-dom/client --outfile="$PROJECT/app.bundle.js" --log-level=warning
mkdir -p "/data/user/0/org.agentos.minilab/files/apps/$APP"
cp "$PROJECT/app.bundle.js" "/data/user/0/org.agentos.minilab/files/apps/$APP/app.bundle.js"
OWNER=$(cmd package list packages -U org.agentos.minilab | sed -n 's/^package:org\.agentos\.minilab uid:\([0-9]*\)$/\1/p')
test -n "$OWNER"
chown -R "$OWNER:$OWNER" "/data/user/0/org.agentos.minilab/files/apps/$APP"
restorecon -RF "/data/user/0/org.agentos.minilab/files/apps/$APP"
END=$(stamp_ms)
echo "device_bundle_ms=$(((END-START)/1))"
if [ "${2:-}" != "--no-open" ]; then
  am start --display 0 -n org.agentos.minilab/.WebActivity --es app "$APP"
fi
