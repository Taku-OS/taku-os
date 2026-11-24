#!/system/bin/sh
set -eu
umask 077

SKILL_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
BINDING="$SKILL_DIR/vehicle-binding.json"
CATALOG="$SKILL_DIR/capabilities.json"
STATE_DIR="$SKILL_DIR/state"
NODE=/data/local/agentos/rea-pack/bin/node
NODE_LIB=/data/local/agentos/rea-pack/lib
ADAPTER="$SKILL_DIR/scripts/command-adapter.jar"
APP=com.teslamotors.tesla
WIDGET_RECEIVER="$APP/com.tesla.widget.WidgetCommandReceiver"
WIDGET_ACTION=com.teslamotors.tesla.action.ACTION_VEHICLE_COMMAND

fail() {
    echo "PREFLIGHT_FAIL: $1" >&2
    exit 2
}

node() {
    LD_LIBRARY_PATH="$NODE_LIB" "$NODE" "$@"
}

[ -x "$NODE" ] || fail "shared REA Node runtime is not executable"
[ -r "$CATALOG" ] || fail "capabilities.json is not readable"
[ "$#" -ge 1 ] || fail "usage: control.sh list | status <request-id> [--once] | <check|send|test> <action> <request-id> [parameters...]"
MODE=$1
if [ "$MODE" = status ]; then
    shift
    node "$SKILL_DIR/scripts/status.cjs" "$@"
    exit $?
fi
if [ "$MODE" = list ]; then
    [ "$#" -eq 1 ] || fail "list takes no parameters"
    node -e '
const c=require(process.argv[1]);
if(c.schema!=="taku.tesla-capabilities/1") throw Error("unsupported catalog");
console.log(JSON.stringify({applicationVersion:c.application.versionName,exampleScope:"encoding-check-only",actions:Object.entries(c.actions).map(([id,a])=>({id,label:a.label,prefix:a.prefix,status:a.status,transport:a.transport||(a.route==="command"?"TRANSPORT_BLUETOOTH":"APP_SELECTED"),validationRequirement:a.validationRequirement,parameters:a.parameters,checkExample:a.checkExample,limitations:a.limitations})),broadcastSubmitted:false}));
' "$CATALOG"
    exit 0
fi
case "$MODE" in check|send|test) ;; *) fail "unsupported mode" ;; esac
[ "$#" -ge 3 ] || fail "action and request id are required"
ACTION_ID=$2
REQUEST_ID=$3
shift 3

META=$(node -e '
const c=require(process.argv[1]),a=c.actions?.[process.argv[2]];
if(c.schema!=="taku.tesla-capabilities/1"||!a||!Object.hasOwn(c.actions,process.argv[2])) throw Error("unknown action");
if(c.application?.package!=="com.teslamotors.tesla"||!/^\d+\.\d+\.\d+-\d+$/.test(c.application.versionName)||!Number.isSafeInteger(c.application.versionCode)||c.application.versionCode<1) throw Error("invalid application binding");
if(!["widget","command"].includes(a.route)||!/^[a-z][a-z0-9_-]{0,63}$/.test(a.prefix)||!Array.isArray(a.parameters)||!["vehicle_verified","prepared"].includes(a.status)) throw Error("invalid action metadata");
if(!new RegExp("^"+a.prefix+"-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").test(process.argv[3])) throw Error("request id must use action prefix and lowercase canonical UUID");
if(a.parameters.length!==Number(process.argv[4])) throw Error("incorrect argument count");
console.log([a.route,a.prefix,a.status,a.widgetCommand||"-",require("node:crypto").createHash("sha256").update(JSON.stringify(a)).digest("hex"),c.application.versionName,c.application.versionCode].join("|"));
' "$CATALOG" "$ACTION_ID" "$REQUEST_ID" "$#") || fail "action, request id or parameter count is invalid"
IFS='|' read -r ROUTE PREFIX STATUS COMMAND CAPABILITY_HASH EXPECTED_VERSION EXPECTED_VERSION_CODE <<EOF
$META
EOF
REQUEST_UUID=${REQUEST_ID#${PREFIX}-}
if [ "$MODE" = send ] && [ "$STATUS" != vehicle_verified ]; then
    fail "action is prepared only; use check, or test for an explicit user-requested vehicle-side trial"
fi

[ "$(id -u)" = 0 ] || fail "caller is not uid 0"
[ -r "$BINDING" ] || fail "vehicle-binding.json is not readable"
VIN=$(node -e 'process.stdout.write(String(require(process.argv[1]).vin||""))' "$BINDING")
[ "${#VIN}" = 17 ] || fail "stored VIN length is not 17"
printf '%s' "$VIN" | grep -Eq '^[A-HJ-NPR-Z0-9]{17}$' || fail "stored VIN format check failed"
cmd package list packages --user 0 | grep -qx "package:$APP" || fail "Tesla package is not installed for user 0"
PACKAGE_DUMP=$(dumpsys package "$APP")
INSTALLED_VERSION=$(printf '%s\n' "$PACKAGE_DUMP" | awk '/^[ \t]*versionName=/ { sub(/^[ \t]*versionName=/, ""); print $1 }' | sort -u)
[ "$INSTALLED_VERSION" = "$EXPECTED_VERSION" ] || fail "installed Tesla version differs from the capability catalog"
INSTALLED_VERSION_CODE=$(printf '%s\n' "$PACKAGE_DUMP" | awk '/^[ \t]*versionCode=/ { sub(/^[ \t]*versionCode=/, ""); print $1 }' | sort -u)
[ "$INSTALLED_VERSION_CODE" = "$EXPECTED_VERSION_CODE" ] || fail "installed Tesla versionCode differs from the capability catalog"

check_widget() {
    [ "$#" -eq 0 ] || fail "widget actions take no parameters"
    case "$COMMAND" in FLASH_LIGHTS|HONK_HORN) ;; *) fail "unsupported widget command" ;; esac
    [ -x /system/bin/am ] || fail "am is not available"
    WIDGET_ID=$(dumpsys appwidget | node -e '
const fs=require("node:fs"),b=require(process.argv[1]);
if(!Number.isSafeInteger(b.appWidgetId)||b.appWidgetId<0||typeof b.widgetHostPackage!=="string"||!/^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(b.widgetHostPackage)) throw Error("widget binding required");
const text=fs.readFileSync(0,"utf8"),blocks=text.split(/(?=^  \[[0-9]+\] id=[0-9]+\r?$)/m);
const matches=blocks.filter(block=>block.split(/\r?\n/,1)[0].trim().match(/^\[[0-9]+\] id=([0-9]+)$/)?.[1]===String(b.appWidgetId));
if(matches.length!==1) throw Error("bound widget ID missing or ambiguous");
const block=matches[0],host=block.match(/hostId=HostId\{[^}\r\n]*\bpkg:([^},\s]+)[^}\r\n]*\}/);
if(host?.[1]!==b.widgetHostPackage) throw Error("widget host differs from private binding");
if(!block.includes("cmp:ComponentInfo{com.teslamotors.tesla/com.tesla.widget.VehicleStatusWidget}")) throw Error("widget provider differs from Tesla VehicleStatusWidget");
console.log(String(b.appWidgetId));
' "$BINDING") || fail "verify the Tesla widget ID, host and provider against the private binding"
}

