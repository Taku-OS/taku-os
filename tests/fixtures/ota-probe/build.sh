#!/bin/bash
# Build offline with the repository toolchain and existing development signing material.
set -euo pipefail
fixture=$(cd "$(dirname "$0")" && pwd)
repo_root=$(cd "$fixture/../../.." && pwd)
source "$repo_root/scripts/android-env.sh"
output=${1:?Usage: build.sh /absolute/path/ota-probe.apk}
case "$output" in /*) ;; *) echo 'Output path must be absolute.' >&2; exit 1 ;; esac
[ ! -e "$output" ] || { echo 'Refusing to overwrite an existing probe APK.' >&2; exit 1; }
if [ -n "${PROBE_TEST_SIGNING_DIR:-}" ] && [ -n "${PROBE_KEYSTORE:-}" ]; then
  echo 'Choose PROBE_TEST_SIGNING_DIR or PROBE_KEYSTORE, not both.' >&2
  exit 1
fi
if [ -n "${PROBE_KEYSTORE:-}" ]; then
  : "${PROBE_KEYSTORE_PASSWORD:?Set the existing keystore password in the environment}"
  test -f "$PROBE_KEYSTORE"
  signing_args=(--ks "$PROBE_KEYSTORE" --ks-key-alias "${PROBE_KEY_ALIAS:-androiddebugkey}"
    --ks-pass env:PROBE_KEYSTORE_PASSWORD --key-pass env:PROBE_KEYSTORE_PASSWORD)
else
  signing_dir=${PROBE_TEST_SIGNING_DIR:-"$repo_root/out/development-signing"}
  # Require both files first: the shared verifier must never download missing inputs here.
  [ -f "$signing_dir/platform.pk8" ] && [ -f "$signing_dir/platform.x509.pem" ] || {
    echo 'Existing public AOSP test key and certificate are required; no keys are created or downloaded.' >&2
    exit 1
  }
  python3 "$repo_root/scripts/aosp-test-keys.py" "$signing_dir"
  signing_args=(--key "$signing_dir/platform.pk8" --cert "$signing_dir/platform.x509.pem")
fi
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
mkdir -p "$temporary/classes" "$temporary/dex"
"$JDK/bin/javac" -source 8 -target 8 -classpath "$ANDROID_JAR" -d "$temporary/classes" "$fixture/ProbeActivity.java"
"$JDK/bin/jar" cf "$temporary/probe.jar" -C "$temporary/classes" .
"$TOOLS/d8" --lib "$ANDROID_JAR" --min-api 36 --output "$temporary/dex" "$temporary/probe.jar"
"$TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest "$fixture/AndroidManifest.xml" -o "$temporary/unsigned.apk"
(cd "$temporary/dex" && zip -q "$temporary/unsigned.apk" classes.dex)
"$TOOLS/zipalign" -f 4 "$temporary/unsigned.apk" "$temporary/aligned.apk"
"$TOOLS/apksigner" sign "${signing_args[@]}" --v4-signing-enabled false \
  --out "$temporary/signed.apk" "$temporary/aligned.apk"
"$TOOLS/apksigner" verify --verbose --print-certs "$temporary/signed.apk"
mkdir -p "$(dirname "$output")"
python3 - "$temporary/signed.apk" "$output" <<'PY'
import pathlib, shutil, sys
with pathlib.Path(sys.argv[1]).open('rb') as source, pathlib.Path(sys.argv[2]).open('xb') as target:
    shutil.copyfileobj(source, target)
PY
shasum -a 256 "$output"
