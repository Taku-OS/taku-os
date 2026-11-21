#!/usr/bin/env python3
"""Synthetic legacy Feed migration on this task's isolated AVD only.

Seed only a freshly installed debug Home. Never reads another app's data, clears
data, removes a card, or invokes an available action. Run verify again after a
real cold AVD restart to exercise committed SQLite restoration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'org.agentos.agent'
AUTHORITY = 'content://org.agentos.agent.home-feed'
AVD = 'Taku_Character_Legacy_Feed_20261121'
ROOT = Path(__file__).resolve().parents[5]
OUT = ROOT / 'out/legacy-feed-compat-20261121/device'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def card(identity, actions, pinned=False):
    declared = {key: {'label': label, 'intent': intent} for key, label, intent in actions}
    elements = {'body': {'type': 'Column', 'props': {'gap': 12},
                         'children': ['title'] + [key for key, _, _ in actions]},
                'title': {'type': 'Text', 'props': {'text': 'Synthetic retained card'}, 'children': []}}
    for key, label, _ in actions:
        elements[key] = {'type': 'Button', 'props': {'label': label}, 'children': [],
                         'on': {'press': {'action': 'dispatch', 'params': {'actionId': key}}}}
    pin = {'label': 'Synthetic saved', 'icon': {'kind': 'pixel', 'glyph': 'chat'}}
    if pinned:
        pin['trailing'] = {'icon': 'play', 'actionId': actions[0][0]}
    return {'schema': 'taku.feed-card/1', 'id': identity, 'source': {'id': 'agent', 'app': 'Synthetic migration QA'},
            'layout': {'span': 'full', 'surface': 'card'}, 'rank': 1000, 'createdAt': 1,
            'summary': 'Synthetic retained card', 'pin': pin, 'ui': {'catalog': 'taku-feed-1',
            'state': {'synthetic': True}, 'spec': {'root': 'body', 'elements': elements}}, 'actions': declared}


def fixture():
    first = card('synthetic.saved.one', [('old_a', 'Saved pinned action', {'type': 'panel', 'panelId': 'synthetic.old.a', 'opaque': {'keep': 1}}),
                                         ('old_b', 'Another saved action', {'type': 'panel', 'panelId': 'synthetic.old.b'})], True)
    second = card('synthetic.saved.two', [('old_c', 'Saved action', {'type': 'panel', 'panelId': 'synthetic.old.c'}),
                                          ('valid', 'Available action', {'type': 'none'})])
    return {'version': 1, 'syntheticFixture': AVD, 'removed': ['synthetic.removed.' + str(i) for i in range(41)],
            'pins': ['synthetic.saved.one', 'synthetic.unresolved.a', 'synthetic.unresolved.b', 'synthetic.unresolved.c'],
            'defaultsApplied': True, 'published': [first, second],
            'actions': [{'at': i + 1, 'cardId': first['id'], 'actionId': 'old_a', 'detail': {'syntheticIndex': i}}
                        for i in range(50)]}


class Device:
    def __init__(self, serial):
        self.command = [str(Path.home() / 'Library/Android/sdk/platform-tools/adb'), '-s', serial]

    def run(self, *args, payload=None, timeout=90, check=True):
        result = subprocess.run(self.command + list(args), input=payload, capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise AssertionError('Assigned AVD command failed: ' + ' '.join(args[:2]) + ': ' + result.stderr.decode(errors='replace')[:250])
        return result

    def shell(self, *args):
        return self.run('shell', shlex.join(args)).stdout.decode().strip()

    def sandbox(self, command, payload=None):
        return self.run('shell', '-T', shlex.join(['run-as', PACKAGE, 'sh', '-c', command]), payload=payload).stdout

    def call(self, method, payload=None, ok=True):
        raw = self.shell('content', 'call', '--uri', AUTHORITY, '--method', method, '--arg', json.dumps(payload or {}))
        match = re.search(r'Result: Bundle\[\{result=(.*)\}\]', raw)
        assert match, 'Missing Feed JSON envelope'
        value = json.loads(match.group(1))
        assert value.get('ok') is ok, (method, value.get('code'))
        return value['result'] if ok else value


def seed(device, apk):
    present = device.run('shell', shlex.join(['pm', 'path', PACKAGE]), check=False).stdout.strip()
    assert not present, 'Refusing to replace existing AVD Home/test data'
    assert apk.is_file()
    result = device.run('install', '-r', str(apk)).stdout.decode()
    assert 'Success' in result
    state = fixture()
    raw = (json.dumps(state, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    OUT.mkdir(parents=True, exist_ok=True)
    db = OUT / 'synthetic-empty-home-feed.db'
    assert not db.exists(), 'Do not reuse or overwrite a previous seed'
    with sqlite3.connect(db) as connection:
        connection.executescript('''PRAGMA user_version=1;
CREATE TABLE feed_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE published_cards (ordinal INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, json TEXT NOT NULL);
CREATE TABLE feed_events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER, origin TEXT, op TEXT, card_id TEXT, action_id TEXT, json BLOB NOT NULL, byte_count INTEGER NOT NULL);
CREATE INDEX feed_events_card_sequence ON feed_events(card_id, sequence);
CREATE INDEX feed_events_origin_op_sequence ON feed_events(origin, op, sequence);''')
        assert connection.execute('PRAGMA quick_check').fetchone() == ('ok',)
    device.sandbox('mkdir -p files/home-feed databases')
    device.sandbox('test ! -e files/home-feed/state.json && cat > files/home-feed/state.json', raw)
    device.sandbox('test ! -e databases/home-feed.db && cat > databases/home-feed.db', db.read_bytes())
    (OUT / 'seed.json').write_text(json.dumps({'synthetic': True, 'avd': AVD, 'apk': str(apk), 'apkSha256': sha(apk.read_bytes()),
                                           'legacySha256': sha(raw), 'initialSqliteUserVersion': 1,
                                           'initialTablesEmpty': True}, indent=2) + '\n')
    print('Seeded synthetic legacy JSON and valid empty SQLite v1 in fresh debug Home only.')


def database_snapshot(device, label, expected):
    device.shell('am', 'force-stop', PACKAGE)
    folder = OUT / label
    folder.mkdir(exist_ok=True)
    for name in ['home-feed.db', 'home-feed.db-wal', 'home-feed.db-shm']:
        path = 'databases/' + name
        present = device.sandbox('if [ -f ' + shlex.quote(path) + ' ]; then echo yes; fi').strip() == b'yes'
        if present:
            (folder / name).write_bytes(device.sandbox('cat ' + shlex.quote(path)))
    with sqlite3.connect(folder / 'home-feed.db') as db:
        assert db.execute('PRAGMA quick_check').fetchone() == ('ok',)
        assert db.execute('PRAGMA user_version').fetchone() == (1,)
        meta = dict(db.execute('SELECT key,value FROM feed_meta'))
        prefs = json.loads(meta['preferences'])
        assert prefs['removed'] == expected['removed'] and prefs['pins'] == expected['pins'] and prefs['defaultsApplied'] is True
        cards = {identity: json.loads(raw) for identity, raw in db.execute('SELECT id,json FROM published_cards ORDER BY ordinal')}
        assert cards == {card['id']: card for card in expected['published']}, 'Stored original card payload changed'
        history = [json.loads(raw) for (raw,) in db.execute('SELECT json FROM feed_events ORDER BY sequence')]
        assert len(history) == 50 and [entry['legacy'] for entry in history] == expected['actions']
        assert meta['migration_complete'] == '1' and meta['legacy_imported_count'] == '50'
        return {'quickCheck': 'ok', 'databaseVersion': 1, 'published': len(cards), 'removed': len(prefs['removed']),
                'pins': len(prefs['pins']), 'history': len(history), 'epoch': meta['database_epoch'],
                'originalCardsAndActionPayloadsPreserved': True, 'historyImportedOnce': True}


def visible_disabled(element, parents):
    while element is not None:
        if element.get('enabled') == 'false':
            return True
        element = parents.get(element)
    return False


def gui(device, label):
    device.shell('am', 'start', '-n', PACKAGE + '/org.agentos.agent.home.HomeActivity')
    # Home opens on the app grid; reach the adjacent Feed by its normal gesture.
    time.sleep(2)
    device.shell('input', 'swipe', '140', '1200', '940', '1200', '350')
    deadline = time.monotonic() + 100
    notice = 'Some actions on this card are no longer available.'
    remote = '/sdcard/taku-feed-compat-' + label + '.xml'
    xml = '<hierarchy/>'
    while time.monotonic() < deadline:
        device.shell('uiautomator', 'dump', remote)
        capture = device.run('shell', shlex.join(['cat', remote]), check=False)
        if capture.returncode:
            time.sleep(1)
            continue
        xml = capture.stdout.decode()
        try:
            tree = ET.fromstring(xml)
        except ET.ParseError:
            time.sleep(1)
            continue
        parents = {child: node for node in tree.iter() for child in node}
        notices = [node for node in tree.iter('node') if notice in (node.get('text', ''), node.get('content-desc', ''))]
        old = [node for node in tree.iter('node') if 'Saved action' in (node.get('text', ''), node.get('content-desc', ''))]
        valid = [node for node in tree.iter('node') if 'Available action' in (node.get('text', ''), node.get('content-desc', ''))]
        if notices and old and valid and any(visible_disabled(node, parents) for node in old) and any(not visible_disabled(node, parents) for node in valid):
            (OUT / (label + '-ui.xml')).write_text(xml)
            (OUT / (label + '-ui.png')).write_bytes(device.run('exec-out', 'screencap', '-p').stdout)
            return {'actualHomeReactNativeBody': True, 'unavailableNoticeVisible': True,
                    'retiredControlDisabled': True, 'validControlStillEnabled': True}
        time.sleep(1)
    (OUT / (label + '-ui-last.xml')).write_text(xml)
    (OUT / (label + '-ui-last.png')).write_bytes(device.run('exec-out', 'screencap', '-p').stdout)
    raise AssertionError('Actual Home body did not expose notice + disabled old control + enabled valid control')


def verify(device, label, require_ui, action_probe):
    expected = fixture()
    seed_record = json.loads((OUT / 'seed.json').read_text())
    assert sha(device.sandbox('cat files/home-feed/state.json')) == seed_record['legacySha256']
    before = device.call('feed/list')
    head = before['eventCursor']
    assert head.endswith(':50') and len(before['pins']) == 1 and len(before['cards']) == 1
    retired = 0
    for card in expected['published']:
        result = device.call('feed/get', {'id': card['id']})
        assert result['card'] == card
        for action_id, action in card['actions'].items():
            availability = result['actionAvailability'][action_id]
            if action['intent']['type'] != 'panel':
                assert availability == {'available': True}
                continue
            retired += 1
            assert availability == {'available': False, 'code': 'ACTION_UNAVAILABLE', 'message': 'This action is no longer available.'}
            error = device.call('feed/action', {'id': card['id'], 'actionId': action_id}, ok=False)
            assert error['code'] == 'ACTION_UNAVAILABLE' and error['message'] == availability['message']
        error = device.call('feed/publish', {'card': card}, ok=False)
        assert error['code'] == 'INVALID_CARD'
    assert retired == 3
    invalid = json.loads(json.dumps(expected['published'][1]))
    invalid['actions']['old_c']['intent']['type'] = 'unavailable'
    assert device.call('feed/publish', {'card': invalid}, ok=False)['code'] == 'INVALID_CARD'
    assert device.call('feed/list')['eventCursor'] == head, 'Rejected actions/publication wrote an accepted event'
    report = {'synthetic': True, 'avd': AVD, 'label': label, 'retiredActions': retired,
              'strictNewPublication': True, 'noAcceptedEventForUnavailableActions': True, 'eventCursor': head,
              'legacyFileBytesPreserved': True}
    if require_ui:
        report.update(gui(device, label))
    if action_probe is not None:
        assert action_probe.is_file()
        assert 'Success' in device.run('install', '-r', str(action_probe)).stdout.decode()
        output = device.shell('am', 'instrument', '-w',
                              'org.agentos.agent.home.feedcompat.tests/org.agentos.agent.home.tests.FeedStoredActionProbe')
        match = re.search(r'^INSTRUMENTATION_RESULT: result=(.*)$', output, re.M)
        assert match, 'Missing synthetic action instrumentation report'
        actions = json.loads(match.group(1))
        assert actions.get('passed') is True, actions
        report['actionInstrumentation'] = actions
        (OUT / (label + '-action-probe.txt')).write_text(output)
    report.update(database_snapshot(device, label, expected))
    first = OUT / 'first-report.json'
    if label != 'first' and first.is_file():
        assert report['epoch'] == json.loads(first.read_text())['epoch'], 'Cold restore re-imported legacy input'
        report['sameEpochAfterColdRestore'] = True
    report['passed'] = True
    (OUT / (label + '-report.json')).write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True, choices=['emulator-5592'])
    parser.add_argument('--phase', required=True, choices=['seed', 'verify'])
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--label', default='first', choices=['first', 'cold'])
    parser.add_argument('--require-ui', action='store_true')
    parser.add_argument('--action-probe', type=Path, help='Optional normally built, matching-debug-signature test APK')
    args = parser.parse_args()
    device = Device(args.serial)
    assert device.run('emu', 'avd', 'name').stdout.decode().splitlines()[0].strip() == AVD
    assert device.shell('getprop', 'sys.boot_completed') == '1'
    OUT.mkdir(parents=True, exist_ok=True)
    if args.phase == 'seed':
        assert args.apk is not None, 'Explicit reviewed debug APK required'
        seed(device, args.apk.resolve())
    else:
        verify(device, args.label, args.require_ui, args.action_probe)


if __name__ == '__main__':
    main()
