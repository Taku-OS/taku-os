#!/usr/bin/env python3
"""Dedicated screens, script S2: the user has an App the thread holds on the main screen. Covers B3 and C4 (design
doc section 2).

The user is simulated from the shell: `am start --display 0` opens an App on the main screen the way tapping its icon
does, and `am stack remove` swipes its task away in Recents. Three parts:

1. Clock is in the user's Recents before the thread asks for it: the request is granted, open_app and tap are refused
   with USER_HOLDS_APP and the user's Clock stays put; once it is swiped away, open_app goes on without a new
   request. (A screenshot is not checked: host tests and S4 cover that; an empty screen gives no frame to capture.)
2. The user opens Settings while the thread uses it: Android moves the thread's task to the main screen and the next
   tap is refused; once it is swiped away, the thread goes on and Settings starts over in a new task.
3. C4: after the turn ends, the user opens Settings and gets a new task, not the thread's page.

Needs what S1 needs.

    python3 system/assistant/tests/dedicated-screens/e2e/s2.py [serial]
"""
import json, re, sys, uuid

import s1
from s1 import CLOCK, SETTINGS, Refused, apps_on, check, shell, tasks, tool, turn_ended


def task_of(pkg, display):
    """pkg's top task id on the display, or None."""
    return next((i for i, p, d in tasks() if p == pkg and d == display), None)


def user_opens(pkg):
    """Opens pkg on the main screen as its icon would; returns its task id there."""
    component = shell(f'cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {pkg}').split()[-1]
    shell(f'am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n {component}')
    return s1.until(lambda: task_of(pkg, 0), f'{pkg} on the main screen: {tasks()}')


def user_swipes(task):
    shell(f'am stack remove {task}')
    # Android removes the task just after `am stack remove` returns (first moved behind, gone ~0.1 s later;
    # about 4 s on a busy emulator)
    s1.until(lambda: task not in [i for i, _, _ in tasks()], f'task {task} swiped away: {tasks()}', seconds=15)


def blocked(thread, name, arguments=None):
    """The fields of the USER_HOLDS_APP refusal the tool call gets."""
    try:
        tool(thread, name, arguments)
    except Refused as error:
        m = re.search(r'USER_HOLDS_APP: (\{.*\})', str(error))
        check(m is not None, f'{name}: expected USER_HOLDS_APP, got {error}')
        return json.loads(m.group(1))
    raise AssertionError(f'{name}: expected USER_HOLDS_APP, but it ran')


def main():
    s1.require_this_broker()
    on_main = set(apps_on(0))
    if on_main & {SETTINGS, CLOCK}:
        sys.exit(f'clear {sorted(on_main & {SETTINGS, CLOCK})} from the main screen first')

    t1, t2 = str(uuid.uuid4()), str(uuid.uuid4())
    try:
        # 1. Clock is in the user's Recents before the thread asks for it (plan 4.2)
        users = user_opens(CLOCK)
        shell('input keyevent HOME')  # back to Taku; Clock stays in Recents
        got = tool(t1, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S2 clock'})
        display = got['displayId']
        check(got['heldApps'] == [CLOCK], f'B3: requesting is not checked: {got}')
        check(blocked(t1, 'open_app', {'packageName': CLOCK})['packageName'] == CLOCK, 'B3: open_app, Clock named')
        check(blocked(t1, 'tap', {'x': 5, 'y': 5})['packageName'] == CLOCK, 'B3: a tap on the empty screen too')
        check(apps_on(display) == [] and task_of(CLOCK, 0) == users, f'B3: the user\'s Clock stays put: {tasks()}')
        user_swipes(users)
        tool(t1, 'open_app', {'packageName': CLOCK})
        check(apps_on(display) == [CLOCK], f'B3: swiped away, open_app goes on without a new request: {tasks()}')
        turn_ended(t1)

        # 2. the user opens Settings while the thread uses it (plan 4.3)
        display = tool(t2, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S2 settings'})['displayId']
        tool(t2, 'open_app', {'packageName': SETTINGS})
        page = s1.until(lambda: task_of(SETTINGS, display), f'Settings on the thread\'s screen: {tasks()}')
        tool(t2, 'tap', {'x': 5, 'y': 5})
        moved = user_opens(SETTINGS)
        check(moved == page and apps_on(display) == [],
              f'Android moves the thread\'s task to the main screen: {page} -> {tasks()}')
        check(blocked(t2, 'tap', {'x': 5, 'y': 5})['packageName'] == SETTINGS, 'B3: the next tap is refused')
        user_swipes(moved)
        tool(t2, 'tap', {'x': 5, 'y': 5})
        tool(t2, 'open_app', {'packageName': SETTINGS})
        again = s1.until(lambda: task_of(SETTINGS, display), f'Settings reopened: {tasks()}')
        check(again != page, f'B3: Settings starts over in a new task: {tasks()}')

        # 3. C4: once the turn has ended, the user gets a new Settings task, not the thread's page
        turn_ended(t2)
        s1.until(lambda: apps_on(display) == [], f'C2: the screen is cleared: {tasks()}', seconds=5)
        users = user_opens(SETTINGS)
        check(users not in (page, again), f'C4: a new Settings task for the user: {tasks()}')
        user_swipes(users)
        check(set(apps_on(0)) == on_main, f'the main screen is as it was: {tasks()}')
    finally:
        turn_ended(t1)
        turn_ended(t2)
        for i, p, d in tasks():
            if d == 0 and p in (SETTINGS, CLOCK):
                shell(f'am stack remove {i}')
    print(f'S2: {s1.checks} checks passed')


if __name__ == '__main__':
    main()
