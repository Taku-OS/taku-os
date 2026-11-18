#!/bin/bash
# Build only. The owning session decides when to install/run on its assigned device.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/agent-gateway-probes"
task_signing="$PWD/out/development-signing"
mkdir -p "$task_out/classes" "$task_out/dex"
python3 scripts/aosp-test-keys.py "$task_signing"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/classes" \
 system/assistant/tests/agent-gateway/device/org/agentos/gatewayprobe/GatewayProbe.java
"$JDK/bin/jar" --create --file "$task_out/classes.jar" -C "$task_out/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$task_out/dex" "$task_out/classes.jar"
for task_variant in allowed client denied; do
  task_package="org.agentos.gatewayprobe"
  task_target="org.agentos.agent"
  if [ "$task_variant" = client ]; then
    task_package="org.agentos.agent"
  elif [ "$task_variant" = denied ]; then
    task_package="org.agentos.gateway.denied"
    task_target="$task_package"
  fi
  task_dir="$task_out/$task_variant"
  mkdir -p "$task_dir"
  cat > "$task_dir/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$task_package" android:versionCode="1" android:versionName="1">
 <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/>
 <uses-permission android:name="org.agentos.permission.AGENT_CLIENT"/>
 <queries><package android:name="org.agentos.assistant"/></queries>
 <application android:label="Taku gateway IPC probe" android:allowBackup="false"/>
 <instrumentation android:name="org.agentos.gatewayprobe.GatewayProbe" android:targetPackage="$task_target" android:functionalTest="true"/>
</manifest>
XML
  "$TOOLS/aapt2" link -o "$task_dir/resources.apk" -I "$ANDROID_JAR" --manifest "$task_dir/AndroidManifest.xml"
  cp "$task_dir/resources.apk" "$task_dir/unsigned.apk"
  (cd "$task_out/dex" && zip -q "$task_dir/unsigned.apk" classes.dex)
  "$TOOLS/zipalign" -f 4 "$task_dir/unsigned.apk" "$task_dir/aligned.apk"
  JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$task_signing/platform.pk8" --cert "$task_signing/platform.x509.pem" \
    --out "$task_out/$task_variant.apk" "$task_dir/aligned.apk"
done
echo "Built allowed.apk, client.apk and denied.apk in $task_out. No device has been modified."
