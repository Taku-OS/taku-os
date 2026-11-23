#!/usr/bin/env python3
"""Exercise Expo dictation with quiet emulator input. No message is sent."""
import argparse
import json
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--thread', required=True, help='UUID of a test conversation on this emulator')
parser.add_argument('--adb', default='adb')
args = parser.parse_args()
if not re.fullmatch(r'emulator-[0-9]+', args.serial):
    parser.error('Select an explicit development emulator')
if not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', args.thread):
    parser.error('Select a test conversation UUID')


def shell(*command):
    return subprocess.check_output([args.adb, '-s', args.serial, 'shell', shlex.join(command)],
                                   text=True, stderr=subprocess.STDOUT, timeout=15).strip()


def content(authority, method, params):
    raw = shell('content', 'call', '--uri', f'content://{authority}', '--method', method,
                '--arg', json.dumps(params))
    match = re.fullmatch(r'Result: Bundle\[\{result=(.*)\}\]', raw)
    assert match, f'{method}: no response envelope'
    envelope = json.loads(match[1])
    assert envelope['ok'], (method, envelope.get('code'), envelope.get('message'))
    return envelope['result']


def voice(action, **params):
    return content('org.agentos.agent.home-assistant', 'assistant/chat-voice-' + action, params)['dictation']


def draft():
    return content('org.agentos.assistant.open-access', 'composer/read', {'threadId': args.thread})


def control(test_id):
    path = '/data/local/tmp/taku-audio-composer.xml'
    if 'dumped to:' not in shell('uiautomator', 'dump', path):
        return None
    return next((node for node in ET.fromstring(shell('cat', path)).iter('node')
                 if node.get('package') == 'org.agentos.agent'
                 and node.get('resource-id', '').split('/')[-1] == test_id), None)


def wait(probe, message):
    deadline = time.monotonic() + 12
    while time.monotonic() < deadline:
        found = probe()
        if found is not None:
            return found
        time.sleep(.2)
    raise AssertionError(message)


def phase(expected, previous_operation):
    state = voice('get')
    if state and state['threadId'] == args.thread and state['operationId'] != previous_operation:
        if state['phase'] == expected:
            return state
        assert state['phase'] != 'Closed', state['outcome']
    return None


def tap(node):
    left, top, right, bottom = map(int, re.findall(r'\d+', node.get('bounds')))
    shell('input', 'tap', str((left + right) // 2), str((top + bottom) // 2))


shell('am', 'start', '-W', '-a', 'android.intent.action.MAIN', '-n', 'org.agentos.agent/.MainActivity',
      '--es', 'threadId', args.thread)
button = wait(lambda: control('voice-input'), 'Composer voice control unavailable')
before = draft()
previous = voice('get') or {}
assert previous.get('phase') in (None, 'Idle', 'Closed'), 'Stop current dictation before running the smoke'
tap(button)
try:
    capturing = wait(lambda: phase('Capturing', previous.get('operationId')), 'GUI did not start recording')
    cancelled = voice('cancel', operationId=capturing['operationId'])
    assert cancelled['phase'] == 'Idle' and cancelled['operationId'] == capturing['operationId']
    wait(lambda: control('message-input'), 'GUI did not restore the composer after Agent cancellation')
    started = voice('start', threadId=args.thread)
    active = wait(lambda: phase('Capturing', capturing['operationId']), 'Agent did not start recording')
    assert active['operationId'] == started['operationId']
    tap(wait(lambda: control('dictation-stop'), 'Agent recording did not expose the GUI stop control'))
    finished = wait(lambda: phase('Closed', capturing['operationId']), 'GUI stop did not finish dictation')
    assert finished['operationId'] == started['operationId'] and finished['outcome']['kind'] == 'Success', finished['outcome']
    wait(lambda: control('message-input'), 'GUI did not restore the composer after finishing')
    after = draft()
    assert all(before[key] == after[key] for key in ('text', 'revision', 'attachments')), 'Quiet dictation changed the draft'
finally:
    current = voice('get') or {}
    if current.get('threadId') == args.thread and current.get('operationId') != previous.get('operationId'):
        voice('cancel', operationId=current['operationId'])

print(json.dumps({'passed': True, 'guiStartAgentCancel': True, 'agentStartGuiFinish': True, 'draftPreserved': True}))
