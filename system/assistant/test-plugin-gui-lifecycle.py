#!/usr/bin/env python3
"""Use actual GUI controls and confirm state via the CLI on the lifecycle AVD."""
import json, pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET

if len(sys.argv) != 2 or not sys.argv[1].startswith('emulator-'):
    raise SystemExit('usage: test-plugin-gui-lifecycle.py EMULATOR_SERIAL')
serial = sys.argv[1]
root = pathlib.Path(__file__).resolve().parents[2]
market = '/data/local/agentos/work/.agents/plugins/marketplace.json'
plugin = 'taku-local-example@taku-test-marketplace'

def adb(*args, **kwargs):
    kwargs.setdefault('check', True)
    return subprocess.run(['adb', '-s', serial, *args], **kwargs)

def cli(*args):
    return json.loads(adb('shell', '/system_ext/bin/taku-plugin', *args, text=True, capture_output=True).stdout)

def catalog():
    entries = [p for m in cli('list')['marketplaces'] for p in m['plugins'] if p['id'] == plugin]
    if len(entries) != 1: raise RuntimeError('fixture missing or duplicate')
    return entries[0]

def nodes():
    result = adb('shell', 'uiautomator', 'dump', '/data/local/tmp/taku-plugin-gui.xml', stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    if result.returncode: return []
    try:
        return list(ET.fromstring(adb('exec-out', 'cat', '/data/local/tmp/taku-plugin-gui.xml', capture_output=True).stdout).iter('node'))
    except ET.ParseError:
        return []

def wait(label, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = nodes()
        for node in current:
            if node.get('text') == label or node.get('content-desc') == label:
                return node
        time.sleep(.5)
    raise RuntimeError('GUI did not show ' + label + ': ' + repr([(n.get('text'), n.get('content-desc')) for n in current]))

def tap(label):
    bounds = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', wait(label).get('bounds', ''))
    if not bounds: raise RuntimeError('no bounds for ' + label)
    x1, y1, x2, y2 = map(int, bounds.groups())
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def wait_catalog(installed, enabled=None):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        row = catalog()
        if row['installed'] == installed and (enabled is None or row['enabled'] == enabled): return
        time.sleep(.5)
    raise RuntimeError('CLI state did not follow GUI: ' + repr(row))

if adb('emu', 'avd', 'name', text=True, capture_output=True).stdout.splitlines()[0].strip() != 'Taku_SkillsLifecycle_20261030':
    raise RuntimeError('wrong AVD')
if adb('shell', 'test', '-e', market, check=False).returncode == 0:
    raise RuntimeError('workspace marketplace already exists')
fixture = root / 'examples/codex-plugin-lifecycle/plugin'
for source in fixture.rglob('*'):
    if source.is_file():
        remote = '/data/local/agentos/work/plugin/' + source.relative_to(fixture).as_posix()
        if adb('exec-out', 'cat', remote, capture_output=True).stdout != source.read_bytes():
            raise RuntimeError('workspace fixture differs')
installed = False
try:
    adb('push', str(root / 'examples/codex-plugin-lifecycle/.agents/plugins/marketplace.json'), market, stdout=subprocess.DEVNULL)
    if catalog()['installed']: raise RuntimeError('fixture already installed')
    adb('shell', 'am', 'force-stop', 'org.agentos.assistant')
    adb('shell', 'am', 'start', '-n', 'org.agentos.assistant/.PanelActivity', stdout=subprocess.DEVNULL)
    for label in ('Conversation tools', 'More options', 'Capabilities', 'Extension connections', 'Plugins and marketplace', 'taku-local-example', 'Install plugin', 'Confirm Install'):
        tap(label)
    installed = True
    wait_catalog(True, True)
    wait('Plugins and marketplace')
    print('PASS GUI install confirmed through CLI', flush=True)
    for label in ('taku-local-example', 'Disable plugin'): tap(label)
    wait_catalog(True, False)
    wait('Plugins and marketplace')
    print('PASS GUI disable confirmed through CLI', flush=True)
    for label in ('taku-local-example', 'Enable plugin'): tap(label)
    wait_catalog(True, True)
    wait('Plugins and marketplace')
    print('PASS GUI enable confirmed through CLI', flush=True)
    for label in ('taku-local-example', 'Uninstall plugin', 'Confirm uninstall'): tap(label)
    wait_catalog(False); installed = False
    wait('Plugins and marketplace')
    print('PASS GUI uninstall confirmed through CLI', flush=True)
finally:
    if installed:
        try: cli('uninstall', plugin)
        except Exception as error: print('cleanup failed:', error, file=sys.stderr)
    adb('shell', 'rm', '-f', market)
    adb('shell', 'rm', '-f', '/data/local/tmp/taku-plugin-gui.xml')
