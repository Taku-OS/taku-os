#!/usr/bin/env python3
"""Dedicated screens, script S1: request, use, release, ask for more, turn end, and two threads queueing for one App.

No model: phone tools go through the broker's root-only test entry (op dedicatedScreensDebug), and Android
tasks are read with `am stack list`. Covers A1, A2, A3, A9, B4, C1, C2, R2 waiting_for_app and screen_status
(design doc section 2).

Needs: the device runs the broker built from this tree (system/assistant/out/task-broker.jar), adb is root,
/data/local/tmp/taku-workspace-tests.jar holds NativeSocketProbe, and Settings / Clock have no task on the
main screen (Android would otherwise move the user's task onto the dedicated screen; that is ticket 05).

    python3 system/assistant/tests/dedicated-screens/e2e/s1.py [serial]
"""
import hashlib, json, os, re, shlex, subprocess, sys, time, uuid

SERIAL = sys.argv[1] if len(sys.argv) > 1 else os.environ.get('TAKU_SERIAL', 'emulator-5582')
ADB = [os.environ.get('ADB', os.path.expanduser('~/Library/Android/sdk/platform-tools/adb')), '-s', SERIAL]
JAR = os.path.join(os.path.dirname(__file__), '../../../out/task-broker.jar')
SETTINGS, CLOCK, CONTACTS = 'com.android.settings', 'com.android.deskclock', 'com.android.contacts'
checks = 0


class Refused(Exception):
    pass


def check(value, message):
    global checks
    checks += 1
    if not value:
        raise AssertionError(message)


def shell(command):
    out = subprocess.run(ADB + ['shell', command], capture_output=True, text=True, timeout=70, stdin=subprocess.DEVNULL)
    if out.returncode != 0:
        raise Refused(out.stderr.strip() or out.stdout.strip())
    return out.stdout


def exchange(request):
    return json.loads(shell(shlex.join(['env', 'CLASSPATH=/data/local/tmp/taku-workspace-tests.jar', 'app_process',
                                        '/system/bin', 'org.agentos.tests.NativeSocketProbe', 'exchange',
                                        json.dumps(request)])))


def tool(thread, name, arguments=None):
    """Runs a phone tool as the thread; returns the JSON text it answers (or the raw result for screenshots)."""
    result = exchange({'op': 'dedicatedScreensDebug', 'action': 'tool', 'threadId': thread, 'tool': name,
                       'arguments': arguments or {}})
    check(result.get('success') is True, f'{name} failed: {result}')
    text = result['contentItems'][0]['text']
    return json.loads(text) if name != 'open_app' else text


def refused(code, thread, name, arguments=None):
    try:
        tool(thread, name, arguments)
    except Refused as error:
        check(code in str(error), f'{name}: expected {code}, got {error}')
        return
    raise AssertionError(f'{name}: expected {code}, but it ran')


def tool_in_background(thread, name, arguments):
    """Starts a phone tool call that may wait; finish() returns its JSON answer."""
    request = {'op': 'dedicatedScreensDebug', 'action': 'tool', 'threadId': thread, 'tool': name,
               'arguments': arguments}
    command = shlex.join(['env', 'CLASSPATH=/data/local/tmp/taku-workspace-tests.jar', 'app_process', '/system/bin',
                          'org.agentos.tests.NativeSocketProbe', 'exchange', json.dumps(request)])
    process = subprocess.Popen(ADB + ['shell', command], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                               stdin=subprocess.DEVNULL)

    def finish():
        out, err = process.communicate(timeout=40)
        check(process.returncode == 0, f'{name} failed: {err.strip() or out.strip()}')
        return json.loads(json.loads(out)['contentItems'][0]['text'])
    return process, finish


def until(probe, message, seconds=20):
    deadline = time.time() + seconds
    while time.time() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(0.3)
    raise AssertionError(message)


def turn_ended(thread):
    return exchange({'op': 'dedicatedScreensDebug', 'action': 'turnEnded', 'threadId': thread})


def rows(thread):
    return [r for r in exchange({'op': 'workspaces', 'action': 'list'})['screens'] if r['threadId'] == thread]


def tasks():
    """[(taskId, package, displayId)] from `am stack list`, each display top to bottom."""
    out, display = [], None
    for line in shell('am stack list').splitlines():
        m = re.search(r'^RootTask id=\d+ .*\bdisplayId=(-?\d+)', line)
        if m:
            display = int(m.group(1))
            continue
        m = re.search(r'^\s+taskId=(\d+): (\S+)', line)
        if m and m.group(2) != 'unknown':
            out.append((int(m.group(1)), m.group(2).split('/')[0], display))
    return out


def apps_on(display):
    return [pkg for _, pkg, d in tasks() if d == display]


def require_this_broker():
    local = hashlib.md5(open(JAR, 'rb').read()).hexdigest()
    device = shell('md5sum /system_ext/framework/agentos-task-broker.jar').split()[0]
    if local != device:
        sys.exit(f'device broker {device} is not this tree\'s {local}; not running')


