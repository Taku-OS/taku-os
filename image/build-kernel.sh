#!/bin/bash
# Invoked under the parent builder's exclusive source-volume lock.
set -euo pipefail
if [[ "${1:-}" == "--kernel-cache-dir" && $# == 2 && -n "$2" ]]; then
    export TAKU_KERNEL_CACHE_DIR="$2"
elif (( $# )); then
    echo "usage: $0 [--kernel-cache-dir DIR]" >&2
    exit 2
fi
if [[ -n "${TAKU_KERNEL_CACHE_DIR:-}" && "$TAKU_KERNEL_CACHE_DIR" != /* ]]; then
    export TAKU_KERNEL_CACHE_DIR="$PWD/$TAKU_KERNEL_CACHE_DIR"
fi
kernel_root=/src/out-kernel/google/gs-6.1
mkdir -p "$kernel_root"
cd "$kernel_root"
touch .out-dir
manifest_commit=$(python3 - <<'PY'
import hashlib,json,pathlib,re
root=pathlib.Path('/taku/image')
lock=json.loads((root/'kernel-source-lock.json').read_text())
if not re.fullmatch('[0-9a-f]{40}',lock['manifestCommit']):raise SystemExit('Kernel manifest commit must be pinned')
if hashlib.sha256((root/'manifests/kernel.xml').read_bytes()).hexdigest()!=lock['kernelManifestSha256']:
    raise SystemExit('Kernel manifest does not match source lock')
print(lock['manifestCommit'])
PY
)
python3 /taku/image/init-source-manifest.py --source-root "$kernel_root" \
    --url https://github.com/LineageOS/android_kernel_google_gs-6.1_manifest.git \
    --commit "$manifest_commit" --manifest /taku/image/manifests/kernel.xml
python3 /taku/image/prepare-reference-cache.py --source-root "$kernel_root" \
    --reference-root /previous/out-kernel/google/gs-6.1 --empty-reference-parent /previous
# A previous interrupted sync can leave a lock in this generated checkout.
# Preserve it for diagnosis; this volume has no other writer.
python3 - <<'PY'
import datetime,pathlib,shutil,subprocess,xml.etree.ElementTree as E
root=pathlib.Path('.repo')
backup=pathlib.Path('/src/metadata-repair')/('kernel-'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ'))
for area in ('projects','project-objects'):
    for p in (root/area).rglob('*.lock'):
        if p.is_file():
            dest=backup/p;dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.move(str(p),str(dest))
# Interrupted first fetches can leave initialized Git metadata with no objects.
# Repo interprets these as existing full-history repositories and drops depth=1.
# Seed only their locked commit so a retry cannot fetch the entire kernel history.
for p in E.parse('.repo/manifests/kernel.xml').getroot().findall('project'):
    gitdir=root/'projects'/(p.attrib['path']+'.git')
    if not (gitdir/'config').exists():continue
    args=['git','--git-dir='+str(gitdir)]
    if subprocess.run(args+['cat-file','-e',p.attrib['revision']+'^{commit}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
        print('Seeding locked kernel commit:',p.attrib['path'],flush=True)
        subprocess.run(args+['fetch','--depth=1','--no-tags',p.attrib.get('remote','github'),p.attrib['revision']],check=True,timeout=300)
PY
repo sync --detach -c -j4 --optimized-fetch --no-clone-bundle
repo manifest -r -o /results/resolved-kernel-manifest.xml
# Upstream builds the device modules against its fixed signed GKI build number.
# Do not replace that kernel ABI with an arbitrary locally built GKI.
if [[ -n "${TAKU_KERNEL_CACHE_DIR:-}" ]]; then
    # Repo sync preserves local modifications. Such inputs cannot use a key
    # based on the resolved manifest's committed source and toolchain versions.
    if ! repo forall -e -c 'status=$(git status --porcelain) && test -z "$status"'; then
        echo 'Kernel cache disabled for a modified kernel checkout' >&2
        unset TAKU_KERNEL_CACHE_DIR
    fi
fi
python3 /taku/image/kernel_cache.py --product lineage_tokay --target caimito \
    "$kernel_root/out/caimito/dist" /src/device/google/caimito-kernels/6.1 /results \
    ./build_caimito.sh --jobs=4 --local_ram_resources=24576
