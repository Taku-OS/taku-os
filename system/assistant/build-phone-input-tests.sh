#!/bin/bash
# Build only: no device is accessed and no production package is replaced.
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")"
source ../../scripts/android-env.sh
OUTPUT=../../out/phone-input-tests
rm -rf "$OUTPUT/classes" "$OUTPUT/dex" "$OUTPUT/helper-classes" "$OUTPUT/helper-dex" "$OUTPUT/compile-only"
mkdir -p "$OUTPUT/classes" "$OUTPUT/dex" "$OUTPUT/helper-classes" "$OUTPUT/helper-dex" "$OUTPUT/compile-only"
"$TOOLS/aapt2" link -o "$OUTPUT/resources.apk" -I "$ANDROID_JAR" --manifest tests/phone-input/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/classes" tests/phone-input/src/org/agentos/phoneinput/tests/*.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
cp "$OUTPUT/resources.apk" "$OUTPUT/unsigned.apk"
(cd "$OUTPUT/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUTPUT/unsigned.apk" "$OUTPUT/aligned.apk"
SIGNING=../../out/development-signing
python3 ../../scripts/aosp-test-keys.py "$SIGNING"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" --out "$OUTPUT/fixture.apk" "$OUTPUT/aligned.apk"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/helper-classes" broker/org/agentos/lab/PhoneTextInput.java tests/phone-input/PhoneTextInputAcceptanceProbe.java
# Compile against the API, but never package an alternate draft helper into the probe jar.
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/compile-only" broker/org/agentos/lab/PhoneDraftTarget.java broker/org/agentos/lab/PhoneDraftHandoff.java
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$OUTPUT/compile-only" -d "$OUTPUT/helper-classes" tests/phone-input/PhoneDraftHandoffAcceptanceProbe.java
"$JDK/bin/jar" --create --file "$OUTPUT/helper-classes.jar" -C "$OUTPUT/helper-classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/helper-dex" "$OUTPUT/helper-classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/helper.jar" -C "$OUTPUT/helper-dex" classes.dex
echo "Built $OUTPUT/fixture.apk and helper.jar; run scripts/test-phone-text-input.py explicitly for device acceptance."
echo "Foreground draft acceptance: scripts/test-phone-foreground-draft.py --serial DEVICE_SERIAL (explicit device operation)."