invoke_adapter() {
    CLASSPATH="$ADAPTER" app_process /system/bin org.agentos.research.TeslaCommandAdapter \
        "$MODE" "$CATALOG" "$VIN" "$ACTION_ID" "$REQUEST_UUID" "$@"
}

case "$ROUTE" in
    widget) check_widget "$@" ;;
    command)
        [ -r "$ADAPTER" ] || fail "command adapter is not readable"
        [ -x /system/bin/app_process ] || fail "app_process is not available"
        ;;
esac

if [ "$MODE" = check ]; then
    if [ "$ROUTE" = command ]; then
        invoke_adapter "$@"
    else
        printf '{"mode":"check","action":"%s","route":"widget","widgetValidated":true,"broadcastSubmitted":false}\n' "$ACTION_ID"
    fi
    exit 0
fi

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR"
# Preserve the namespace so previously attempted requests cannot be replayed.
MARKER="$STATE_DIR/flash-once.${REQUEST_ID}.attempted"
RECEIPT="$STATE_DIR/flash-once.${REQUEST_ID}.receipt"
[ ! -e "$RECEIPT" ] || fail "receipt already exists for this request id"
mkdir "$MARKER" 2>/dev/null || fail "one-shot marker already exists; refusing repeat"
chmod 700 "$MARKER"
printf '%s\n' "attempted_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "mode=$MODE" "action=$ACTION_ID" "request_id=$REQUEST_ID" \
    "capability_sha256=$CAPABILITY_HASH" > "$MARKER/attempt.txt"
chmod 600 "$MARKER/attempt.txt"
: > "$RECEIPT"
chmod 600 "$RECEIPT"

dispatch_command() {
    if [ "$ROUTE" = widget ]; then
        am broadcast --user 0 -a "$WIDGET_ACTION" -n "$WIDGET_RECEIVER" \
            -d "tesla-widget://command?vin=${VIN}&command=${COMMAND}&app_widget_id=${WIDGET_ID}"
    else
        invoke_adapter "$@"
    fi
}

echo "SENDING_ONE_BROADCAST mode=$MODE action=$ACTION_ID request_id=$REQUEST_ID"
set +e
OUTPUT=$(dispatch_command "$@" 2>&1)
RESULT=$?
set -e
SANITIZED_OUTPUT=$(printf '%s\n' "$OUTPUT" | sed "s|$VIN|<VIN_REDACTED>|g")
printf 'MODE=%s\nACTION=%s\nREQUEST_ID=%s\nBROADCAST_EXIT=%s\n%s\n' \
    "$MODE" "$ACTION_ID" "$REQUEST_ID" "$RESULT" "$SANITIZED_OUTPUT" | tee "$RECEIPT"
chmod 600 "$RECEIPT"
exit "$RESULT"
