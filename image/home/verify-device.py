#!/usr/bin/env python3
"""Exercise HOME recovery on an explicitly selected, isolated Taku ROM emulator."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

PACKAGE = 'org.agentos.agent'
ACTIVITY = PACKAGE + '/org.agentos.agent.home.HomeActivity'
ROLE = 'android.app.role.HOME'
ASSISTANT_ROLE = 'android.app.role.ASSISTANT'
VOICE = PACKAGE + '/org.agentos.agent.home.assistant.SystemAssistantService'


def full_component(value):
    return value.replace(PACKAGE + '/.', PACKAGE + '/' + PACKAGE + '.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', default=os.environ.get('ADB', str(Path.home() / 'Library/Android/sdk/platform-tools/adb')))
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        parser.error('Only an explicitly selected isolated emulator is supported')

    def shell(*command, check=True):
        result = subprocess.run([args.adb, '-s', args.serial, 'shell', *command],
                                text=True, capture_output=True, timeout=30)
        if check and result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return result.stdout.strip()

    # Refuse to mutate a stock emulator or a physical device.
    if shell('getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('Target is not an emulator')
    if shell('getprop', 'ro.taku.home.package') != PACKAGE:
        raise RuntimeError('Target has no Taku HOME ROM policy; no defaults changed')
    if 'RUNNING_UNLOCKED' not in shell('am', 'get-started-user-state', '0'):
        raise RuntimeError('Unlock the isolated emulator before running this check')

    def state():
        return {'holder': shell('cmd', 'role', 'get-role-holders', '--user', '0', ROLE),
                'resolved': shell('cmd', 'package', 'resolve-activity', '--brief', '--user', '0',
                                  '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME'),
                'assistantHolder': shell('cmd', 'role', 'get-role-holders', '--user', '0', ASSISTANT_ROLE),
                **{key: shell('settings', '--user', '0', 'get', 'secure', key)
                   for key in ('assistant', 'voice_interaction_service')}}

    def settled():
        deadline = time.monotonic() + 15
        while True:
            value = state()
            if (value['holder'] == PACKAGE and ACTIVITY in full_component(value['resolved'])
                    and value['assistantHolder'] == PACKAGE
                    and all(full_component(value[key]) == VOICE
                            for key in ('assistant', 'voice_interaction_service'))):
                return value
            if time.monotonic() >= deadline:
                raise AssertionError('HOME did not converge: ' + json.dumps(value))
            time.sleep(0.25)

    evidence = {'serial': args.serial, 'fingerprint': shell('getprop', 'ro.build.fingerprint'),
                'before': state(), 'checks': {}}
    try:
        evidence['checks']['initial'] = settled()
        shell('cmd', 'role', 'remove-role-holder', '--user', '0', ROLE, PACKAGE)
        evidence['checks']['removed'] = settled()
        shell('cmd', 'role', 'clear-role-holders', '--user', '0', ROLE)
        evidence['checks']['cleared'] = settled()
        shell('cmd', 'role', 'remove-role-holder', '--user', '0', ASSISTANT_ROLE, PACKAGE)
        evidence['checks']['assistantRemoved'] = settled()
        shell('cmd', 'role', 'clear-role-holders', '--user', '0', ASSISTANT_ROLE)
        evidence['checks']['assistantCleared'] = settled()
        for key in ('assistant', 'voice_interaction_service'):
            shell('settings', '--user', '0', 'delete', 'secure', key)
            evidence['checks'][key + 'Cleared'] = settled()
        for legacy in ('com.android.launcher3', 'org.agentos.assistant'):
            if shell('pm', 'path', legacy, check=False).startswith('package:'):
                shell('cmd', 'role', 'add-role-holder', '--user', '0', ROLE, legacy, check=False)
                # Reject an alternate holder immediately, not just eventual app repair.
                if shell('cmd', 'role', 'get-role-holders', '--user', '0', ROLE) != PACKAGE:
                    raise AssertionError('Legacy HOME was accepted: ' + legacy)
                evidence['checks'][legacy] = settled()
        if shell('pm', 'path', 'org.agentos.assistant', check=False).startswith('package:'):
            shell('cmd', 'role', 'add-role-holder', '--user', '0', ASSISTANT_ROLE,
                  'org.agentos.assistant', check=False)
            if shell('cmd', 'role', 'get-role-holders', '--user', '0', ASSISTANT_ROLE) != PACKAGE:
                raise AssertionError('Legacy ASSISTANT was accepted')
            evidence['checks']['legacyAssistantRejected'] = settled()
        shell('am', 'start', '-W', '--user', '0', '-a', 'android.intent.action.MAIN',
              '-c', 'android.intent.category.HOME')
        activities = shell('dumpsys', 'activity', 'activities')
        resumed = [line.strip() for line in activities.splitlines()
                   if 'ResumedActivity' in line or 'mResumedActivity' in line]
        if not any(ACTIVITY in full_component(line) for line in resumed):
            raise AssertionError('Taku Home is not resumed: ' + '\n'.join(resumed))
        evidence['checks']['resumed'] = resumed
        for key in ('KEYCODE_ASSIST', 'KEYCODE_VOICE_ASSIST'):
            shell('am', 'start', '-W', '-a', 'android.settings.SETTINGS')
            shell('input', 'keyevent', key)
            deadline = time.monotonic() + 10
            while True:
                activities = shell('dumpsys', 'activity', 'activities')
                resumed = [line.strip() for line in activities.splitlines()
                           if 'ResumedActivity' in line]
                if any(ACTIVITY in full_component(line) for line in resumed):
                    break
                if time.monotonic() >= deadline:
                    raise AssertionError(key + ' did not reach Taku: ' + '\n'.join(resumed))
                time.sleep(0.25)
            evidence['checks'][key] = resumed
            evidence['checks'][key + 'State'] = shell('content', 'call', '--uri',
                    'content://org.agentos.agent.home-assistant', '--method', 'assistant/get')
            shell('content', 'call', '--uri', 'content://org.agentos.agent.home-assistant',
                  '--method', 'assistant/voice-cancel')
    finally:
        shell('cmd', 'package', 'set-home-activity', '--user', '0', ACTIVITY)
        shell('cmd', 'role', 'add-role-holder', '--user', '0', ASSISTANT_ROLE, PACKAGE)
        for key in ('assistant', 'voice_interaction_service'):
            shell('settings', '--user', '0', 'put', 'secure', key, VOICE)
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
