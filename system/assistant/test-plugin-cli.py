#!/usr/bin/env python3
"""Exercise GUI-shared plugin RPC through the CLI on the disposable lifecycle AVD."""
import json
import subprocess
import sys
from pathlib import Path

serial = sys.argv[1] if len(sys.argv) == 2 else ''
if not serial.startswith('emulator-'):
    raise SystemExit('Provide the dedicated emulator serial')
def adb(*args, **kwargs):
    return subprocess.run(['adb', '-s', serial, *args], check=True, text=True, **kwargs)
if adb('emu', 'avd', 'name', capture_output=True).stdout.splitlines()[0].strip() != 'Taku_SkillsLifecycle_20261030':
    raise SystemExit('Wrong AVD')
root = Path(__file__).resolve().parents[2]
market = '/data/local/agentos/work/.agents/plugins/marketplace.json'
plugin = 'taku-local-example@taku-test-marketplace'
if subprocess.run(['adb', '-s', serial, 'shell', 'test', '-e', market]).returncode == 0:
    raise SystemExit('Workspace marketplace already exists')
def cli(*args):
    return json.loads(adb('shell', '/system_ext/bin/taku-plugin', *args, capture_output=True).stdout)
def entry():
    return [p for m in cli('list')['marketplaces'] for p in m['plugins'] if p['id'] == plugin]
# This fixture's server is owned by this repository; third-party MCP still runs as root.
fixture = root / 'examples/codex-plugin-lifecycle/plugin'
workspace_plugin = '/data/local/agentos/work/plugin'
created_plugin = subprocess.run(['adb', '-s', serial, 'shell', 'test', '-e', workspace_plugin]).returncode != 0
if created_plugin:
    adb('push', str(fixture), workspace_plugin, stdout=subprocess.DEVNULL)
else:
    for source in fixture.rglob('*'):
        if source.is_file():
            remote = workspace_plugin + '/' + source.relative_to(fixture).as_posix()
            actual = subprocess.run(['adb', '-s', serial, 'exec-out', 'cat', remote], check=True, capture_output=True).stdout
            if actual != source.read_bytes():
                raise SystemExit('Workspace plugin differs from test fixture; refusing to change it')
adb('push', str(root / 'examples/codex-plugin-lifecycle/.agents/plugins/marketplace.json'), market, stdout=subprocess.DEVNULL)
installed = False
try:
    found = entry()
    if len(found) != 1 or found[0]['installed']:
        raise RuntimeError('fixture missing or already installed')
    cli('install', market, 'taku-local-example')
    installed = True
    found = entry()
    if len(found) != 1 or not found[0]['installed'] or not found[0].get('localVersion'):
        raise RuntimeError('installed version missing from catalog')
    if not any(s.get('pluginId') == plugin and 'hello' in s.get('tools', {}) for s in cli('status')['data']):
        raise RuntimeError('MCP tool missing')
    cli('disable', plugin)
    if entry()[0]['enabled'] or any(s.get('pluginId') == plugin for s in cli('status')['data']):
        raise RuntimeError('disable did not update shared state')
    cli('enable', plugin)
    if not entry()[0]['enabled'] or not any(s.get('pluginId') == plugin for s in cli('status')['data']):
        raise RuntimeError('enable did not update shared state')
    cli('uninstall', plugin)
    installed = False
    if entry()[0]['installed'] or any(s.get('pluginId') == plugin for s in cli('status')['data']):
        raise RuntimeError('uninstall did not update shared state')
    print('PASS: CLI catalog, version, install, disable, enable, MCP status and uninstall')
finally:
    if installed:
        try:
            cli('uninstall', plugin)
        except Exception as error:
            print('Fixture cleanup failed:', error, file=sys.stderr)
    adb('shell', 'rm', '-f', market)
    if created_plugin:
        adb('shell', 'rm', '-rf', workspace_plugin)
