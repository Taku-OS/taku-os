#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
demo=false; api_tests=false
for argument in "$@"; do
    case "$argument" in
        --demo) demo=true ;;
        --tests) api_tests=true ;;
        *) echo 'Usage: build.sh [--demo] [--tests]' >&2; exit 2 ;;
    esac
done
source ../../scripts/android-env.sh
mkdir -p out
SIGNING=../../out/development-signing
python3 ../../scripts/aosp-test-keys.py "$SIGNING"
build_apk() {
    local label="$1" manifest="$2"; shift 2
    local directory="out/$label"
    rm -rf "$directory/classes" "$directory/dex" "$directory/generated"
    mkdir -p "$directory/classes" "$directory/dex" "$directory/generated"
    "$TOOLS/aapt2" compile --dir res -o "$directory/resources.zip"
    "$TOOLS/aapt2" link -o "$directory/resources.apk" -I "$ANDROID_JAR" --manifest "$manifest" --java "$directory/generated" "$directory/resources.zip"
    local -a generated=()
    while IFS= read -r -d '' source; do generated+=("$source"); done < <(find "$directory/generated" -name '*.java' -print0)
    "$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$directory/classes" "$@" "${generated[@]}"
    "$JDK/bin/jar" --create --file "$directory/classes.jar" -C "$directory/classes" .
    JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$directory/dex" "$directory/classes.jar"
    cp "$directory/resources.apk" "$directory/unsigned.apk"
    (cd "$directory/dex" && zip -q ../unsigned.apk classes.dex)
    "$TOOLS/zipalign" -f 4 "$directory/unsigned.apk" "$directory/aligned.apk"
    JAVA_HOME="$JDK" "$TOOLS/apksigner" sign --key "$SIGNING/platform.pk8" --cert "$SIGNING/platform.x509.pem" --out "out/$label.apk" "$directory/aligned.apk"
    JAVA_HOME="$JDK" "$TOOLS/apksigner" verify "out/$label.apk"
}
build_apk TakuSurfaces AndroidManifest.xml contract/org/agentos/surfaces/*.java wire/org/agentos/surfaces/*.java src/org/agentos/surfaces/*.java
rm -rf out/client/classes
mkdir -p out/client/classes
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d out/client/classes contract/org/agentos/surfaces/*.java wire/org/agentos/surfaces/*.java client/org/agentos/surfaces/client/*.java
"$JDK/bin/jar" --create --file out/taku-surfaces-client.jar -C out/client/classes .
if $demo; then
    build_apk TakuSurfaceDemo demo/AndroidManifest.xml contract/org/agentos/surfaces/*.java wire/org/agentos/surfaces/*.java client/org/agentos/surfaces/client/*.java demo/src/org/agentos/surfacedemo/*.java
fi
if $api_tests; then
    build_apk TakuSurfaceApiTests tests/AndroidManifest.xml contract/org/agentos/surfaces/*.java wire/org/agentos/surfaces/*.java tests/instrumentation/org/agentos/surfacestests/*.java
    mkdir -p out/consumer-check
    "$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:out/taku-surfaces-client.jar" -d out/consumer-check tests/consumer/*.java
fi
echo "Built in system/surfaces/out; no device was changed."
