#!/usr/bin/env python3
"""Dedicated screens, script S5: take over, pause and give back a dedicated screen the way the full-screen view does
(op workspaces: takeControl, input, pause, returnAndResume, executionStatus), then closes it (release). Covers E1, E2,
E3, F4 and R2's user_control, paused and mainThreadId (design doc section 2).

The session is a real Codex thread, started as in the UI. A successful handback keeps Settings open
until explicit release. The script then gives control back again and closes the workspace. Phone tool
setup uses the root-only test entry. Two other real threads hold Clock and wait for Settings.

Needs what S1 needs, plus Codex logged in with a model that takes images.

    python3 system/assistant/tests/dedicated-screens/e2e/s5.py [serial]
"""
import re, sys, uuid

import s1
from s1 import CLOCK, SETTINGS, Refused, apps_on, check, exchange, refused, rows, shell, tasks, tool, \
    tool_in_background, turn_ended
from s6 import up

LOOK = str(uuid.uuid4())


def rpc(method, params):
    return exchange({'op': 'rpc', 'method': method, 'params': params})


def control(action, row, **extra):
    return exchange({'op': 'workspaces', 'action': action, 'threadId': row['threadId'],
                     'screenKey': row['screenKey'], 'generation': row['generation'], **extra})


def row_of(thread):
    found = rows(thread)
    check(len(found) == 1, f'one UI list item for {thread}: {found}')
    return found[0]


def app(pkg):
    """pkg's entry in screen_status: heldBy, waiting."""
    for entry in tool(LOOK, 'screen_status')['apps']:
        if entry['packageName'] == pkg:
            return entry
    return {'heldBy': None, 'waiting': 0}


def session_state(thread):
    return exchange({'op': 'state', 'threadId': thread})


def recent_tasks():
    """Task ids in Recents, including entries left without a page."""
    return {int(i) for i in re.findall(r'Recent #\d+: Task\{\S+ #(\d+) ', shell('dumpsys activity recents'))}


def settled(row, request_id):
    receipt = control('executionStatus', row, requestId=request_id)
    return receipt if receipt['status'] != 'pending' else None


