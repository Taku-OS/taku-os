#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
source scripts/kotlin-env.sh
MODULE=system/audio-engine
python3 "$MODULE/record-build.py" begin
python3 "$MODULE/models/prepare.py" "$@"
python3 "$MODULE/network/prepare.py"
NETWORK_CP="$MODULE/out/network/okhttp-4.12.0.jar:$MODULE/out/network/okio-jvm-3.6.0.jar:$MODULE/out/network/msgpack-core-0.9.10.jar"
mkdir -p "$MODULE/out/assets/licenses/kotlin"
for notice in LICENSE.txt COPYRIGHT.txt NOTICE.txt; do
  [ ! -f "$KOTLIN_LIB/../license/$notice" ] || cp "$KOTLIN_LIB/../license/$notice" "$MODULE/out/assets/licenses/kotlin/$notice"
done
mkdir -p "$MODULE/out/dex"
python3 scripts/aosp-test-keys.py out/development-signing
"$KOTLINC" "$MODULE"/contract/org/agentos/audio/*.kt "$MODULE"/client/org/agentos/audio/*.kt "$MODULE"/src/org/agentos/audio/*.kt -jvm-target 17 -include-runtime -classpath "$ANDROID_JAR:$MODULE/out/sdk/classes.jar:$NETWORK_CP" -d "$MODULE/out/classes.jar"
"$KOTLINC" "$MODULE"/contract/org/agentos/audio/AudioContract.kt "$MODULE"/client/org/agentos/audio/*.kt -jvm-target 17 -include-runtime -classpath "$ANDROID_JAR" -d "$MODULE/out/audio-client.jar"
mkdir -p "$MODULE/out/bridge-dex"
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$MODULE/out/bridge-dex" "$MODULE/out/audio-client.jar"
"$JDK/bin/jar" --create --file "$MODULE/out/audio-bridge.jar" -C "$MODULE/out/bridge-dex" classes.dex
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$MODULE/out/dex" "$MODULE/out/classes.jar" "$MODULE/out/sdk/classes.jar" "$MODULE/out/network/okhttp-4.12.0.jar" "$MODULE/out/network/okio-jvm-3.6.0.jar" "$MODULE/out/network/msgpack-core-0.9.10.jar"
"$TOOLS/aapt2" compile --dir "$MODULE/res" -o "$MODULE/out/resources.zip"
"$TOOLS/aapt2" link -o "$MODULE/out/unsigned.apk" -I "$ANDROID_JAR" --manifest "$MODULE/AndroidManifest.xml" -A "$MODULE/out/assets" "$MODULE/out/resources.zip"
python3 - "$MODULE" <<'PY'
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1])
with zipfile.ZipFile(root/'out/unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as apk:
 for dex in (root/'out/dex').glob('classes*.dex'):apk.write(dex,dex.name)
 for path in (root/'out/sdk/lib').glob('*/*.so'):apk.write(path,'lib/'+path.parent.name+'/'+path.name,compress_type=zipfile.ZIP_STORED)
PY
"$TOOLS/zipalign" -f -P 16 4 "$MODULE/out/unsigned.apk" "$MODULE/out/aligned.apk"
"$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$MODULE/out/TakuAudioEngine.apk" "$MODULE/out/aligned.apk"
"$TOOLS/apksigner" verify "$MODULE/out/TakuAudioEngine.apk"
python3 "$MODULE/record-build.py" finish
echo 'Built Kotlin Audio Engine and client; model pack remains separate. No device changed.'
