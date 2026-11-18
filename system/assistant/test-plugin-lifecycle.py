#!/usr/bin/env python3
"""Native plugin/MCP test; only the disposable lifecycle AVD may be modified."""
import json
import subprocess
import sys
from pathlib import Path

serial = sys.argv[1] if len(sys.argv) == 2 else ''
if not serial.startswith('emulator-'):
    raise SystemExit('Provide the dedicated emulator serial')
def adb(*args, **kwargs):
    return subprocess.run(['adb', '-s', serial, *args], check=True, text=True, **kwargs)
name = adb('emu', 'avd', 'name', capture_output=True).stdout.splitlines()[0].strip()
if name != 'Taku_SkillsLifecycle_20261030':
    raise SystemExit('Wrong AVD')
root = Path(__file__).resolve().parents[2]
remote = '/data/local/tmp/taku-plugin-lifecycle-fixture'
plugin_id = 'taku-local-example@taku-test-marketplace'
marketplace = remote + '/.agents/plugins/marketplace.json'
adb('push', str(root / 'examples/codex-plugin-lifecycle'), remote, stdout=subprocess.DEVNULL)
proc = subprocess.Popen(['adb', '-s', serial, 'shell', '/system_ext/bin/codex', 'app-server'],
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                        text=True, bufsize=1)
sequence = 0
installed = False

def call(method, params):
    global sequence
    sequence += 1
    request = {'id': sequence, 'method': method, 'params': params}
    proc.stdin.write(json.dumps(request) + '\n')
    proc.stdin.flush()
    while True:
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError('app-server closed')
        response = json.loads(line)
        if response.get('id') == sequence:
            if 'error' in response:
                raise RuntimeError(method + ': ' + response['error'].get('message', 'failed'))
            return response['result']

try:
    call('initialize', {'clientInfo': {'name': 'taku_plugin_fixture', 'version': '1'},
                        'capabilities': {'experimentalApi': True}})
    proc.stdin.write('{"method":"initialized"}\n')
    proc.stdin.flush()
    catalog = call('plugin/list', {'cwds': [remote]})
    plugins = [p for m in catalog['marketplaces'] for p in m['plugins'] if p['id'] == plugin_id]
    if len(plugins) != 1 or plugins[0]['installed']:
        raise RuntimeError('fixture is missing or already installed; refusing to change it')
    call('plugin/install', {'marketplacePath': marketplace, 'pluginName': 'taku-local-example'})
    installed = True
    call('config/mcpServer/reload', {})
    status = call('mcpServerStatus/list', {})['data']
    servers = [s for s in status if s.get('pluginId') == plugin_id]
    if len(servers) != 1 or 'hello' not in servers[0]['tools'] or servers[0].get('toolsError'):
        raise RuntimeError('native MCP tool discovery failed: ' + json.dumps(servers))
    print('PASS: native plugin installation and MCP hello tool discovery')
    call('config/value/write', {'keyPath': 'plugins."' + plugin_id + '".enabled',
                                'mergeStrategy': 'upsert', 'value': False})
    call('config/mcpServer/reload', {})
    status = call('mcpServerStatus/list', {})['data']
    if any(s.get('pluginId') == plugin_id for s in status):
        raise RuntimeError('disabled plugin still present in MCP status')
    print('PASS: disabled plugin removed from native MCP status')
    call('plugin/uninstall', {'pluginId': plugin_id})
    installed = False
    call('config/mcpServer/reload', {})
    if any(s.get('pluginId') == plugin_id for s in call('mcpServerStatus/list', {})['data']):
        raise RuntimeError('uninstalled plugin still present in MCP status')
    print('PASS: native plugin uninstall')
finally:
    if installed and proc.poll() is None:
        try:
            call('plugin/uninstall', {'pluginId': plugin_id})
        except Exception as error:
            print('Cleanup failed:', error, file=sys.stderr)
    proc.terminate()
    proc.wait(timeout=5)
