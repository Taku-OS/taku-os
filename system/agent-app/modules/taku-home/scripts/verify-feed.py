#!/usr/bin/env python3
"""Test the production push-only Feed on an explicitly selected isolated emulator.
Uses temporary, clearly labelled cards; never resets Feed or desktop state.
No model calls: a supplied card tests feed/generate's validation and commit path.
"""
import argparse
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', required=True)
parser.add_argument('--output', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Use an explicitly selected isolated emulator')
adb = [args.adb, '-s', args.serial]
out = Path(args.output)
out.mkdir(parents=True, exist_ok=True)
owned = []
proof = {}


def shell(*parts):
    return subprocess.check_output(adb + ['shell', shlex.join(parts)], text=True, timeout=120).strip()


def call(method, params=None, authority='org.agentos.agent.home-feed'):
    raw = shell('content', 'call', '--uri', 'content://' + authority,
                '--method', method, '--arg', json.dumps(params or {}))
    response = json.JSONDecoder().raw_decode(raw[raw.index('{"'):])[0]
    assert response['ok'], response
    return response['result']


def ui(name):
    shell('uiautomator', 'dump', '/sdcard/taku-feed-verification.xml')
    raw = shell('cat', '/sdcard/taku-feed-verification.xml')
    (out / (name + '.xml')).write_text(raw)
    return ET.fromstring(raw)


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    return str((x1 + x2) // 2), str((y1 + y2) // 2)


def card(index):
    return {
        'schema': 'taku.feed-card/1', 'id': prefix + str(index),
        'source': {'id': 'agent', 'app': 'Integration test'}, 'rank': 1e9 + index,
        'summary': 'Temporary Feed integration check.',
        'pin': {'label': 'Integration check ' + str(index), 'icon': {'kind': 'pixel', 'glyph': 'chat'}},
        'ui': {'catalog': 'taku-feed-1', 'state': {}, 'spec': {'root': 'text', 'elements': {
            'text': {'type': 'Text', 'props': {'text': 'Temporary Feed integration check.',
                                             'variant': 'body-lg-medium'}, 'children': []}}}},
        'actions': {},
    }


prefix = 'feed-check-' + uuid.uuid4().hex[:12] + '-'
services = shell('settings', 'get', 'secure', 'enabled_accessibility_services')
try:
    initial = call('feed/list')
    assert initial['sources'] == [] and initial['endReached'] and not initial['loading'], initial
    assert all(c['source'] != 'sample' for c in initial['cards'] + initial['pins'])
    proof['initial'] = initial
    for index in range(2):
        c = card(index)
        owned.append(c['id'])
        call('feed/publish', {'card': c})
    first, second = owned
    call('feed/pin', {'id': first})
    call('feed/pin', {'id': second})
    pins = [c['id'] for c in call('feed/list')['pins']]
    reordered = [first, second] + [p for p in pins if p not in owned]
    call('feed/reorder-pins', {'ids': reordered})
    assert [c['id'] for c in call('feed/list')['pins']] == reordered
    call('feed/unpin', {'id': second})
    call('feed/remove', {'id': second})
    shell('am', 'force-stop', 'org.agentos.agent')
    shell('am', 'start', '-n', 'org.agentos.agent/.home.HomeActivity')
    restored = call('feed/list')
    assert first in [c['id'] for c in restored['pins']] and second in restored['removed']
    call('feed/restore', {'id': second})
    generated = call('feed/generate', {'card': card(2)})
    owned.append(generated['id'])
    call('feed/retract', {'id': generated['id']})
    owned.remove(generated['id'])
    proof['generated'] = generated
    width, height = map(int, re.search(r'(\d+)x(\d+)', shell('wm', 'size')).groups())
    shell('input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    shell('input', 'swipe', str(width // 5), str(height // 2), str(width * 4 // 5), str(height // 2), '350')
    time.sleep(1)
    nodes = ui('feed')
    # The shell owns card gestures. Fabric descendants can be absent from the
    # accessibility dump while Compose records the view into its graphics layer.
    target = next(n for n in nodes.iter('node') if n.get('content-desc') ==
                  'Integration test: Temporary Feed integration check.')
    subprocess.check_call(adb + ['emu', 'screenrecord', 'screenshot', str(out.resolve())])
    x, y = center(target)
    shell('input', 'swipe', x, y, x, y, '850')
    menu = ui('long-press')
    labels = {n.get('text') for n in menu.iter('node')}
    assert {'Remove', 'Pin to top', 'Ask Taku'} <= labels, labels
    target = next(n for n in menu.iter('node') if n.get('text') == 'Pin to top')
    shell('input', 'tap', *center(target))
    pinned = call('feed/list')
    assert second in [c['id'] for c in pinned['pins']]
    stream = shell('content', 'read', '--uri',
                   'content://org.agentos.agent.home-feed/events?after=' + initial['eventCursor'] + '&follow=0&op=pin&origin=gui')
    frames = [json.loads(line) for line in stream.splitlines() if line.startswith('{')]
    assert frames[-1]['type'] == 'checkpoint'
    assert any(a.get('cardId') == second and a.get('op') == 'pin' for a in frames)
    proof['guiPin'] = pinned
    proof['passed'] = True
finally:
    for cid in owned:
        call('feed/retract', {'id': cid})
    proof['final'] = call('feed/list')
    # UIAutomator can disconnect accessibility services. Restore the prior enabled
    # service list by rebinding it after the final UI dump.
    component = 'org.agentos.memory/.ObservationService'
    if component in services.split(':'):
        shell('settings', 'put', 'secure', 'enabled_accessibility_services',
              ':'.join(s for s in services.split(':') if s != component))
        shell('settings', 'put', 'secure', 'enabled_accessibility_services', services)
    (out / 'proof.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n')
print('PASS: push-only Feed, pin/reorder/hide/restore, persistence, generate, GUI long press and Pin; test cards removed.')
