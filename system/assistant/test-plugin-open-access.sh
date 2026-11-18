#!/bin/bash
# Requires the creator fixture to be installed/enabled on the disposable creator AVD.
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
serial=${1:-}
[[ "$serial" == emulator-* && "$("$ADB" -s "$serial" emu avd name | head -1 | tr -d '\r')" == Taku_PluginCreator_20261030 ]] || { echo 'Dedicated creator AVD required' >&2; exit 1; }
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/classes" "$tmp/dex"
cat > "$tmp/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.agentos.tests.creatorprobe"><uses-sdk android:minSdkVersion="28" android:targetSdkVersion="36"/><application android:label="Creator Probe"><activity android:name="org.agentos.tests.PluginOpenAccessProbe" android:exported="true"/></application></manifest>
XML
"$TOOLS/aapt2" link -o "$tmp/resources.apk" -I "$ANDROID_JAR" --manifest "$tmp/AndroidManifest.xml"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$tmp/classes" system/assistant/tests/org/agentos/tests/PluginOpenAccessProbe.java
"$JDK/bin/jar" cf "$tmp/classes.jar" -C "$tmp/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 28 --lib "$ANDROID_JAR" --output "$tmp/dex" "$tmp/classes.jar"
cp "$tmp/resources.apk" "$tmp/unsigned.apk"
(cd "$tmp/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$tmp/unsigned.apk" "$tmp/aligned.apk"
"$JDK/bin/keytool" -genkeypair -keystore "$tmp/probe.jks" -storepass android -keypass android -alias probe -keyalg RSA -dname CN=Probe -validity 1 >/dev/null 2>&1
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --ks "$tmp/probe.jks" --ks-pass pass:android --key-pass pass:android --out "$tmp/probe.apk" "$tmp/aligned.apk"
"$ADB" -s "$serial" install --no-incremental "$tmp/probe.apk" >/dev/null
trap '"$ADB" -s "$serial" uninstall org.agentos.tests.creatorprobe >/dev/null 2>&1 || true; rm -rf "$tmp"' EXIT
"$ADB" -s "$serial" shell am start -n org.agentos.tests.creatorprobe/org.agentos.tests.PluginOpenAccessProbe >/dev/null
pid=""
for attempt in {1..20}; do
 pid=$("$ADB" -s "$serial" shell pidof org.agentos.tests.creatorprobe 2>/dev/null | tr -d '\r' || true)
 [[ -n "$pid" ]] && break
 sleep 0.5
done
[[ -n "$pid" ]] || { echo 'Probe process did not start' >&2; exit 1; }
for attempt in {1..30}; do
 log=$("$ADB" -s "$serial" logcat -d --pid="$pid" -s CreatorProbe:I '*:S')
 if [[ "$log" == *'command=list exit=0'* && "$log" == *'command=disable exit=0'* && "$log" == *'command=enable exit=0'* ]]; then
  echo 'PASS: independently signed ordinary app queried, disabled and enabled the shared plugin via CLI'
  exit 0
 fi
 sleep 1
done
printf '%s\n' "$log" >&2
exit 1
