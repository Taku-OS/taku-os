#!/usr/bin/env python3
"""Validate every declared module before installing generated kernel artifacts."""
import hashlib,json,pathlib,shutil,sys
dist,dest,results=map(pathlib.Path,sys.argv[1:])
from kernel_cache import distribution
try:
    files, required = distribution(dist, dest)
except ValueError as error:
    raise SystemExit(str(error)) from error
records=[]
for name,p in sorted(files.items()):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(8*1024*1024),b''):h.update(chunk)
    records.append({'name':name,'bytes':p.stat().st_size,'sha256':h.hexdigest()})
# Keep checked-in module lists unless the distribution replaces them.
dest.mkdir(parents=True,exist_ok=True)
for name,p in files.items():
    shutil.copy2(p,dest/name)
    (dest/name).chmod((dest/name).stat().st_mode & ~0o111)
record={'sourceLock':json.loads(pathlib.Path(__file__).with_name('kernel-source-lock.json').read_text()),
        'requiredModuleCount':len(required)-1,'artifacts':records,'physicalDeviceVerified':False}
(results/'kernel-artifacts.json').write_text(json.dumps(record,indent=2)+'\n')
print('Validated and installed kernel boot image and all declared modules:',len(required)-1)
