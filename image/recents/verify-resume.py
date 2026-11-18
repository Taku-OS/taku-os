#!/usr/bin/env python3
"""Regression for a dormant fallback RecentsActivity returning without a gesture handshake.
Uses an existing portrait emulator; never wipes data, installs packages or removes tasks.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--adb', default='adb')
p.add_argument('--out', type=Path, required=True)
p.add_argument('--cycles', type=int, default=3)
a = p.parse_args()
if not a.serial.startswith('emulator-') or not 1 <= a.cycles <= 10:
    p.error('Use an existing emulator and 1–10 cycles')
a.out.mkdir(parents=True, exist_ok=True)
adb = [a.adb, '-s', a.serial]

def run(*args):
    return subprocess.check_output(adb + list(args), text=True, timeout=20)

def shell(*args):
    return run('shell', *args)

def focus():
    return shell('dumpsys', 'window', 'displays')

def wait_focus(name):
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        if any(name in line for line in focus().splitlines() if 'mCurrentFocus=' in line):
            return
        time.sleep(.2)
    raise AssertionError('Focus did not reach ' + name)

def shot(name):
    data = subprocess.check_output(adb + ['exec-out', 'screencap', '-p'], timeout=20)
    (a.out / (name + '.png')).write_bytes(data)

def layout():
    return shell('content', 'call', '--uri', 'content://org.agentos.agent.home-layout',
                 '--method', 'layout/get', '--arg', '{}')

def thumbnail_log():
    return shell('logcat', '-d', '-s', 'TakuRecents:I', 'TaskThumbnailView:D', '*:S')

before = layout()
size = re.findall(r'(\d+)x(\d+)', shell('wm', 'size'))[-1]
w, h = map(int, size)
assert h > w, 'Portrait required'
shell('am', 'start', '-W', '--display', '0', '-n', 'com.android.deskclock/.DeskClock')
wait_focus('com.android.deskclock/')
shell('content', 'call', '--uri', 'content://com.android.launcher3.taku.recents', '--method', 'show')
wait_focus('RecentsActivity')
time.sleep(1)
tasks = shell('dumpsys', 'activity', 'activities')
match = re.search(r'Task\{[^\n]*#(\d+) type=recents', tasks)
assert match, 'No native overview task'
recents_id = match.group(1)
results = []
try:
    for cycle in range(a.cycles):
        shell('input', '-d', '0', 'tap', str(round(w * .88)), str(round(h * .91)))
        wait_focus('org.agentos.agent.home.HomeActivity')
        time.sleep(.8)  # Allow HOME transition to unbind the old previews.
        shot(f'{cycle}-home')
        previous_log = thumbnail_log()
        shell('am', 'task', 'focus', recents_id)
        wait_focus('RecentsActivity')
        time.sleep(1)
        shot(f'{cycle}-restored')
        shell('uiautomator', 'dump', '/data/local/tmp/taku-recents-resume.xml')
        xml = shell('cat', '/data/local/tmp/taku-recents-resume.xml')
        (a.out / f'{cycle}-restored.xml').write_text(xml)
        labels = [n.get('text', '') + ' ' + n.get('content-desc', '')
                  for n in ET.fromstring(xml).iter('node')]
        assert any('Clock' in label for label in labels), 'Clock accessibility label is missing'
        # Labels can survive while their drawable is cleared. Require fresh bindings
        # on every cycle and retain screenshots for actual icon/preview inspection.
        log = thumbnail_log()
        (a.out / f'{cycle}-log.txt').write_text(log)
        marker = 'Restoring idle foreground overview'
        assert log.count(marker) > previous_log.count(marker), 'Recovery path was not exercised'
        assert log.count('to: SnapshotSplash') > previous_log.count('to: SnapshotSplash'), \
            'No fresh task preview binding observed'
        results.append({'cycle': cycle, 'nativeTaskId': int(recents_id), 'restored': True})
    assert layout() == before, 'Desktop layout changed'
    (a.out / 'result.json').write_text(json.dumps({'cycles': results, 'layoutUnchanged': True}, indent=2)+'\n')
    print(json.dumps({'cycles': len(results), 'layoutUnchanged': True}))
finally:
    shell('input', '-d', '0', 'keyevent', 'KEYCODE_HOME')
