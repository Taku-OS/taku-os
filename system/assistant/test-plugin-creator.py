#!/usr/bin/env python3
"""Packaging checks on the dedicated disposable creator AVD; no account/network needed."""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile
from pathlib import Path

serial = sys.argv[1] if len(sys.argv) == 2 else ''
if not serial.startswith('emulator-'):
    raise SystemExit('Provide the dedicated emulator serial')
def adb(*args, check=True):
    return subprocess.run(['adb', '-s', serial, *args], check=check, capture_output=True, text=True)
if adb('emu', 'avd', 'name').stdout.splitlines()[0].strip() != 'Taku_PluginCreator_20261030':
    raise SystemExit('Wrong AVD')
root = Path(__file__).resolve().parents[2]
remote = '/data/local/tmp/creator-check-' + uuid.uuid4().hex

def cli(*args, check=True):
    result = adb('shell', os.environ.get('TAKU_PLUGIN_CLI', '/system_ext/bin/taku-plugin'), *args, check=check)
    return json.loads(result.stdout) if check else result

try:
    adb('shell', 'mkdir', '-p', remote)
    adb('push', str(root / 'examples/plugin-creator/status-tip'), remote + '/source')
    assert cli('validate', remote + '/source')['valid']
    packed = cli('pack', remote + '/source', remote + '/package')
    assert cli('pack', remote + '/source', remote + '/package', check=False).returncode != 0
    published = cli('publish', remote + '/source', remote + '/publication')
    assert not published['published'] and 'local' in published['reason'].lower()
    with tempfile.TemporaryDirectory() as tmp:
        local_zip = Path(tmp) / 'plugin.zip'
        adb('pull', packed['package'], str(local_zip))
        assert hashlib.sha256(local_zip.read_bytes()).hexdigest() == packed['sha256']
        with zipfile.ZipFile(local_zip) as z:
            assert 'taku-status-tip/.codex-plugin/plugin.json' in z.namelist()
            assert 'taku-status-tip/server.sh' in z.namelist()
            assert z.getinfo('taku-status-tip/server.sh').external_attr >> 16 & 0o111, 'Executable bit lost'
    adb('shell', 'ln', '-s', '/data/local/tmp', remote + '/source/link')
    assert cli('validate', remote + '/source', check=False).returncode != 0
    print('PASS: validate, pack, hash, zip contents, existing output rejection, symlink rejection, local publication')
finally:
    adb('shell', 'rm', '-rf', remote, check=False)
