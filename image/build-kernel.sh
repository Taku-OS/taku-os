#!/bin/bash
# Invoked under the parent builder's exclusive source-volume lock.
set -euo pipefail
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
./build_caimito.sh --jobs=4 --local_ram_resources=24576
python3 /taku/image/install-kernel.py "$kernel_root/out/caimito/dist" \
    /src/device/google/caimito-kernels/6.1 /results
# Release the persistent kernel JVM before Soong's memory-heavy graph analysis.
# The upstream wrapper selects the same output root; shutdown retains its cache.
./tools/bazel shutdown
