#!/usr/bin/env python3
"""Post-ROM shared-settings UI and live text-route checks; never enable Memory capture."""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('serial')
p.add_argument('--allow-physical', action='store_true')
p.add_argument('--cold-report', type=Path, required=True)
p.add_argument('--before', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
if not a.serial.startswith('emulator-') and not a.allow_physical:
    p.error('Device owner authorization and --allow-physical required')
cold, before = json.loads(a.cold_report.read_text()), json.loads(a.before.read_text())
assert cold['status'] == 'cold_runtime_verified_model_test_pending'
a.output.mkdir(parents=True, exist_ok=True)
adb = ['adb', '-s', a.serial]
report = {'passed': False, 'checks': [], 'modelResponsesSimulated': False, 'memoryCollectionEnabledByTest': False}

def sh(*parts):
    return subprocess.check_output(adb + ['shell', shlex.join(parts)], text=True, timeout=60).strip()

def exchange(**request):
    return json.loads(sh('env', 'CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar', 'app_process', '/system/bin',
                         'org.agentos.tests.NativeSocketProbe', 'exchange', json.dumps(request, separators=(',', ':'))))

def policy():
    return exchange(op='systemAi', action='read')

def memory():
    raw = sh('content', 'call', '--user', '0', '--uri', 'content://org.agentos.memory', '--method', 'status', '--arg', '{}')
    result = json.loads(raw[raw.index('result=') + 7:raw.rindex('}]')])
    assert result['ok']
    return result['result']

def nodes():
    raw = sh('am', 'instrument', '-w', 'org.agentos.inputmethod.probe/.UiProbe')
    prefix = 'INSTRUMENTATION_RESULT: nodes='
    line = next((s[len(prefix):] for s in raw.splitlines() if s.startswith(prefix)), '[]')
    return [n for n in json.loads(line) if n.get('package') in ['com.android.settings', 'org.agentos.assistant', 'org.agentos.inputmethod', 'org.agentos.memory']]

def match(text, data=None):
    return next((n for n in (nodes() if data is None else data) if text in [n.get('text'), n.get('content-desc')]), None)

def tap(node):
    assert node
    x, y, z, w = map(int, re.findall(r'\d+', node['bounds']))
    assert z > x and w > y
    sh('input', 'tap', str((x+z)//2), str((y+w)//2))

def wait(test, seconds=12):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        if value := test():
            return value
        time.sleep(.25)
    raise AssertionError('Expected shared settings state missing')

def reveal(text):
    # Only visible accessibility nodes are returned. Account and app rows can
    # push feature links below the fold on the physical device.
    for _ in range(6):
        if node := match(text):
            return node
        sh('input', 'swipe', '540', '1900', '540', '900', '300')
        time.sleep(.25)
    raise AssertionError('Shared settings link missing after scrolling: ' + text)

def shot(name):
    (a.output / (name + '.png')).write_bytes(subprocess.check_output(adb + ['exec-out', 'screencap', '-p']))

def passed(text):
    report['checks'].append(text)
    print('PASS ' + text, flush=True)

try:
    assert sh('cat', '/proc/sys/kernel/random/boot_id') == cold['bootId'], 'Cold gate belongs to a different boot'
    state = policy()
    assert {k: state[k] for k in ['enabled', 'textModel', 'imageModel', 'apps']} == before['systemAi']
    assert state['connected']
    for consumer, role, key in [('ime', 'image', 'imageModel'), ('memory', 'text', 'textModel')]:
        resolved = exchange(op='systemAi', action='resolve', consumer=consumer, role=role, model='')
        assert resolved['id'] == before['systemAi'][key]
    passed('Cold-boot IME image and Memory text routes resolve the preserved system defaults')
    subprocess.run(adb + ['shell', 'cmd', 'voiceinteraction', 'hide'], capture_output=True, timeout=15)
    sh('input', 'keyevent', 'BACK')
    sh('am', 'force-stop', 'com.android.settings')
    sh('am', 'start', '-W', '-f', '0x14000000', '-a', 'android.settings.SETTINGS')
    entry = None
    for _ in range(8):
        entry = match('Taku AI')
        if entry:
            break
        sh('input', 'swipe', '540', '1950', '540', '650', '300')
        time.sleep(.2)
    assert entry, 'Android Settings Taku AI tile missing'
    shot('01-android-settings-entry')
    tap(entry)
    wait(lambda: match('文字模型'))
    shot('02-system-ai-preserved')
    passed('Baked Android Settings tile opens the shared AI page')
    tap(reveal('Input method feature settings'))
    wait(lambda: any('跟随系统' in n.get('text', '') and 'gpt-6-luna' in n.get('text', '').lower() for n in nodes()))
    shot('03-ime-follows-system')
    passed('Baked IME settings display the inherited image model')
    tap(wait(lambda: match('Taku AI system settings')))
    wait(lambda: match('文字模型'))
    # Force-stop unregisters the package's enabled accessibility service.
    # Recreate only its activity task so observation keeps the owner's state.
    sh('am', 'start', '-W', '-f', '0x10008000', '-n', 'org.agentos.memory/.MemoryActivity', '--es', 'page', 'settings')
    wait(lambda: memory()['model'] == before['systemAi']['textModel'])
    cfg = memory()
    assert cfg['followsSystem'] and cfg['modelOverride'] == ''
    assert {k: cfg[k] for k in before['memory'] if k != 'model'} == {k: v for k, v in before['memory'].items() if k != 'model'}
    for _ in range(3):
        if any(('跟随系统' in n.get('text', '') or 'Follow system' in n.get('text', '')) and 'gpt-6-luna' in n.get('text', '') for n in nodes()):
            break
        sh('input', 'swipe', '540', '1900', '540', '900', '300')
    assert any(('跟随系统' in n.get('text', '') or 'Follow system' in n.get('text', '')) and 'gpt-6-luna' in n.get('text', '') for n in nodes())
    shot('04-memory-follows-system')
    passed('Memory resolves the preserved text model without changing capture, analysis or sources')
    # A synthetic root diagnostic tests the shared text route, not Memory observation/classification.
    text = exchange(op='systemAi', action='generate', consumer='memory', role='text', prompt='这是系统重启后的合成连通性测试。仅返回JSON：{"connected":true}，不要Markdown。')
    assert text['model'] == before['systemAi']['textModel']
    assert json.loads(text['text']) == {'connected': True}
    report['sharedTextDiagnostic'] = text
    passed('Shared text backend makes a real model request after reboot using the existing account')
    state = policy()
    assert {k: state[k] for k in ['enabled', 'textModel', 'imageModel', 'apps']} == before['systemAi']
    report['memory'] = {k: cfg[k] for k in before['memory']}
    report['passed'] = True
finally:
    (a.output / 'result.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
