#!/bin/bash
# Build only. The owning session decides when to install/run on its assigned device.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
task_out="$PWD/out/open-access-probes"
task_signing="$PWD/out/development-signing"
mkdir -p "$task_out/classes" "$task_out/dex"
python3 scripts/aosp-test-keys.py "$task_signing"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/classes" \
 system/assistant/tests/open-access/device/org/agentos/permissionprobe/OpenAccessProbe.java
"$JDK/bin/jar" --create --file "$task_out/classes.jar" -C "$task_out/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$task_out/dex" "$task_out/classes.jar"
if [ ! -f "$task_out/probe.keystore" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$task_out/probe.keystore" -storepass android -keypass android \
    -alias probe -keyalg RSA -keysize 2048 -validity 30 -dname 'CN=Disposable permission test' >/dev/null 2>&1
fi
for task_variant in control gateway; do
  task_package="org.agentos.permissionprobe.$task_variant"
  task_target="$task_package"
  if [ "$task_variant" = gateway ]; then task_target="org.agentos.agent"; fi
  task_dir="$task_out/$task_variant"
  mkdir -p "$task_dir"
  cat > "$task_dir/AndroidManifest.xml" <<XML
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$task_package" android:versionCode="1" android:versionName="1">
 <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36"/>
 <uses-permission android:name="org.agentos.permission.AGENT_CLIENT"/>
 <queries><package android:name="org.agentos.assistant"/></queries>
 <application android:label="Taku permission probe" android:allowBackup="false"/>
 <instrumentation android:name="org.agentos.permissionprobe.OpenAccessProbe" android:targetPackage="$task_target" android:functionalTest="true"/>
</manifest>
XML
  "$TOOLS/aapt2" link -o "$task_dir/resources.apk" -I "$ANDROID_JAR" --manifest "$task_dir/AndroidManifest.xml"
  cp "$task_dir/resources.apk" "$task_dir/unsigned.apk"
  (cd "$task_out/dex" && zip -q "$task_dir/unsigned.apk" classes.dex)
  "$TOOLS/zipalign" -f 4 "$task_dir/unsigned.apk" "$task_dir/aligned.apk"
  if [ "$task_variant" = gateway ]; then
    JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$task_signing/platform.pk8" --cert "$task_signing/platform.x509.pem" \
      --out "$task_out/$task_variant.apk" "$task_dir/aligned.apk"
  else
    JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --ks "$task_out/probe.keystore" --ks-pass pass:android \
      --out "$task_out/$task_variant.apk" "$task_dir/aligned.apk"
  fi
done
echo "Built control.apk (third-party signer) and gateway.apk (platform signer). No device changed."
