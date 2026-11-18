#!/usr/bin/env python3
"""Read-only post-OTA gate. Never install product APKs, start services, or restore bind mounts."""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET


def backing(path, mountinfo):
    mounts = []
    for line in mountinfo.splitlines():
        left, right = line.split(' - ', 1)
        fields, fs = left.split(), right.split()
        point = fields[4].replace('\\040', ' ')
        if path == point or path.startswith(point.rstrip('/') + '/'):
            mounts.append({'mountPoint': point, 'root': fields[3], 'options': fields[5].split(','), 'fs': fs[0]})
    if not mounts:
        raise AssertionError('No backing mount found for ' + path)
    mount = max(mounts, key=lambda m: len(m['mountPoint']))
    if mount['mountPoint'] == path or mount['fs'] not in ['ext4', 'erofs'] or 'ro' not in mount['options']:
        raise AssertionError('Expected baked, read-only system file without bind/overlay: ' + path)
    return mount


def ime_settings(raw):
    values = {n.get('name'): (sorted(c.text for c in n) if n.tag == 'set' else n.get('value') if n.tag == 'boolean' else n.text or '') for n in ET.fromstring(raw)}
    return {k: values.get(k) for k in ['enabled', 'model', 'apps']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('--allow-physical', action='store_true')
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--rom-report', type=Path, required=True)
    parser.add_argument('--expected-build', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not args.serial.startswith('emulator-') and not args.allow_physical:
        parser.error('Device-owner authorization and --allow-physical required')
    before, image = json.loads(args.before.read_text()), json.loads(args.rom_report.read_text())
    if before['serial'] != args.serial or image['status'] != 'artifact_verified':
        raise SystemExit('Wrong device baseline or unverified ROM component report')
    adb = ['adb', '-s', args.serial]
    def sh(*parts):
        return subprocess.check_output(adb + ['shell', shlex.join(parts)], text=True, timeout=45).strip()
    assert sh('id', '-u') == '0', 'Root diagnostics required; do not deploy or restart the runtime'
    assert sh('getprop', 'sys.boot_completed') == '1'
    assert sh('getprop', 'ro.build.version.incremental') == args.expected_build
    boot = sh('cat', '/proc/sys/kernel/random/boot_id')
    assert boot != before['bootId'], 'A real device reboot is required'
    assert sh('getenforce') == 'Enforcing'
    assert sh('settings', 'get', 'secure', 'default_input_method') == before['defaultIme']
    services = {n: sh('getprop', 'init.svc.' + n) for n in ['taku_broker', 'taku_magpie', 'taku_display', 'taku_resolver']}
    assert all(v == 'running' for v in services.values()), services
    mounts = sh('cat', '/proc/self/mountinfo')
    verified = {}
    targets = {'org.agentos.inputmethod': 'app/TakuInputMethod/TakuInputMethod.apk',
               'org.agentos.assistant': 'priv-app/TakuAssistant/TakuAssistant.apk',
               'org.agentos.memory': 'priv-app/TakuMemory/TakuMemory.apk'}
    if 'priv-app/TakuSurfaces/TakuSurfaces.apk' in image['components']:
        targets['org.agentos.surfaces'] = 'priv-app/TakuSurfaces/TakuSurfaces.apk'
    for package, target in targets.items():
        paths = sh('pm', 'path', package).splitlines()
        assert len(paths) == 1, 'Expected one active base APK: ' + package
        actual = paths[0].removeprefix('package:')
        assert actual.startswith('/system_ext/'), 'Data APK must not shadow the ROM: ' + package
        expected = image['components'][target]
        assert sh('sha256sum', actual).split()[0] == expected['sha256']
        verified[package] = dict(expected, activePath=actual, mount=backing(sh('readlink', '-f', actual), mounts))
    runtime_targets = ['framework/agentos-task-broker.jar', 'bin/magpie', 'bin/magpie-runtime', 'bin/taku-runtime-start',
                       'etc/init/taku-runtime.rc', 'etc/init/taku-magpie.rc']
    runtime_targets += [name for name in ['framework/agentos-virtual-display.jar', 'bin/phone-display'] if name in image['components']]
    for target in runtime_targets:
        actual = '/system_ext/' + target
        assert sh('sha256sum', actual).split()[0] == image['components'][target]['sha256'], target
        verified[target] = {'sha256': image['components'][target]['sha256'], 'mount': backing(sh('readlink', '-f', actual), mounts)}
    native = {}
    for target, expected in image['nativeComponents'].items():
        actual = '/system_ext/' + target
        assert sh('sha256sum', actual).split()[0] == expected['sha256'], target
        assert sh('stat', '-c', '%u:%g:%a', actual) == str(expected['uid']) + ':' + str(expected['gid']) + ':' + expected['mode'].lstrip('0'), target
        native[target] = {'sha256': expected['sha256'], 'mount': backing(sh('readlink', '-f', actual), mounts)}
    for target, expected in image['nativeSymlinks'].items():
        assert sh('readlink', '/system_ext/' + target) == expected, target
        backing(sh('readlink', '-f', '/system_ext/' + target), mounts)
    uid = re.search(r'^package:org\.agentos\.inputmethod uid:(\d+)$', sh('pm', 'list', 'packages', '-U', 'org.agentos.inputmethod'), re.M)
    assert uid and int(uid.group(1)) >= 10000, 'IME must keep its ordinary application UID'
    assert 'org.agentos.assistant.permission.IME_REPLIES: granted=true' in sh('dumpsys', 'package', 'org.agentos.inputmethod')
    config = json.loads(sh('cat', '/data/local/agentos/ai/settings.json'))
    actual_config = {k: config[k] for k in ['enabled', 'textModel', 'imageModel', 'apps']}
    assert actual_config == before['systemAi'], 'Existing system AI defaults or permissions changed'
    assert ime_settings(sh('cat', '/data/user/0/org.agentos.inputmethod/shared_prefs/taku-ai-replies.xml')) == before['imeAi']
    raw = sh('content', 'call', '--user', '0', '--uri', 'content://org.agentos.memory', '--method', 'status', '--arg', '{}')
    memory = json.loads(raw[raw.index('result=') + 7:raw.rindex('}]')])
    assert memory['ok']
    controls = [k for k in before['memory'] if k != 'model']
    assert {k: memory['result'][k] for k in controls} == {k: before['memory'][k] for k in controls}, 'Memory defaults or collection policy changed'
    permissions = {p: sh('stat', '-c', '%u:%g:%a', p) for p in ['/data/local/agentos/ai', '/data/local/agentos/ai/settings.json', '/data/local/agentos/home/.codex/auth.json']}
    assert permissions['/data/local/agentos/ai'] == '0:0:700'
    assert permissions['/data/local/agentos/ai/settings.json'] == '0:0:600'
    assert permissions['/data/local/agentos/home/.codex/auth.json'] == before['accountCacheMetadataOnly']
    chain = sh('iptables', '-S', 'taku_ai').splitlines()
    assert any('--uid-owner 0 -j RETURN' in l for l in chain) and any('--uid-owner 1000 -j RETURN' in l for l in chain)
    assert any('-j REJECT --reject-with tcp-reset' in l for l in chain)
    assert any('-o lo' in l and '--dport 3425' in l and '-j taku_ai' in l for l in sh('iptables', '-S', 'OUTPUT').splitlines())
    report = {'status': 'cold_runtime_verified_model_test_pending', 'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'build': args.expected_build, 'bootId': boot, 'selinux': 'Enforcing', 'services': services, 'components': verified,
              'nativeComponents': native, 'nativeSymlinks': image['nativeSymlinks'],
              'systemAi': actual_config, 'configurationPreserved': True, 'imeUid': int(uid.group(1)), 'gatewayRules': chain,
              'permissions': permissions, 'accountContentsRead': False, 'runtimeDeploymentPerformed': False, 'realGenerationVerified': False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print('PASS baked read-only components, init services and preserved settings; live model/UI checks still required')


if __name__ == '__main__':
    main()
