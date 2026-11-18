#!/usr/bin/env python3
"""Dedicated screens, script S7: a broker restart, Codex going away, a screen process dying. Covers G1, G2, and G3 for
a tap (design doc section 2).

G1: init kills the broker's whole process group, screen processes included, so after a restart no screen is left
and the ledger is empty; the pages on them die with them and leave empty entries in Recents (a known limit; the
script removes them). G2: re-selecting the current model connection closes Codex (the user's own way of doing it):
every screen closes, tasks first so none is left in Recents, and a request in line and a request for more fail at
once. G3: a tap on a screen whose process died fails with SCREEN_LOST (Android drops the tap and `input` still
reports success, so the broker asks the screen process first); the screen leaves the list and its App goes to the
session waiting.

Restarts taku_broker and Codex, so it needs the device to itself: no dedicated screen in use and no thread loaded in
Codex. Needs what S1 needs, and the model connection set to openai (it re-selects openai).

    python3 system/assistant/tests/dedicated-screens/e2e/s7.py [serial]
"""
import json, sys, uuid

import s1
from s1 import CLOCK, CONTACTS, SETTINGS, Refused, apps_on, check, exchange, refused, shell, tasks, tool, \
    tool_in_background, turn_ended, until
from s5 import recent_tasks, rpc
from s6 import up

PROBE = str(uuid.uuid4())


def status():
    return tool(PROBE, 'screen_status')


def broker_pid():
    found = shell('for p in $(pidof app_process); do grep -q TaskBroker /proc/$p/cmdline && echo $p; done; true')
    return found.split()[0] if found.split() else None


def codex_pid():
    broker = broker_pid()
    for line in shell('ps -A -o PID,PPID,NAME').splitlines():
        pid, parent, name = (line.split() + ['', '', ''])[:3]
        if parent == broker and name.startswith('codex-') and 'code-mode' not in name:
            return pid
    return None


def answers():
    try:
        return status()
    except Refused:
        return None


def holding_a_page(thread):
    """A session holding Settings with a page open; returns its screen and the page's task id."""
    got = tool(thread, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S7 holds a page'})
    tool(thread, 'open_app', {'packageName': SETTINGS})
    until(lambda: SETTINGS in apps_on(got['displayId']), 'Settings never opened')
    page = [t for t, pkg, d in tasks() if d == got['displayId'] and pkg == SETTINGS][0]
    return got, page


def idle_screen(thread):
    got = tool(thread, 'request_apps', {'packages': [CLOCK], 'taskTitle': 'S7 goes idle'})
    turn_ended(thread)
    return got


def refusal(process):
    out, err = process.communicate(timeout=40)
    check(process.returncode != 0, f'expected a refusal, got {out.strip()}')
    return err.strip() or out.strip()


def all_closed(keys, page, label, recents_clean):
    until(lambda: not any(up(k) for k in keys), f'{label}: a screen never closed', seconds=30)
    check(page not in [t for t, _, _ in tasks()], f'{label}: the page on it is gone')
    if recents_clean:
        check(page not in recent_tasks(), f'{label}: its tasks were removed first, nothing left in Recents')
    elif page in recent_tasks():
        shell(f'am stack remove {page}')
    after = status()
    check(not after['screens'] and after['idleScreens'] == 0 and not after['apps'],
          f'{label}: no screen left, in use or idle, no App held: {after}')
    fresh = str(uuid.uuid4())
    got = tool(fresh, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S7 afterwards'})
    check(got['screenKey'] not in keys and got['heldApps'] == [SETTINGS],
          f'{label}: afterwards a new screen and the App, free: {got}')
    turn_ended(fresh)


def broker_restart():
    holder, idler = str(uuid.uuid4()), str(uuid.uuid4())
    held, page = holding_a_page(holder)
    keys = [held['screenKey'], idle_screen(idler)['screenKey']]
    check(all(up(k) for k in keys), 'G1 setup: a screen in use and an idle one')
    before = broker_pid()
    shell('stop taku_broker; start taku_broker')
    until(lambda: broker_pid() not in (None, before) and answers(), 'the broker never came back', seconds=90)
    all_closed(keys, page, 'G1', recents_clean=False)


def codex_gone():
    rpc('thread/loaded/list', {})
    before = codex_pid()
    check(before is not None, 'G2 setup: Codex runs')
    holder, queued, adding, idler = (str(uuid.uuid4()) for _ in range(4))
    held, page = holding_a_page(holder)
    waits, _ = tool_in_background(queued, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S7 waits'})
    tool(adding, 'request_apps', {'packages': [CONTACTS], 'taskTitle': 'S7 asks for more'})
    more, _ = tool_in_background(adding, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S7 asks for more'})
    until(lambda: [s['status'] for s in status()['screens']].count('waiting_for_app') == 2,
          'G2 setup: two requests never got in line')
    keys = [s['screenKey'] for s in exchange({'op': 'workspaces', 'action': 'list'})['screens']]
    keys.append(idle_screen(idler)['screenKey'])
    check(len(keys) == 4 and all(up(k) for k in keys), f'G2 setup: three screens in use and an idle one: {keys}')
    exchange({'op': 'modelConnection', 'action': 'select', 'mode': 'openai'})
    for name, process in (('in line', waits), ('for more', more)):
        check('TURN_ENDED' in refusal(process), f'G2: the request {name} fails')
    all_closed(keys, page, 'G2', recents_clean=True)
    rpc('thread/loaded/list', {})
    check(codex_pid() not in (None, before), 'G2: Codex was restarted')
    for t in (holder, queued, adding, idler):
        turn_ended(t)


def screen_crash():
    holder, waiter = str(uuid.uuid4()), str(uuid.uuid4())
    held, page = holding_a_page(holder)
    process, finish = tool_in_background(waiter, 'request_apps', {'packages': [SETTINGS], 'taskTitle': 'S7 waits'})
    until(lambda: 'waiting_for_app' in [s['status'] for s in status()['screens']], 'G3 setup: never got in line')
    pid = json.loads(shell(f'/system_ext/bin/phone-display status {held["screenKey"]}'))['pid']
    shell(f'kill -9 {pid}')
    until(lambda: not up(held['screenKey']), 'G3 setup: the screen process never died')
    refused('SCREEN_LOST', holder, 'tap', {'x': 700, 'y': 1260})
    check(finish()['heldApps'] == [SETTINGS], 'G3: its App goes to the session waiting')
    check(holder not in [s['threadId'] for s in status()['screens']], 'G3: the lost screen leaves the list')
    if page in recent_tasks():
        shell(f'am stack remove {page}')
    for t in (holder, waiter):
        turn_ended(t)


def main():
    s1.require_this_broker()
    now = status()
    if now['screens']:
        sys.exit(f'needs no dedicated screen in use: {now}')
    loaded = rpc('thread/loaded/list', {}).get('data')
    if loaded:
        sys.exit(f'needs no thread loaded in Codex (somebody may be using the device): {loaded}')
    mode = exchange({'op': 'modelConnection', 'action': 'read'})['mode']
    if mode != 'openai':
        sys.exit(f'needs the model connection on openai, not {mode}')
    if set(apps_on(0)) & {SETTINGS, CLOCK, CONTACTS}:
        sys.exit('clear Settings, Clock and Contacts from the main screen first')
    broker_restart()
    codex_gone()
    screen_crash()
    check(exchange({'op': 'modelConnection', 'action': 'read'})['mode'] == 'openai', 'model connection still openai')
    print(f'S7: {s1.checks} checks passed')


if __name__ == '__main__':
    main()
