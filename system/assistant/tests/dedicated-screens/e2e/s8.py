#!/usr/bin/env python3
"""S8: dedicated-screen regression through the broker's device interfaces (R1, R2).

Needs what S1 needs and Codex available. Uses real threads and the public
workspaces/state/RPC interfaces, including the execution-admission gate. Phone
fixtures use the existing root-only entry. Restores policy and archives its
threads. A default phone-display host must be running. The full-screen view and
chat preview need a separate visual check.

    python3 system/assistant/tests/dedicated-screens/e2e/s8.py [serial]
"""
import json, shlex, sys, uuid

import s1
from s1 import CLOCK, CONTACTS, SETTINGS, Refused, apps_on, check, exchange, refused, rows, shell, tool, \
    tool_in_background, until
from s5 import control, row_of, rpc, session_state, settled
from s6 import up


def retired_interfaces():
    for action in ('list', 'bind', 'release', 'releaseStale'):
        rejected({'op': 'executionScreen', 'action': action}, '未支持的原生操作')


def rejected(request, code):
    try:
        exchange(request)
    except Refused as error:
        check(code in str(error), f'expected {code}, got {error}')
        return
    raise AssertionError(f'the retired or forbidden request still runs: {request}')


def default_screen():
    return json.loads(shell('/system_ext/bin/phone-display status'))


def same_ledger(thread):
    row = row_of(thread)
    state = session_state(thread)['executionScreen']
    for key in ('screenKey', 'generation', 'threadId', 'controlOwner', 'controlRevision', 'taskTitle', 'status'):
        check(state[key] == row[key], f'R2: state and UI list agree on {key}: {state}, {row}')
    selected = exchange({'op': 'workspaces', 'action': 'list', 'threadId': thread})['screens']
    check(len(selected) == 1 and selected[0]['threadId'] == thread, 'R2: the filtered list selects one session')
    return row


def workspace_cli(action, arguments):
    output = shell(shlex.join(['content', 'call', '--uri', 'content://org.agentos.assistant.open-access',
                               '--method', f'workspaces/{action}', '--arg', json.dumps(arguments)]))
    envelope, _ = json.JSONDecoder().raw_decode(output.split('Bundle[{result=', 1)[1])
    if not envelope['ok']:
        raise Refused(envelope.get('message', 'workspace content call failed'))
    return envelope['result']


def revocation(row, status):
    result = workspace_cli('list', {'threadId': row['threadId']})
    for receipt in result['revocations']:
        if receipt['generation'] == row['generation'] and receipt['status'] != 'pending':
            check(receipt['status'] == status and receipt['packageNames'] == [CLOCK],
                  f'expected {status} for Clock: {receipt}')
            return receipt


def notified(row):
    tag = f"workspace-revocation-{row['screenKey']}-{row['threadId']}"
    output = shell('dumpsys notification --noredact')
    return (any(tag in line and 'NotificationRecord(' in line for line in output.splitlines())
            and row['taskTitle'] in output)


