#!/bin/bash
# Inside an isolated pinned platform checkout; /taku is a sealed input snapshot.
set -eo pipefail
cd /src
export GIT_OPTIONAL_LOCKS=0
test "$(git -C frameworks/base rev-parse HEAD)" = 7f6de40b6429462d47ba9ba351b99943269c8067
test -f /taku/emulator-input.json
include_google_apps="${TAKU_INCLUDE_GOOGLE_APPS:-true}"
if [ "$include_google_apps" != "true" ] && [ "$include_google_apps" != "false" ]; then
    echo "Invalid TAKU_INCLUDE_GOOGLE_APPS=$include_google_apps (expected true or false)" >&2
    exit 1
fi
if [ "$include_google_apps" = "true" ]; then
    # Required read-only mounts, verified against this snapshot before product discovery.
    python3 /taku/image/google_services.py --source /src/vendor/gapps
    python3 /taku/image/google_apps.py --source /src/vendor/taku-google-apps
fi
python3 - <<'PY'
import hashlib,json
from pathlib import Path
root=Path('/taku')
manifest=json.loads((root/'emulator-input.json').read_text())
assert manifest['schema']=='taku.emulator-input/1'
for record in manifest['files']:
    p=root/record['path']
    assert p.is_file() and not p.is_symlink(), str(p)
    assert hashlib.sha256(p.read_bytes()).hexdigest()==record['sha256'], str(p)
PY
mkdir -p vendor/taku device/taku/taku_emu64a
rsync -a --checksum --no-times --delete /taku/ vendor/taku/
printf 'TAKU_INCLUDE_GOOGLE_APPS := %s\n' "$include_google_apps" > vendor/taku/image/emulator/build-options.mk
# Product discovery already finds vendor/taku/image/emulator/AndroidProducts.mk.
# Only the board needs a device directory; a second product registration is invalid.
cp /taku/image/emulator/BoardConfig.mk device/taku/taku_emu64a/
python3 /taku/image/emulator/prepare-audio.py --source-root /src
python3 /taku/image/apply-home-policy.py --source-root /src --results-dir /results
python3 /taku/image/apply-launcher-patches.py --source-root /src --results-dir /results
python3 /taku/image/apply-recents-patches.py --source-root /src --results-dir /results
if [ -f /taku/image/apply-display-audio-patch.py ]; then
    python3 /taku/image/apply-display-audio-patch.py --source-root /src --results-dir /results
fi
python3 /taku/image/apply-island-patches.py --source-root /src --results-dir /results
python3 /taku/image/apply-island-patches.py --source-root /src --results-dir /results --verify-only
python3 /taku/image/apply-lockscreen-patches.py --source-root /src --results-dir /results
python3 /taku/image/apply-lockscreen-patches.py --source-root /src --results-dir /results --verify-only
export SKIP_KERNEL_BUILD=true WITH_AVB=true
export BUILD_USERNAME=taku BUILD_HOSTNAME=taku-emulator-builder
export BUILD_NUMBER="${TAKU_EMULATOR_BUILD_NUMBER:?Set a unique emulator build number}"
export SOONG_NINJA=ninja TAKU_R8_SERIAL_GC=1 GOMEMLIMIT=80GiB GOMAXPROCS=24 GODEBUG=asyncpreemptoff=1
unset SOONG_UI_NINJA_ARGS
source build/envsetup.sh
source vendor/lineage/vars/aosp_target_release
# Avoid lunch's roomservice fallback on a local product-configuration error.
TARGET_PRODUCT=lineage_taku_emu_arm64 TARGET_RELEASE="$aosp_target_release" TARGET_BUILD_VARIANT=userdebug \
    build/soong/soong_ui.bash --dumpvar-mode TARGET_PRODUCT
lunch "lineage_taku_emu_arm64-${aosp_target_release}-userdebug"
m -j"${TAKU_EMULATOR_JOBS:-24}" droid apksigner
product=out/target/product/taku_emu64a
python3 /taku/image/emulator/prepare-audio.py --product "$product" > /results/emulator-audio.json
python3 /taku/image/verify-island-artifacts.py --product "$product" \
    --aapt2 out/host/linux-x86/bin/aapt2 --output /results/island-artifacts.json
python3 /taku/system/ai/verify-rom.py --assistant-pair \
    "$product/system_ext/priv-app/TakuAssistant/TakuAssistant.apk" "$product/system_ext/app/TakuAgent/TakuAgent.apk" \
    --aapt2 out/host/linux-x86/bin/aapt2 --output /results/assistant-pair.json
extra_tar_files=(assistant-pair.json emulator-audio.json)
if [ "$include_google_apps" = "true" ]; then
    python3 /taku/image/emulator/verify-default-apps.py --product "$product" \
        --tools out/host/linux-x86/bin --output /results/default-apps.json
    extra_tar_files+=(default-apps.json)
fi
mkdir -p /results/images
for name in system-qemu.img vendor-qemu.img ramdisk-qemu.img kernel-ranchu userdata.img encryptionkey.img \
    advancedFeatures.ini kernel_cmdline.txt; do
    test -f "$product/$name"
    cp --sparse=always "$product/$name" /results/images/
done
cp "$product/system/build.prop" /results/images/build.prop
cp "$product/system_ext/priv-app/SystemUI/SystemUI.apk" /results/SystemUI.apk
cp "$product/system_ext/app/TakuAgent/TakuAgent.apk" /results/TakuAgent.apk
cp /taku/emulator-input.json /results/
python3 - "$include_google_apps" <<'PY'
import hashlib,json,sys
from pathlib import Path
root=Path('/results/images')
files=[{'path':p.name,'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()}
       for p in sorted(root.iterdir()) if p.is_file()]
distribution='internal-test-only' if sys.argv[1]=='true' else 'open-source'
Path('/results/images.json').write_text(json.dumps({'schema':'taku.emulator-images/1',
    'product':'lineage_taku_emu_arm64','abi':'arm64-v8a','distribution':distribution,
    'files':files,'deviceVerified':False},indent=2)+'\n')
PY
tar -I 'gzip -1' -cf /results/images.tar.gz -C /results images images.json island-artifacts.json "${extra_tar_files[@]}" emulator-input.json
echo TAKU_EMULATOR_BUILD_COMPLETE
