#!/usr/bin/env python3
"""Lock-screen acceptance on an isolated Taku emulator (explicit adb serial only).

  python3 system/lockscreen/device-tests/acceptance.py SERIAL [--first-unlock] [--report PATH]

Checks what the GUI and `cmd statusbar lockscreen` share: shortcut defaults and selection, the
"Open app" target, the authenticated surface (cards, dismiss, Taku) on a swipe (no-credential)
lock screen, and with --first-unlock the locked state and first unlock after reboot (sets a
temporary PIN, reboots, enters it, measures time to Taku Home, then removes the PIN).

It changes device state and restores it afterwards: lock-screen disabled flag, shortcut
selections, the Open app target and, with --first-unlock, the PIN. Never point it at a personal
phone. It does not replace looking at the screen: compare the screenshots it saves with Figma.
"""
import argparse
import json
import pathlib
import subprocess
import sys
import time

PIN = '1590'
TAKU_HOME = 'org.agentos.agent/.home.HomeActivity'
TEST_TAG = 'taku_lockscreen_acceptance'


class Device:
    def __init__(self, serial, out):
        self.serial = serial
        self.out = out

    def adb(self, *args, check=True, timeout=60):
        result = subprocess.run(['adb', '-s', self.serial, *args], capture_output=True, text=True, timeout=timeout)
        if check and result.returncode != 0:
            raise RuntimeError(f'adb {" ".join(args)} failed: {result.stderr.strip() or result.stdout.strip()}')
        return result.stdout.strip()

    def shell(self, command, check=True, timeout=60):
        return self.adb('shell', command, check=check, timeout=timeout)

    def lockscreen(self, *args):
        for name in ('lockscreen', 'taku-lockscreen'):
            raw = self.shell(f'cmd statusbar {name} ' + ' '.join(f"'{a}'" for a in args), check=False)
            line = raw.splitlines()[-1] if raw else ''
            if line.startswith('{'):
                return json.loads(line)
        raise RuntimeError(f'cmd statusbar lockscreen unavailable: {raw!r}')

    def screenshot(self, name):
        path = self.out / f'{name}.png'
        path.write_bytes(subprocess.run(['adb', '-s', self.serial, 'exec-out', 'screencap', '-p'],
                                        capture_output=True, check=True).stdout)
        return str(path)

    def top_activity(self):
        dump = self.shell('dumpsys activity activities', check=False)
        for line in dump.splitlines():
            if 'topResumedActivity' in line or 'mResumedActivity' in line:
                return line.strip()
        return ''

    def wait_boot(self, timeout=300):
        self.adb('wait-for-device', timeout=timeout)
        deadline = time.time() + timeout
        while time.time() < deadline:
            if self.shell('getprop sys.boot_completed', check=False) == '1':
                return
            time.sleep(2)
        raise RuntimeError('boot did not complete')

    def lock(self):
        self.shell('input keyevent KEYCODE_SLEEP')
        time.sleep(1.5)
        self.shell('input keyevent KEYCODE_WAKEUP')
        time.sleep(2)


class Checks:
    def __init__(self):
        self.results = []

    def check(self, name, condition, detail=None):
        self.results.append({'check': name, 'pass': bool(condition), 'detail': detail})
        print(f"{'PASS' if condition else 'FAIL'} {name}" + (f' :: {detail}' if detail is not None and not condition else ''))
        return bool(condition)


def shortcuts_suite(device, checks):
    listing = device.lockscreen('shortcuts')
    checks.check('shortcuts list ok', listing.get('ok'), listing)
    slots = {slot['id']: slot['selected'] for slot in listing.get('slots', [])}
    ids = {item['id'] for item in listing.get('shortcuts', [])}
    checks.check('default start shortcut is flashlight', slots.get('bottom_start') == ['flashlight'], slots)
    checks.check('default end shortcut is camera', slots.get('bottom_end') == ['camera'], slots)
    checks.check('Open app shortcut offered', 'taku_open_app' in ids, sorted(ids))
    original = dict(slots)
    try:
        result = device.lockscreen('shortcuts', 'set', 'bottom_start', 'taku_open_app')
        after = {s['id']: s['selected'] for s in device.lockscreen('shortcuts')['slots']}
        checks.check('select Open app on start slot', result.get('ok') and after.get('bottom_start') == ['taku_open_app'],
                     [result, after])
        checks.check('invalid slot rejected', device.lockscreen('shortcuts', 'set', 'nowhere', 'camera').get('error') == 'INVALID_SLOT')
        checks.check('unknown shortcut rejected',
                     device.lockscreen('shortcuts', 'set', 'bottom_start', 'no_such').get('error') == 'INVALID_SHORTCUT')
        device.shell('input keyevent KEYCODE_SLEEP; sleep 1; input keyevent KEYCODE_WAKEUP')
        time.sleep(2)
        checks.check('screenshot: Open app on start slot', True, device.screenshot('shortcut-open-app'))
    finally:
        for slot, selected in original.items():
            if selected:
                device.lockscreen('shortcuts', 'set', slot, selected[0])
            else:
                device.lockscreen('shortcuts', 'clear', slot)

    app = device.lockscreen('shortcuts', 'app')
    checks.check('Open app defaults to Taku', app.get('openApp', {}).get('isDefault') and
                 str(app['openApp'].get('component', '')).startswith('org.agentos.agent/'), app)
    stored = device.shell('settings get secure taku_lockscreen_open_app', check=False)
    try:
        result = device.lockscreen('shortcuts', 'app', 'com.android.settings/.Settings')
        checks.check('Open app target set to Settings', result.get('ok') and not result['openApp']['isDefault'], result)
        checks.check('setting holds the target', 'com.android.settings' in
                     device.shell('settings get secure taku_lockscreen_open_app', check=False))
        checks.check('non-launchable target rejected',
                     device.lockscreen('shortcuts', 'app', 'com.android.settings/.NoSuchActivity').get('error') == 'NOT_LAUNCHABLE')
        reset = device.lockscreen('shortcuts', 'app', 'default')
        checks.check('Open app target back to Taku', reset.get('ok') and reset['openApp']['isDefault'], reset)
    finally:
        if stored and stored != 'null':
            device.shell(f"settings put secure taku_lockscreen_open_app '{stored}'")
        else:
            device.shell('settings delete secure taku_lockscreen_open_app', check=False)


