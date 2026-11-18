#!/usr/bin/env python3
"""Reject emulator exports missing the default Google apps or their native runtime."""
import argparse
import json
from pathlib import Path
import runpy

ROOT = Path(__file__).resolve().parents[2]
APPS = runpy.run_path(str(ROOT / 'image/google_apps.py'))
GMS = runpy.run_path(str(ROOT / 'image/google_services.py'))


def verify(product, tools):
    lock, checksum = APPS['load_lock']()
    core, core_checksum = GMS['load_lock']()
    product, tools = Path(product), Path(tools)
    # Include permissions, sysconfig, APEX and overlays, not just three package filenames.
    core_files = []
    for record in core['installedFiles']:
        partition, relative = record['path'].split('/', 1)
        path = product / partition.lower() / relative
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError('Missing Google runtime file: ' + record['path'])
        actual = GMS['digest'](path)
        if record['exactBytes'] and (actual != record['sha256'] or path.stat().st_size != record['bytes']):
            raise ValueError('Google runtime bytes differ: ' + record['path'])
        core_files.append({'path': record['path'], 'sha256': actual})
    for module, partition in [(n, 'product') for n in core['overlays']] + [(core['takuAssistantOverlay'], 'system_ext')]:
        overlays = list((product / partition / 'overlay').glob('**/' + module + '.apk'))
        if len(overlays) != 1 or overlays[0].is_symlink() or overlays[0].stat().st_size == 0:
            raise ValueError('Missing/ambiguous runtime overlay: ' + module)
    properties = dict(line.split('=', 1) for line in (product / 'product/etc/build.prop').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    if properties.get('ro.taku.image.distribution') != lock['distribution']:
        raise ValueError('Missing internal Google distribution marker')
    apps = []
    for app in lock['apps']:
        path = product / 'product/app' / app['module'] / (app['module'] + '.apk')
        result = APPS['inspect_apk'](path, app, tools / 'aapt2', tools / 'apksigner', tools / 'zipalign')
        apps.append({'id': app['id'], 'packageName': app['packageName'], 'sha256': app['sha256'],
                     'signers': result['signers'], 'manifest': result['manifest']})
    return {'schema': 'taku.emulator-default-apps/1', 'distribution': lock['distribution'],
            'appsLockSha256': checksum, 'coreLockSha256': core_checksum,
            'runtimeFiles': core_files, 'apps': apps, 'runtimeVerified': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--product', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(verify(args.product, args.tools), indent=2) + '\n')
