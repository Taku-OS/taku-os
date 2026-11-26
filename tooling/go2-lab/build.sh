#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
mkdir -p out/go2-lab
build=$(mktemp -d "$PWD/out/go2-lab/build.XXXXXX")
mkdir -p "$build/classes" "$build/dex" "$build/generated" "$build/assets"
# The dashboard remains available while the local connection service is stopped.
for asset in panel.html panel.js panel.css robot.png wallpaper.png; do cp "tooling/go2-lab/web/$asset" "$build/assets/$asset"; done
signing=out/development-signing
python3 scripts/aosp-test-keys.py "$signing"
"$TOOLS/aapt2" compile --dir tooling/go2-lab/res -o "$build/resources.zip"
"$TOOLS/aapt2" link -o "$build/resources.apk" -I "$ANDROID_JAR" --manifest tooling/go2-lab/AndroidManifest.xml --java "$build/generated" -A "$build/assets" "$build/resources.zip"
"$JDK/bin/javac" -encoding UTF-8 --release 17 -cp "$ANDROID_JAR" -d "$build/classes" "$build/generated/org/taku/go2lab/R.java" tooling/go2-lab/src/org/taku/go2lab/*.java system/ai/client/org/agentos/ai/AiClient.java
"$JDK/bin/jar" --create --file "$build/classes.jar" -C "$build/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$build/dex" "$build/classes.jar"
cp "$build/resources.apk" "$build/unsigned.apk"
(cd "$build/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
"$TOOLS/apksigner" sign --key "$signing/platform.pk8" --cert "$signing/platform.x509.pem" --out out/go2-lab/TakuGo2.apk "$build/aligned.apk"
"$TOOLS/apksigner" verify out/go2-lab/TakuGo2.apk
"$JDK/bin/jar" --create --file out/go2-lab/client.jar -C "$build/dex" classes.dex
