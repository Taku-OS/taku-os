#!/bin/bash
# Test-only fixture for visual comparison; not model or device-action acceptance.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
MARKDOWN_CP=$(python3 ../../scripts/prepare-markdown.py)
IFS=: read -r -a MARKDOWN_JARS <<< "$MARKDOWN_CP"
export ADB_SERVER_SOCKET=tcp:5037
SERIAL=emulator-5634
[[ "${1:-}" == "" || "${1:-}" == --verify-conversation || "${1:-}" == --verify-markdown ]] || exit 2
[ "$("$ADB" -s "$SERIAL" emu avd name | head -1 | tr -d '\r')" = Taku_ImageIntegration_20261010 ] || exit 1
OUTPUT=../../out/agent-drawer-ui/visual-fixture
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$TOOLS/aapt2" link -o "$OUTPUT/resources.apk" -I "$ANDROID_JAR" --manifest tests/visual/AndroidManifest.xml out/resources.zip
cp -R out/classes/org "$OUTPUT/classes/"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:out/classes:$MARKDOWN_CP" -d "$OUTPUT/classes" tests/visual/src/org/agentos/assistant/*.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar" "${MARKDOWN_JARS[@]}"
cp "$OUTPUT/resources.apk" "$OUTPUT/test.apk"
(cd "$OUTPUT/dex" && zip -q ../test.apk classes.dex)
python3 ../../scripts/package-markdown-resources.py "$OUTPUT/test.apk"
"$TOOLS/zipalign" -f 4 "$OUTPUT/test.apk" "$OUTPUT/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key ../../out/development-signing/platform.pk8 --cert ../../out/development-signing/platform.x509.pem --out "$OUTPUT/signed.apk" "$OUTPUT/aligned.apk"
"$ADB" -s "$SERIAL" install --no-incremental -r "$OUTPUT/signed.apk"
if [[ "${1:-}" == --verify-conversation || "${1:-}" == --verify-markdown ]]; then
 ACTIVITY=ConversationRendererActivity
 REPORT=conversation-renderer
 if [[ "$1" == --verify-markdown ]]; then ACTIVITY=MarkdownRendererActivity; REPORT=markdown-renderer; fi
 trap '"$ADB" -s "$SERIAL" uninstall org.agentos.drawer.visualtests >/dev/null' EXIT
 "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_BACK
 for theme in light dark; do
  "$ADB" -s "$SERIAL" shell am force-stop org.agentos.drawer.visualtests
  "$ADB" -s "$SERIAL" shell rm -f "/data/user/0/org.agentos.drawer.visualtests/files/$REPORT.json"
  "$ADB" -s "$SERIAL" shell am start --display 0 -n "org.agentos.drawer.visualtests/org.agentos.assistant.$ACTIVITY" --es theme "$theme"
  for attempt in {1..100}; do
   "$ADB" -s "$SERIAL" exec-out cat "/data/user/0/org.agentos.drawer.visualtests/files/$REPORT.json" > "$OUTPUT/$REPORT.json" 2>/dev/null || true
   if python3 -c 'import json,sys;json.load(open(sys.argv[1]))' "$OUTPUT/$REPORT.json" 2>/dev/null; then break; fi
   sleep .25
  done
  "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUTPUT/$REPORT-$theme.png"
  python3 - "$OUTPUT/$REPORT.json" <<'VERIFY'
import json,sys
result=json.load(open(sys.argv[1]))
assert result['passed'],result
print(json.dumps(result,ensure_ascii=False))
VERIFY
  [[ "$1" == --verify-markdown ]] || break
 done
 exit 0
fi

"$ADB" -s "$SERIAL" shell input keyevent KEYCODE_BACK
for theme in dark light; do
 "$ADB" -s "$SERIAL" shell am start -n org.agentos.assistant/.CodexHomeActivity
 "$ADB" -s "$SERIAL" shell am start -n org.agentos.drawer.visualtests/org.agentos.assistant.DrawerVisualActivity --es theme "$theme"
 sleep 1
 "$ADB" -s "$SERIAL" exec-out screencap -p > "$OUTPUT/$theme.png"
 "$ADB" -s "$SERIAL" shell am force-stop org.agentos.drawer.visualtests
done
printf '%s\n' 'Captured native-view visual fixtures only. No model runtime, no system command execution.'
