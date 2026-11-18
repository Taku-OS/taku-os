#!/bin/bash
# Runs only inside the dedicated amd64 Linux builder. No device operations.
set -euo pipefail
cd /src
exec 9>.taku-builder.lock
flock -n 9 || { echo 'Another builder owns this generated checkout' >&2; exit 1; }
phase() {
    python3 - "$1" <<'PY'
import datetime,json,pathlib,sys
p=pathlib.Path('/results/status.json')
state={'phase':sys.argv[1],'updatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'romBuilt':False,'physicalDeviceVerified':False}
tmp=p.with_suffix('.tmp');tmp.write_text(json.dumps(state,indent=2)+'\n');tmp.replace(p)
PY
}
fifo_guard=
finish() {
    code=$?
    if [ -n "$fifo_guard" ]; then kill "$fifo_guard" 2>/dev/null || true; wait "$fifo_guard" 2>/dev/null || true; fi
    if [ "$code" -ne 0 ]; then phase "failed-exit-$code"; fi
}
trap finish EXIT
export GIT_TERMINAL_PROMPT=0
git config --global user.name 'Taku Image Builder'
git config --global user.email 'taku-image@example.invalid'
git config --global credential.helper ''
git config --global http.version HTTP/1.1
git config --global http.lowSpeedLimit 1024
git config --global http.lowSpeedTime 60

integrate_taku() {
    phase integrate
    python3 /taku/image/google_services.py --source /src/vendor/gapps
    if [ -f /taku/image/google-apps.lock.json ]; then
        python3 /taku/image/google_apps.py --source /src/vendor/taku-google-apps
    fi
    python3 /taku/system/inputmethod/prepare-rom.py --verify-only
    mkdir -p vendor/taku
    rsync -a --checksum --no-times --delete /taku/ vendor/taku/
    python3 - <<'PY'
from pathlib import Path
p=Path('device/google/caimito/lineage_tokay.mk')
line='$(call inherit-product, vendor/taku/image/taku.mk)'
s=p.read_text()
if line not in s:s+='\n# Taku development runtime integration\n'+line+'\n'
# This isolated builder targets the user's personal Pixel, not a commercial ROM.
personal='TAKU_PREINSTALL_PERSONAL_PROXY := true'
if personal not in s:s=s.replace(line,personal+'\n'+line)
if p.read_text()!=s:p.write_text(s)
PY
    python3 /taku/image/apply-product-branding.py --source-root /src --results-dir /results
}

if [[ "${1:-}" = "--incremental" || "${1:-}" = "--updater-only" || "${1:-}" = "--google-apps-only" ]]; then
    phase incremental-check
    python3 /taku/image/check-incremental.py
    integrate_taku
elif [[ "${1:-}" = "--resume" || "${1:-}" = "--verify-only" ]]; then
    phase resume-check
    python3 /taku/image/resume-check.py
else
manifest_commit=$(python3 - <<'PY'
import hashlib,json,pathlib,re
root=pathlib.Path('/taku/image')
lock=json.loads((root/'source-lock.json').read_text())
if not re.fullmatch('[0-9a-f]{40}',lock['manifestCommit']):raise SystemExit('Manifest commit must be pinned')
if hashlib.sha256((root/'manifests/platform.xml').read_bytes()).hexdigest()!=lock['platformManifestSha256']:
    raise SystemExit('Platform manifest does not match source lock')
if hashlib.sha256((root/'manifests/devices.xml').read_bytes()).hexdigest()!=lock['devicesManifestSha256']:
    raise SystemExit('Device manifest does not match source lock')
print(lock['manifestCommit'])
PY
)
phase initialize
python3 /taku/image/init-source-manifest.py --source-root /src \
    --url https://github.com/LineageOS/android.git --commit "$manifest_commit" \
    --manifest /taku/image/manifests/platform.xml
mkdir -p .repo/local_manifests
cp /taku/image/manifests/devices.xml .repo/local_manifests/taku-devices.xml
phase prepare-cache
python3 /taku/image/prepare-reference-cache.py --source-root /src --reference-root /previous
phase source-sync
# Let all in-flight checkouts finish on an error; fail-fast can leave Git locks.
repo sync --detach -c -j4 --optimized-fetch --no-clone-bundle
repo manifest -r -o /results/resolved-manifest.xml

phase kernel-build
bash /taku/image/build-kernel.sh

integrate_taku
fi

export TAKU_R8_SERIAL_GC=$(python3 -c "import json; print(int(json.load(open('/taku/image/build-config.json'))['r8SerialGc']))")
# The workaround carries these into Soong's isolated bootstrap environment;
# shell exports alone do not reach soong_build.
export GOMEMLIMIT=$(python3 -c "import json; print(str(json.load(open('/taku/image/build-config.json'))['soongGoMemoryLimitGiB'])+'GiB')")
export GOMAXPROCS=$(python3 -c "import json; print(json.load(open('/taku/image/build-config.json'))['soongGoMaxProcs'])")
# Pass this exact host compatibility setting through both isolated Go builders
# and the main Ninja filter. Arbitrary GODEBUG settings remain filtered.
export GODEBUG=asyncpreemptoff=1
python3 /taku/image/apply-build-workarounds.py --source-root /src --results-dir /results

verify_artifacts() {
    phase verify-artifacts
    python3 /taku/image/verify-artifacts.py out/target/product/tokay /results
}
if [[ "${1:-}" = "--verify-only" ]]; then
    verify_artifacts
    exit 0
fi

export BUILD_USERNAME=taku
export BUILD_HOSTNAME=pixel9-builder
export BUILD_NUMBER=$(python3 -c "import json; print(json.load(open('/results/run-input.json'))['buildNumber'])")
# The pinned Google BoardConfig uses --flags 3 unless WITH_AVB is enabled.
# Keep the bootloader's real vbmeta measurement for source-bound OTA acceptance.
# userdebug/root capabilities remain configured independently by the product.
export WITH_AVB=true
jobs=$(python3 -c "import json; print(json.load(open('/taku/image/build-config.json'))['ninjaJobs'])")
# Use Soong's supported C++ Ninja executor for both bootstrap and compilation.
# The long-running Go Siso process trapped in Rosetta's signal handling, and
# The reviewed workaround retains only the fixed Go compatibility setting.
export SOONG_NINJA=ninja
# Keep concurrency in m -j; inherited executor-specific flags must not leak.
unset SOONG_UI_NINJA_ARGS
# Kernel distribution was built and checked above from the pinned manifest.
# Avoid Lineage lunch fetching a second, floating kernel source tree.
export SKIP_KERNEL_BUILD=true
set +u
source build/envsetup.sh
source vendor/lineage/vars/aosp_target_release
lunch "lineage_tokay-${aosp_target_release}-userdebug"
[ "$TARGET_BUILD_VARIANT" = userdebug ] || { echo 'Expected userdebug target' >&2; exit 1; }
phase compile
# Android envsetup functions intentionally use optional, unset shell variables.
# Binfmt's Rosetta interpreter can be outside the container mount namespace.
# The FIFO handoff is also valid on native Linux; do not infer it from a host path.
python3 /taku/image/ninja-fifo-guard.py "$PWD/out/.ninja_fifo" "$$" > /results/ninja-fifo-guard.log 2>&1 &
fifo_guard=$!
if [[ "${1:-}" = "--updater-only" ]]; then
    m -j"$jobs" Updater
    python3 /taku/image/apply-product-branding.py --source-root /src --results-dir /results \
        --apk /src/out/target/product/tokay/system_ext/priv-app/Updater/Updater.apk
    phase updater-apk-verified
    exit 0
fi
if [[ "${1:-}" = "--google-apps-only" ]]; then
    m -j"$jobs" $(python3 /taku/image/google_apps.py --modules)
    python3 /taku/image/google_apps.py --preview-product out/target/product/tokay --results /results
    phase google-apps-preview-verified
    exit 0
fi
m -j"$jobs" target-files-package bacon
set -u

python3 /taku/image/product_branding.py --stage-full-ota out/target/product/tokay --build-number "$BUILD_NUMBER"

verify_artifacts
