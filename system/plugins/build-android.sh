#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
export PATH="$JDK/bin:$PATH"
out=${1:-system/plugins/out}
mkdir -p "$out/host-classes" "$out/host-dex" "$out/ui-classes" "$out/ui-dex"
host_sources=()
while IFS= read -r host_source; do
  host_sources+=("$host_source")
done < <(find system/plugins/src system/plugins/host system/plugins/android -name '*.java' ! -name Server.java ! -name Cli.java | sort)
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$out/host-classes" "${host_sources[@]}"
"$JDK/bin/jar" cf "$out/host-classes.jar" -C "$out/host-classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/host-dex" "$out/host-classes.jar"
"$JDK/bin/jar" cf "$out/taku-godmode-host.jar" -C "$out/host-dex" classes.dex
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$out/ui-classes" system/plugins/android-ui/org/taku/plugins/ui/*.java
"$JDK/bin/jar" cf "$out/ui-classes.jar" -C "$out/ui-classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/ui-dex" "$out/ui-classes.jar"
"$TOOLS/aapt2" link -o "$out/ui-unsigned.apk" -I "$ANDROID_JAR" --manifest system/plugins/AndroidManifest.xml
python3 - "$out/ui-unsigned.apk" "$out/ui-dex/classes.dex" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], 'a', compression=zipfile.ZIP_DEFLATED) as apk: apk.write(sys.argv[2], 'classes.dex')
PY
"$TOOLS/zipalign" -f 4 "$out/ui-unsigned.apk" "$out/ui-aligned.apk"
python3 scripts/aosp-test-keys.py out/development-signing
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$out/TakuGodMode.apk" "$out/ui-aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" verify "$out/TakuGodMode.apk"
for name in status-bar settings-page launcher-grid; do
  mkdir -p "$out/examples/$name" "$out/examples-classes/$name" "$out/examples-dex/$name"
  "$JDK/bin/javac" --release 17 -cp "$out/host-classes:$ANDROID_JAR" -d "$out/examples-classes/$name" examples/godmode/"$name"/*.java
  "$JDK/bin/jar" cf "$out/examples-classes/$name.jar" -C "$out/examples-classes/$name" .
  JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/examples-dex/$name" "$out/examples-classes/$name.jar"
  cp "$out/examples-dex/$name/classes.dex" "$out/examples/$name/plugin.dex"
  cp examples/godmode/"$name"/manifest.json "$out/examples/$name/manifest.json"
done
echo "Built Android host, management APK and three DEX examples in $out; no device was changed."
