#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
source scripts/android-env.sh
fixture_out=system/desktop-widgets/out/test-fixture
mkdir -p "$fixture_out/classes" "$fixture_out/dex"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$fixture_out/classes" system/desktop-widgets/tests/MusicFixture.java
"$JDK/bin/jar" --create --file "$fixture_out/classes.jar" -C "$fixture_out/classes" .
"$TOOLS/d8" --min-api 31 --lib "$ANDROID_JAR" --output "$fixture_out/dex" "$fixture_out/classes.jar"
"$TOOLS/aapt2" link -o "$fixture_out/unsigned.apk" -I "$ANDROID_JAR" --manifest system/desktop-widgets/tests/AndroidManifest.xml
(cd "$fixture_out/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$fixture_out/unsigned.apk" "$fixture_out/aligned.apk"
"$TOOLS/apksigner" sign --key out/development-signing/platform.pk8 --cert out/development-signing/platform.x509.pem --out "$fixture_out/fixture.apk" "$fixture_out/aligned.apk"
