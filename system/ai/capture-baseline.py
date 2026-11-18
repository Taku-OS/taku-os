#!/usr/bin/env python3
"""Read-only pre-OTA configuration baseline; account contents are never read."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import xml.etree.ElementTree as ET

AI_FILE = '/data/local/agentos/ai/settings.json'
IME_FILE = '/data/user/0/org.agentos.inputmethod/shared_prefs/taku-ai-replies.xml'
ACCOUNT_FILE = '/data/local/agentos/home/.codex/auth.json'
PACKAGES = ['org.agentos.inputmethod', 'org.agentos.assistant', 'org.agentos.memory']
MEMORY_KEYS = ['followsSystem', 'modelOverride', 'model', 'enabled', 'analysisEnabled',
               'queryEnabled', 'proactive', 'sources', 'retentionDays']


def settings(sh):
    config = json.loads(sh('cat', AI_FILE))
    ime = {n.get('name'): (sorted(c.text for c in n) if n.tag == 'set' else
           n.get('value') if n.tag == 'boolean' else n.text or '')
           for n in ET.fromstring(sh('cat', IME_FILE))}
    reply = sh('content', 'call', '--user', '0', '--uri', 'content://org.agentos.memory',
               '--method', 'status', '--arg', '{}')
    data, _ = json.JSONDecoder().raw_decode(reply[reply.index('result=') + 7:])
    if not data.get('ok'):
        raise ValueError('Memory status unavailable')
    return {
        'build': sh('getprop', 'ro.build.version.incremental'),
        'bootId': sh('cat', '/proc/sys/kernel/random/boot_id'),
        'selinux': sh('getenforce'),
        'defaultIme': sh('settings', 'get', 'secure', 'default_input_method'),
        'systemAi': {k: config[k] for k in ['enabled', 'textModel', 'imageModel', 'apps']},
        'imeAi': {k: ime.get(k) for k in ['enabled', 'model', 'apps']},
        'memory': {k: data['result'][k] for k in MEMORY_KEYS},
        'systemAiFilePermissions': sh('stat', '-c', '%u:%g:%a', AI_FILE),
        'systemAiDirectoryPermissions': sh('stat', '-c', '%u:%g:%a', '/data/local/agentos/ai'),
        'accountCacheMetadataOnly': sh('stat', '-c', '%u:%g:%a', ACCOUNT_FILE),
    }


def packages(sh):
    result = {}
    for package in PACKAGES:
        paths = sh('pm', 'path', package).splitlines()
        if len(paths) != 1 or not paths[0].startswith('package:/'):
            raise ValueError('Expected one active APK: ' + package)
        path = paths[0][8:]
        code = re.search(r'\bversionCode=(\d+)\b', sh('dumpsys', 'package', package))
        digest = sh('sha256sum', path).split()[0]
        if not code or not re.fullmatch(r'[a-f0-9]{64}', digest):
            raise ValueError('Invalid active APK metadata: ' + package)
        result[package] = {'path': path, 'versionCode': int(code[1]), 'sha256': digest}
    return result


def capture(serial, sh):
    if sh('id', '-u') != '0' or sh('getprop', 'sys.boot_completed') != '1':
        raise ValueError('An already booted device with root diagnostics is required')
    before = settings(sh)
    active = packages(sh)
    overrides = [line for line in sh('cat', '/proc/self/mountinfo').splitlines()
                 if len(line.split()) > 4 and line.split()[4] in [
                     '/system_ext/framework/agentos-task-broker.jar', '/system_ext/bin/magpie',
                     '/system_ext/bin/magpie-runtime', '/system_ext/bin/taku-runtime-start']]
    if settings(sh) != before or packages(sh) != active:
        raise ValueError('Device or settings changed during capture; wait for handoff and capture again')
    if (before['selinux'] != 'Enforcing' or before['systemAiFilePermissions'] != '0:0:600'
            or before['systemAiDirectoryPermissions'] != '0:0:700'
            or before['accountCacheMetadataOnly'] != '0:0:600'):
        raise ValueError('Unexpected device security or configuration permissions')
    return {
        'schema': 'taku.ai-before-ota/1',
        'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'serial': serial, **before, 'activePackages': active,
        'accountCacheContentsRead': False, 'runtimeOverridesBefore': overrides,
        'deviceModified': False,
    }


def write_baseline(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial')
    p.add_argument('--allow-physical', action='store_true')
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    if not args.serial.startswith('emulator-') and not args.allow_physical:
        p.error('Device-owner authorization and --allow-physical required')

    def sh(*parts):
        return subprocess.check_output(['adb', '-s', args.serial, 'shell', shlex.join(parts)],
                                       text=True, timeout=45).strip()

    write_baseline(args.output, capture(args.serial, sh))
    print('Saved read-only baseline: ' + str(args.output))


if __name__ == '__main__':
    main()
