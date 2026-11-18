#!/usr/bin/env python3
"""Smoke-test a source-built musl binary on the dedicated disposable lifecycle AVD."""
import json
import argparse
import select
import subprocess
import sys
import uuid
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial')
parser.add_argument('binary', type=Path)
parser.add_argument('--avd', default='Taku_SkillsLifecycle_20261030', help='Exact isolated AVD name; physical devices are never accepted')
args = parser.parse_args()
serial, binary = args.serial, args.binary
if not serial.startswith('emulator-'):
    raise SystemExit('Only an explicitly selected emulator is accepted')
if not binary.is_file():
    raise SystemExit('Binary does not exist')
def adb(*args, **kwargs):
    kwargs.setdefault('check', True)
    return subprocess.run(['adb', '-s', serial, *args], **kwargs)
name = adb('emu', 'avd', 'name', text=True, capture_output=True).stdout.splitlines()[0].strip()
if name != args.avd or adb('shell', 'getprop', 'ro.kernel.qemu', text=True, capture_output=True).stdout.strip() != '1':
    raise SystemExit('Wrong AVD')
remote = '/data/local/tmp/taku-source-smoke-' + uuid.uuid4().hex
created = False
try:
    adb('shell', 'mkdir', remote, stdout=subprocess.DEVNULL)
    created = True
    adb('push', str(binary), remote + '/codex', stdout=subprocess.DEVNULL)
    adb('shell', 'chmod', '755', remote + '/codex')
    adb('shell', 'mkdir', remote + '/home')
    environment = 'HOME={0} CODEX_HOME={0}/home TMPDIR={0}'.format(remote)
    version_probe = adb('shell', environment + ' ' + remote + '/codex --version', text=True, capture_output=True, check=False)
    if version_probe.returncode:
        raise RuntimeError('Version probe failed: ' + version_probe.stderr.strip())
    version = version_probe.stdout.strip()
    if version != 'codex-cli 0.161.0':
        raise RuntimeError('Unexpected version: ' + version)
    process = subprocess.Popen(['adb', '-s', serial, 'shell', environment + ' ' + remote + '/codex app-server'],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
    try:
        process.stdin.write(json.dumps({'id': 1, 'method': 'initialize', 'params': {'clientInfo': {'name': 'taku-source-smoke', 'version': '1'}}}) + '\n')
        process.stdin.flush()
        ready, _, _ = select.select([process.stdout], [], [], 30)
        if not ready:
            raise RuntimeError('App-server initialization timed out')
        result = json.loads(process.stdout.readline())
        if result.get('id') != 1 or 'result' not in result:
            raise RuntimeError('App-server initialization failed: ' + str(result))
    finally:
        process.stdin.close()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    print('PASS: source-built codex version and app-server initialization on dedicated AVD')
finally:
    if created:
        adb('shell', 'rm', '-rf', remote)
