#!/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later
set -euo pipefail
cd "$(dirname "$0")/../.."
export TAKU_BUILD_TOOLS="${TAKU_BUILD_TOOLS:-36.1.0}"
source scripts/android-env.sh
abi="${1:-arm64-v8a}"
rom_preinstall="${TAKU_IME_ROM_PREINSTALL:-0}"
case "$rom_preinstall" in 0) rom_flag=false; artifact_suffix=;; 1) rom_flag=true; artifact_suffix=-rom;; *) echo 'TAKU_IME_ROM_PREINSTALL must be 0 or 1' >&2; exit 2;; esac
case "$abi" in arm64-v8a|x86_64) ;; *) echo 'Usage: build.sh [arm64-v8a|x86_64]' >&2; exit 2;; esac
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
python3 - "$SDK" <<'PYCHECK'
import json,pathlib,sys
lock=json.loads(pathlib.Path('system/inputmethod/source-lock.json').read_text())
sdk=pathlib.Path(sys.argv[1]);toolchain=lock['toolchain']
for kind,key in [('ndk','ndk'),('cmake','cmake')]:
 path=sdk/kind/toolchain[key]/'source.properties'
 if not path.is_file():
  raise SystemExit(f'{kind} {toolchain[key]} is not fully installed. See system/inputmethod/README.md.')
PYCHECK
# The SDK CMake can run under Rosetta; explicitly locate Homebrew's host modules.
if [ -z "${ECM_DIR:-}" ] && command -v brew >/dev/null 2>&1; then
  ecm_prefix="$(brew --prefix extra-cmake-modules 2>/dev/null || true)"
  if [ -n "$ecm_prefix" ]; then export ECM_DIR="$ecm_prefix/share/ECM/cmake"; fi
fi
command -v msgfmt >/dev/null 2>&1 || { echo 'Install host gettext (msgfmt); see the input method README.' >&2; exit 1; }
python3 system/inputmethod/prepare.py
repo="$PWD"
source_dir="$repo/out/inputmethod/source"
build_timestamp="${SOURCE_DATE_EPOCH:-$(date +%s)}000"
(
  cd "$source_dir"
  ./gradlew :app:assembleRelease :app:testDebugUnitTest \
    -PbuildABI="$abi" -PbuildVersionName=0.4.0 -PbuildTimestamp="$build_timestamp" \
    -PtakuRomPreinstall="$rom_flag" \
    --max-workers="${TAKU_GRADLE_WORKERS:-4}" --console=plain
)
# AGP may vary the output basename. Read its metadata instead of depending on a glob.
unsigned=$(python3 - "$source_dir/app/build/outputs/apk/release/output-metadata.json" "$abi" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]);m=json.loads(p.read_text())
for e in m['elements']:
 if any(f['filterType']=='ABI' and f['value']==sys.argv[2] for f in e['filters']):
  print(p.parent/e['outputFile']);break
else: raise SystemExit('No APK for requested ABI')
PY
)
keystore="${TAKU_IME_KEYSTORE:-$repo/out/development-signing/taku-inputmethod.jks}"
if [ -z "${TAKU_IME_KEYSTORE:-}" ] && [ ! -f "$keystore" ]; then
  mkdir -p "$(dirname "$keystore")"
  "$JAVA/bin/keytool" -genkeypair -keystore "$keystore" -storepass android -keypass android \
    -alias taku-inputmethod -dname 'CN=Taku Input Method Development' -keyalg RSA -keysize 3072 -validity 10000 >/dev/null 2>&1
fi
export TAKU_IME_STORE_PASSWORD="${TAKU_IME_STORE_PASSWORD:-android}"
apk="$repo/out/inputmethod/TakuInputMethod-$abi$artifact_suffix.apk"
"$TOOLS/zipalign" -P 16 -f 4 "$unsigned" "$repo/out/inputmethod/aligned-$abi.apk"
"$TOOLS/apksigner" sign --ks "$keystore" --ks-key-alias "${TAKU_IME_KEY_ALIAS:-taku-inputmethod}" \
  --ks-pass env:TAKU_IME_STORE_PASSWORD --key-pass env:TAKU_IME_STORE_PASSWORD \
  --out "$apk" "$repo/out/inputmethod/aligned-$abi.apk"
"$TOOLS/apksigner" verify --verbose "$apk"
python3 system/inputmethod/verify-apk.py "$apk"
printf 'Built %s\n' "$apk"
