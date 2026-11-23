#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Verify pinned dependencies and prepare classes/JNI. Offline unless --download."""
import argparse
import hashlib
import io
import json
import shutil
import tempfile
from pathlib import Path
import urllib.request
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
DEFAULT = ROOT / 'out/tryon-native-deps'


def verify(path, artifact):
    return path.is_file() and path.stat().st_size == artifact['bytes'] and hashlib.sha256(path.read_bytes()).hexdigest() == artifact['sha256']


def prepare(root, download=False):
    lock = json.loads((HERE / 'dependencies.lock.json').read_text())
    root.mkdir(parents=True, exist_ok=True)
    jars, natives = [], []
    for artifact in lock['artifacts']:
        name = artifact['file']
        if Path(name).name != name or not artifact['url'].startswith('https://repo.maven.apache.org/maven2/'):
            raise ValueError('invalid pinned artifact')
        path = root / 'downloads' / name
        if path.exists() and not verify(path, artifact):
            raise ValueError('cached dependency differs from lock: ' + name)
        if not path.exists():
            if not download:
                raise ValueError('missing offline dependency; explicitly run prepare-native-deps.py --download: ' + name)
            path.parent.mkdir(parents=True, exist_ok=True)
            temporary = None
            try:
                request = urllib.request.Request(artifact['url'], headers={'User-Agent': 'Taku-build-dependency-fetch/1'})
                with tempfile.NamedTemporaryFile(dir=path.parent, prefix=name + '.', suffix='.download', delete=False) as output:
                    temporary = Path(output.name)
                    with urllib.request.urlopen(request, timeout=60) as response:
                        remaining = artifact['bytes'] + 1
                        while remaining:
                            chunk = response.read(min(65536, remaining))
                            if not chunk:
                                break
                            output.write(chunk)
                            remaining -= len(chunk)
                if not verify(temporary, artifact):
                    raise ValueError('downloaded dependency differs from lock: ' + name)
                temporary.replace(path)
            finally:
                if temporary is not None:
                    temporary.unlink(missing_ok=True)
        if name.endswith('.aar'):
            with zipfile.ZipFile(path) as archive:
                for entry in archive.namelist():
                    if not (entry == 'classes.jar' or entry.startswith('libs/') and entry.endswith('.jar')):
                        continue
                    data = archive.read(entry)
                    with zipfile.ZipFile(io.BytesIO(data)) as nested:
                        if not any(item.endswith('.class') for item in nested.namelist()):
                            continue
                    jar = root / ('webrtc.jar' if name.startswith('webrtc-') and entry == 'classes.jar'
                                  else Path(name).stem + '-' + Path(entry).name)
                    jar.write_bytes(data)
                    jars.append(str(jar))
                for item in archive.namelist():
                    pieces = Path(item).parts
                    if len(pieces) != 3 or pieces[0] != 'jni' or pieces[1] not in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64') or not pieces[2].endswith('.so'):
                        continue
                    target = root / 'native/lib' / pieces[1] / pieces[2]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    data = archive.read(item)
                    if str(target.relative_to(root / 'native')) in natives and target.read_bytes() != data:
                        raise ValueError('conflicting pinned JNI library: ' + item)
                    target.write_bytes(data)
                    natives.append(str(target.relative_to(root / 'native')))
        else:
            jars.append(str(path))
    # Notices remain alongside source; this machine-readable closure is included in the APK.
    (root / 'classpath.txt').write_text(':'.join(jars) + '\n')
    (root / 'dex-inputs.txt').write_text('\n'.join(jars) + '\n')
    (root / 'verified.json').write_text(json.dumps({'artifacts': lock['artifacts'], 'jni': natives}, indent=2) + '\n')
    return root


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=DEFAULT)
    parser.add_argument('--download', action='store_true')
    parser.add_argument('--soong', action='store_true', help='Stage verified pinned archives in ignored app/downloads for Soong')
    args = parser.parse_args()
    prepared = prepare(args.output.resolve(), args.download)
    if args.soong:
        destination = HERE / 'downloads'
        destination.mkdir(parents=True, exist_ok=True)
        for artifact in json.loads((HERE / 'dependencies.lock.json').read_text())['artifacts']:
            source = prepared / 'downloads' / artifact['file']
            target = destination / artifact['file']
            if source != target:
                shutil.copyfile(source, target)
    print(prepared)


if __name__ == '__main__':
    main()
