#!/usr/bin/env python3
"""Read actual daily-app screens; never seed memory rows, model answers, or Agent tasks.

Prepare local demo content in the actual apps first. This requires the designated
AVD, an idle Agent, existing model credentials, and explicit live-call opt-in.
Use separate capture/classify/finish stages so acceptance happens through the UI.
"""
import argparse, json, os, re, shlex, subprocess, time
from pathlib import Path

ROOT = Path(os.environ.get('TAKU_MEMORY_DAILY_ROOT', Path(__file__).resolve().parents[1]))
OUT = Path(os.environ.get('TAKU_MEMORY_DAILY_OUT', ROOT / 'out/memory-daily-device'))
ADB = os.environ.get('ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
SOURCES = {'chat': 'com.android.messaging', 'calendar': 'org.fossify.calendar', 'travel': 'app.organicmaps.web'}
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial', required=True)
p.add_argument('--phase', choices=['capture', 'classify', 'finish', 'no-offer'], required=True)
p.add_argument('--scenario', choices=list(SOURCES), required=True)
p.add_argument('--query', default='')
p.add_argument('--expected-focus', choices=['meal_checklist', 'event_prep', 'travel_outline', 'key_points', 'commitment_checklist'])
p.add_argument('--allow-live-model-calls', action='store_true')
p.add_argument('--wake-jobs', action='store_true', help='Wake Android jobs; merge/budget/lease rules still apply')
a = p.parse_args()
OUT.mkdir(parents=True, exist_ok=True)

def adb(*args):
    r = subprocess.run([ADB, '-s', a.serial, *map(str, args)], stdin=subprocess.DEVNULL,
                       capture_output=True, text=True, timeout=50)
    if r.returncode:
        raise RuntimeError(r.stderr[:300] or r.stdout[:300])
    return r.stdout.strip()

def sh(*args): return adb('shell', shlex.join(map(str, args)))
def provider(method, args=None, authority='org.agentos.memory'):
    raw = sh('content', 'call', '--user', '0', '--uri', 'content://' + authority,
             '--method', method, '--arg', json.dumps(args or {}, ensure_ascii=False))
    result = json.loads(raw[raw.index('result=') + 7:raw.rindex('}]')])
    assert result['ok'], result
    return result['result']
def gateway(action, args):
    return provider('memory', dict(args, action=action), 'org.agentos.assistant.memory-gateway')
def save(name, obj): (OUT / name).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n')
def load(name): return json.loads((OUT / name).read_text())
def wait_for(predicate, seconds=42):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = predicate()
        if result: return result
        time.sleep(.6)
    raise AssertionError('Stage still pending; inspect status before retrying this stage')
def shot(name):
    path = '/data/local/tmp/taku-memory-daily-' + name + '.png'
    sh('screencap', '-p', path); adb('pull', path, OUT / (name + '.png')); sh('rm', path)
def tasks():
    return set(sh('sh', '-c', 'for d in /data/user/0/org.agentos.assistant/files/runtime/tasks/*; do [ -f "$d/ready" ] && echo "${d##*/}"; done; true').split())
def read_record(): return provider('get', {'id': load(a.scenario + '-stage.json')['id']})

assert a.serial == 'emulator-5634'
assert sh('getprop', 'ro.boot.qemu.avd_name') == 'Taku_ImageIntegration_20261010'
assert (OUT / 'baseline.json').exists(), 'Snapshot original configuration/accessibility/pause state first'
if a.phase == 'capture':
    assert a.query, 'Use a distinctive phrase already visible in the actual app'
    cfg = provider('status')
    assert cfg['observationConnected'], 'Reconnect observation after the last UIAutomator dump'
    assert not cfg['analysisEnabled'], 'Capture stage must prove local saving with model analysis off'
    assert SOURCES[a.scenario] in cfg['sources'] and cfg['enabled']
    before_attempts = cfg['analysisBatchAttempts']
    record = wait_for(lambda: next((r for r in provider('search', {'query': a.query, 'limit': 20})['items']
                                    if r['source'] == SOURCES[a.scenario] and r['analysis'] == 'pending'), None))
    assert provider('status')['analysisBatchAttempts'] == before_attempts
    shot('source-' + a.scenario + '-en')
    save(a.scenario + '-stage.json', dict(record, taskBaseline=sorted(tasks()),
         automaticCapture=True, fixtureAppUsed=False, directDatabaseSeeding=False,
         modelCallsDuringCapture=0, localDemoData=True))
    print('Actual app captured locally:', record['source'], record['id'], flush=True)
elif a.phase == 'classify':
    assert a.allow_live_model_calls, 'Explicit live model opt-in required'
    stage = load(a.scenario + '-stage.json')
    assert not read_record()['task_id'], 'Already accepted; use finish instead'
    cfg = provider('status')
    changes = {k: True for k in ['analysisEnabled', 'queryEnabled', 'proactive'] if not cfg[k]}
    if changes: provider('configure', changes)
    if a.wake_jobs:
        for job in [4102, 4103]:
            try: sh('cmd', 'jobscheduler', 'run', '-f', 'org.agentos.memory', job)
            except RuntimeError: pass
    record = wait_for(lambda: (lambda r: r if r['analysis'] == 'ready' else None)(read_record()))
    assert record['source'] == SOURCES[a.scenario] and record['revision'] == stage['revision']
    assert record['model'] == provider('status')['model']
    assert tasks() == set(stage['taskBaseline']), 'A task appeared before UI acceptance'
    save(a.scenario + '-classified.json', dict(record, modelResponseSimulated=False,
         androidJobWakeRequested=a.wake_jobs, noTaskBeforeConsent=True))
    shot(a.scenario + '-suggestion-en')
    print('Real Jev:', record['category'], record['decision'], record['confidence'], flush=True)
    print('Accept the actual system hint before finish.' if record['suggested_at'] else 'No actionable offer: use no-offer to verify the quiet outcome.', flush=True)
elif a.phase == 'no-offer':
    stage = load(a.scenario + '-stage.json')
    record = read_record()
    assert record['analysis'] == 'ready' and record['suggested_at'] == 0
    assert record['decision'] == 'none' or record['confidence'] < .75
    assert not record['task_id'] and tasks() == set(stage['taskBaseline'])
    save(a.scenario + '-quiet-proof.json', dict(passed=True, id=record['id'], source=record['source'],
         model=record['model'], category=record['category'], decision=record['decision'],
         confidence=record['confidence'], noOffer=True, noAgentTask=True, modelResponseSimulated=False,
         localDemoData=True, physicalDevice=False, fullRom=False))
    print('Actual app classified; non-actionable evidence correctly creates no hint or Agent task.', flush=True)
else:
    stage = load(a.scenario + '-stage.json')
    record = wait_for(lambda: (lambda r: r if r['task_id'] else None)(read_record()))
    task = wait_for(lambda: (lambda t: t if t['state'] in ['completed', 'failed', 'needs_user'] else None)(gateway('task', {'id': record['id']})))
    assert task['state'] == 'completed', task['state']
    assert len(tasks() - set(stage['taskBaseline'])) == 1
    info = json.loads(task['runtime-info.json'])
    assert not info['screenshotAttached']
    request = json.loads(sh('cat', '/data/user/0/org.agentos.assistant/files/runtime/tasks/' + record['task_id'] + '/request.json'))
    assert request['memoryId'] == record['id'] and request['permissionMode'] == 'readOnly'
    assert request['responseLocale'] == 'en'
    if a.expected_focus:
        assert request.get('memoryFocus') == a.expected_focus, request.get('memoryFocus')
    history = json.loads(sh('/system/bin/env', 'CLASSPATH=/data/local/tmp/taku-jev-live.jar',
        'app_process', '/system/bin', 'org.agentos.tests.NativeSocketProbe', 'rpc', 'thread/read',
        json.dumps({'threadId': info['threadId'], 'includeTurns': True})))
    calls = [item for turn in history['thread']['turns'] for item in turn['items'] if item.get('type') == 'dynamicToolCall']
    gets = [item for item in calls if item.get('namespace') == 'taku_phone' and item.get('tool') == 'memory_get'
            and item.get('arguments', {}).get('id') == record['id'] and item.get('status') == 'completed' and item.get('success')]
    assert gets, 'No actual successful memory_get for this record'
    evidence = json.loads(next(item['text'] for item in gets[0]['contentItems'] if item.get('type') == 'inputText'))
    assert evidence['source'] == SOURCES[a.scenario] and evidence['revision'] == stage['revision']
    assert evidence['body'] == stage['body'], 'Agent did not receive the exact captured evidence'
    save(a.scenario + '-native-thread.json', history)
    save(a.scenario + '-proof.json', dict(passed=True, id=record['id'], revision=evidence['revision'],
         source=evidence['source'], model=record['model'], category=record['category'], decision=record['decision'],
         confidence=record['confidence'], taskId=record['task_id'], threadId=info['threadId'],
         realMemoryToolCall=True, responseLocale='en', readOnly=True, screenshotAttached=False,
         memoryFocus=request.get('memoryFocus'),
         oneTaskAfterUiAcceptance=True, localDemoData=True, agentReply=task.get('result.txt', ''),
         physicalDevice=False, fullRom=False))
    print('Actual app → local memory → Jev → UI acceptance → TaskBroker → Agent memory_get: PASS', flush=True)
