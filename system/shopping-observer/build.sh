#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
task_out="$PWD/out/build"
mkdir -p "$task_out/classes" "$task_out/dex" "$task_out/generated" out/signing
# Development builds get an ignored, locally generated signer. Production uses Soong's platform
# certificate and its own UID. CI can inject a signer without copying a key into source control.
task_keystore="${TAKU_OBSERVER_KEYSTORE:-$PWD/out/signing/shopping.jks}"
task_alias="${TAKU_OBSERVER_KEY_ALIAS:-shopping}"
task_store_pass="${TAKU_OBSERVER_STORE_PASS:-taku-development-only}"
task_key_pass="${TAKU_OBSERVER_KEY_PASS:-$task_store_pass}"
if [ ! -f "$task_keystore" ]; then
 if [ -n "${TAKU_OBSERVER_KEYSTORE:-}" ]; then echo 'Injected observer keystore is missing.' >&2; exit 1; fi
 "$JDK/bin/keytool" -genkeypair -alias "$task_alias" -keyalg RSA -keysize 2048 -validity 3650 \
  -dname 'CN=Taku Shopping Observer Development' -keystore "$task_keystore" \
  -storepass "$task_store_pass" -keypass "$task_key_pass" >/dev/null 2>&1
fi
find "$task_out/classes" "$task_out/dex" "$task_out/generated" -type f -delete
"$TOOLS/aapt2" compile --dir res -o "$task_out/resources.zip"
"$TOOLS/aapt2" link -o "$task_out/resources.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
 --java "$task_out/generated" "$task_out/resources.zip"
find core src "$task_out/generated" ../proactivity/client/org ../proactivity/contract/org \
 ../tryon/common/org ../tryon/contract/org -name '*.java' -print > "$task_out/sources.txt"
printf '%s\n' "$PWD/../ai/client/org/agentos/ai/AiClient.java" >> "$task_out/sources.txt"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$task_out/classes" @"$task_out/sources.txt"
"$JDK/bin/jar" --create --file "$task_out/classes.jar" -C "$task_out/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$task_out/dex" "$task_out/classes.jar"
cp "$task_out/resources.apk" "$task_out/unsigned.apk"
(cd "$task_out/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$task_out/unsigned.apk" "$task_out/aligned.apk"
export task_store_pass task_key_pass
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --ks "$task_keystore" --ks-key-alias "$task_alias" \
 --ks-pass env:task_store_pass --key-pass env:task_key_pass \
 --out out/TakuShoppingObserver.apk "$task_out/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" verify out/TakuShoppingObserver.apk
echo 'Built out/TakuShoppingObserver.apk. No device changed.'
