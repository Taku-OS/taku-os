#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
SIGNING=../../out/development-signing
python3 ../../scripts/aosp-test-keys.py "$SIGNING"
for component in clock weather; do
  directory="out/$component"
  mkdir -p "$directory/classes" "$directory/dex" "$directory/generated"
  "$TOOLS/aapt2" compile --dir shared-res -o "$directory/shared-resources.zip"
  "$TOOLS/aapt2" compile --dir "$component/res" -o "$directory/resources.zip"
  "$TOOLS/aapt2" link -o "$directory/resources.apk" -I "$ANDROID_JAR" --manifest "$component/AndroidManifest.xml" --java "$directory/generated" "$directory/shared-resources.zip" "$directory/resources.zip"
  sources=()
  while IFS= read -r -d '' src; do sources+=("$src"); done < <(find "$component/src" shared "$directory/generated" -name '*.java' -print0)
  "$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$directory/classes" "${sources[@]}"
  "$JDK/bin/jar" --create --file "$directory/classes.jar" -C "$directory/classes" .
  JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 31 --lib "$ANDROID_JAR" --output "$directory/dex" "$directory/classes.jar"
  cp "$directory/resources.apk" "$directory/unsigned.apk"
  (cd "$directory/dex" && zip -q ../unsigned.apk classes.dex)
  "$TOOLS/zipalign" -f 4 "$directory/unsigned.apk" "$directory/aligned.apk"
  name=TakuClock; if [ "$component" = weather ]; then name=TakuWeather; fi
  JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" --out "out/$name.apk" "$directory/aligned.apk"
  JAVA_HOME="$JDK" "$TOOLS/apksigner" verify "out/$name.apk"
done
