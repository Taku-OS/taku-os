#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# Explicit compatibility target for the recorded Pixel Proactivity 8 host. Build only.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../../.." && pwd)"
APP="$ROOT/system/shopping-observer"
source "$ROOT/scripts/android-env.sh"
OUT="$ROOT/out/shopping-observer-pixel-compat"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/generated"
python3 "$HERE/generate.py"
find "$OUT/classes" "$OUT/dex" "$OUT/generated" -type f -delete
"$TOOLS/aapt2" compile --dir "$APP/res" -o "$OUT/resources.zip"
"$TOOLS/aapt2" link -o "$OUT/resources.apk" -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/generated" "$OUT/resources.zip"
find "$APP/core" "$APP/src" "$ROOT/system/proactivity/client/org" "$ROOT/system/proactivity/contract/org" \
 "$ROOT/system/tryon/common/org" "$ROOT/system/tryon/contract/org" "$OUT/generated" -name '*.java' \
 ! -path '*/org/agentos/proactivity/api/ProactivityClient.java' ! -path '*/org/agentos/shopping/ReminderConnection.java' > "$OUT/sources.txt"
find "$HERE/src" "$OUT/src" -name '*.java' >> "$OUT/sources.txt"
printf '%s\n' "$ROOT/system/ai/client/org/agentos/ai/AiClient.java" >> "$OUT/sources.txt"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
"$JDK/bin/jar" --create --file "$OUT/classes.jar" -C "$OUT/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/classes.jar"
cp "$OUT/resources.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ -n "${TAKU_OBSERVER_KEYSTORE:-}" ]; then
  task_store_pass="${TAKU_OBSERVER_STORE_PASS:?Set TAKU_OBSERVER_STORE_PASS for the supplied development keystore}"
  task_key_pass="${TAKU_OBSERVER_KEY_PASS:-$task_store_pass}"
  export task_store_pass task_key_pass
  "$TOOLS/apksigner" sign --ks "$TAKU_OBSERVER_KEYSTORE" --ks-key-alias "${TAKU_OBSERVER_KEY_ALIAS:-shopping}" \
    --ks-pass env:task_store_pass --key-pass env:task_key_pass --out "$OUT/TakuShoppingObserver.apk" "$OUT/aligned.apk"
else
  SIGNING="$ROOT/out/development-signing"
  python3 "$ROOT/scripts/aosp-test-keys.py" "$SIGNING"
  "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" \
    --out "$OUT/TakuShoppingObserver.apk" "$OUT/aligned.apk"
fi
"$TOOLS/apksigner" verify "$OUT/TakuShoppingObserver.apk"
echo "Built $OUT/TakuShoppingObserver.apk; no device, credentials or model accessed."
