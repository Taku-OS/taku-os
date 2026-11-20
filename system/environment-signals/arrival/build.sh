#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../../scripts/android-env.sh
python3 ../../../scripts/aosp-test-keys.py ../../../out/development-signing
mkdir -p out
work=$(mktemp -d "${TMPDIR:-/tmp}/taku-arrival.XXXXXXXX")
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"
"$TOOLS/aapt2" link -o "$work/unsigned.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$work/classes" src/org/agentos/environment/arrival/*.java
"$JDK/bin/jar" --create --file "$work/classes.jar" -C "$work/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
"$TOOLS/apksigner" sign --key ../../../out/development-signing/platform.pk8 \
  --cert ../../../out/development-signing/platform.x509.pem --out out/TakuArrival.apk "$work/aligned.apk"
"$TOOLS/apksigner" verify out/TakuArrival.apk
