#!/usr/bin/env python3
"""Prepare pinned SDK and separate model pack, reusing the old development cache when present."""
from pathlib import Path
import argparse
import hashlib,json,shutil,tarfile,urllib.request,zipfile
root=Path(__file__).resolve().parents[1]
repo=root.parents[1]
args=argparse.ArgumentParser();args.add_argument('--with-tts',action='store_true');options=args.parse_args()
lock=json.loads((root/'models/dependencies.lock.json').read_text())
if not options.with_tts:lock.pop('tts',None)
cache=root/'out/downloads';cache.mkdir(parents=True,exist_ok=True)
pack=root/'out/model-pack';pack.mkdir(parents=True,exist_ok=True)
for role,entry in lock.items():
    path=cache/entry['file']
    old=repo/'system/call-translation/downloads/local-speech'/entry['file']
    if not path.exists():
        if old.is_file(): shutil.copyfile(old,path)
        else:
            temp=path.with_suffix('.partial')
            with urllib.request.urlopen(entry['url'],timeout=60) as source,temp.open('wb') as output:
                shutil.copyfileobj(source,output)
            temp.replace(path)
    with path.open('rb') as data: actual=hashlib.file_digest(data,'sha256').hexdigest()
    if actual!=entry['sha256']: raise SystemExit('Dependency hash mismatch: '+role)
    if role=='sdk':
        with zipfile.ZipFile(path) as archive:
            sdk=root/'out/sdk';sdk.mkdir(exist_ok=True)
            (sdk/'classes.jar').write_bytes(archive.read('classes.jar'))
            for name in archive.namelist():
                if name.startswith('jni/arm64-v8a/') and name.endswith('.so'):
                    target=sdk/'lib/arm64-v8a'/Path(name).name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.read(name))
    elif entry['file'].endswith('.onnx'):
        # A single-file model (Silero VAD) is copied as-is; its hash was verified above.
        directory=pack/role;directory.mkdir(exist_ok=True)
        target=directory/entry['file']
        if not target.exists() or target.read_bytes()!=path.read_bytes():shutil.copyfile(path,target)
    else:
        directory=pack/role
        if not (directory/'tokens.txt').exists():
            directory.mkdir(exist_ok=True)
            with tarfile.open(path,'r:bz2') as archive:
                for member in archive:
                    parts=Path(member.name).parts[1:]
                    if not member.isfile() or not parts or '..' in parts:continue
                    if role!='tts' and not (len(parts)==1 and (parts[0].endswith('.int8.onnx') or parts[0] in ('tokens.txt','LICENSE','README.md','bpe.vocab'))):continue
                    target=directory.joinpath(*parts)
                    target.parent.mkdir(parents=True,exist_ok=True)
                    target.write_bytes(archive.extractfile(member).read())
files=[]
for path in sorted(pack.rglob('*')):
    if path.is_file() and path.name!='manifest.json':
        with path.open('rb') as data: digest=hashlib.file_digest(data,'sha256').hexdigest()
        files.append({'path':str(path.relative_to(pack)),'size':path.stat().st_size,'sha256':digest})
(pack/'manifest.json').write_text(json.dumps({'version':1,'files':files},indent=2)+'\n')
print('Verified SDK and separate speech pack:',sum(x['size'] for x in files),'bytes')

notices=root/'out/assets/licenses'
notices.mkdir(parents=True,exist_ok=True)
for entry in json.loads((root/'models/notices.lock.json').read_text()):
    path=notices/entry['file']
    old=repo/'system/call-translation/downloads/assets/licenses/local-speech'/entry['file']
    if not path.exists():
        if old.is_file(): shutil.copyfile(old,path)
        else: path.write_bytes(urllib.request.urlopen(entry['url'],timeout=30).read())
    if hashlib.sha256(path.read_bytes()).hexdigest()!=entry['sha256']:raise SystemExit('Notice hash mismatch')
