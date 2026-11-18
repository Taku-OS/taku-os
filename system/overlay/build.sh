#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
mkdir -p out
SIGNING=../../out/development-signing
python3 ../../scripts/aosp-test-keys.py "$SIGNING"
"$TOOLS/aapt2" compile --dir res -o out/resources.zip
"$TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml --no-resource-deduping --no-resource-removal -o out/unsigned.apk out/resources.zip
"$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" --out out/AgentOSLabSettingsOverlay.apk out/unsigned.apk
"$TOOLS/apksigner" verify out/AgentOSLabSettingsOverlay.apk
