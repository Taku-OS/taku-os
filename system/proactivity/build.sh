#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
test -f ../surfaces/out/taku-surfaces-client.jar || bash ../surfaces/build.sh
python3 ../../scripts/aosp-test-keys.py ../../out/development-signing
task_out=out
rm -rf "$task_out/sdk" "$task_out/host"
mkdir -p "$task_out/sdk/classes" "$task_out/host/classes" "$task_out/host/dex" "$task_out/host/generated"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/sdk/classes" contract/org/agentos/proactivity/api/*.java client/org/agentos/proactivity/api/*.java
"$JDK/bin/jar" --create --file "$task_out/taku-proactivity-client.jar" -C "$task_out/sdk/classes" .
"$TOOLS/aapt2" compile --dir res -o "$task_out/host/resources.zip"
"$TOOLS/aapt2" link -o "$task_out/host/resources.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml --java "$task_out/host/generated" "$task_out/host/resources.zip"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$task_out/taku-proactivity-client.jar:../surfaces/out/taku-surfaces-client.jar" -d "$task_out/host/classes" "$task_out/host/generated/org/agentos/proactivity/R.java" src/org/agentos/proactivity/*.java
"$JDK/bin/jar" --create --file "$task_out/host/classes.jar" -C "$task_out/host/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$task_out/host/dex" "$task_out/host/classes.jar" "$task_out/taku-proactivity-client.jar" ../surfaces/out/taku-surfaces-client.jar
cp "$task_out/host/resources.apk" "$task_out/host/unsigned.apk"
(cd "$task_out/host/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$task_out/host/unsigned.apk" "$task_out/host/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key ../../out/development-signing/platform.pk8 --cert ../../out/development-signing/platform.x509.pem --out "$task_out/TakuProactivity.apk" "$task_out/host/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" verify "$task_out/TakuProactivity.apk"
echo 'Built independent host APK and public SDK JAR. No device was changed.'
