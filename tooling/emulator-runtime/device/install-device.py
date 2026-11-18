#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Install only the opt-in device restoration files; never redeploy runtime/apps.

Prints the complete host-verifiable manifest as JSON. The caller must arrange
adb root first. --dry-run gathers the same read-only deployment hashes but does
not create device directories. Existing unknown dev-runtime contents are refused.
"""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import tempfile
import uuid
from pathlib import Path

OWNER = 'taku-emulator-runtime-v1'
ROOT = '/data/local/agentos/dev-runtime'
SCRIPT_NAMES = ('common.sh', 'health.sh', 'restore.sh', 'probe.mjs')
MANAGED_NAMES = (*SCRIPT_NAMES, 'runtime.conf', 'assets.sha256', 'targets.sha256', 'self.sha256')
SAFE_PATH = re.compile(r'/[A-Za-z0-9_./-]+\Z')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def path(value):
    if not SAFE_PATH.fullmatch(value) or '..' in value.split('/') or not value.startswith('/data/local/agentos/'):
        raise ValueError('Unsafe device asset path: ' + value)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial', required=True)
    parser.add_argument('--avd', required=True)
    parser.add_argument('--stage', required=True)
    parser.add_argument('--service', required=True)
    parser.add_argument('--app-id', required=True)
    parser.add_argument('--state-method', required=True)
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]+', args.serial):
        parser.error('An explicit emulator serial is required')
    if not re.fullmatch(r'[A-Za-z0-9_-]+', args.avd) or not re.fullmatch(r'[A-Za-z0-9_-]+', args.app_id):
        parser.error('Invalid AVD or app ID')
    if not re.fullmatch(r'[A-Za-z0-9_.-]{1,64}', args.state_method):
        parser.error('Invalid read-only state method name')
    if not 1024 <= args.port <= 65535:
        parser.error('Port must be in 1024..65535')
    stage, service = path(args.stage), path(args.service)
    if not service.endswith('/api/service.sh'):
        parser.error('Service must be an existing app-kit api/service.sh')
    if '/state/' in stage or '/state/' in service or stage == ROOT or stage.startswith(ROOT + '/'):
        parser.error('Runtime deployment and state paths must remain separate')

    def adb(*command):
        return subprocess.run([args.adb, '-s', args.serial, *command], check=True,
                              capture_output=True, text=True, timeout=60).stdout.strip()

    def shell(command):
        return adb('shell', command)

    def file_hash(filename):
        line = shell('sha256sum ' + shlex.quote(filename))
        digest, _, name = line.partition('  ')
        if not re.fullmatch(r'[0-9a-f]{64}', digest) or name != filename:
            raise ValueError('Invalid device hash response: ' + filename)
        return digest

    properties = shell('id -u; getprop ro.kernel.qemu; getprop ro.build.type; getprop ro.boot.qemu.avd_name; getprop sys.boot_completed; am get-current-user; cmd activity get-started-user-state 0').splitlines()
    if properties != ['0', '1', 'userdebug', args.avd, '1', '0', 'RUNNING_UNLOCKED']:
        raise ValueError('Device must be the named, rooted userdebug emulator with unlocked user 0')
    source = shell('cat ' + shlex.quote(service))
    def setting(name):
        matches = re.findall(r'^(?:export )?' + re.escape(name) + r'=([^\n]+)$', source, re.M)
        if len(matches) != 1:
            raise ValueError('Unsupported existing service setting: ' + name)
        return path(matches[0].strip('"\''))
    kit, node, node_lib = setting('KIT'), setting('NODE'), setting('LD_LIBRARY_PATH')
    if service != kit + '/api/service.sh':
        raise ValueError('Service KIT does not match the requested path')

    assets = {}
    targets = {}
    stage_files = shell('find ' + shlex.quote(stage + '/system-ext-upper') + ' ' + shlex.quote(stage + '/system-etc-upper') + ' -type f').splitlines()
    required_stage = {
        'system-ext-upper/bin/codex', 'system-ext-upper/bin/phone-display',
        'system-ext-upper/framework/agentos-task-broker.jar',
        'system-ext-upper/framework/agentos-virtual-display.jar',
        'system-ext-upper/etc/codex/cacert.pem', 'system-ext-upper/etc/codex/codex.tar.gz',
        'system-ext-upper/etc/codex/code-mode-host.tar.gz',
        'system-ext-upper/etc/agentos/task-result.schema.json',
        'system-ext-upper/etc/init/agentos-codex.rc', 'system-etc-upper/resolv.conf',
    }
    if set(stage_files) != {stage + '/' + item for item in required_stage}:
        raise ValueError('Stage files differ from the supported runtime component layout')
    deployed_files = [*stage_files, service, node, kit + '/mini-app', kit + '/api/client.mjs', kit + '/api/server.mjs']
    # Capture hashes without modifying any installed component, APK or state file.
    for filename in sorted(deployed_files):
        path(filename)
        if shell('stat -c %u ' + shlex.quote(filename)) != '0':
            raise ValueError('Asset is not root-owned: ' + filename)
        assets[filename] = file_hash(filename)
    for filename in stage_files:
        target = filename.replace(stage + '/system-ext-upper', '/system_ext', 1).replace(stage + '/system-etc-upper', '/system/etc', 1)
        targets[target] = assets[filename]

    old = None
    if shell('[ ! -e ' + ROOT + ' ] || echo exists'):
        if shell('[ -d ' + ROOT + ' ] && [ ! -L ' + ROOT + ' ] && stat -c "%u:%a" ' + ROOT) != '0:700':
            raise ValueError('Refusing unknown or unsafe existing dev-runtime directory')
        try:
            old = json.loads(shell('cat ' + ROOT + '/owner.json'))
        except (ValueError, subprocess.CalledProcessError) as error:
            raise ValueError('Existing dev-runtime has no valid ownership manifest') from error
        if old.get('owner') != OWNER or old.get('schemaVersion') != 1 or old.get('deviceRoot') != ROOT or set(old.get('managedFiles', {})) != set(MANAGED_NAMES):
            raise ValueError('Refusing to take over unknown existing dev-runtime files')
        entries = shell('ls -A ' + ROOT).splitlines()
        if set(entries) - {*MANAGED_NAMES, 'owner.json', 'archive', 'restore.lock'}:
            raise ValueError('Refusing dev-runtime containing unrecognized files')
        for name, digest in old['managedFiles'].items():
            if file_hash(ROOT + '/' + name) != digest:
                raise ValueError('Existing managed file was changed outside this installer: ' + name)
        if old.get('serial') != args.serial or old.get('avd') != args.avd:
            raise ValueError('Existing ownership belongs to another emulator')

    here = Path(__file__).resolve().parent
    files = {name: (here / name).read_bytes() for name in SCRIPT_NAMES}
    config = {
        'OWNER': OWNER, 'EXPECTED_AVD': args.avd, 'STAGE': stage,
        'SERVICE': service, 'SERVICE_PORT': str(args.port), 'KIT': kit,
        'NODE': node, 'NODE_LIB': node_lib, 'APP_ID': args.app_id,
        'STATE_METHOD': args.state_method, 'DISPLAY_DIR': '/data/local/agentos/virtual-display',
    }
    files['runtime.conf'] = ''.join(key + '=' + shlex.quote(value) + '\n' for key, value in config.items()).encode()
    files['assets.sha256'] = ''.join(digest + '  ' + name + '\n' for name, digest in sorted(assets.items())).encode()
    files['targets.sha256'] = ''.join(digest + '  ' + name + '\n' for name, digest in sorted(targets.items())).encode()
    files['self.sha256'] = ''.join(sha(data) + '  ' + name + '\n' for name, data in sorted(files.items())).encode()
    manifest = {
        'schemaVersion': 1, 'owner': OWNER, 'serial': args.serial, 'avd': args.avd,
        'deviceRoot': ROOT, 'stage': stage, 'service': service, 'port': args.port,
        'managedFiles': {name: sha(data) for name, data in sorted(files.items())},
        'deployedAssets': assets,
    }
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode()
    receipt = {**manifest, 'manifestSha256': sha(manifest_bytes), 'dryRun': args.dry_run}
    if not args.dry_run:
        token = uuid.uuid4().hex
        upload = '/data/local/tmp/taku-runtime-install-' + token
        with tempfile.TemporaryDirectory(prefix='taku-runtime-device-') as directory:
            bundle = Path(directory)
            for name, data in {**files, 'owner.json': manifest_bytes}.items():
                (bundle / name).write_bytes(data)
            shell('mkdir -m 700 ' + upload)
            for name in (*MANAGED_NAMES, 'owner.json'):
                adb('push', str(bundle / name), upload + '/' + name)
            commands = ['set -eu', 'umask 077']
            if old:
                commands += ['exec 9>' + ROOT + '/restore.lock', 'flock -n 9 9>&9']
                # Recheck ownership under the same lock used by restore.sh.
                for name, digest in old['managedFiles'].items():
                    commands += ['test "$(sha256sum ' + ROOT + '/' + name + ' | cut -d \' \' -f 1)" = ' + digest]
                backup = ROOT + '/archive/install-' + token
                commands += ['mkdir -p ' + backup, 'chmod 700 ' + ROOT + '/archive ' + backup]
                commands += ['cp ' + ROOT + '/' + name + ' ' + backup + '/' + name for name in (*MANAGED_NAMES, 'owner.json')]
            else:
                commands += ['mkdir -m 700 ' + ROOT]
            for name in (*MANAGED_NAMES, 'owner.json'):
                commands += ['chown 0:0 ' + upload + '/' + name, 'chmod 600 ' + upload + '/' + name,
                             'mv ' + upload + '/' + name + ' ' + ROOT + '/' + name]
            commands += ['rmdir ' + upload, 'cd ' + ROOT, 'sha256sum -c self.sha256 >/dev/null']
            shell('\n'.join(commands))
        for name, digest in manifest['managedFiles'].items():
            if file_hash(ROOT + '/' + name) != digest:
                raise ValueError('Installed script hash mismatch: ' + name)
        if file_hash(ROOT + '/owner.json') != receipt['manifestSha256']:
            raise ValueError('Installed ownership manifest hash mismatch')
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(receipt, indent=2, sort_keys=True) + '\n')
    print(json.dumps(receipt, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
