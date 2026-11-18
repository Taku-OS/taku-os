#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../.."
source scripts/android-env.sh
out=out/screen-background-tests
mkdir -p "$out/fixture/classes" "$out/fixture/dex" "$out/classes" "$out/dex"
"$TOOLS/aapt2" link -o "$out/fixture/resources.apk" -I "$ANDROID_JAR" --manifest system/assistant/tests/screen-fixture/AndroidManifest.xml
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$out/fixture/classes" system/assistant/tests/screen-fixture/*.java
"$JDK/bin/jar" --create --file "$out/fixture/classes.jar" -C "$out/fixture/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$out/fixture/dex" "$out/fixture/classes.jar"
cp "$out/fixture/resources.apk" "$out/fixture/unsigned.apk"
(cd "$out/fixture/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$out/fixture/unsigned.apk" "$out/fixture/aligned.apk"
JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$out/fixture.apk" "$out/fixture/aligned.apk"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d "$out/classes" \
 system/assistant/tests/org/agentos/tests/NativeSocketProbe.java system/assistant/tests/org/agentos/tests/AgentInterfaceTest.java system/assistant/tests/org/agentos/tests/BackgroundScreenUiTest.java
"$JDK/bin/jar" --create --file "$out/classes.jar" -C "$out/classes" .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --lib "$SDK/platforms/android-36/uiautomator.jar" --lib "$SDK/platforms/android-36/optional/android.test.base.jar" --output "$out/dex" "$out/classes.jar"
"$JDK/bin/jar" --create --file "$out/test.jar" -C "$out/dex" classes.dex
