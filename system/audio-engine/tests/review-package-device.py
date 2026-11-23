#!/usr/bin/env python3
"""Verify Audio launcher/routes and live native package broadcasts on an offline disposable AVD."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
PACKAGE = 'org.agentos.audio'
HOME = PACKAGE + '/.AudioHomeActivity'
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
parser.add_argument('--output', required=True, type=Path)
parser.add_argument('--apk', type=Path, default=ROOT / 'system/audio-engine/out/TakuAudioEngine.apk')
args = parser.parse_args()


def adb(*command):
    return subprocess.check_output([args.adb, '-s', args.serial, *command], text=True).strip()


assert args.serial.startswith('emulator-')
assert adb('emu', 'avd', 'name').splitlines()[0].startswith('Taku_Audio_ROM_')
assert adb('shell', 'pm', 'path', PACKAGE).startswith('package:')
assert args.apk.is_file()
assert adb('shell', 'settings', 'get', 'global', 'wifi_on') == '0'
assert adb('shell', 'settings', 'get', 'global', 'mobile_data') == '0'
args.output.mkdir(parents=True, exist_ok=True)


def capture(name):
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/review-package.xml')
    path = args.output / (name + '.xml')
    adb('pull', '/data/local/tmp/review-package.xml', str(path))
    return list(ET.parse(path).iter('node'))


def swipe(nodes, down=True):
    left, top, right, bottom = map(int, re.findall(r'\d+', nodes[0].get('bounds')))
    x, height = (left + right) // 2, bottom - top
    start, end = top + height * 4 // 5, top + height // 3
    if not down:
        start, end = end, start
    adb('shell', 'input', 'swipe', str(x), str(start), str(x), str(end), '200')


def tap(node):
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((left + right) // 2), str((top + bottom) // 2))


def short_component(component):
    package, activity = component.split('/', 1)
    return package + '/' + (activity[len(package):] if activity.startswith(package + '.') else activity)


def wait_component(expected):
    for _ in range(30):
        output = adb('shell', 'dumpsys', 'activity', 'activities')
        resumed = re.findall(r'(?:topResumedActivity|mTopResumedActivity|mResumedActivity)\s*[:=].*?\s([\w.]+/[\w.$]+)', output)
        if resumed and short_component(resumed[0]) == expected:
            return
        time.sleep(.1)
    raise AssertionError(f'Expected resumed component {expected}; observed {resumed}')


def resolved_launcher():
    output = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-p', PACKAGE)
    components = re.findall(r'(?m)^([\w.]+/[\w.$]+)$', output)
    return short_component(components[-1]) if components else None


def launch(*extras):
    component = resolved_launcher()
    assert component is not None, 'Audio launcher entry is unavailable'
    adb('shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', '-n', component, '-f', '0x10200000', *extras)


def idle(name):
    output = subprocess.check_output(['node', 'sdk/cli/taku.mjs', 'call', 'audio.capabilities', '--transport', 'adb', '--serial', args.serial, '--adb', args.adb], cwd=ROOT, text=True)
    response = json.loads(output)
    assert response['ok'] is True, response
    assert response['result']['resourceState']['busy'] is False, f'{name}: navigation opened an audio session'


def control(name, resource):
    scrolled_for_target = False
    for attempt in range(12):
        nodes = capture(name)
        node = next((node for node in nodes if node.get('resource-id') == PACKAGE + ':id/' + resource and node.get('enabled') == 'true'), None)
        if node is not None:
            bounds = list(map(int, re.findall(r'\d+', node.get('bounds'))))
            screen = list(map(int, re.findall(r'\d+', nodes[0].get('bounds'))))
            if not scrolled_for_target and bounds[3] > screen[1] + (screen[3] - screen[1]) * 4 // 5:
                scrolled_for_target = True
                swipe(nodes)
                continue
            return node
        swipe(nodes, down=attempt < 8)
        time.sleep(.2)
    raise AssertionError(f'{name}: control {resource} unavailable')


def open_access(authority, method):
    raw = adb('shell', 'content', 'call', '--uri', f'content://{authority}', '--method', method)
    match = re.fullmatch(r'Result: Bundle\[\{result=(.*)\}\]', raw)
    assert match, f'{method}: no response envelope'
    envelope = json.loads(match[1])
    assert envelope['ok'], (method, envelope.get('code'))
    return envelope['result']


def inspect_entry(name, available):
    detail = 'Voice, models, translation, and local reading' if available else 'Install or enable Taku Audio first'
    for attempt in range(14):
        nodes = capture(name)
        row = next((node for node in nodes if node.get('package') == 'org.agentos.agent'
                    and node.get('content-desc', '').startswith('Taku Audio, ' + detail)
                    and node.get('clickable') == 'true'), None)
        if row is not None:
            settings = open_access('org.agentos.assistant.open-access', 'readSystemAi')
            home = open_access('org.agentos.agent.home-layout', 'apps/list')
            if settings['audioAvailable'] == available and any(app['package'] == PACKAGE for app in home['apps']) == available:
                return row
        else:
            swipe(nodes, down=attempt < 9)
        time.sleep(.3)
    raise AssertionError(f'{name}: Expo, System AI and Home catalog did not report Audio availability {available}')


reports = []
try:
    assert resolved_launcher() == HOME, 'Audio launcher discovery must resolve to the hub'
    adb('shell', 'am', 'force-stop', PACKAGE)
    launch()
    wait_component(HOME)
    idle('launcher')
    for page, activity in [('media', 'MediaTranslationActivity'), ('call', 'MediaTranslationActivity'), ('reading', 'AudioSettingsActivity'), ('status', 'AudioStatusActivity')]:
        tap(control('hub-' + page, 'audio_' + page))
        wait_component(PACKAGE + '/.' + activity)
        control('route-' + page, 'audio_' + page + '_page')
        idle(page)
        if page in ('media', 'call'):
            launch()
        else:
            adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        wait_component(HOME)
        reports.append({'page': page, 'component': activity, 'navigationKeptAudioIdle': True, 'returnedToHub': True})
    for page, activity in [('reading', 'AudioSettingsActivity'), ('status', 'AudioStatusActivity'), ('media', 'MediaTranslationActivity'), ('call', 'MediaTranslationActivity'), ('falCredential', 'AudioHomeActivity')]:
        adb('shell', 'am', 'force-stop', PACKAGE)
        launch('--es', 'page', page, '--ez', 'quickStart', 'true')
        wait_component(PACKAGE + '/.' + activity)
        control('page-extra-' + page, 'audio_' + ('home' if page == 'falCredential' else page) + '_page')
        idle('page-extra-' + page)
    reports.append({'closedPageExtras': True, 'pageNavigationKeptAudioIdle': True, 'launcherReentryReachedHub': True})
    adb('shell', 'am', 'start', '-W', '-a', 'org.agentos.action.AI_SETTINGS', '-p', 'org.agentos.agent')
    wait_component('org.agentos.agent/.MainActivity')
    inspect_entry('settings-installed', True)
    packages = ('org.agentos.agent', 'org.agentos.assistant')
    pids = [adb('shell', 'pidof', package) for package in packages]
    assert 'Success' in adb('shell', 'pm', 'uninstall', '-k', '--user', '0', PACKAGE)
    inspect_entry('settings-removed', False)
    assert resolved_launcher() is None, 'Removed Audio package still has a launcher entry'
    assert [adb('shell', 'pidof', package) for package in packages] == pids
    adb('install', '--no-incremental', '-r', str(args.apk))
    node = inspect_entry('settings-restored', True)
    assert [adb('shell', 'pidof', package) for package in packages] == pids
    assert resolved_launcher() == HOME
    tap(node)
    wait_component(HOME)
    idle('expo-settings')
    reports.append({'page': 'expo-settings', 'sameProcesses': True, 'removedAndRestoredWithoutNavigation': True,
                    'homeCatalogParity': True, 'navigationComponent': HOME})
    report = {'passed': True, 'launcherDiscovered': True, 'cloudTranslationVerified': False, 'reports': reports}
    (args.output / 'packages.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
finally:
    adb('install', '--no-incremental', '-r', str(args.apk))
