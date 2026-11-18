#!/system/bin/sh
set -eu
stamp_ms() {
  read uptime_seconds rest < /proc/uptime
  whole=${uptime_seconds%%.*}
  fraction=${uptime_seconds#*.}
  echo "$((whole * 1000 + (1000$fraction % 100) * 10))"
}
KIT=/data/local/agentos/light-app-lab/free-app-kit
LAB=/data/local/agentos/light-app-lab
APP="$1"
PROMPT_FILE="$2"
case "$APP" in *[!a-zA-Z0-9_-]*|'') exit 2;; esac
mkdir -p "$KIT/apps/$APP"
cp "$KIT/seed/main.jsx" "$KIT/seed/ui.jsx" "$KIT/apps/$APP/"
START=$(stamp_ms)
/system_ext/bin/codex exec --skip-git-repo-check --dangerously-bypass-approvals-and-sandbox -C "$KIT/apps/$APP" --output-schema "$KIT/source.schema.json" -o "$KIT/apps/$APP/source.json" "$(cat "$KIT/api/generation-contract.txt")

Application request:
$(cat "$PROMPT_FILE")" </dev/null > "$KIT/apps/$APP/generation.log" 2>&1
GENERATED=$(stamp_ms)
export LD_LIBRARY_PATH=/data/local/agentos/light-app-lab/native/lib
"$LAB/native/node" -e 'const fs=require("fs");const data=JSON.parse(fs.readFileSync(process.argv[1],"utf8"));if(typeof data.source!=="string"||!data.source.trim()||typeof data.apiSource!=="string"||!data.apiSource.trim())throw Error("Missing source or API module");fs.writeFileSync(process.argv[2],data.source);fs.writeFileSync(process.argv[3],data.apiSource)' "$KIT/apps/$APP/source.json" "$KIT/apps/$APP/App.jsx" "$KIT/apps/$APP/api.mjs"
/system/bin/sh "$KIT/build.sh" "$APP" > "$KIT/apps/$APP/build-result.txt" 2>&1
END=$(stamp_ms)
echo "device_generation_ms=$(((GENERATED-START)/1))"
echo "device_generation_and_preview_command_ms=$(((END-START)/1))"
wc -c "$KIT/apps/$APP/App.jsx"
cat "$KIT/apps/$APP/build-result.txt"
