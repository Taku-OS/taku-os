#!/bin/bash
set -euo pipefail
[ "$#" -le 1 ] || { echo 'Usage: build.sh [installed-or-ROM-notebook.apk]' >&2; exit 2; }
base_apk="${1:-}"
case "$base_apk" in ""|/*) ;; *) base_apk="$PWD/$base_apk" ;; esac
signing="${TAKU_NOTEBOOK_SIGNING_DIR:-}"
case "$signing" in ""|/*) ;; *) signing="$PWD/$signing" ;; esac
cd "$(dirname "$0")"
source ../../scripts/android-env.sh
if [ -z "$signing" ]; then
    signing=../../out/development-signing
    python3 ../../scripts/aosp-test-keys.py "$signing"
fi
mkdir -p out
work=$(mktemp -d out/.notebook-build.XXXXXXXX)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/classes" "$work/dex"
"$TOOLS/aapt2" compile --dir res -o "$work/resources.zip"
"$TOOLS/aapt2" link -o "$work/unsigned.apk" -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
    --java "$work/generated" "$work/resources.zip"
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR" -d "$work/classes" src/org/agentos/notebook/*.java \
    ../ai/client/org/agentos/ai/AiClient.java ../ai/client/org/agentos/ai/NotebookSocialSource.java \
    "$work/generated/org/agentos/notebook/R.java"
"$JDK/bin/jar" --create --file "$work/classes.jar" -C "$work/classes" .
"$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output "$work/dex" "$work/classes.jar"
(cd "$work/dex" && zip -q ../unsigned.apk classes.dex)
"$TOOLS/zipalign" -f 4 "$work/unsigned.apk" "$work/aligned.apk"
apk="$work/TakuNotebook.apk"
"$TOOLS/apksigner" sign --key "$signing/platform.pk8" --cert "$signing/platform.x509.pem" \
    --out "$apk" "$work/aligned.apk"
"$TOOLS/apksigner" verify "$apk"

if [ -n "$base_apk" ]; then
    version_code() {
        "$TOOLS/aapt2" dump badging "$1" | sed -n "s/^package:.* versionCode='\([0-9][0-9]*\)'.*/\1/p"
    }
    signer_digest() {
        "$TOOLS/apksigner" verify --print-certs "$1" \
            | awk -F ': ' '/^(Signer #[0-9]+|V3\.[01] Signer:) certificate SHA-256 digest: / {print $NF}'
    }
    if [ "$("$TOOLS/aapt2" dump packagename "$base_apk")" != "$("$TOOLS/aapt2" dump packagename "$apk")" ]; then
        echo 'Upgrade APK package name must match the base APK package name.' >&2
        exit 1
    fi
    base_version=$(version_code "$base_apk")
    version=$(version_code "$apk")
    if [ -z "$base_version" ] || [ -z "$version" ] || [ "$version" -le "$base_version" ]; then
        echo 'Upgrade versionCode must be greater than the base APK versionCode.' >&2
        exit 1
    fi
    base_signer=$(signer_digest "$base_apk")
    signer=$(signer_digest "$apk")
    if [[ ! "$signer" =~ ^[0-9a-f]{64}$ ]] || [ "$signer" != "$base_signer" ]; then
        echo 'Upgrade signer must match the base APK signer.' >&2
        exit 1
    fi
fi

mv -f "$apk" out/TakuNotebook.apk
