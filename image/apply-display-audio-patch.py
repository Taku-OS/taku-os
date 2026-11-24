#!/usr/bin/env python3
"""Apply the dedicated display audio patch to the pinned framework checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

UPSTREAM = '7f6de40b6429462d47ba9ba351b99943269c8067'
REPOSITORY = 'frameworks/base'
ROOT = Path(__file__).resolve().parent / 'display-audio'


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def apply(source_root, results_dir, verify_only=False):
    manifest = json.loads((ROOT / 'manifest.json').read_text())
    patch = ROOT / 'dedicated-display-mute.patch'
    if (manifest['schema'] != 'taku.display-audio-patch/1' or manifest['upstreamCommit'] != UPSTREAM
            or digest(patch) != manifest['patchSha256']):
        raise ValueError('Display audio patch contract differs')
    touched = {line[6:] for line in patch.read_text().splitlines()
               if line.startswith(('--- a/', '+++ b/'))}
    paths = [entry['path'] for entry in manifest['files']]
    if (set(paths) != touched or len(paths) != len(touched) or not paths
            or any(Path(name).is_absolute() or '..' in Path(name).parts for name in paths)):
        raise ValueError('Display audio patch target coverage differs')
    repo = Path(source_root) / REPOSITORY
    if subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip() != UPSTREAM:
        raise ValueError('Framework checkout is not pinned')
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
        raise ValueError('Partially applied display audio patch; preserve and inspect')
    if verify_only and not all(states):
        raise ValueError("Display audio patch is not applied")
    if not all(states):
        command = ['git', '-C', str(repo), 'apply', '--whitespace=error']
        subprocess.run([*command, '--check', str(patch)], check=True)
        subprocess.run([*command, str(patch)], check=True)
    for entry in manifest['files']:
        if digest(repo / entry['path']) != entry['afterSha256']:
            raise ValueError('Display audio patch output differs: ' + entry['path'])
    subprocess.run(['git', '-C', str(repo), 'apply', '--reverse', '--check',
                    '--whitespace=error', str(patch)], check=True)
    out = Path(results_dir)
    out.mkdir(parents=True, exist_ok=True)
    record = {'schema': 'taku.display-audio-integration/1', 'upstreamCommit': UPSTREAM,
              'patchSha256': manifest['patchSha256'], 'sourcePatchVerified': True,
              'frameworkBuiltVerified': False, 'deviceVerified': False}
    (out / 'display-audio-integration.json').write_text(json.dumps(record, indent=2) + '\n')
    return record


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', required=True)
    parser.add_argument('--results-dir', required=True)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    print(json.dumps(apply(args.source_root, args.results_dir, args.verify_only)))
