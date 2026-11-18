#!/bin/bash
# Build-only fixture. Device deployment is deliberately a separate, isolated-emulator step.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/compile-jvm.sh
task_out="$PWD/out/session-removals-device"
task_broker="$PWD/system/assistant/out/broker-classes.jar"
task_app="$PWD/system/assistant/out/classes.jar"
[ -f "$task_broker" ] && [ -f "$task_app" ] || bash system/assistant/build.sh
mkdir -p "$task_out/probe-classes" "$task_out/ui-classes" "$task_out/probe-dex" "$task_out/ui-dex"
"$JDK/bin/javac" --release 17 -cp "$task_broker:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" \
 -d "$task_out/probe-classes" system/assistant/tests/session-removals-device/org/agentos/lab/SessionRemovalDeviceProbe.java
"$JDK/bin/javac" --release 17 -cp "$task_app:$ANDROID_JAR:$KOTLIN_LIB/kotlin-stdlib.jar" \
 -d "$task_out/ui-classes" system/assistant/tests/session-removals-device/org/agentos/assistant/SessionRemovalTestActivity.java
"$JDK/bin/jar" --create --file "$task_out/probe-classes.jar" -C "$task_out/probe-classes" .
"$JDK/bin/jar" --create --file "$task_out/ui-classes.jar" -C "$task_out/ui-classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$task_out/probe-dex" \
 "$task_broker" "$task_out/probe-classes.jar" "$KOTLIN_LIB/kotlin-stdlib.jar"
"$JDK/bin/jar" --create --file "$task_out/probe.jar" -C "$task_out/probe-dex" classes.dex
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --classpath "$task_app" \
 --output "$task_out/ui-dex" "$task_out/ui-classes.jar"
python3 - "$task_out" <<'PY'
from pathlib import Path
import sys
out = Path(sys.argv[1])
manifest = Path('system/assistant/AndroidManifest.xml').read_text()
activity = '<activity android:name="org.agentos.assistant.SessionRemovalTestActivity" android:exported="true" android:theme="@android:style/Theme.Material.Light.NoActionBar"/>'
(out / 'AndroidManifest.xml').write_text(manifest.replace('</application>', activity + '\n</application>'))
PY
"$TOOLS/aapt2" link -o "$task_out/resources.apk" -I "$ANDROID_JAR" --manifest "$task_out/AndroidManifest.xml" \
 system/assistant/out/resources.zip
python3 - "$task_out" <<'PY'
from pathlib import Path
import re, sys, zipfile
out = Path(sys.argv[1])
with zipfile.ZipFile(out / 'resources.apk') as resources:
    manifest = resources.read('AndroidManifest.xml')
with zipfile.ZipFile('system/assistant/out/AgentOSAssistant.apk') as source, zipfile.ZipFile(out / 'unsigned.apk', 'w') as target:
    names = source.namelist()
    indexes = [int(match.group(1) or 1) for name in names if (match := re.fullmatch(r'classes(\d*)\.dex', name))]
    for item in source.infolist():
        if item.filename == 'AndroidManifest.xml' or re.fullmatch(r'META-INF/[^/]+\.(RSA|SF|MF)', item.filename):
            continue
        target.writestr(item, source.read(item.filename))
    target.writestr('AndroidManifest.xml', manifest)
    target.write(out / 'ui-dex/classes.dex', 'classes' + str(max(indexes) + 1) + '.dex')
PY
"$TOOLS/zipalign" -f 4 "$task_out/unsigned.apk" "$task_out/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 \
 --cert out/development-signing/platform.x509.pem --out "$task_out/SessionRemovalFixture.apk" "$task_out/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" verify "$task_out/SessionRemovalFixture.apk"
echo 'Built probe.jar and SessionRemovalFixture.apk; no device contacted.'
