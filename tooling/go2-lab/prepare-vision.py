#!/usr/bin/env python3
"""Download pinned public vision assets for offline, on-phone inference."""
import base64
import hashlib
import io
import json
from pathlib import Path
import tarfile
from urllib.request import urlopen

root = Path(__file__).resolve().parent
lock = json.loads((root / 'vision-assets.lock.json').read_text())
destination = root / 'web/vision-assets'
destination.mkdir(exist_ok=True)

def valid(name):
    p = destination / name
    return p.is_file() and hashlib.sha256(p.read_bytes()).hexdigest() == lock['files'][name]

if not all(valid(name) for name in lock['files']):
    with urlopen(lock['tarball'], timeout=90) as response:
        package = response.read()
    assert 'sha512-' + base64.b64encode(hashlib.sha512(package).digest()).decode() == lock['integrity'], 'Package integrity mismatch'
    with tarfile.open(fileobj=io.BytesIO(package)) as archive:
        for name, digest in lock['files'].items():
            if valid(name): continue
            if name.endswith('.task'):
                with urlopen(lock['modelUrl'], timeout=90) as response: data = response.read()
            else:
                member = 'package/' + ('wasm/' if name.startswith('vision_wasm_') else '') + name
                data = archive.extractfile(member).read()
            assert hashlib.sha256(data).hexdigest() == digest, 'Asset integrity mismatch: ' + name
            temporary = destination / (name + '.partial')
            temporary.write_bytes(data)
            temporary.replace(destination / name)
print('Verified MediaPipe ' + lock['version'] + ' and Pose Landmarker Lite; assets are ready for offline installation.')
