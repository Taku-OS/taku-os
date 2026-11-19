#!/bin/bash
set -euo pipefail
cd "$(python3 -c 'import os,sys;print(os.path.dirname(os.path.realpath(sys.argv[1])))' "$0")/../.."
source scripts/android-env.sh
OUTPUT=out/jev-magpie-device-tests
mkdir -p "$OUTPUT/fixture/classes" "$OUTPUT/fixture/dex" "$OUTPUT/classes" "$OUTPUT/dex"
SIGNING=out/development-signing
python3 scripts/aosp-test-keys.py "$SIGNING"
"$TOOLS/aapt2" link -o "$OUTPUT/fixture/resources.apk" -I "$ANDROID_JAR" \
 --manifest system/assistant/tests/jev-fixture/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$OUTPUT/fixture/classes" \
 system/assistant/tests/jev-fixture/ChatFixtureActivity.java
"$JDK/bin/jar" --create --file "$OUTPUT/fixture/classes.jar" -C "$OUTPUT/fixture/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$OUTPUT/fixture/dex" "$OUTPUT/fixture/classes.jar"
cp "$OUTPUT/fixture/resources.apk" "$OUTPUT/fixture/unsigned.apk"
(cd "$OUTPUT/fixture/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$OUTPUT/fixture/unsigned.apk" "$OUTPUT/fixture/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" \
 --out "$OUTPUT/fixture.apk" "$OUTPUT/fixture/aligned.apk"
"$JDK/bin/javac" --release 17 \
 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" \
 -d "$OUTPUT/classes" system/assistant/tests/org/agentos/tests/NativeSocketProbe.java \
 system/assistant/tests/org/agentos/tests/AgentInterfaceTest.java \
 system/assistant/tests/org/agentos/tests/JevMagpieUiTest.java \
 system/assistant/tests/org/agentos/tests/JevMagpieLiveUiTest.java \
 system/assistant/tests/org/agentos/tests/ModelConnectionUiTest.java \
 system/assistant/tests/org/agentos/tests/UnicodePasteTest.java
"$JDK/bin/jar" --create --file "$OUTPUT/classes.jar" -C "$OUTPUT/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" \
 --lib "$SDK/platforms/android-36/uiautomator.jar" --lib "$SDK/platforms/android-36/optional/android.test.base.jar" \
 --output "$OUTPUT/dex" "$OUTPUT/classes.jar"
"$JDK/bin/jar" --create --file "$OUTPUT/test.jar" -C "$OUTPUT/dex" classes.dex
echo "Built synthetic fixture APK and Android UI test JAR in $OUTPUT"