def authenticated_suite(device, checks):
    """A swipe lock screen (no credential) is always authenticated: cards and Taku show."""
    if device.shell('locksettings get-disabled', check=False) not in ('true', 'false'):
        checks.check('locksettings available', False)
        return
    disabled = device.shell('locksettings get-disabled', check=False)
    try:
        device.shell('locksettings set-disabled false', check=False)
        device.shell(f"cmd notification post -S bigtext -t 'Taku acceptance' {TEST_TAG} 'Card body from the shell'")
        device.lock()
        state = device.lockscreen('state')['state']
        checks.check('keyguard showing and authenticated (swipe lock)',
                     state.get('keyguardShowing') and state.get('authenticated'), state)
        checks.check('compact clock when authenticated', state.get('clockSize') == 'small', state)
        listing = device.lockscreen('notifications')
        card = next((n for n in listing.get('notifications', []) if n.get('title') == 'Taku acceptance'), None)
        checks.check('posted notification is a card', card is not None, listing)
        checks.check('screenshot: authenticated lock screen', True, device.screenshot('authenticated'))
        if card:
            checks.check('shell notification without intent is not clickable',
                         device.lockscreen('open', card['key']).get('error') == 'NOT_CLICKABLE')
            checks.check('invalid action rejected', device.lockscreen('action', card['key'], '7').get('error') == 'INVALID_ACTION')
            dismissed = device.lockscreen('dismiss', card['key'])
            time.sleep(1)
            remaining = [n['key'] for n in device.lockscreen('notifications').get('notifications', [])]
            checks.check('dismiss removes the card', dismissed.get('ok') and card['key'] not in remaining, [dismissed, remaining])
        checks.check('unknown key rejected', device.lockscreen('open', 'no|such|key').get('error') == 'NOT_FOUND')
        taku = device.lockscreen('taku')
        time.sleep(4)
        top = device.top_activity()
        checks.check('Taku button opens Taku Home', taku.get('ok') and 'org.agentos.agent' in top, [taku, top])
    finally:
        device.shell(f'locksettings set-disabled {disabled}', check=False)


def first_unlock_suite(device, checks):
    if device.shell(f'locksettings set-pin {PIN}', check=False).lower().startswith('error'):
        checks.check('temporary PIN set', False)
        return
    try:
        device.adb('reboot')
        device.wait_boot()
        time.sleep(5)
        device.shell('input keyevent KEYCODE_WAKEUP')
        time.sleep(2)
        state = device.lockscreen('state')['state']
        checks.check('after reboot: locked', state.get('keyguardShowing') and not state.get('authenticated'), state)
        checks.check('after reboot: tall clock', state.get('clockSize') == 'large', state)
        checks.check('locked: no cards', device.lockscreen('notifications').get('notifications') == [])
        checks.check('locked: Taku refused', device.lockscreen('taku').get('error') == 'LOCKED')
        checks.check('screenshot: locked lock screen', True, device.screenshot('locked'))
        device.shell('wm dismiss-keyguard', check=False)
        time.sleep(1.5)
        device.shell(f'input text {PIN}')
        device.shell('input keyevent KEYCODE_ENTER')
        start = time.time()
        top = ''
        while time.time() - start < 20:
            top = device.top_activity()
            if TAKU_HOME.split('/')[0] in top and 'FallbackHome' not in top:
                break
            time.sleep(0.25)
        elapsed = round(time.time() - start, 2)
        checks.check('first unlock lands on Taku Home', TAKU_HOME.split('/')[0] in top, {'top': top, 'seconds': elapsed})
        checks.check('first unlock time recorded (seconds)', True, elapsed)
    finally:
        device.shell(f'locksettings clear --old {PIN}', check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('serial', help='explicit adb serial of the isolated emulator')
    parser.add_argument('--first-unlock', action='store_true', help='also run the PIN + reboot first-unlock suite')
    parser.add_argument('--report', type=pathlib.Path, default=pathlib.Path('out/lockscreen-acceptance/report.json'))
    args = parser.parse_args()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial, args.report.parent)
    checks = Checks()
    device.wait_boot()
    state = device.lockscreen('state')
    checks.check('cmd statusbar lockscreen present', state.get('ok'), state)
    shortcuts_suite(device, checks)
    authenticated_suite(device, checks)
    if args.first_unlock:
        first_unlock_suite(device, checks)
    failed = [r for r in checks.results if not r['pass']]
    args.report.write_text(json.dumps({'serial': args.serial, 'results': checks.results,
                                       'failed': len(failed)}, indent=2) + '\n')
    print(f'{len(checks.results) - len(failed)}/{len(checks.results)} passed; report {args.report}')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
