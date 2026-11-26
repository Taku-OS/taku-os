#!/usr/bin/env python3
"""Validate an existing tokay checkpoint before replacing only vendor/taku inputs."""
import datetime
import hashlib
import json
import pathlib


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(8 * 1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def verify(old_root, new_root, source_root, results):
    old = json.loads((old_root / 'input-manifest.json').read_text())
    new = json.loads((new_root / 'input-manifest.json').read_text())
    previous = {item['path']: item['sha256'] for item in old['files']}
    current = {item['path']: item['sha256'] for item in new['files']}
    pinned = ['image/source-lock.json', 'image/kernel-source-lock.json', 'image/manifests/platform.xml',
              'image/manifests/devices.xml', 'image/manifests/kernel.xml',
              'image/build-kernel.sh', 'image/install-kernel.py']
    if 'image/kernel_cache.py' in previous or 'image/kernel_cache.py' in current:
        pinned.append('image/kernel_cache.py')
    for name in pinned:
        if not previous.get(name) or previous[name] != current.get(name):
            raise ValueError('Incremental build requires the same platform/kernel lock: ' + name)
    # Earlier resume controllers were allowed to change these files without reintegration.
    controllers = {'image/build.sh', 'image/README.md', 'image/resume-check.py', 'image/verify-artifacts.py'}
    for name, expected in previous.items():
        if name not in controllers and digest(old_root / name) != expected:
            raise ValueError('Existing integrated input drifted: ' + name)
    for item in new['files']:
        if digest(new_root / item['path']) != item['sha256']:
            raise ValueError('New immutable snapshot differs: ' + item['path'])
    for name in ['resolved-manifest.xml', 'resolved-kernel-manifest.xml', 'kernel-artifacts.json']:
        if not (results / name).is_file():
            raise ValueError('Missing source/kernel checkpoint: ' + name)
    if not (source_root / 'out/combined-lineage_tokay.ninja').is_file():
        raise ValueError('Missing existing tokay build graph')
    kernel = json.loads((results / 'kernel-artifacts.json').read_text())
    checked = 0
    for item in kernel['artifacts']:
        if item['name'] == 'boot.img' or item['name'].endswith('.ko'):
            path = source_root / 'device/google/caimito-kernels/6.1' / item['name']
            if digest(path) != item['sha256']:
                raise ValueError('Kernel checkpoint changed: ' + item['name'])
            checked += 1
    if not checked:
        raise ValueError('Kernel checkpoint has no boot/module artifacts')
    return {
        'checkedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'previousSnapshotDigest': old['digest'], 'newSnapshotDigest': new['digest'],
        'sourceAndKernelLocksUnchanged': True, 'kernelArtifactsChecked': checked,
        'compileCachePreserved': True, 'sourceSyncedAgain': False, 'kernelRebuilt': False,
        'changedInputs': sorted(name for name in set(previous) | set(current)
                                if previous.get(name) != current.get(name)),
    }


if __name__ == '__main__':
    results = pathlib.Path('/results')
    report = verify(pathlib.Path('/src/vendor/taku'), pathlib.Path('/taku'), pathlib.Path('/src'), results)
    (results / 'incremental-check.json').write_text(json.dumps(report, indent=2) + '\n')
    print('Locked source/kernel checkpoint verified; updating Taku inputs while preserving compile cache.')
