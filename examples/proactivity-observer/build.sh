#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
task_version="${1:-1}"
case "$task_version" in 1|2) ;; *) echo 'Usage: build.sh [1|2]' >&2; exit 2 ;; esac
if [ -f ../../scripts/android-env.sh ]; then
    source ../../scripts/android-env.sh
else
    # A copied community project needs only Android tools, JDK and the distributed SDK JAR.
    task_android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    JDK="${JAVA_HOME:?Set JAVA_HOME to JDK 17 or newer}"
    TOOLS="$task_android_sdk/build-tools/36.0.0"
    ANDROID_JAR="$task_android_sdk/platforms/android-36/android.jar"
    test -f "$ANDROID_JAR" && test -x "$TOOLS/aapt2" && test -x "$JDK/bin/javac" || { echo 'Android SDK 36 and JDK 17 are required.' >&2; exit 1; }
fi
task_sdk="${TAKU_PROACTIVITY_SDK:-../../system/proactivity/out/taku-proactivity-client.jar}"
test -f "$task_sdk" || { echo 'Build or supply TAKU_PROACTIVITY_SDK first.' >&2; exit 1; }
mkdir -p out/signing
if [ ! -f out/signing/observer.jks ]; then
    "$JDK/bin/keytool" -genkeypair -alias observer -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Taku Independent Observer Development' -keystore out/signing/observer.jks -storepass taku-development-only -keypass taku-development-only >/dev/null 2>&1
fi
task_out="out/v$task_version"
rm -rf "$task_out"
mkdir -p "$task_out/classes" "$task_out/dex" "$task_out/generated/org/example/taku/observer"
python3 - "$task_version" "$task_out" <<'PY'
from pathlib import Path
import sys
v,out=sys.argv[1],Path(sys.argv[2])
manifest=Path('AndroidManifest.xml').read_text().replace('android:versionCode="1"',f'android:versionCode="{v}"').replace('android:versionName="1.0"',f'android:versionName="{v}.0"')
(out/'AndroidManifest.xml').write_text(manifest)
(out/'generated/org/example/taku/observer/BuildVersion.java').write_text(f'package org.example.taku.observer; final class BuildVersion {{ static final int VALUE={v}; }}\n')
PY
"$TOOLS/aapt2" link -o "$task_out/resources.apk" -I "$ANDROID_JAR" --manifest "$task_out/AndroidManifest.xml"
# Only the exported JAR and Android SDK are on the plugin compiler classpath.
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$task_sdk" -d "$task_out/classes" src/org/example/taku/observer/*.java "$task_out/generated/org/example/taku/observer/BuildVersion.java"
"$JDK/bin/jar" --create --file "$task_out/classes.jar" -C "$task_out/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$task_out/dex" "$task_out/classes.jar" "$task_sdk"
cp "$task_out/resources.apk" "$task_out/unsigned.apk"
(cd "$task_out/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$task_out/unsigned.apk" "$task_out/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --ks out/signing/observer.jks --ks-key-alias observer --ks-pass pass:taku-development-only --key-pass pass:taku-development-only --out "out/Observer-v$task_version.apk" "$task_out/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" verify "out/Observer-v$task_version.apk"
echo "Built observer v$task_version using a separate development signer; no install performed."