def main():
    require_this_broker()
    on_main = set(apps_on(0))
    if on_main & {SETTINGS, CLOCK}:
        sys.exit(f'clear {sorted(on_main & {SETTINGS, CLOCK})} from the main screen first')

    t1 = str(uuid.uuid4())
    refused('APPS_NOT_REQUESTED', t1, 'observe_background')  # B1: no screen, no fallback to the default one
    got = tool(t1, 'request_apps', {'packages': [SETTINGS, CLOCK], 'taskTitle': 'S1 settings and clock'})
    key, display = got['screenKey'], got['displayId']
    check(display > 0 and sorted(got['heldApps']) == sorted([SETTINGS, CLOCK]), f'A1: {got}')
    check(json.loads(shell(f'/system_ext/bin/phone-display status {key}'))['displayId'] == display, 'A1: screen up')
    row = rows(t1)
    check(len(row) == 1 and row[0]['taskTitle'] == 'S1 settings and clock' and row[0]['status'] == 'running'
          and row[0]['packageName'] == '', f'R2: {row}')

    tool(t1, 'open_app', {'packageName': SETTINGS})
    tool(t1, 'open_app', {'packageName': CLOCK})
    check(apps_on(display)[:2] == [CLOCK, SETTINGS], f'both open on the dedicated screen: {tasks()}')
    check(rows(t1)[0]['packageName'] == CLOCK, 'R2: packageName is the App on top')
    refused('APP_NOT_HELD', t1, 'open_app', {'packageName': CONTACTS})  # B4
    shot = exchange({'op': 'dedicatedScreensDebug', 'action': 'tool', 'threadId': t1, 'tool': 'observe_background'})
    check(any(i.get('type') == 'inputImage' for i in shot['contentItems']), 'B6: a screenshot comes back')

    released = tool(t1, 'release_apps', {'packages': [SETTINGS]})
    check(released['released'] == [SETTINGS] and released['heldApps'] == [CLOCK], f'C1: {released}')
    check(apps_on(display) == [CLOCK], f'C1: only Settings\' task removed: {tasks()}')
    check(set(apps_on(0)) == on_main, 'C1: the main screen untouched')

    # A9: holding Clock, ask for Settings as well: given at once, Clock's page stays
    more = tool(t1, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S1 clock, then settings'})
    check(more['screenKey'] == key and more['heldApps'] == [CLOCK, SETTINGS], f'A9: {more}')
    check(apps_on(display) == [CLOCK], f'A9: Clock\'s page is still there: {tasks()}')

    check(turn_ended(t1)['released'] is True, 'C2: the turn ends')
    check(apps_on(display) == [] and rows(t1) == [], f'C2: screen cleared and idle: {tasks()}')
    check(json.loads(shell(f'/system_ext/bin/phone-display status {key}'))['displayId'] == display, 'C2: kept')

    t2 = str(uuid.uuid4())
    again = tool(t2, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S1 second thread'})
    check(again['screenKey'] == key, f'A2: the idle screen is reused: {again}')
    check(apps_on(display) == [], 'A2: and it is clean')
    turn_ended(t2)

    # two threads, one App: the second waits in line until the first releases it (A3)
    t3, t4, t5 = (str(uuid.uuid4()) for _ in range(3))
    first = tool(t3, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S1 holds settings'})
    tool(t3, 'open_app', {'packageName': SETTINGS})
    waiting, finish = tool_in_background(t4, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S1 waits'})
    try:
        row = until(lambda: [r for r in rows(t4) if r['status'] == 'waiting_for_app'], 'A3: t4 never waited')[0]
        check(row['waitReason']['code'] == 'WAITING_FOR_APP' and row['taskTitle'] == 'S1 waits', f'R2: {row}')
        status = tool(t5, 'screen_status')
        apps = {a['packageName']: a for a in status['apps']}
        check(status['you'] == t5 and apps[SETTINGS]['heldBy'] == t3 and apps[SETTINGS]['waiting'] == 1,
              f'screen_status: {status}')
        check(t5 not in [s['threadId'] for s in status['screens']], 'screen_status starts no screen')
        check(waiting.poll() is None, 'A3: still waiting while t3 holds Settings')
        tool(t3, 'release_apps', {'packages': [SETTINGS]})
        second = finish()
    finally:
        if waiting.poll() is None:
            turn_ended(t4)
    check(second['heldApps'] == [SETTINGS] and second['screenKey'] != first['screenKey'], f'A3: {second}')
    check(apps_on(first['displayId']) == [], 'A3: Settings left t3\'s screen before t4 got it')
    tool(t4, 'open_app', {'packageName': SETTINGS})
    check(apps_on(second['displayId']) == [SETTINGS], f'A3: t4 opens it on its own screen: {tasks()}')
    turn_ended(t3)
    turn_ended(t4)
    check(apps_on(second['displayId']) == [] and set(apps_on(0)) == on_main, 'C2: both screens clean, main untouched')
    print(f'S1: {checks} checks passed (screens {first["screenKey"]}, {second["screenKey"]} left idle)')


if __name__ == '__main__':
    main()
