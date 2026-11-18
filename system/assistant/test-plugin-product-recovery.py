#!/usr/bin/env python3
"""Interrupt CLI uninstall in the broker's actual app-server on the lifecycle AVD."""
import json, pathlib, shlex, subprocess, sys, time, tomllib

if len(sys.argv) != 2 or not sys.argv[1].startswith('emulator-'):
    raise SystemExit('usage: test-plugin-product-recovery.py EMULATOR_SERIAL')
serial = sys.argv[1]
root = pathlib.Path(__file__).resolve().parents[2]
market = '/data/local/agentos/work/.agents/plugins/marketplace.json'
fixture = '/data/local/agentos/work/plugin'
home = '/data/local/agentos/home/.codex'
key = 'taku-local-example@taku-test-marketplace'
cache = home + '/plugins/cache/taku-test-marketplace/taku-local-example'

def adb(*args, **kwargs):
    kwargs.setdefault('check', True)
    return subprocess.run(['adb', '-s', serial, *args], **kwargs)

def cli(*args):
    return json.loads(adb('shell', '/system_ext/bin/taku-plugin', *args, text=True, capture_output=True, timeout=80).stdout)

if adb('emu', 'avd', 'name', text=True, capture_output=True).stdout.splitlines()[0].strip() != 'Taku_SkillsLifecycle_20261030':
    raise RuntimeError('wrong AVD')
for path in (market, fixture + '/bulk'):
    if adb('shell', 'test', '-e', path, check=False).returncode == 0: raise RuntimeError('refusing to replace ' + path)
for source in (root / 'examples/codex-plugin-lifecycle/plugin').rglob('*'):
    if source.is_file():
        remote = fixture + '/' + source.relative_to(root / 'examples/codex-plugin-lifecycle/plugin').as_posix()
        if adb('exec-out', 'cat', remote, capture_output=True).stdout != source.read_bytes(): raise RuntimeError('fixture differs')
watcher = None
installed = False
try:
    adb('push', str(root / 'examples/codex-plugin-lifecycle/.agents/plugins/marketplace.json'), market, stdout=subprocess.DEVNULL)
    entries = [p for m in cli('list')['marketplaces'] for p in m['plugins'] if p['id'] == key]
    if len(entries) != 1 or entries[0]['installed']: raise RuntimeError('fixture already installed or missing')
    adb('shell', f'mkdir -p {fixture}/bulk; i=0; while [ "$i" -lt 20000 ]; do : > {fixture}/bulk/f$i; i=$((i+1)); done')
    cli('install', market, 'taku-local-example'); installed = True
    first = cache + '/1.0.0/bulk/f0'
    adb('shell', 'test', '-f', first)
    pids = adb('shell', 'pidof', 'codex-aarch64-unknown-linux-musl', text=True, capture_output=True).stdout.split()
    if len(pids) != 1 or not pids[0].isdigit(): raise RuntimeError('expected one broker runtime PID: ' + repr(pids))
    pid = pids[0]
    flag = '/data/local/tmp/taku-product-recovery-killed'
    adb('shell', 'rm', '-f', flag)
    script = f'while [ -e {shlex.quote(first)} ]; do kill -0 {pid} 2>/dev/null || exit 2; sleep 0.01; done; echo killed > {flag}; kill -9 {pid}'
    watcher = subprocess.Popen(['adb', '-s', serial, 'shell', script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    failed = adb('shell', '/system_ext/bin/taku-plugin', 'uninstall', key, check=False, text=True, capture_output=True, timeout=80)
    if failed.returncode == 0: raise RuntimeError('uninstall completed before interruption')
    if watcher.wait(timeout=30) != 0: raise RuntimeError('kill watcher failed')
    adb('shell', 'test', '-f', flag)
    print('PASS killed broker app-server after uninstall started removing cached files', flush=True)
    deadline = time.monotonic() + 90
    while True:
        try:
            entries = [p for m in cli('list')['marketplaces'] for p in m['plugins'] if p['id'] == key]
            if len(entries) == 1 and not entries[0]['installed']: break
        except (subprocess.SubprocessError, ValueError):
            pass
        if time.monotonic() > deadline: raise RuntimeError('broker did not recover after interruption')
        time.sleep(1)
    for path in (cache, home + '/plugins/.backups/taku-test-marketplace/taku-local-example', home + '/plugins/.transaction'):
        if adb('shell', 'test', '-e', path, check=False).returncode == 0: raise RuntimeError('recovery residue: ' + path)
    config = tomllib.loads(adb('shell', 'cat', home + '/config.toml', text=True, capture_output=True).stdout)
    if key in config.get('plugins', {}): raise RuntimeError('plugin config remained after recovery')
    installed = False
    print('PASS broker restart completed uninstall and removed cache, backup, journal and plugin config', flush=True)
finally:
    if watcher is not None and watcher.poll() is None:
        watcher.terminate(); watcher.wait(timeout=10)
    if installed:
        try: cli('uninstall', key)
        except Exception as error: print('cleanup failed:', error, file=sys.stderr)
    adb('shell', 'rm', '-rf', fixture + '/bulk')
    adb('shell', 'rm', '-f', market, '/data/local/tmp/taku-product-recovery-killed')
