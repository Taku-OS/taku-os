#!/usr/bin/env python3
"""Build a versioned, source-complete development kit from a clean Git commit."""
import argparse
import hashlib
import json
import posixpath
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT=Path(__file__).resolve().parents[2]
SOURCE_PATHS=['tooling/go2-lab','tests/go2-*','scripts/android-env.sh','scripts/aosp-test-keys.py',
              'system/ai/client/org/agentos/ai/AiClient.java','tests/helpers/go2*',
              'tests/helpers/verify-go2*','tests/helpers/verify_go2*','LICENSE','NOTICE','LICENSES']


def git(*args):
    return subprocess.check_output(['git',*args],cwd=ROOT)


def digest(data): return hashlib.sha256(data).hexdigest()


def verify(path):
    with zipfile.ZipFile(path) as archive:
        names=archive.namelist()
        if len(names)!=len(set(names)): raise ValueError('Duplicate ZIP entries')
        prefix=names[0].split('/')[0]+'/'
        files={}
        for name in names:
            parts=PurePosixPath(name).parts
            if not name.startswith(prefix) or name.startswith('/') or '..' in parts or '\\' in name:
                raise ValueError('Invalid archive path')
            files[name[len(prefix):]]=archive.read(name)
        listed={}
        for line in files['SHA256SUMS'].decode().splitlines():
            value,name=line.split('  ',1);listed[name]=value
        actual={name:digest(data) for name,data in files.items() if name!='SHA256SUMS'}
        if listed!=actual: raise ValueError('Archive checksum mismatch')
        manifest=json.loads(files['manifest.json'])
        if manifest['format']!='taku-go2-devkit/v1' or manifest['realAutonomousNavigation'] is not False:
            raise ValueError('Unsupported manifest')
        for required in ['README.md','go2','artifacts/TakuGo2.apk','artifacts/client.jar',
                         'tooling/go2-lab/web/index.html','tooling/go2-lab/bridge.py','LICENSE']:
            if required not in files: raise ValueError('Missing component: '+required)
        return {'files':len(files),'version':manifest['version'],'sourceCommit':manifest['sourceCommit']}


def package(output):
    if git('status','--porcelain','--',*SOURCE_PATHS).strip():
        raise ValueError('Commit Go2 source changes before packaging; refusing an unversioned bundle')
    revision=git('rev-parse','HEAD').decode().strip()
    manifest=json.loads((ROOT/'tooling/go2-lab/distribution/manifest.json').read_text())
    version=manifest['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+-[a-z0-9.-]+',version): raise ValueError('Expected a named development version')
    android=ET.parse(ROOT/'tooling/go2-lab/AndroidManifest.xml').getroot()
    if android.attrib['{http://schemas.android.com/apk/res/android}versionName']!=version:
        raise ValueError('APK and bundle versions differ')
    # Always rebuild from this clean source instead of accidentally releasing an old APK.
    subprocess.run(['bash','tooling/go2-lab/build.sh'],cwd=ROOT,check=True)
    files={}
    for name in git('ls-files','-z','--',*SOURCE_PATHS).decode().strip('\0').split('\0'):
        if not name: continue
        path=ROOT/name
        if path.is_symlink() or any(part.startswith('.env') for part in path.parts):
            raise ValueError('Unexpected source entry: '+name)
        files[name]=path.read_bytes()
    for name in ['TakuGo2.apk','client.jar']:
        files['artifacts/'+name]=(ROOT/'out/go2-lab'/name).read_bytes()
    quickstart=files['tooling/go2-lab/distribution/QUICKSTART.md'].decode()
    # One guide readable both within the repository and at the extracted bundle root.
    quickstart=re.sub(r'\]\(([^)]+)\)',lambda m: ']('+(
        m[1] if '://' in m[1] or m[1].startswith('#') else
        posixpath.normpath('tooling/go2-lab/distribution/'+m[1]))+')',quickstart)
    files['README.md']=quickstart.encode()
    files['go2']=files['tooling/go2-lab/distribution/go2']
    files['package.json']=b'{"private":true,"type":"module","scripts":{"test":"bash tooling/go2-lab/check-simulation.sh"}}\n'
    manifest.update(sourceCommit=revision,sourceRepository='https://github.com/Taku-OS/taku-os',
                    components={p:digest(files[p]) for p in ['artifacts/TakuGo2.apk','artifacts/client.jar']})
    files['manifest.json']=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()
    files['SHA256SUMS']=''.join(f'{digest(data)}  {name}\n' for name,data in sorted(files.items())).encode()
    output.mkdir(parents=True,exist_ok=True)
    stem='taku-go2-devkit-'+version
    archive=output/(stem+'.zip')
    if archive.exists(): raise ValueError('Output already exists; use a fresh directory or version')
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,data in sorted(files.items()):
            info=zipfile.ZipInfo(stem+'/'+name,(2026,1,1,0,0,0))
            info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=(0o100755 if name=='go2' or name.endswith('.sh') else 0o100644)<<16
            z.writestr(info,data)
    verify(archive)
    apk=output/('TakuGo2-'+version+'.apk');apk.write_bytes(files['artifacts/TakuGo2.apk'])
    sums=output/'SHA256SUMS'
    sums.write_text(''.join(f'{digest(p.read_bytes())}  {p.name}\n' for p in [archive,apk]))
    print(json.dumps({'archive':str(archive),'apk':str(apk),'checksums':str(sums),**verify(archive)},indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,default=ROOT/'out/go2-releases')
    parser.add_argument('--verify',type=Path)
    args=parser.parse_args()
    try:
        if args.verify: print(json.dumps(verify(args.verify),indent=2))
        else: package(args.output.resolve())
    except (ValueError,KeyError,zipfile.BadZipFile) as error:
        sys.exit(str(error))
