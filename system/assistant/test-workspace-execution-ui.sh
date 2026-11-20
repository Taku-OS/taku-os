#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
SERIAL=${TAKU_SERIAL:?Set TAKU_SERIAL to the isolated emulator serial}
if [[ "$#" != 0 || ! "$SERIAL" =~ ^emulator-[0-9]+$ ]]; then
 echo 'Set TAKU_SERIAL to a development emulator serial and pass no arguments.' >&2
 exit 2
fi
OUTPUT=../../out/workspace-execution-ui
PACKAGE=org.agentos.workspace.visualtests
REPORT=workspace-execution-tests.json
AUDIO_CP=../audio-engine/out/audio-client.jar
rm -rf "$OUTPUT/classes" "$OUTPUT/dex"
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
"$TOOLS/aapt2" link -o "$OUTPUT/resources.apk" -I "$ANDROID_JAR" --manifest tests/visual/AndroidManifest.xml -A assets out/resources.zip
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:out/classes.jar" -d "$OUTPUT/classes" tests/visual/src/org/agentos/assistant/WorkspaceExecutionTestActivity.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar" out/classes.jar "$AUDIO_CP"
cp "$OUTPUT/resources.apk" "$OUTPUT/test.apk"
(cd "$OUTPUT/dex" && zip -q ../test.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUTPUT/test.apk" "$OUTPUT/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key ../../out/development-signing/platform.pk8 --cert ../../out/development-signing/platform.x509.pem --out "$OUTPUT/signed.apk" "$OUTPUT/aligned.apk"
"$ADB" -s "$SERIAL" install --no-incremental -r "$OUTPUT/signed.apk"
trap '"$ADB" -s "$SERIAL" uninstall "$PACKAGE" >/dev/null' EXIT
"$ADB" -s "$SERIAL" shell pm clear "$PACKAGE"
"$ADB" -s "$SERIAL" shell am start -W --display 0 -n "$PACKAGE/org.agentos.assistant.WorkspaceExecutionTestActivity"
for attempt in {1..100}; do
 if "$ADB" -s "$SERIAL" exec-out run-as "$PACKAGE" cat "files/$REPORT" > "$OUTPUT/$REPORT" 2>/dev/null &&
    python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$OUTPUT/$REPORT" 2>/dev/null; then
  break
 fi
 sleep .25
done
python3 - "$OUTPUT/$REPORT" <<'VERIFY'
import json
import sys

with open(sys.argv[1]) as report:
    result = json.load(report)
assert result['passed'], result
print(json.dumps(result, ensure_ascii=False))
VERIFY
