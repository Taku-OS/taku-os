#!/usr/bin/env python3
"""Apply the pinned product HOME policy to framework resolution and role assignment."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_contract():
    manifest = ROOT / 'home/manifest.json'
    contract = json.loads(manifest.read_text())
    require(contract['schema'] == 'taku.home-policy/1', 'Unknown HOME policy schema')
    require(contract['property'] == 'ro.taku.home.package'
            and contract['package'] == 'org.agentos.agent', 'Unexpected HOME policy')
    expected = {
        'frameworks/base': {'services/core/java/com/android/server/pm/ResolveIntentHelper.java',
                            'services/core/java/com/android/server/pm/TakuHomePolicy.java',
                            'services/core/java/com/android/server/policy/PhoneWindowManager.java',
                            'services/voiceinteraction/java/com/android/server/voiceinteraction/VoiceInteractionManagerService.java'},
        'packages/modules/Permission': {
            'PermissionController/role-controller/java/com/android/role/controller/behavior/HomeRoleBehavior.java',
            'PermissionController/role-controller/java/com/android/role/controller/behavior/AssistantRoleBehavior.java'},
    }
    projects = {p.attrib['path']: p.attrib['revision']
                for p in ET.parse(ROOT / 'manifests/platform.xml').iter('project')}
    require(len(contract['repositories']) == len(expected), 'Unexpected HOME repository count')
    require({r['path'] for r in contract['repositories']} == set(expected), 'Unexpected repositories')
    for repo in contract['repositories']:
        require(repo['revision'] == projects[repo['path']], 'HOME patch is not at the platform pin')
        require({f['path'] for f in repo['files']} == expected[repo['path']], 'Unexpected HOME targets')
        require(len(repo['files']) == len(expected[repo['path']]), 'Duplicate HOME targets')
        require(Path(repo['patch']).name == repo['patch'], 'Invalid patch path')
        patch = ROOT / 'home' / repo['patch']
        require(digest(patch) == repo['patchSha256'], 'HOME patch bytes differ')
        touched = set()
        for line in patch.read_text().splitlines():
            if line.startswith(('--- a/', '+++ b/')):
                touched.add(line[6:])
            require(not line.startswith(('GIT binary patch', 'rename ', 'old mode ', 'new mode ',
                                         'deleted file')), 'Unsupported HOME patch operation')
        require(touched == expected[repo['path']], 'HOME patch coverage differs')
    helper = contract['repositories'][0]['files'][1]
    require(digest(ROOT / 'home/TakuHomePolicy.java') == helper['afterSha256'],
            'HOME resolver source differs from patch')
    return contract


def inspect(repository, entry):
    head = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', 'HEAD'],
                                   text=True).strip()
    require(head == entry['revision'], f'{repository}: expected pinned {entry["revision"]}, got {head}')
    states = []
    for target in entry['files']:
        path = repository / target['path']
        require(not path.is_symlink(), f'Symlink HOME target: {path}')
        current = digest(path) if path.exists() else None
        require(current in (target['beforeSha256'], target['afterSha256']),
                f'Unknown HOME source bytes: {path}; preserve and inspect')
        states.append(current == target['afterSha256'])
    require(all(states) or not any(states), f'Partially applied HOME patch: {repository}')
    return all(states)


def apply(source_root, results_dir, verify_only=False):
    contract = load_contract()
    pending = []
    # Validate *both* checkouts and dry-run all unapplied patches before changing either.
    for entry in contract['repositories']:
        repository = Path(source_root) / entry['path']
        if not inspect(repository, entry):
            require(not verify_only, f'HOME policy not applied: {repository}')
            command = ['git', '-C', str(repository), 'apply', '--whitespace=error']
            patch = str(ROOT / 'home' / entry['patch'])
            subprocess.run([*command, '--check', patch], check=True)
            pending.append([*command, patch])
    for command in pending:
        subprocess.run(command, check=True)
    for entry in contract['repositories']:
        require(inspect(Path(source_root) / entry['path'], entry), 'HOME patch verification failed')
    report = Path(results_dir) / 'home-policy-integration.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    value = {'schema': 'taku.home-policy-integration/1',
             'manifestSha256': digest(ROOT / 'home/manifest.json'),
             'repositories': contract['repositories'], 'sourcePatchVerified': True,
             'romBuiltVerified': False, 'deviceVerified': False}
    report.write_text(json.dumps(value, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', required=True)
    parser.add_argument('--results-dir', required=True)
    parser.add_argument('--verify-only', action='store_true')
    args = parser.parse_args()
    print(apply(args.source_root, args.results_dir, args.verify_only))
