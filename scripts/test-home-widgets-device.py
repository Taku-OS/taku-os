#!/usr/bin/env python3
"""Exercise standard widget CRUD on an explicitly selected isolated emulator.
Adds/removes only a temporary Date widget; preserves existing widget IDs and app order.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import time

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
parser.add_argument('--output', required=True)
args = parser.parse_args()
if not args.serial.startswith('emulator-'):
    parser.error('Use an isolated emulator serial')
adb = [args.adb, '-s', args.serial]
uri = 'content://org.agentos.agent.home-layout'
proof = {}


def shell(command):
    return subprocess.check_output(adb + ['shell', command], text=True, timeout=45)


def call(method, params=None, authority=uri, ok=True):
    raw = shell('content call --uri ' + authority + ' --method ' + method + ' --arg ' + shlex.quote(json.dumps(params or {})))
    result = json.loads(raw.split('result=', 1)[1].rsplit('}]', 1)[0])
    if ok:
        assert result['ok'], result
        return result['result']
    return result


def snapshot():
    return call('layout/get')['pages']


baseline = snapshot()
widget_id = None
try:
    proof['providers'] = call('widgets/providers')
    initial = call('widgets/list')['widgets']
    assert len(initial) >= 6, initial
    assert len({w['appWidgetId'] for w in initial}) == len(initial)
    for widget in initial:
        content = call('widgets/content', {'appWidgetId': widget['appWidgetId']})
        assert "Couldn't add widget" not in json.dumps(content), content
    proof['clock'] = call('get', authority='content://org.taku.widgets.data')
    proof['weather'] = call('get', authority='content://org.taku.weather.data')
    assert proof['weather']['city'] == 'Guangzhou'
    assert proof['weather']['available'], proof['weather']
    added = call('widgets/add', {'provider': 'org.taku.widgets/.DateWidget', 'page': len(baseline), 'spanX': 1, 'spanY': 1})
    widget_id = added['appWidgetId']
    assert added['status'] == 'added', added
    call('widgets/move', {'appWidgetId': widget_id, 'page': 0, 'index': 0})
    resized = call('widgets/resize', {'appWidgetId': widget_id, 'spanX': 2, 'spanY': 1})
    assert resized['spanX'] == 2 and resized['spanY'] == 1
    before_invalid = snapshot()
    invalid = call('widgets/resize', {'appWidgetId': widget_id, 'spanX': 5, 'spanY': 1}, ok=False)
    assert not invalid['ok'] and invalid['code'] == 'INVALID_ARGUMENT', invalid
    assert snapshot() == before_invalid
    # Move the temporary item back out so overflowed original entries can be restored below.
    call('widgets/remove', {'appWidgetId': widget_id})
    removed = widget_id
    widget_id = None
    # Restore original page/order through the same public mutation API, preserving folders.
    for page in baseline:
        for slot in page['items']:
            params = {'page': page['page'], 'index': slot['index']}
            if slot['type'] == 'widget':
                params['appWidgetId'] = slot['appWidgetId']; method = 'widgets/move'
            elif slot['type'] == 'folder':
                params['folderId'] = slot['id']; method = 'layout/move'
            else:
                params['key'] = slot['key']; method = 'layout/move'
            call(method, params)
    assert snapshot() == baseline
    shell('am force-stop org.agentos.agent')
    shell('am start -n org.agentos.agent/.home.HomeActivity')
    time.sleep(2)
    after = call('widgets/list')['widgets']
    assert {w['appWidgetId'] for w in after} == {w['appWidgetId'] for w in initial}
    assert removed not in {w['appWidgetId'] for w in after}
    assert snapshot() == baseline
    proof['result'] = 'PASS: add, move, resize, invalid-write atomicity, remove, process restart, layout preservation'
finally:
    if widget_id is not None:
        call('widgets/remove', {'appWidgetId': widget_id})
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(proof, ensure_ascii=False, indent=2) + '\n')
print(proof['result'])
