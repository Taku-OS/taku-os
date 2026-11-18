#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/android-env.sh
out="$PWD/out/inputmethod/chatcase"
mkdir -p "$out/classes" "$out/dex"
"$TOOLS/aapt2" link -o "$out/resources.apk" -I "$ANDROID_JAR" --manifest system/inputmethod/tests/chatcase/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$out/classes" system/inputmethod/tests/chatcase/ChatActivity.java
"$JDK/bin/jar" --create --file "$out/classes.jar" -C "$out/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/dex" "$out/classes.jar"
cp "$out/resources.apk" "$out/unsigned.apk"
(cd "$out/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$out/unsigned.apk" "$out/aligned.apk"
key="$PWD/out/inputmethod/fixture/fixture.jks"
test -f "$key" || bash system/inputmethod/tests/build.sh
"$TOOLS/apksigner" sign --ks "$key" --ks-pass pass:android --out "$out/chatcase.apk" "$out/aligned.apk"
