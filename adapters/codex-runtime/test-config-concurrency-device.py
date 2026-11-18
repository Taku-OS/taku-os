#!/usr/bin/env python3
"""Race independent app-server processes on one disposable lifecycle AVD home."""
import concurrent.futures, json, pathlib, queue, shlex, subprocess, sys, threading, time, tomllib, uuid

if len(sys.argv) != 3 or not sys.argv[1].startswith('emulator-'):
    raise SystemExit('usage: test-config-concurrency-device.py EMULATOR_SERIAL LOCAL_CODEX_BINARY')
serial, binary = sys.argv[1:]
root = pathlib.Path(__file__).resolve().parents[2]
remote = '/data/local/tmp/taku-config-race-' + uuid.uuid4().hex
market = remote + '/fixture/.agents/plugins/marketplace.json'
key = 'taku-local-example@taku-test-marketplace'

def adb(*args, **kw):
    kw.setdefault('check', True)
    return subprocess.run(['adb', '-s', serial, *args], **kw)

if adb('emu', 'avd', 'name', text=True, capture_output=True).stdout.splitlines()[0].strip() != 'Taku_SkillsLifecycle_20261030':
    raise RuntimeError('wrong AVD')

class Server:
    def __init__(self):
        cmd = 'exec env HOME={0} CODEX_HOME={0}/home TMPDIR={0} {0}/codex app-server'.format(shlex.quote(remote))
        self.p = subprocess.Popen(['adb', '-s', serial, 'shell', cmd], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, bufsize=1)
        self.seq = 0
        self.lines = queue.Queue()
        def read_lines():
            for line in self.p.stdout:
                self.lines.put(line)
            self.lines.put(None)
        threading.Thread(target=read_lines, daemon=True).start()
        self.call('initialize', {'clientInfo': {'name': 'config_race', 'version': '1'}, 'capabilities': {'experimentalApi': True}})
        self.p.stdin.write('{"method":"initialized"}\n'); self.p.stdin.flush()
    def call(self, method, params):
        self.seq += 1
        self.p.stdin.write(json.dumps({'id': self.seq, 'method': method, 'params': params}) + '\n'); self.p.stdin.flush()
        while True:
            try: line = self.lines.get(timeout=120)
            except queue.Empty: raise RuntimeError(method + ' timed out')
            if line is None: raise RuntimeError(method + ' server exited')
            response = json.loads(line)
            if response.get('id') == self.seq:
                if 'error' in response: raise RuntimeError(method + ': ' + str(response['error']))
                return response.get('result')
    def close(self):
        self.p.terminate(); self.p.wait(timeout=10)

def write(server, key_path, value):
    return server.call('config/value/write', {'keyPath': key_path, 'value': value, 'mergeStrategy': 'upsert'})

servers = []
try:
    adb('shell', 'mkdir', '-p', remote + '/home')
    adb('push', binary, remote + '/codex', stdout=subprocess.DEVNULL)
    adb('shell', 'chmod', '755', remote + '/codex')
    adb('push', str(root / 'examples/codex-plugin-lifecycle'), remote + '/fixture', stdout=subprocess.DEVNULL)
    for _ in range(3):
        servers.append(Server())
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for i in range(20):
            for action in ('install', 'uninstall'):
                params = {'marketplacePath': market, 'pluginName': 'taku-local-example'} if action == 'install' else {'pluginId': key}
                unique = f'race-{i}-{action}@unrelated'
                jobs = [pool.submit(servers[0].call, 'plugin/' + action, params),
                        pool.submit(write, servers[1], 'plugins."' + key + '".enabled', bool(i % 2)),
                        pool.submit(write, servers[2], 'plugins."' + unique + '".enabled', True)]
                for job in jobs: job.result(timeout=150)
                data = tomllib.loads(adb('shell', 'cat', remote + '/home/config.toml', text=True, capture_output=True).stdout)
                for j in range(i + 1):
                    for completed in ('install', 'uninstall'):
                        if j == i and completed == 'uninstall' and action == 'install': continue
                        name = f'race-{j}-{completed}@unrelated'
                        if data.get('plugins', {}).get(name, {}).get('enabled') is not True:
                            raise RuntimeError('lost unrelated configuration: ' + name)
    print('PASS: 20 install/uninstall rounds concurrent with enable/disable and 40 distinct unrelated writes across three processes')
    lock_path = shlex.quote(remote + '/home/plugins/.lock')
    holder = subprocess.Popen(['adb', '-s', serial, 'shell', f'exec 9>{lock_path}; flock -x 9 9>&9; echo locked; sleep 14'], stdout=subprocess.PIPE, text=True)
    try:
        if holder.stdout.readline().strip() != 'locked': raise RuntimeError('lock holder did not start')
        started = time.monotonic()
        try:
            write(servers[1], 'plugins."timeout@unrelated".enabled', True)
            raise RuntimeError('contended config write unexpectedly succeeded')
        except RuntimeError as error:
            if 'timed out waiting for config/plugin lock' not in str(error): raise
        elapsed = time.monotonic() - started
        if not 9 <= elapsed < 14: raise RuntimeError(f'lock timeout outside bound: {elapsed:.1f}s')
        holder.wait(timeout=10)
        write(servers[1], 'plugins."after-timeout@unrelated".enabled', True)
        data = tomllib.loads(adb('shell', 'cat', remote + '/home/config.toml', text=True, capture_output=True).stdout)
        if 'timeout@unrelated' in data.get('plugins', {}): raise RuntimeError('timed out write was persisted')
        if not data['plugins']['after-timeout@unrelated']['enabled']: raise RuntimeError('write after release failed')
        print(f'PASS: contended config write failed after {elapsed:.1f}s, then succeeded after lock release')
    finally:
        if holder.poll() is None:
            holder.terminate(); holder.wait(timeout=10)
finally:
    for server in servers: server.close()
    adb('shell', 'rm', '-rf', remote)
