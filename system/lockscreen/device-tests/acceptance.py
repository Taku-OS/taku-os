#!/usr/bin/env python3
"""Lock-screen acceptance on an isolated Taku emulator (explicit adb serial only).

  python3 system/lockscreen/device-tests/acceptance.py SERIAL [--first-unlock] [--report PATH]

Checks what the GUI and `cmd statusbar lockscreen` share: shortcut defaults and selection, the
"Open app" target, the authenticated surface (cards, dismiss, Taku) on a swipe (no-credential)
lock screen, and with --first-unlock the locked state and first unlock after reboot (sets a
temporary PIN, reboots, enters it, measures time to Taku Home, then removes the PIN).

It changes device state and restores it afterwards: lock-screen disabled flag, shortcut
selections, the Open app target and, with --first-unlock, the PIN. Restores are read back and
reported; if the current shortcut selection cannot be restored (an unavailable shortcut), the
selection checks are skipped rather than run. Never point it at a personal phone. Screenshots and
timings are printed as INFO, not counted as passes: compare the screenshots with Figma yourself.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

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

    def size(self):
        match = re.search(r'(\d+)x(\d+)', self.shell('wm size', check=False).splitlines()[-1])
        return (int(match.group(1)), int(match.group(2))) if match else (1080, 2400)

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

    def skip(self, name, reason):
        """Not run: recorded as skipped, never as passed."""
        self.results.append({'check': name, 'pass': None, 'detail': reason})
        print(f'SKIP {name} :: {reason}')


def selections(device):
    return {slot['id']: slot['selected'] for slot in device.lockscreen('shortcuts').get('slots', [])}


def restore_selections(device, original):
    for slot, selected in original.items():
        device.lockscreen('shortcuts', 'clear', slot)
        for shortcut in selected:
            device.lockscreen('shortcuts', 'set', slot, shortcut)


def date_suite(device, checks, name):
    """Inspect the actual accessible Keyguard tree, independent of date locale or notification text."""
    selection = device.shell('settings get secure lock_screen_custom_clock_face')
    if 'TAKU' not in selection:
        checks.skip(f'{name}: single Taku date', 'Taku clock is not explicitly selected')
        return
    device.shell('input keyevent KEYCODE_WAKEUP')
    path = '/data/local/tmp/taku-lockscreen-date.xml'
    device.shell(f'rm -f {path}')
    dump = device.shell(f'uiautomator dump {path}')
    if 'dumped to' not in dump:
        raise RuntimeError(f'Fresh Keyguard tree unavailable: {dump}')
    xml = device.shell(f'cat {path}')
    (device.out / f'{name}-date.xml').write_text(xml + '\n')
    tree = ET.fromstring(xml)
    roots = [node for node in tree.iter('node')
             if node.get('resource-id') == 'com.android.systemui:id/keyguard_root_view']
    checks.check(f'{name}: Keyguard tree present', len(roots) == 1)
    if len(roots) != 1:
        return
    root = roots[0]
    stock_ids = {'keyguard_slice_view', 'date_smartspace_view', 'date_smartspace_view_large',
                 'weather_smartspace_view', 'weather_smartspace_view_large'}
    duplicates = [n.attrib for n in root.iter('node')
                  if n.get('resource-id', '').split('/')[-1] in stock_ids]
    checks.check(f'{name}: stock dates hidden', not duplicates, duplicates)
    # TakuClockFaceView exposes its localized date as a direct TextView child, and date + time
    # as its content description. This excludes notification titles and the status-bar clock.
    dates = [child for face in root if face.get('class') == 'android.widget.LinearLayout'
             for child in face if child.get('class') == 'android.widget.TextView'
             and child.get('text') and face.get('content-desc', '').startswith(child.get('text'))]
    checks.check(f'{name}: exactly one Taku date', len(dates) == 1, [n.attrib for n in dates])
    if len(dates) == 1:
        density = int(re.findall(r'density:\s*(\d+)', device.shell('wm density'))[-1]) / 160
        top = int(re.match(r'\[\d+,(\d+)\]', dates[0].get('bounds'))[1]) / density
        checks.check(f'{name}: date at design top 76dp', abs(top - 76) <= 2, {'topDp': top})
        clocks = [child for face in root if face.get('class') == 'android.widget.LinearLayout'
                  for child in face if child.get('class') == 'android.view.View'
                  and re.fullmatch(r'\d{1,2}:\d{2}', child.get('content-desc', ''))]
        checks.check(f'{name}: exactly one clock', len(clocks) == 1)
        if len(clocks) == 1:
            _, y1, _, y2 = map(int, re.findall(r'\d+', clocks[0].get('bounds')))
            height = (y2 - y1) / density
            expected = 320 if device.lockscreen('state')['state']['clockSize'] == 'large' else 87.36
            checks.check(f'{name}: clock at design height', abs(height - expected) <= 1,
                         {'heightDp': height, 'expectedDp': expected})


def shortcuts_suite(device, checks):
    listing = device.lockscreen('shortcuts')
    checks.check('shortcuts list ok', listing.get('ok'), listing)
    original = {slot['id']: slot['selected'] for slot in listing.get('slots', [])}
    enabled = {item['id']: item['enabled'] for item in listing.get('shortcuts', [])}
    checks.check('Open app shortcut offered', 'taku_open_app' in enabled, sorted(enabled))
    # The image default is the overlay resource; the user's current selection may differ from it.
    defaults = device.shell('cmd overlay lookup com.android.systemui '
                            'com.android.systemui:array/config_keyguardQuickAffordanceDefaults', check=False)
    checks.check('overlay default: start flashlight, end camera',
                 'bottom_start:flashlight' in defaults and 'bottom_end:camera' in defaults, defaults)
    print(f'INFO current shortcut selection: {original}')

    unrestorable = sorted({s for selected in original.values() for s in selected if not enabled.get(s, False)})
    if unrestorable:
        reason = f'current selection {unrestorable} is unavailable on this device and could not be restored'
        for name in ('select Open app on start slot', 'invalid slot rejected', 'unknown shortcut rejected',
                     'shortcut selection restored'):
            checks.skip(name, reason)
    else:
        try:
            result = device.lockscreen('shortcuts', 'set', 'bottom_start', 'taku_open_app')
            after = selections(device)
            checks.check('select Open app on start slot',
                         result.get('ok') and after.get('bottom_start') == ['taku_open_app'], [result, after])
            checks.check('invalid slot rejected',
                         device.lockscreen('shortcuts', 'set', 'nowhere', 'camera').get('error') == 'INVALID_SLOT')
            checks.check('unknown shortcut rejected',
                         device.lockscreen('shortcuts', 'set', 'bottom_start', 'no_such').get('error') == 'INVALID_SHORTCUT')
            device.shell('input keyevent KEYCODE_SLEEP; sleep 1; input keyevent KEYCODE_WAKEUP')
            time.sleep(2)
            print(f"INFO screenshot {device.screenshot('shortcut-open-app')}")
        finally:
            restore_selections(device, original)
            restored = selections(device)
            checks.check('shortcut selection restored', restored == original, {'expected': original, 'actual': restored})

    app = device.lockscreen('shortcuts', 'app')
    print(f"INFO Open app target: {app.get('openApp')}")
    stored = device.shell('settings get secure taku_lockscreen_open_app', check=False)
    try:
        result = device.lockscreen('shortcuts', 'app', 'com.android.settings/.Settings')
        checks.check('Open app target set to Settings', result.get('ok') and not result['openApp']['isDefault'], result)
        checks.check('setting holds the target', 'com.android.settings' in
                     device.shell('settings get secure taku_lockscreen_open_app', check=False))
        checks.check('non-launchable target rejected',
                     device.lockscreen('shortcuts', 'app', 'com.android.settings/.NoSuchActivity').get('error') == 'NOT_LAUNCHABLE')
        reset = device.lockscreen('shortcuts', 'app', 'default')
        checks.check('Open app target back to Taku', reset.get('ok') and reset['openApp']['isDefault'] and
                     str(reset['openApp'].get('component', '')).startswith('org.agentos.agent/'), reset)
    finally:
        if stored and stored != 'null':
            device.shell(f"settings put secure taku_lockscreen_open_app '{stored}'")
        else:
            device.shell('settings delete secure taku_lockscreen_open_app', check=False)
        now = device.shell('settings get secure taku_lockscreen_open_app', check=False)
        checks.check('Open app target restored', now == (stored or 'null'), {'expected': stored, 'actual': now})


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
        # Authentication decides content, not layout: the lock screen appears collapsed, tall clock.
        checks.check('tall clock on appear, also when authenticated', state.get('clockSize') == 'large', state)
        checks.check('notifications collapsed on appear', state.get('expanded') is False, state)
        checks.check('swipe lock: no padlock in the island', state.get('lockStatus', {}).get('status') is None, state)
        date_suite(device, checks, 'authenticated')
        listing = device.lockscreen('notifications')
        card = next((n for n in listing.get('notifications', []) if n.get('title') == 'Taku acceptance'), None)
        checks.check('posted notification is a card', card is not None, listing)
        print(f"INFO screenshot {device.screenshot('authenticated')}")
        if card:
            checks.check('shell notification without intent is not clickable',
                         device.lockscreen('open', card['key']).get('error') == 'NOT_CLICKABLE')
            checks.check('invalid action rejected', device.lockscreen('action', card['key'], '7').get('error') == 'INVALID_ACTION')
            dismissed = device.lockscreen('dismiss', card['key'])
            time.sleep(1)
            remaining = [n['key'] for n in device.lockscreen('notifications').get('notifications', [])]
            checks.check('dismiss removes the card', dismissed.get('ok') and card['key'] not in remaining, [dismissed, remaining])
        checks.check('unknown key rejected', device.lockscreen('open', 'no|such|key').get('error') == 'NOT_FOUND')
        expansion_suite(device, checks)
        taku = device.lockscreen('taku')
        time.sleep(4)
        top = device.top_activity()
        checks.check('Taku button opens Taku Home', taku.get('ok') and 'org.agentos.agent' in top, [taku, top])
    finally:
        device.shell(f'locksettings set-disabled {disabled}', check=False)


def expansion_suite(device, checks):
    """Dragging the notification area opens and closes it; the clock follows. Needs a card on screen."""
    device.shell(f"cmd notification post -S bigtext -t 'Taku stack' {TEST_TAG}-stack 'Second card'")
    time.sleep(1)
    width, height = device.size()
    # A drag up from the collapsed card (over the shortcuts) opens the list.
    device.shell(f'input swipe {width // 2} {int(height * 0.80)} {width // 2} {int(height * 0.45)} 300')
    time.sleep(1)
    state = device.lockscreen('state')['state']
    checks.check('drag up opens the notifications', state.get('expanded') is True and state.get('clockSize') == 'small'
                 and state.get('keyguardShowing'), state)
    print(f"INFO screenshot {device.screenshot('expanded')}")
    device.shell(f'input swipe {width // 2} {int(height * 0.40)} {width // 2} {int(height * 0.75)} 300')
    time.sleep(1)
    state = device.lockscreen('state')['state']
    checks.check('drag down closes them, tall clock', state.get('expanded') is False and state.get('clockSize') == 'large', state)
    opened = device.lockscreen('expand')
    time.sleep(1)
    state = device.lockscreen('state')['state']
    checks.check('expand command opens them', opened.get('ok') and state.get('expanded') is True, [opened, state])
    closed = device.lockscreen('collapse')
    time.sleep(1)
    state = device.lockscreen('state')['state']
    checks.check('collapse command closes them', closed.get('ok') and state.get('clockSize') == 'large', [closed, state])
    device.shell(f'cmd notification cancel {TEST_TAG}-stack', check=False)


def bottom_unlock_suite(device, checks):
    """With no credential, a swipe up from the gesture bar is the system unlock and lands on Home."""
    disabled = device.shell('locksettings get-disabled', check=False)
    try:
        device.shell('locksettings set-disabled false', check=False)
        device.lock()
        width, height = device.size()
        device.shell(f'input swipe {width // 2} {height - 4} {width // 2} {int(height * 0.55)} 250')
        time.sleep(3)
        state = device.lockscreen('state')
        top = device.top_activity()
        checks.check('gesture bar swipe unlocks to Home', state.get('error') == 'KEYGUARD_NOT_SHOWING'
                     or not state.get('state', {}).get('keyguardShowing'), [state, top])
        checks.check('lands on Taku Home', 'org.agentos.agent' in top, top)
    finally:
        device.shell(f'locksettings set-disabled {disabled}', check=False)


def island_suite(device, checks):
    """The island's lock-screen switch: one value for the GUI switch, the command and raw settings."""
    def lock_screen():
        return json.loads(device.shell('cmd statusbar island settings'))['settings'].get('lockScreen')
    before = lock_screen()
    checks.check('island lock-screen setting readable', before in (True, False), before)
    try:
        for value in ('0', '1'):
            device.shell(f'cmd statusbar island setLockScreen {value}')
            raw = device.shell('settings get global taku_island_lock_screen', check=False).strip()
            checks.check(f'setLockScreen {value} writes the setting', raw == value and lock_screen() is (value == '1'),
                         {'raw': raw, 'state': lock_screen()})
        refused = json.loads(device.shell('cmd statusbar island setLockScreen 2', check=False))
        checks.check('invalid lock-screen value rejected', refused.get('error') == 'invalid_lock_screen', refused)
        state = device.lockscreen('state')['state']
        checks.check('lock-screen state reports the island',
                     set(state.get('island', {})) >= {'key', 'redacted', 'card', 'capsule', 'cardView'}, state)
        surface = json.loads(device.shell('cmd statusbar island state'))['presentation'].get('surface')
        checks.check('island reports its surface', surface in ('status-bar', 'lock-screen'), surface)
    finally:
        device.shell(f"cmd statusbar island setLockScreen {'1' if before else '0'}", check=False)


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
        checks.check('locked: closed padlock in the island', state.get('lockStatus') == {'status': 'locked', 'visible': True}, state)
        checks.check('locked: no cards', device.lockscreen('notifications').get('notifications') == [])
        checks.check('locked: Taku refused', device.lockscreen('taku').get('error') == 'LOCKED')
        date_suite(device, checks, 'locked')
        print(f"INFO screenshot {device.screenshot('locked')}")
        systemui_pid = device.shell('pidof com.android.systemui')
        device.shell('wm dismiss-keyguard', check=False)
        time.sleep(1.5)
        bouncer = device.lockscreen('state')['state'].get('bouncer', {})
        checks.check('bouncer in the Taku style with glass PIN keys',
                     bouncer.get('visible') and bouncer.get('styled') and bouncer.get('input') == 'pin'
                     and bouncer.get('glassControls', 0) >= 10, bouncer)
        print(f"INFO screenshot {device.screenshot('bouncer')}")
        # Both first appearance and reinflation run NumPadButton's background-alpha animation.
        # A missing background used to crash SystemUI here, then again after it restarted.
        device.shell('input keyevent KEYCODE_BACK')
        time.sleep(0.5)
        device.shell('wm dismiss-keyguard', check=False)
        time.sleep(1.5)
        checks.check('PIN appearance and reopening keep SystemUI alive',
                     device.shell('pidof com.android.systemui') == systemui_pid)
        checks.check('reopened PIN still requires authentication',
                     not device.lockscreen('state')['state'].get('authenticated'))
        device.shell('input text 0')
        device.shell('input keyevent KEYCODE_DEL')
        device.shell(f'input text {PIN}')
        device.shell('input keyevent KEYCODE_ENTER')
        start = time.time()
        top = ''
        padlock = set()
        while time.time() - start < 20:
            padlock.add(device.lockscreen('state')['state'].get('lockStatus', {}).get('status'))
            top = device.top_activity()
            if TAKU_HOME.split('/')[0] in top and 'FallbackHome' not in top and 'unlocked' in padlock:
                break
            time.sleep(0.25)
        elapsed = round(time.time() - start, 2)
        checks.check('first unlock lands on Taku Home', TAKU_HOME.split('/')[0] in top, {'top': top, 'seconds': elapsed})
        checks.check('unlock: open padlock shown', 'unlocked' in padlock, sorted(map(str, padlock)))
        time.sleep(3)
        state = device.lockscreen('state')['state']
        checks.check('unlock: padlock gone after its moment', state.get('lockStatus') == {'status': None, 'visible': False}, state)
        print(f'INFO PIN entry to Taku Home: {elapsed} s (not boot time)')
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
    island_suite(device, checks)
    bottom_unlock_suite(device, checks)
    if args.first_unlock:
        first_unlock_suite(device, checks)
    failed = [r for r in checks.results if r['pass'] is False]
    skipped = [r for r in checks.results if r['pass'] is None]
    args.report.write_text(json.dumps({'serial': args.serial, 'results': checks.results,
                                       'failed': len(failed), 'skipped': len(skipped)}, indent=2) + '\n')
    ran = len(checks.results) - len(skipped)
    print(f'{ran - len(failed)}/{ran} passed, {len(skipped)} skipped; report {args.report}')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
