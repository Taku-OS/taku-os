#!/usr/bin/env python3
"""Apply the SystemUI island implementation to the exact platform pin, without building/flashing."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
INPUTS = ROOT / 'image/island'
HOOKS = {
    'packages/SystemUI/src/com/android/systemui/statusbar/chips/ui/viewmodel/OngoingActivityChipsViewModel.kt',
    'packages/SystemUI/AndroidManifest.xml',
    'packages/SystemUI/src/com/android/systemui/dagger/SystemUIModule.java',
    'packages/SystemUI/src/com/android/systemui/statusbar/phone/PhoneStatusBarView.java',
    'packages/SystemUI/src/com/android/systemui/statusbar/pipeline/shared/ui/composable/StatusBarRoot.kt',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_contract():
    value = json.loads((INPUTS / 'manifest.json').read_text())
    require(value['schema'] == 'taku.island-probe/1', 'Unknown island patch schema')
    require(value['repository'] == 'frameworks/base', 'Unexpected island repository')
    pins = {project.attrib['path']: project.attrib['revision']
            for project in ET.parse(ROOT / 'image/manifests/platform.xml').iter('project')}
    require(value['revision'] == pins[value['repository']], 'Island platform pin differs')
    require(value['patch'] == 'systemui-probe.patch', 'Unexpected island patch path')
    patch = INPUTS / value['patch']
    require(digest(patch) == value['patchSha256'], 'Island patch bytes differ')
    sources = {str(path.relative_to(ROOT)): path for path in
               (ROOT / 'system/island/src').rglob('*') if path.is_file()}
    expected = HOOKS | {
        'packages/SystemUI/src/' + name.removeprefix('system/island/src/') for name in sources}
    paths = [entry['path'] for entry in value['files']]
    require(set(paths) == expected and len(paths) == len(expected), 'Island target coverage differs')
    for entry in value['files']:
        if entry['path'] in HOOKS:
            require(entry['beforeSha256'] is not None, 'Missing upstream hook hash')
        else:
            name = 'system/island/src/' + entry['path'].removeprefix('packages/SystemUI/src/')
            require(entry.get('source') == name and entry['beforeSha256'] is None,
                    'Invalid island source mapping')
            require(digest(sources[name]) == entry['afterSha256'], 'Island source differs from patch')
    touched = set()
    for line in patch.read_text().splitlines():
        if line.startswith(('--- a/', '+++ b/')):
            touched.add(line[6:])
        require(not line.startswith(('GIT binary patch', 'rename ', 'old mode ', 'new mode ',
                                     'deleted file')), 'Unsupported island patch operation')
    require(touched == expected, 'Island patch coverage differs')
    return value


def layered_after(entry):
    """Final bytes of a later Taku patch built on this island file (lock screen `layeredOn: island`)."""
    path = ROOT / 'image/lockscreen/manifest.json'
    if not path.is_file():
        return set()
    return {item['afterSha256'] for item in json.loads(path.read_text()).get('files', [])
            if item['path'] == entry['path'] and item.get('layeredOn') == 'island'
            and item['beforeSha256'] == entry['afterSha256']}


def inspect(repository, contract):
    repository = Path(repository).resolve()
    head = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', 'HEAD'], text=True).strip()
    require(head == contract['revision'], f'Island requires pinned revision {contract["revision"]}; got {head}')
    states = []
    for entry in contract['files']:
        path = repository / entry['path']
        # Reject parent symlinks too: a generated checkout must not redirect writes elsewhere.
        require(all(not parent.is_symlink() for parent in [path, *path.parents]
                    if parent != repository.parent), f'Symlink island target: {path}')
        current = digest(path) if path.exists() else None
        # The island edit is still present when a later layer (the lock screen) has built on it.
        applied = {entry['afterSha256']} | layered_after(entry)
        require(current == entry['beforeSha256'] or current in applied,
                f'Unknown island source bytes; preserving {path}')
        states.append(current in applied)
    require(all(states) or not any(states), 'Partially applied island patch; inspect before continuing')
    return all(states)


def apply(source_root, results_dir, verify_only=False):
    contract = load_contract()
    repository = Path(source_root).resolve() / contract['repository']
    if not inspect(repository, contract):
        require(not verify_only, 'Island patch not applied')
        command = ['git', '-C', str(repository), 'apply', '--whitespace=error']
        patch = str(INPUTS / contract['patch'])
        subprocess.run([*command, '--check', patch], check=True)
        subprocess.run([*command, patch], check=True)
    require(inspect(repository, contract), 'Island patch verification failed')
    report = Path(results_dir) / 'island-probe-integration.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({
        'schema': 'taku.island-probe-integration/1',
        'revision': contract['revision'], 'manifestSha256': digest(INPUTS / 'manifest.json'),
        'sourcePatchVerified': True, 'systemUiCompiled': False,
        'romBuiltVerified': False, 'deviceVerified': False,
    }, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root')
    parser.add_argument('--results-dir')
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--check-inputs', action='store_true')
    args = parser.parse_args()
    if args.check_inputs:
        print(json.dumps({'inputContractValid': bool(load_contract()), 'systemUiCompiled': False}))
    else:
        if not args.source_root or not args.results_dir:
            parser.error('--source-root and --results-dir are required for apply/verification')
        print(apply(args.source_root, args.results_dir, args.verify_only))
