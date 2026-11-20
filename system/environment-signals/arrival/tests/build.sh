#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../../../scripts/android-env.sh
OUTPUT=../out/fixtures
SIGNING="$OUTPUT/signing"
rm -rf "$OUTPUT/classes" "$OUTPUT/dex"
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex"
if [ ! -f "$SIGNING/untrusted.jks" ]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$SIGNING/untrusted.jks" -storepass development-only \
        -keypass development-only -alias fixture -dname CN=TakuArrivalFixture -keyalg RSA \
        -validity 3650 -noprompt
fi
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" Fixture.java ArrivalReceiver.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"

build_fixture() {
    local name=$1 label=$2
    local output="$OUTPUT/$name"
    mkdir -p "$output"
    sed -e "s/@PACKAGE@/org.agentos.arrivalfixture.$name/g" -e "s/@LABEL@/$label/g" \
        AndroidManifest.xml > "$output/AndroidManifest.xml"
    "$TOOLS/aapt2" link -o "$output/unsigned.apk" -I "$ANDROID_JAR" --manifest "$output/AndroidManifest.xml"
    (cd "$OUTPUT/dex" && zip -q "../$name/unsigned.apk" classes.dex)
    "$TOOLS/zipalign" -f 4 "$output/unsigned.apk" "$output/aligned.apk"
    "$TOOLS/apksigner" sign --ks "$SIGNING/untrusted.jks" --ks-pass pass:development-only \
        --out "$OUTPUT/$name.apk" "$output/aligned.apk"
    "$TOOLS/apksigner" verify "$OUTPUT/$name.apk"
}

build_fixture notebook "Notebook arrival fixture"
build_fixture itinerary "Itinerary arrival fixture"
