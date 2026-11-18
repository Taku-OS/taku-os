#!/usr/bin/env python3
"""Dedicated screens, script S4: an in-App jump puts an App the thread does not hold on top. Covers B5 (design doc
section 2).

The jump is simulated: Clock is started on the thread's screen from the shell (`am start --display`), which leaves the
screen the way a jump from inside Settings would, Clock's task on top of Settings'. Acting is blocked until the thread
holds Clock; a screenshot is not; releasing Clock closes it and Settings is on top again.

Needs what S1 needs.

    python3 system/assistant/tests/dedicated-screens/e2e/s4.py [serial]
"""
import json, re, sys, uuid

import s1
from s1 import CLOCK, SETTINGS, Refused, apps_on, check, exchange, shell, tasks, tool, turn_ended


def blocked(thread, name, arguments=None):
    """The fields of the UNHELD_APP_ON_TOP refusal the tool call gets."""
    try:
        tool(thread, name, arguments)
    except Refused as error:
        m = re.search(r'UNHELD_APP_ON_TOP: (\{.*\})', str(error))
        check(m is not None, f'{name}: expected UNHELD_APP_ON_TOP, got {error}')
        return json.loads(m.group(1))
    raise AssertionError(f'{name}: expected UNHELD_APP_ON_TOP, but it ran')


def main():
    s1.require_this_broker()
    on_main = set(apps_on(0))
    if on_main & {SETTINGS, CLOCK}:
        sys.exit(f'clear {sorted(on_main & {SETTINGS, CLOCK})} from the main screen first')
    clock = shell(f'cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {CLOCK}').split()[-1]

    t1 = str(uuid.uuid4())
    try:
        display = tool(t1, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S4 settings'})['displayId']
        tool(t1, 'open_app', {'packageName': SETTINGS})
        tool(t1, 'tap', {'x': 5, 'y': 5})
        shell(f'am start --display {display} -n {clock}')  # the jump
        s1.until(lambda: apps_on(display)[:1] == [CLOCK], f'Clock on top of the screen: {tasks()}')

        check(blocked(t1, 'tap', {'x': 5, 'y': 5})['packageName'] == CLOCK, 'B5: acting is blocked, Clock named')
        check(blocked(t1, 'open_app', {'packageName': SETTINGS})['packageName'] == CLOCK, 'B5: open_app too')
        shot = exchange({'op': 'dedicatedScreensDebug', 'action': 'tool', 'threadId': t1, 'tool': 'observe_background'})
        check(any(i.get('type') == 'inputImage' for i in shot['contentItems']), 'B5: a screenshot is not blocked')

        more = tool(t1, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S4 settings, then clock'})
        check(more['heldApps'] == [SETTINGS, CLOCK], f'B5: Clock requested on top of Settings: {more}')
        tool(t1, 'tap', {'x': 5, 'y': 5})
        check(apps_on(display) == [CLOCK, SETTINGS], f'B5: goes on, Settings\' page still there: {tasks()}')

        tool(t1, 'release_apps', {'packages': [CLOCK]})
        # Android removes the task just after `am stack remove` returns (first moved behind, gone ~0.1 s later)
        s1.until(lambda: apps_on(display) == [SETTINGS], f'B5: releasing Clock closes it: {tasks()}', seconds=5)
        tool(t1, 'tap', {'x': 5, 'y': 5})
        check(set(apps_on(0)) == on_main, 'the main screen untouched')
    finally:
        turn_ended(t1)
    print(f'S4: {s1.checks} checks passed')


if __name__ == '__main__':
    main()
