#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
SIGNING=../../out/development-signing
python3 ../../scripts/aosp-test-keys.py "$SIGNING"
rm -rf out/classes out/dex out/generated
mkdir -p out/{classes,dex,generated}
"$TOOLS/aapt2" compile --dir res -o out/resources.zip
"$TOOLS/aapt2" link -o out/resources.apk -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
    --java out/generated out/resources.zip
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d out/classes src/org/agentos/notebook/*.java \
    ../ai/client/org/agentos/ai/AiClient.java ../ai/client/org/agentos/ai/NotebookSocialSource.java \
    ../surfaces/contract/org/agentos/surfaces/*.java \
    ../surfaces/wire/org/agentos/surfaces/*.java \
    ../surfaces/client/org/agentos/surfaces/client/*.java \
    out/generated/org/agentos/notebook/R.java
"$JDK/bin/jar" --create --file out/classes.jar -C out/classes .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/dex out/classes.jar
cp out/resources.apk out/unsigned.apk
(cd out/dex && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 out/unsigned.apk out/aligned.apk
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" \
    --out out/TakuNotebook.apk out/aligned.apk

JAVA_HOME="$JDK" "$TOOLS/apksigner" verify out/TakuNotebook.apk
printf "%s\n" "Built system/notebook/out/TakuNotebook.apk; no device changed."