def main():
    s1.require_this_broker()
    before = exchange({'op': 'workspaces', 'action': 'list'})
    if before['screens'] or rpc('thread/loaded/list', {})['data']:
        sys.exit('S8 needs an idle emulator, with no screen in use or thread loaded')
    if set(apps_on(0)) & {SETTINGS, CLOCK, CONTACTS}:
        sys.exit('clear Settings, Clock and Contacts from the main screen first')
    retired_interfaces()
    default = default_screen()
    service = shell('getprop init.svc.taku_display').strip()
    if service:
        check(service == 'running', 'R1: the boot-started default display runs')
    else:
        print('S8 scope: component emulator has no taku_display init service; ROM cold boot is not covered', flush=True)
    probe = str(uuid.uuid4())
    if tool(probe, 'screen_status')['idleScreens'] > 1:
        sys.exit('S8 needs at most one idle dedicated screen; run after a broker restart')
    for name, args in [('observe_background', {}), ('tap', {'x': 5, 'y': 5}),
                       ('open_app', {'packageName': SETTINGS})]:
        refused('APPS_NOT_REQUESTED', probe, name, args)
    check(default_screen()['generation'] == default['generation'], 'R1: unallocated tools leave the default screen alone')
    limits = before['limits']['maxActiveScreens']
    check(limits == {'min': 1, 'max': 10}, f'R1: the settings range is 1 to 10: {limits}')
    for value in (0, 11):
        rejected({'op': 'workspaces', 'action': 'configure', 'patch': {'maxActiveScreens': value}},
                 'INVALID_WORKSPACE_LIMIT')
    policy = {key: before['policy'][key] for key in ('maxActiveScreens', 'allowBackground', 'blockedPackages')}
    a, b = (rpc('thread/start', {})['thread']['id'] for _ in range(2))
    waiting = None
    try:
        exchange({'op': 'workspaces', 'action': 'configure',
                  'patch': {'maxActiveScreens': 1, 'allowBackground': True, 'blockedPackages': []}})
        got = tool(a, 'request_apps', {'packages': [SETTINGS, CLOCK], 'taskTitle': 'S8 Settings and Clock'})
        refused('SCREEN_LIMIT_REACHED', b, 'request_apps', {'packages': [CONTACTS], 'taskTitle': 'S8 limit'})
        exchange({'op': 'workspaces', 'action': 'configure', 'patch': {'maxActiveScreens': 10}})
        check(tool(probe, 'screen_status')['maxScreens'] == 10, 'R1: the maximum setting takes effect')
        tool(a, 'open_app', {'packageName': SETTINGS})
        until(lambda: SETTINGS in apps_on(got['displayId']), 'S8 Settings never opened')
        running = same_ledger(a)
        check(running['packageName'] == SETTINGS and running['taskTitle'] == 'S8 Settings and Clock'
              and running['status'] == 'running', f'R2: the current App and title are real: {running}')
        rejected({'op': 'workspaces', 'action': 'input', 'threadId': a, 'screenKey': running['screenKey'],
                  'generation': running['generation'], 'controlRevision': running['controlRevision'],
                  'input': {'type': 'key', 'keyCode': 4}}, 'SCREEN_USER_CONTROL_REQUIRED')
        waiting, finish = tool_in_background(b, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S8 waits for Clock'})
        until(lambda: any(row['status'] == 'waiting_for_app' for row in rows(b)), 'S8 the other session never queued')
        queued = same_ledger(b)
        check(queued['taskTitle'] == 'S8 waits for Clock' and queued['waitReason']['code'] == 'WAITING_FOR_APP',
              f'R2: a queued session has its own title and waiting reason: {queued}')
        # The probe times out after 45 seconds: this queue case ends before the unrelated model preflight.
        s1.turn_ended(b)
        out, err = waiting.communicate(timeout=40)
        check(waiting.returncode != 0 and 'TURN_ENDED' in err, f'R2: ending the queued turn withdraws it: {out} {err}')
        waiting = None
        control('takeControl', running)
        taken = same_ledger(a)
        check(taken['status'] == 'user_control' and taken['canResume'], 'R2: takeover is in the same ledger')
        for method in ('turn/start', 'turn/steer', 'thread/realtime/start'):
            rejected({'op': 'rpc', 'method': method, 'params': {'threadId': a,
                      'input': [{'type': 'text', 'text': 'Reply OK.'}]}}, 'SCREEN_AGENT_PAUSED')
        control('input', taken, controlRevision=taken['controlRevision'],
                input={'type': 'swipe', 'fromX': 360, 'fromY': 1000, 'toX': 360, 'toY': 600, 'durationMs': 200})
        rejected({'op': 'workspaces', 'action': 'input', 'threadId': a, 'screenKey': taken['screenKey'],
                  'generation': taken['generation'], 'controlRevision': taken['controlRevision'] - 1,
                  'input': {'type': 'key', 'keyCode': 4}}, 'SCREEN_CONTROL_CHANGED')
        rejected({'op': 'workspaces', 'action': 'input', 'threadId': a, 'screenKey': taken['screenKey'],
                  'generation': str(uuid.uuid4()), 'controlRevision': taken['controlRevision'],
                  'input': {'type': 'key', 'keyCode': 4}}, 'SCREEN_OWNER_MISMATCH')
        # Clock is held but not on top: configuration must consult the App ledger, not the preview's packageName.
        exchange({'op': 'workspaces', 'action': 'configure', 'patch': {'blockedPackages': [CLOCK]}})
        kept = until(lambda: revocation(taken, 'retained'), 'the takeover receipt did not settle')
        check(kept in exchange({'op': 'workspaces', 'action': 'list'})['revocations'],
              'the Settings RPC and content command read the same receipt')
        until(lambda: notified(taken), 'the takeover notice did not appear')
        taken = same_ledger(a)
        check(taken['status'] == 'user_control' and taken['controlOwner'] == 'user',
              'revoking a held App preserves a user takeover')
        control('input', taken, controlRevision=taken['controlRevision'], input={'type': 'key', 'keyCode': 4})
        rejected({'op': 'workspaces', 'action': 'returnControl', 'threadId': a,
                  'screenKey': taken['screenKey'], 'generation': taken['generation']}, 'UNKNOWN_WORKSPACE_ACTION')
        request_id = str(uuid.uuid4())
        control('returnAndResume', taken, requestId=request_id, controlRevision=taken['controlRevision'])
        receipt = until(lambda: settled(taken, request_id), 'S8 the blocked return never settled', seconds=60)
        check(receipt['status'] == 'failed' and receipt.get('error') == 'APP_NOT_ALLOWED',
              f'R1: a blocked held App cannot be resumed: {receipt}')
        check(not session_state(a).get('activeTurnId') and row_of(a)['controlOwner'] == 'user',
              'the rejected give-back starts no turn and preserves user control')
        exchange({'op': 'workspaces', 'action': 'configure', 'patch': {'blockedPackages': []}})
        waiting, finish = tool_in_background(b, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S8 waits for close'})
        until(lambda: any(row['status'] == 'waiting_for_app' for row in rows(b)), 'S8 the close waiter never queued')
        listed = control('release', row_of(a))
        check(a not in [row['threadId'] for row in listed['screens']], 'R2: closing removes the session from the list')
        check(finish()['heldApps'] == [CLOCK], 'R2: closing frees Apps and wakes the queued request')
        waiting = None
        check(session_state(a)['executionScreen'] == {'bound': False}, 'R2: state agrees that the screen is gone')
        s1.turn_ended(b)

        got = tool(a, 'request_apps', {'packages': [SETTINGS, CLOCK], 'taskTitle': 'S8 revoke Clock'})
        tool(a, 'open_app', {'packageName': SETTINGS})
        running = row_of(a)
        turn = rpc('turn/start', {'threadId': a, 'input': [{'type': 'text', 'text':
                   'Use the phone ask_user tool now with question "S8 revoke Clock?" and one option "Continue". '
                   'Wait for the answer. Do not use other tools.'}]})['turn']['id']
        until(lambda: any(r['method'] == 'phone/askUser' for r in session_state(a)['requests']),
              'S8 main turn never waited for input', seconds=90)
        waiting, _ = tool_in_background(b, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S8 revoked waiter'})
        until(lambda: any(row['status'] == 'waiting_for_app' for row in rows(b)), 'S8 the waiter never queued')
        workspace_cli('configure', {'patch': {'blockedPackages': [CLOCK]}})
        check(exchange({'op': 'workspaces', 'action': 'list'})['policy']['blockedPackages'] == [CLOCK],
              'a content write is visible to the Settings RPC')
        _, err = waiting.communicate(timeout=40)
        check(waiting.returncode != 0 and 'APP_NOT_ALLOWED' in err, f'old queue refused: {err}')
        waiting = None
        released = until(lambda: revocation(running, 'released'), 'S8 the revocation did not settle', seconds=70)
        check(released['released'] == [CLOCK] and released['heldApps'] == [SETTINGS]
              and released['screenRetained'], f'only Clock is released: {released}')
        until(lambda: notified(running), 'the App-release notice did not appear')
        state = session_state(a)
        check(state.get('activeTurnId') == turn,
              f'App revocation preserves the active turn: {state.get("activeTurnId")}')
        check(same_ledger(a)['screenKey'] == running['screenKey'] and up(running['screenKey'])
              and SETTINGS in apps_on(got['displayId']) and CLOCK not in apps_on(got['displayId']),
              'the original screen and allowed Settings page survive')
        held = tool(probe, 'screen_status')['apps']
        check(not any(item['packageName'] == CLOCK and item.get('heldBy') for item in held)
              and any(item['packageName'] == SETTINGS and item.get('heldBy') == a for item in held),
              'Clock is released while Settings stays with the original session')
        rejected({'op': 'dedicatedScreensDebug', 'action': 'tool', 'threadId': a, 'tool': 'request_apps',
                  'arguments': {'packages': [CLOCK], 'taskTitle': 'S8 blocked Clock'}}, 'APP_NOT_ALLOWED')
        control('release', row_of(a))
        until(lambda: session_state(a).get('completedTurn') == {'id': turn, 'status': 'interrupted'},
              'explicit user close still interrupts the active turn')
        check(not rows(a) and not up(running['screenKey']), 'explicit user close still releases the whole screen')
        next_turn = rpc('turn/start', {'threadId': a, 'input': [{'type': 'text', 'text': 'Reply with only OK.'}]})['turn']['id']
        until(lambda: session_state(a).get('completedTurn') == {'id': next_turn, 'status': 'completed'},
              'S8 the same main session could not complete its next turn', seconds=90)
        check(default_screen()['generation'] == default['generation'], 'R1: dedicated control leaves the default screen alive')
    finally:
        exchange({'op': 'workspaces', 'action': 'configure', 'patch': policy})
        for thread in (a, b):
            active = session_state(thread).get('activeTurnId')
            if active:
                rpc('turn/interrupt', {'threadId': thread, 'turnId': active})
            if rows(thread):
                control('release', row_of(thread))
            rpc('thread/archive', {'threadId': thread})
        if waiting is not None:
            waiting.communicate(timeout=40)
    print(f'S8: {s1.checks} checks passed')


if __name__ == '__main__':
    main()
