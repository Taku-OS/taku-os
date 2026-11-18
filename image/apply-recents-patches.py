#!/usr/bin/env python3
"""Apply the Taku overview presentation patch to the pinned Launcher3 checkout.

Separate from gesture routing: no HOME/overview component or nav-handle semantics change.
Unknown, mixed or locally edited source is preserved and fails closed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

UPSTREAM = '818f72b74e6f914cbca2b465776d6d20f960ee87'
REPOSITORY = 'packages/apps/Launcher3'
ROOT = Path(__file__).resolve().parent / 'recents'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def apply(source_root, results_dir, verify_only=False):
    manifest = json.loads((ROOT / 'manifest.json').read_text())
    patch = ROOT / 'taku-recents.patch'
    if (manifest['schema'] != 'taku.recents-patch/1' or manifest['upstreamCommit'] != UPSTREAM
            or digest(patch) != manifest['patchSha256']):
        raise ValueError('Recents patch contract differs')
    touched = {line[6:] for line in patch.read_text().splitlines()
               if line.startswith(('--- a/', '+++ b/'))}
    paths = [entry['path'] for entry in manifest['files']]
    if (set(paths) != touched or len(paths) != len(touched) or not paths
            or any(Path(name).is_absolute() or '..' in Path(name).parts for name in paths)):
        raise ValueError('Recents patch target coverage differs')
    repo = Path(source_root) / REPOSITORY
    if subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip() != UPSTREAM:
        raise ValueError('Launcher3 checkout is not pinned')
    states = []
    for entry in manifest['files']:
        path = repo / entry['path']
        if path.is_symlink() or any(parent.is_symlink() for parent in path.parents if repo in parent.parents):
            raise ValueError('Unexpected symlink: ' + entry['path'])
        value = digest(path) if path.exists() else None
        if value not in (entry['beforeSha256'], entry['afterSha256']):
            raise ValueError('Unexpected source bytes; preserve and inspect: ' + entry['path'])
        states.append(value == entry['afterSha256'])
    if any(states) and not all(states):
        raise ValueError('Partially applied recents patch; preserve and inspect')
    if verify_only and not all(states):
        raise ValueError("Recents patch is not applied")
    if not all(states):
        command = ['git', '-C', str(repo), 'apply', '--whitespace=error']
        subprocess.run([*command, '--check', str(patch)], check=True)
        subprocess.run([*command, str(patch)], check=True)
    for entry in manifest['files']:
        if digest(repo / entry['path']) != entry['afterSha256']:
            raise ValueError('Recents patch output differs: ' + entry['path'])
    out = Path(results_dir); out.mkdir(parents=True, exist_ok=True)
    record = {'schema': 'taku.recents-integration/1', 'upstreamCommit': UPSTREAM,
              'patchSha256': manifest['patchSha256'], 'sourcePatchVerified': True,
              'apkBuiltVerified': False, 'deviceVerified': False}
    (out / 'recents-integration.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', required=True)
    parser.add_argument('--results-dir', required=True)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    print(json.dumps(apply(args.source_root, args.results_dir, args.verify_only)))
