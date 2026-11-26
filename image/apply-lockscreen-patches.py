#!/usr/bin/env python3
"""Apply the Taku lock screen (SystemUI keyguard) to the exact platform pin, without building/flashing."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
INPUTS = ROOT / 'image/lockscreen'
ISLAND = ROOT / 'image/island/manifest.json'
SOURCES = ROOT / 'system/lockscreen/src'
SYSTEMUI = 'packages/SystemUI/src/'
HOOKS = {
    SYSTEMUI + 'com/android/systemui/keyguard/ui/binder/KeyguardQuickAffordanceViewBinder.kt',
    'packages/SystemUI/AndroidManifest.xml',
    SYSTEMUI + 'com/android/systemui/keyguard/data/quickaffordance/KeyguardDataQuickAffordanceModule.kt',
    SYSTEMUI + 'com/android/systemui/keyguard/ui/view/layout/sections/transitions/ClockSizeTransition.kt',
    SYSTEMUI + 'com/android/systemui/keyguard/ui/view/layout/blueprints/KeyguardBlueprintModule.kt',
    SYSTEMUI + 'com/android/systemui/keyguard/domain/interactor/KeyguardBlueprintInteractor.kt',
    SYSTEMUI + 'com/android/systemui/keyguard/data/repository/KeyguardBlueprintRepository.kt',
    SYSTEMUI + 'com/android/keyguard/dagger/ClockRegistryModule.java',
    SYSTEMUI + 'com/android/systemui/shade/NotificationPanelViewController.java',
    SYSTEMUI + 'com/android/systemui/statusbar/notification/interruption/KeyguardNotificationVisibilityProvider.kt',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def load_contract():
    value = json.loads((INPUTS / 'manifest.json').read_text())
    require(value['schema'] == 'taku.lockscreen/1', 'Unknown lock screen patch schema')
    require(value['repository'] == 'frameworks/base', 'Unexpected lock screen repository')
    pins = {project.attrib['path']: project.attrib['revision']
            for project in ET.parse(ROOT / 'image/manifests/platform.xml').iter('project')}
    require(value['revision'] == pins[value['repository']], 'Lock screen platform pin differs')
    require(value['patch'] == 'systemui-lockscreen.patch', 'Unexpected lock screen patch path')
    patch = INPUTS / value['patch']
    require(digest(patch) == value['patchSha256'], 'Lock screen patch bytes differ')
    sources = {str(path.relative_to(ROOT)): path for path in SOURCES.rglob('*') if path.is_file()}
    expected = HOOKS | {SYSTEMUI + name.removeprefix('system/lockscreen/src/') for name in sources}
    paths = [entry['path'] for entry in value['files']]
    require(set(paths) == expected and len(paths) == len(expected), 'Lock screen target coverage differs')
    island = {item['path']: item for item in json.loads(ISLAND.read_text())['files']} if ISLAND.is_file() else {}
    for entry in value['files']:
        if entry.get('layeredOn') is not None:
            # A file the island patch also edits: the lock screen starts from the island's final bytes.
            require(entry['layeredOn'] == 'island' and entry['path'] in HOOKS, 'Unknown lock screen patch layer')
            require(entry['path'] in island and island[entry['path']]['afterSha256'] == entry['beforeSha256'],
                    'Lock screen layer does not start from the island patch; run system/lockscreen/update-patch.py')
        if entry['path'] in HOOKS:
            require(entry['beforeSha256'] is not None, 'Missing upstream hook hash')
        else:
            name = 'system/lockscreen/src/' + entry['path'].removeprefix(SYSTEMUI)
            require(entry.get('source') == name and entry['beforeSha256'] is None, 'Invalid lock screen source mapping')
            require(digest(sources[name]) == entry['afterSha256'],
                    'Lock screen source differs from patch; run system/lockscreen/update-patch.py')
    touched = set()
    for line in patch.read_text().splitlines():
        if line.startswith(('--- a/', '+++ b/')):
            touched.add(line[6:])
        require(not line.startswith(('GIT binary patch', 'rename ', 'old mode ', 'new mode ', 'deleted file')),
                'Unsupported lock screen patch operation')
    require(touched == expected, 'Lock screen patch coverage differs')
    return value


def inspect(repository, contract):
    repository = Path(repository).resolve()
    head = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', 'HEAD'], text=True).strip()
    require(head == contract['revision'], f'Lock screen requires pinned revision {contract["revision"]}; got {head}')
    states = []
    for entry in contract['files']:
        path = repository / entry['path']
        # Reject parent symlinks too: a generated checkout must not redirect writes elsewhere.
        require(all(not parent.is_symlink() for parent in [path, *path.parents] if parent != repository.parent),
                f'Symlink lock screen target: {path}')
        current = digest(path) if path.exists() else None
        if entry.get('layeredOn') == 'island' and current not in (entry['beforeSha256'], entry['afterSha256']):
            require(False, f'{path} is not in the island-patched state; apply the island patch before the '
                           'lock screen patch (image/build.sh order)')
        require(current in (entry['beforeSha256'], entry['afterSha256']),
                f'Unknown lock screen source bytes; preserving {path}')
        states.append(current == entry['afterSha256'])
    require(all(states) or not any(states), 'Partially applied lock screen patch; inspect before continuing')
    return all(states)


def apply(source_root, results_dir, verify_only=False):
    contract = load_contract()
    repository = Path(source_root).resolve() / contract['repository']
    if not inspect(repository, contract):
        require(not verify_only, 'Lock screen patch not applied')
        command = ['git', '-C', str(repository), 'apply', '--whitespace=error']
        patch = str(INPUTS / contract['patch'])
        subprocess.run([*command, '--check', patch], check=True)
        subprocess.run([*command, patch], check=True)
    require(inspect(repository, contract), 'Lock screen patch verification failed')
    report = Path(results_dir) / 'lockscreen-integration.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps({
        'schema': 'taku.lockscreen-integration/1',
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
