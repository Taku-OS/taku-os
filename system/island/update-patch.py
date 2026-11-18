#!/usr/bin/env python3
"""Refresh owned SystemUI source in the pinned patch; retain and hash-check existing upstream hooks."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
INPUTS = ROOT / 'image/island'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def update(upstream):
    manifest = json.loads((INPUTS / 'manifest.json').read_text())
    patch = INPUTS / manifest['patch']
    if digest(patch.read_bytes()) != manifest['patchSha256']:
        raise ValueError('Existing patch hash differs; review hook changes explicitly')
    with tempfile.TemporaryDirectory(prefix='taku-island-patch-') as temp:
        checkout = Path(temp)
        before = {}
        for entry in manifest['files']:
            if entry['beforeSha256'] is None:
                continue
            data = (upstream / entry['path']).read_bytes()
            if digest(data) != entry['beforeSha256']:
                raise ValueError('Upstream bytes differ: ' + entry['path'])
            path = checkout / entry['path']
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            before[entry['path']] = data.decode()
        subprocess.run(['git', 'init', '-q', str(checkout)], check=True)
        subprocess.run(['git', '-C', str(checkout), 'apply', '--whitespace=error', str(patch)], check=True)
        after = {name: (checkout / name).read_text() for name in before}
        sources = {}
        for source in sorted((ROOT / 'system/island/src').rglob('*')):
            if source.is_file():
                name = 'packages/SystemUI/src/' + str(source.relative_to(ROOT / 'system/island/src'))
                after[name] = source.read_text()
                sources[name] = str(source.relative_to(ROOT))
        output = []
        files = []
        for name in sorted(after):
            original = before.get(name)
            output.extend(difflib.unified_diff((original or '').splitlines(True), after[name].splitlines(True),
                                              fromfile='a/' + name if original is not None else '/dev/null',
                                              tofile='b/' + name))
            entry = {'path': name, 'beforeSha256': digest(original.encode()) if original is not None else None,
                     'afterSha256': digest(after[name].encode())}
            if name in sources:
                entry['source'] = sources[name]
            files.append(entry)
        content = ''.join(output).encode()
        manifest['files'] = files
        manifest['patchSha256'] = digest(content)
        patch.write_bytes(content)
        (INPUTS / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream-root', required=True, type=Path)
    update(parser.parse_args().upstream_root)
