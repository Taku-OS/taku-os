#!/usr/bin/env python3
"""Use actual GUI controls and confirm state via the CLI on the lifecycle AVD."""
import json, os, pathlib, re, subprocess, sys, time, xml.etree.ElementTree as ET

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
    if result.returncode: return None
    try:
        return ET.fromstring(adb('exec-out', 'cat', '/data/local/tmp/taku-plugin-gui.xml', capture_output=True).stdout)
    except ET.ParseError:
        return None

def bounds(node):
    match = re.fullmatch(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', node.get('bounds', ''))
    if not match: raise RuntimeError('Accessibility node has no bounds')
    return tuple(map(int, match.groups()))

def wait(label, state=None, scroll=False):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        tree = nodes()
        if tree is None: continue
        for node in tree.iter('node'):
            if state is not None and not any(child.get('text') == state for child in node): continue
            candidates = node.iter('node') if state is not None else (node,)
            for child in candidates:
                if label in (child.get('text'), child.get('content-desc'), child.get('resource-id')):
                    return child
        if scroll:
            container = next((node for node in tree.iter('node') if node.get('scrollable') == 'true'), None)
            if container is not None:
                left, top, right, bottom = bounds(container)
                x = (left + right) // 2
                inset = (bottom - top) // 5
                adb('shell', 'input', 'swipe', str(x), str(bottom - inset), str(x), str(top + inset), '350')
        time.sleep(.5)
    raise RuntimeError(f'GUI did not show {label!r} in fixture state {state!r}')

def tap(label, state=None):
    x1, y1, x2, y2 = bounds(wait(label, state, scroll=state is not None))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def wait_catalog(installed, enabled=None):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        row = catalog()
        if row['installed'] == installed and (enabled is None or row['enabled'] == enabled): return
        time.sleep(.5)
    raise RuntimeError('CLI state did not follow GUI: ' + repr(row))

def fixture_status(label, row):
    version = row.get('localVersion')
    return f'taku-test-marketplace · {label}' + (f' · {version}' if version else '')

if adb('emu', 'avd', 'name', text=True, capture_output=True).stdout.splitlines()[0].strip() != os.environ.get('TAKU_AVD', 'Taku_SkillsLifecycle_20261030'):
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
    row = catalog()
    if row['installed']: raise RuntimeError('fixture already installed')
    available = fixture_status('Available to install', row)
    adb('shell', 'am', 'start', '-S', '-W', '-a', 'android.intent.action.VIEW', '-n', 'org.agentos.agent/.MainActivity',
        '-d', "'taku-agent:///assistant-management?section=codex-plugins'", stdout=subprocess.DEVNULL)
    wait('Codex plugins')
    tap('Install plugin', available)
    wait('Install Codex plugin?')
    tap('android:id/button1')
    installed = True
    wait_catalog(True, True)
    row = catalog()
    enabled = fixture_status('Enabled', row)
    disabled = fixture_status('Disabled', row)
    print('PASS GUI install confirmed through CLI', flush=True)
    tap('Disable plugin', enabled)
    wait_catalog(True, False)
    print('PASS GUI disable confirmed through CLI', flush=True)
    tap('Enable plugin', disabled)
    wait_catalog(True, True)
    print('PASS GUI enable confirmed through CLI', flush=True)
    cli('disable', plugin)
    wait(disabled, scroll=True)
    cli('enable', plugin)
    wait(enabled, scroll=True)
    print('PASS open Expo page follows CLI state changes', flush=True)
    tap('Uninstall plugin', enabled)
    wait('Uninstall Codex plugin?')
    tap('android:id/button1')
    wait_catalog(False); installed = False
    print('PASS GUI uninstall confirmed through CLI', flush=True)
finally:
    if installed:
        try: cli('uninstall', plugin)
        except Exception as error: print('cleanup failed:', error, file=sys.stderr)
    adb('shell', 'rm', '-f', market)
    adb('shell', 'rm', '-f', '/data/local/tmp/taku-plugin-gui.xml')
