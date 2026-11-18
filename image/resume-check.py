#!/usr/bin/env python3
"""Resume only an unchanged integrated source/kernel checkpoint."""
import datetime,hashlib,json,pathlib
old_root=pathlib.Path('/src/vendor/taku');new_root=pathlib.Path('/taku')
old=json.loads((old_root/'input-manifest.json').read_text())
new=json.loads((new_root/'input-manifest.json').read_text())
previous={p['path']:p['sha256'] for p in old['files']}
current={p['path']:p['sha256'] for p in new['files']}
allowed={'image/build.sh','image/README.md','image/resume-check.py','image/verify-artifacts.py'}
def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(8*1024*1024),b''):h.update(chunk)
    return h.hexdigest()
for name in set(previous)|set(current):
    if name in allowed:continue
    if previous.get(name)!=current.get(name):raise SystemExit('Resume input changed: '+name)
    if digest(old_root/name)!=previous[name]:raise SystemExit('Integrated input changed: '+name)
for name in ['resolved-manifest.xml','resolved-kernel-manifest.xml','kernel-artifacts.json']:
    if not (pathlib.Path('/results')/name).is_file():raise SystemExit('Missing build checkpoint: '+name)
if not pathlib.Path('/src/out/combined-lineage_tokay.ninja').is_file():raise SystemExit('Missing tokay build graph')
kernel=json.loads(pathlib.Path('/results/kernel-artifacts.json').read_text())
for item in kernel['artifacts']:
    if item['name']=='boot.img' or item['name'].endswith('.ko'):
        p=pathlib.Path('/src/device/google/caimito-kernels/6.1')/item['name']
        if digest(p)!=item['sha256']:raise SystemExit('Kernel checkpoint changed: '+item['name'])
pathlib.Path('/results/resume-check.json').write_text(json.dumps({
    'checkedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'integratedSnapshotDigest':old['digest'],'controllerSnapshotDigest':new['digest'],
    'integratedInputsUnchanged':True,'kernelCheckpointUnchanged':True,
    'compileCachePreserved':True,'sourceSyncedAgain':False,'kernelRebuilt':False},indent=2)+'\n')
print('Existing integrated inputs and kernel checkpoint match; resuming without sync, kernel rebuild or cleaning.')
