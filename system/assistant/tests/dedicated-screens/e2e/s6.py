#!/usr/bin/env python3
"""Dedicated screens, script S6: idle screens close, with the idle time shortened for the run. Covers F6
(design doc section 2).

An idle screen closes once idle for the set time and leaves the ledger; one a session takes meanwhile stays
open. Screens already idle when the script starts keep the time they went idle with, so the script first
takes and frees each of them once: they go idle again under the short time and close too. Needs what S1
needs and no dedicated screen in use. Puts the idle time back to 2 hours when done (a broker restart does
too).

    python3 system/assistant/tests/dedicated-screens/e2e/s6.py [serial]
"""
import json, sys, time, uuid

import s1
from s1 import SETTINGS, Refused, check, exchange, rows, shell, tool, turn_ended, until

IDLE_MS = 4000
TWO_HOURS_MS = 2 * 60 * 60 * 1000


def idle_timeout(ms):
    return exchange({'op': 'dedicatedScreensDebug', 'action': 'idleTimeout', 'ms': ms})


def up(key):
    try:
        return 'displayId' in json.loads(shell(f'/system_ext/bin/phone-display status {key}'))
    except (Refused, ValueError):
        return False


def idle_screens(probe):
    return tool(probe, 'screen_status')['idleScreens']


def use_once(thread):
    """A turn that takes a screen and ends; returns the screen's key and when it went idle."""
    key = tool(thread, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S6 idle'})['screenKey']
    turn_ended(thread)
    return key, time.time()


def main():
    s1.require_this_broker()
    probe = str(uuid.uuid4())
    status = tool(probe, 'screen_status')
    if status['screens']:
        sys.exit(f'needs no dedicated screen in use: {status}')
    threads = []
    check(idle_timeout(IDLE_MS)['idleMs'] == IDLE_MS, 'idle time shortened')
    try:
        for _ in range(12):
            if idle_screens(probe) == 0:
                break
            threads.append(str(uuid.uuid4()))
            key, _ = use_once(threads[-1])
            until(lambda: not up(key), f'F6: earlier idle screen {key} never closed', seconds=IDLE_MS / 1000 + 10)
        check(idle_screens(probe) == 0, 'F6: every earlier idle screen closed')

        threads.append(str(uuid.uuid4()))
        key, idle_at = use_once(threads[-1])
        check(up(key) and idle_screens(probe) == 1, 'F6: a new screen, idle after its turn')
        until(lambda: not up(key), 'F6: the idle screen never closed', seconds=IDLE_MS / 1000 + 10)
        waited = time.time() - idle_at
        check(waited >= IDLE_MS / 1000 - 1, f'F6: closed only after the idle time ({waited:.1f} s)')
        check(idle_screens(probe) == 0, 'F6: and out of the ledger')

        threads.append(str(uuid.uuid4()))
        key, _ = use_once(threads[-1])
        taker = str(uuid.uuid4())
        threads.append(taker)
        check(tool(taker, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S6 taker'})['screenKey'] == key,
              'F6: the idle screen is taken')
        time.sleep(IDLE_MS / 1000 + 3)
        check(up(key) and len(rows(taker)) == 1, 'F6: taken meanwhile, still open and in use')
        turn_ended(taker)
        until(lambda: not up(key), 'F6: freed again, it never closed', seconds=IDLE_MS / 1000 + 10)
        check(idle_screens(probe) == 0, 'F6: closed after its own idle time')
    finally:
        for t in threads:
            turn_ended(t)
        idle_timeout(TWO_HOURS_MS)
    print(f'S6: {s1.checks} checks passed (idle time back to 2 hours)')


if __name__ == '__main__':
    main()