def main():
    s1.require_this_broker()
    on_main = set(apps_on(0))
    if on_main & {SETTINGS, CLOCK}:
        sys.exit(f'clear {sorted(on_main & {SETTINGS, CLOCK})} from the main screen first')
    threads = []
    try:
        for _ in range(3):
            threads.append(rpc('thread/start', {})['thread']['id'])
        a, b, c = threads
        display = tool(a, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S5 settings'})['displayId']
        tool(a, 'open_app', {'packageName': SETTINGS})
        s1.until(lambda: SETTINGS in apps_on(display), f'Settings opens on the screen: {tasks()}')
        tool(c, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S5 clock'})
        more, _ = tool_in_background(a, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S5 settings, then clock'})
        s1.until(lambda: app(CLOCK)['waiting'] == 1, 'the session waits for Clock')
        row = row_of(a)
        check(row['controlOwner'] == 'agent' and row['status'] == 'waiting_for_app' and row['mainThreadId'] == a,
              f'R2: before: {row}')
        before = row['controlRevision']

        taken = control('takeControl', row)['screen']
        check(taken['status'] == 'user_control' and taken.get('packageName') == SETTINGS
              and taken.get('mainThreadId') == a, f'E1: the takeover answers with the screen and its App: {taken}')
        _, err = more.communicate(timeout=40)
        check(more.returncode != 0 and 'SCREEN_AGENT_PAUSED' in err, f'E1: its request in line is withdrawn: {err}')
        check(app(CLOCK)['waiting'] == 0, 'E1: out of Clock\'s line')
        row = row_of(a)
        check(row['status'] == 'user_control' and row['controlOwner'] == 'user' and row['canResume']
              and row['controlRevision'] > before and row['mainThreadId'] == a, f'R2: user_control: {row}')
        check(app(SETTINGS)['heldBy'] == a and SETTINGS in apps_on(display), 'E1: Settings and its page stay')

        refused('SCREEN_AGENT_PAUSED', a, 'tap', {'x': 5, 'y': 5})
        shot = exchange({'op': 'dedicatedScreensDebug', 'action': 'tool', 'threadId': a, 'tool': 'observe_background'})
        check(any(i.get('type') == 'inputImage' for i in shot['contentItems']), 'B2: a screenshot is not blocked')
        check(turn_ended(a)['released'] is False and row_of(a)['controlOwner'] == 'user',
              'E2: a turn ending meanwhile releases nothing')

        waits, granted = tool_in_background(b, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S5 waits'})
        s1.until(lambda: app(SETTINGS)['waiting'] == 1, 'another session waits for Settings')
        swiped = control('input', row, controlRevision=row['controlRevision'],
                         input={'type': 'swipe', 'fromX': 360, 'fromY': 1000, 'toX': 360, 'toY': 500,
                                'durationMs': 400})
        check(swiped.get('inputAccepted') is True, f'the user\'s swipe runs: {swiped}')

        control('pause', row)
        row = row_of(a)
        check(row['status'] == 'paused' and row['controlOwner'] == 'paused' and row['canResume'], f'R2: paused: {row}')
        try:
            control('returnControl', row)
            raise AssertionError('returnControl ran')
        except Refused as error:
            check('UNKNOWN_WORKSPACE_ACTION' in str(error), f'giving back without continuing is gone: {error}')
        check(waits.poll() is None and app(SETTINGS)['heldBy'] == a, 'E1: the other session still waits')

        request_id = str(uuid.uuid4())
        control('returnAndResume', row, requestId=request_id, controlRevision=row['controlRevision'])
        receipt = s1.until(lambda: settled(row, request_id), 'the give-back settles', seconds=60)
        check(receipt['status'] == 'accepted' and receipt['targetThreadId'] == a
              and receipt['screen']['generation'] == row['generation'] and receipt['screen']['controlOwner'] == 'agent',
              f'E3: a new turn on the original thread and screen: {receipt}')
        def end_of_first_return():
            found = rows(a)
            if not found:
                return 'released'
            if found[0]['status'] == 'ready':
                return 'ready'
            return None

        outcome = s1.until(end_of_first_return, 'E3: handback finishes', seconds=60)
        check(outcome == 'ready' and SETTINGS in apps_on(display) and waits.poll() is None,
              'E3: a successful handback keeps the live app and its reservation')
        control('release', row_of(a))
        check(granted()['heldApps'] == [SETTINGS], 'E3: then Settings goes to the session waiting')
        check(set(apps_on(0)) == on_main, 'the main screen untouched')

        turn_ended(b)
        display = tool(a, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S5 close'})['displayId']
        tool(a, 'open_app', {'packageName': SETTINGS})
        task = s1.until(lambda: next((t for t, pkg, d in tasks() if d == display and pkg == SETTINGS), None),
                        f'Settings opens on the screen again: {tasks()}')
        _, granted = tool_in_background(b, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S5 waits again'})
        s1.until(lambda: app(SETTINGS)['waiting'] == 1, 'another session waits for Settings again')
        row = row_of(a)
        key = row['screenKey']
        control('takeControl', row)
        row = row_of(a)
        request_id = str(uuid.uuid4())
        control('returnAndResume', row, requestId=request_id, controlRevision=row['controlRevision'])
        receipt = s1.until(lambda: settled(row, request_id), 'the give-back settles again', seconds=60)
        check(receipt['status'] == 'accepted', f'a turn runs on the screen given back: {receipt}')
        listed = control('release', row)
        check(all(r['threadId'] != a for r in listed['screens']), 'F4: the closed screen leaves the UI list')
        now = session_state(a)
        ended = now.get('completedTurn') or {}
        # interrupted, unless it ended on its own while the close was stopping it
        check(ended.get('id') == receipt['turnId'] and ended.get('status') in ('interrupted', 'completed')
              and not now.get('activeTurnId'), f'F4: its turn is over once the close returns: {ended}')
        print(f'S5: the turn given back ended {ended["status"]}')
        check(not up(key), 'F4: the screen process is gone')
        check(task not in [t for t, _, _ in tasks()] and task not in recent_tasks(),
              f'F4: the Settings task is removed, and leaves nothing in Recents: {tasks()}')
        check(granted()['heldApps'] == [SETTINGS], 'F4: Settings goes to the session waiting')
        turn = rpc('turn/start', {'threadId': a, 'input': [{'type': 'text', 'text': 'Reply with OK.'}]})['turn']['id']
        s1.until(lambda: turn in (session_state(a).get('activeTurnId'), (session_state(a).get('completedTurn') or {}).get('id')),
                 'the next turn starts')
        check(session_state(a).get('phoneStoppedTurnId') != turn, 'F4: a main session\'s next turn is not refused')
        try:
            rpc('turn/interrupt', {'threadId': a, 'turnId': turn})
        except Refused:
            pass  # it ended on its own
    finally:
        for thread in threads:
            turn_ended(thread)
            try:
                rpc('thread/archive', {'threadId': thread})
            except Refused as error:
                print(f'archive {thread}: {error}')
    print(f'S5: {s1.checks} checks passed')


if __name__ == '__main__':
    main()
