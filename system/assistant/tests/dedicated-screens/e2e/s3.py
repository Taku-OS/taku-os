#!/usr/bin/env python3
"""Dedicated screens, script S3: the screen limit, lowered to 2 for the run. Covers A7 (design doc section 2).

Every dedicated screen counts, a thread waiting in line included; an idle screen is reused at the limit.
Needs what S1 needs, and at most 2 dedicated screens on the device, none in use (right after S1, or after a
broker restart). Puts the limit back when done.

    python3 system/assistant/tests/dedicated-screens/e2e/s3.py [serial]
"""
import sys, time, uuid

import s1
from s1 import CLOCK, SETTINGS, check, exchange, refused, rows, tool, tool_in_background, turn_ended, until


def main():
    s1.require_this_broker()
    probe = str(uuid.uuid4())
    status = tool(probe, 'screen_status')
    if status['screens'] or len(status['screens']) + status['idleScreens'] > 2:
        sys.exit(f'needs at most 2 dedicated screens, none in use: {status}')
    before = exchange({'op': 'workspaces', 'action': 'list'})['policy']['maxActiveScreens']
    t1, t2, t3 = (str(uuid.uuid4()) for _ in range(3))
    exchange({'op': 'workspaces', 'action': 'configure', 'patch': {'maxActiveScreens': 2}})
    waiting = None
    try:
        check(tool(probe, 'screen_status')['maxScreens'] == 2, 'the limit is 2')
        tool(t1, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S3 one'})
        waiting, finish = tool_in_background(t2, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S3 two'})
        until(lambda: [r for r in rows(t2) if r['status'] == 'waiting_for_app'], 't2 never got in line')
        started = time.time()
        refused('SCREEN_LIMIT_REACHED', t3, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S3 three'})
        check(time.time() - started < 5, 'A7: refused at once')
        status = tool(probe, 'screen_status')
        check(len(status['screens']) == 2 and status['idleScreens'] == 0, f'A7: t2 waits on a screen of its own: {status}')
        turn_ended(t1)
        check(finish()['heldApps'] == [SETTINGS], 't2 gets Settings when t1\'s turn ends')
        tool(t3, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S3 three'})
        status = tool(probe, 'screen_status')
        check(len(status['screens']) == 2 and status['idleScreens'] == 0, f'A7: t1\'s idle screen reused: {status}')
    finally:
        if waiting is not None and waiting.poll() is None:
            turn_ended(t2)
        for t in (t1, t2, t3):
            turn_ended(t)
        exchange({'op': 'workspaces', 'action': 'configure', 'patch': {'maxActiveScreens': before}})
    print(f'S3: {s1.checks} checks passed (limit back to {before})')


if __name__ == '__main__':
    main()
