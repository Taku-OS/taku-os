#!/usr/bin/env python3
"""Kill a committed install before its enabled config write on the dedicated AVD."""
import json, pathlib, select, shlex, subprocess, sys, tempfile, tomllib, uuid

if len(sys.argv) != 3 or not sys.argv[1].startswith('emulator-'):
    raise SystemExit('usage: test-plugin-install-kill-device.py EMULATOR_SERIAL LOCAL_CODEX_BINARY')
serial, binary = sys.argv[1:]
root = pathlib.Path(__file__).resolve().parents[2]
remote = '/data/local/tmp/taku-install-kill-' + uuid.uuid4().hex
market = remote + '/fixture/.agents/plugins/marketplace.json'
key = 'taku-local-example@taku-test-marketplace'
proc = watcher = None
seq = 0

def adb(*args, **kwargs):
    kwargs.setdefault('check', True)
    return subprocess.run(['adb', '-s', serial, *args], **kwargs)

if adb('emu', 'avd', 'name', text=True, capture_output=True).stdout.splitlines()[0].strip() != 'Taku_SkillsLifecycle_20261030':
    raise RuntimeError('wrong AVD')

def call(method, params):
    global seq
    seq += 1
    proc.stdin.write(json.dumps({'id': seq, 'method': method, 'params': params}) + '\n'); proc.stdin.flush()
    while True:
        ready, _, _ = select.select([proc.stdout], [], [], 180)
        if not ready: raise RuntimeError(method + ' timed out')
        line = proc.stdout.readline()
        if not line: raise RuntimeError(method + ' server exited')
        response = json.loads(line)
        if response.get('id') == seq:
            if 'error' in response: raise RuntimeError(method + ': ' + str(response['error']))
            return response.get('result')

def start():
    global proc, seq
    seq = 0
    shell = 'echo $$ > {0}/server.pid; exec env HOME={0} CODEX_HOME={0}/home TMPDIR={0} {0}/codex app-server'.format(remote)
    proc = subprocess.Popen(['adb', '-s', serial, 'shell', shell], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
    call('initialize', {'clientInfo': {'name': 'install_kill', 'version': '1'}, 'capabilities': {'experimentalApi': True}})
    proc.stdin.write('{"method":"initialized"}\n'); proc.stdin.flush()

try:
    adb('shell', 'mkdir', '-p', remote + '/home')
    adb('push', binary, remote + '/codex', stdout=subprocess.DEVNULL)
    adb('shell', 'chmod', '755', remote + '/codex')
    adb('push', str(root / 'examples/codex-plugin-lifecycle'), remote + '/fixture', stdout=subprocess.DEVNULL)
    # Large but valid TOML widens the window between the committed journal and
    # config replacement without modifying or instrumenting the tested binary.
    with tempfile.NamedTemporaryFile(mode='w') as f:
        f.write('# padding for kill window\n' * 250000); f.write('[plugins."unrelated@market"]\nenabled = false\n'); f.flush()
        adb('push', f.name, remote + '/home/config.toml', stdout=subprocess.DEVNULL)
    start()
    pid = adb('shell', 'cat', remote + '/server.pid', text=True, capture_output=True).stdout.strip()
    if not pid.isdigit(): raise RuntimeError('invalid PID')
    journal = remote + '/home/plugins/.transaction/journal.json'
    script = 'while kill -0 {pid} 2>/dev/null; do case "$(cat {journal} 2>/dev/null)" in *committed_enabled*) kill -9 {pid}; exit 0;; esac; done; exit 1'.format(pid=pid, journal=shlex.quote(journal))
    watcher = subprocess.Popen(['adb', '-s', serial, 'shell', script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        call('plugin/install', {'marketplacePath': market, 'pluginName': 'taku-local-example'})
        raise RuntimeError('install completed before kill')
    except RuntimeError as error:
        if 'server exited' not in str(error): raise
    if watcher.wait(timeout=20) != 0: raise RuntimeError('watcher failed')
    proc.wait(timeout=10); proc = None
    record = json.loads(adb('shell', 'cat', journal, text=True, capture_output=True).stdout)
    if record.get('step') != 'committed_enabled': raise RuntimeError('missing committed enabled journal')
    before = tomllib.loads(adb('shell', 'cat', remote + '/home/config.toml', text=True, capture_output=True).stdout)
    if key in before.get('plugins', {}): raise RuntimeError('config already enabled before kill')
    print('PASS SIGKILL after committed enabled journal and before config write', flush=True)
    start()
    after = tomllib.loads(adb('shell', 'cat', remote + '/home/config.toml', text=True, capture_output=True).stdout)
    if after.get('plugins', {}).get(key, {}).get('enabled') is not True: raise RuntimeError('enabled config not recovered')
    if after.get('plugins', {}).get('unrelated@market', {}).get('enabled') is not False: raise RuntimeError('unrelated config changed')
    adb('shell', 'test', '-f', remote + '/home/plugins/cache/taku-test-marketplace/taku-local-example/1.0.0/.codex-plugin/plugin.json')
    if adb('shell', 'test', '-e', journal, check=False).returncode == 0: raise RuntimeError('journal remains')
    print('PASS restart recovered installed cache and enabled TOML, preserving unrelated config', flush=True)
finally:
    if watcher is not None and watcher.poll() is None: watcher.terminate(); watcher.wait(timeout=10)
    if proc is not None: proc.terminate(); proc.wait(timeout=10)
    adb('shell', 'rm', '-rf', remote)
