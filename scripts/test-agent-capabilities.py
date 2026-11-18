#!/usr/bin/env python3
"""Actual emulator chat/model/tool acceptance, with independent device reads and cleanup.

Uses the existing device account; never reads credentials or touches another serial.
Raw diagnostic output remains under ignored out; curated evidence has separate boundaries.
"""
import argparse
import datetime
import json
import os
import pathlib
import re
import shlex
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]
SERIAL = 'emulator-5634'
AVD = 'Taku_ImageIntegration_20261010'
DEVICE_OUTPUT = '/data/local/tmp/taku-capability'
OUTPUT = ROOT / 'out/agent-capabilities-2026-10-14'


def run(args, *, timeout=30, check=True):
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f'{args[0]} exited {result.returncode}')
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--case', help='Run one listed case; default runs the complete suite.')
    parser.add_argument('--thread-id', default='', help='Reuse only a dedicated acceptance thread.')
    args = parser.parse_args()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    sdk = pathlib.Path(os.environ.get('ANDROID_SDK_ROOT', str(pathlib.Path.home() / 'Library/Android/sdk')))
    adb = str(sdk / 'platform-tools/adb')
    def device(command):
        return run([adb, '-s', SERIAL, 'shell', command])
    def pull(name):
        run([adb, '-s', SERIAL, 'pull', f'{DEVICE_OUTPUT}/{name}', str(OUTPUT / name)])
    if run([adb, '-s', SERIAL, 'emu', 'avd', 'name']).splitlines()[0] != AVD:
        raise RuntimeError('Dedicated emulator identity mismatch')
    if 'uid=0(' not in device('id'):
        raise RuntimeError('Existing development emulator root required; no auto-unlock or flash')
    account = json.loads(device('CLASSPATH=/data/local/tmp/taku-account-tests.jar app_process /system/bin org.agentos.tests.AccountBridgeProbe'))
    if not account.get('connected') or account.get('challengePresent'):
        raise RuntimeError('Already-connected account required; no login changes performed')
    device(f'mkdir -p {DEVICE_OUTPUT} /sdcard/Pictures')
    compile_script = '''source scripts/android-env.sh
mkdir -p out/agent-capabilities-2026-10-14/classes out/agent-capabilities-2026-10-14/dex
"$JDK/bin/javac" --release 17 -cp "$ANDROID_JAR:$SDK/platforms/android-36/uiautomator.jar:$SDK/platforms/android-36/optional/android.test.base.jar" -d out/agent-capabilities-2026-10-14/classes system/assistant/tests/org/agentos/tests/AgentCapabilityTest.java system/assistant/tests/org/agentos/tests/WallpaperRestore.java
"$JDK/bin/jar" --create --file out/agent-capabilities-2026-10-14/classes.jar -C out/agent-capabilities-2026-10-14/classes .
JAVA_HOME="$JDK" "$TOOLS/d8" --min-api 36 --lib "$ANDROID_JAR" --output out/agent-capabilities-2026-10-14/dex out/agent-capabilities-2026-10-14/classes.jar
"$JDK/bin/jar" --create --file out/agent-capabilities-2026-10-14/test.jar -C out/agent-capabilities-2026-10-14/dex classes.dex
'''
    run(['bash', '-c', compile_script], timeout=60)
    run([adb, '-s', SERIAL, 'push', str(OUTPUT / 'test.jar'), f'{DEVICE_OUTPUT}/test.jar'])
    volume = int(re.search(r'volume is (\d+)', device('cmd media_session volume --stream 3 --get'))[1])
    target_volume = 7 if volume != 7 else 6
    brightness = device('settings get system screen_brightness')
    brightness_mode = device('settings get system screen_brightness_mode')
    target_brightness = 120 if brightness != '120' else 130
    wallpaper_command = f'CLASSPATH={DEVICE_OUTPUT}/test.jar app_process /system/bin org.agentos.tests.WallpaperRestore'
    wallpaper_before = json.loads(device(wallpaper_command))
    wallpaper_exists = device('if [ -f /data/system/users/0/wallpaper ]; then echo yes; fi') == 'yes'
    if wallpaper_before['liveWallpaper']:
        raise RuntimeError('Live wallpaper baseline needs a component-specific restore; leave unchanged')
    if wallpaper_exists:
        device(f'cp /data/system/users/0/wallpaper {DEVICE_OUTPUT}/original-wallpaper')
    fixture = OUTPUT / 'wallpaper-fixture.png'
    if not fixture.exists():
        import struct
        import zlib
        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
        raw = (b'\0' + b'\x23\x58\x7d' * 64) * 64
        fixture.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 64, 64, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
    run([adb, '-s', SERIAL, 'push', str(fixture), '/sdcard/Pictures/taku-capability-wallpaper.png'])
    cases = [
        ('chat', '记住这次测试口令：青叶731。只回复「已记住」，不要调用工具。'),
        ('memory', '刚才的测试口令是什么？只回答口令，不要调用工具。'),
        ('system-read', '请真实读取 Android 版本、设备型号和执行工具进程的 UID，用中文汇报。只读，不读取账号、认证文件或个人数据。'),
        ('volume', f'把媒体音量设为 {target_volume}，然后读取实际音量确认。不要改铃声和闹钟音量。'),
        ('brightness', f'把屏幕亮度设为 {target_brightness}/255，使用手动亮度，然后回读确认。'),
        ('open-app', '在独立后台屏打开系统设置 App，核对它确实显示在后台屏；保留主屏原来的界面。'),
        ('clock-gui', '在独立后台屏打开系统时钟，通过截图观察并点击进入秒表页；不要启动秒表，不设置闹钟或计时器。操作后截图确认，保留主屏原界面。'),
        ('wallpaper', '把手机主屏壁纸换成 /sdcard/Pictures/taku-capability-wallpaper.png 这张蓝色测试图，保持锁屏壁纸不变。可以在独立后台屏用系统壁纸界面操作，先截图观察再点击，最后确认实际系统壁纸已改变。不要读取账号或其他图片。'),
        ('interrupt', '执行 /system/bin/sleep 60，然后只回复等待完成。这是停止功能测试，不要执行其他命令或修改系统。'),
    ]
    if args.case:
        cases = [case for case in cases if case[0] == args.case]
        if not cases:
            raise RuntimeError('Unknown case')
    report = {'date': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'device': AVD,
              'physicalDeviceVerified': False, 'realUpstream': True, 'cases': [], 'restored': {}}
    thread_id = args.thread_id
    try:
        device('am start -n com.android.settings/.Settings')
        for case_id, prompt in cases:
            plan = {'id': case_id, 'prompt': prompt, 'threadId': thread_id,
                    'cancelAfterCommand': case_id == 'interrupt'}
            (OUTPUT / 'plan.json').write_text(json.dumps(plan, ensure_ascii=False))
            run([adb, '-s', SERIAL, 'push', str(OUTPUT / 'plan.json'), f'{DEVICE_OUTPUT}/plan.json'])
            print(f'RUN {case_id}', flush=True)
            log = run([adb, '-s', SERIAL, 'shell', 'timeout', '220', 'uiautomator', 'runtest',
                       '/system/framework/android.test.base.jar', f'{DEVICE_OUTPUT}/test.jar',
                       '-c', 'org.agentos.tests.AgentCapabilityTest'], timeout=240, check=False)
            (OUTPUT / f'{case_id}.test.txt').write_text(log)
            if 'OK (1 test)' not in log:
                observation = {'id': case_id, 'verified': False, 'reason': 'UI acceptance failed', 'infrastructureFailure': True}
            else:
                pull(case_id + '.json')
                pull(case_id + '.png')
                observation = json.loads((OUTPUT / f'{case_id}.json').read_text())
                thread_id = observation.get('threadId', thread_id)
                completed = observation.get('state') == 'completed'
                commands = observation.get('commands', [])
                observation['realToolEvidence'] = bool(commands)
                if case_id == 'chat':
                    verified = completed and '已记住' in observation['reply']
                elif case_id == 'memory':
                    verified = completed and '青叶731' in observation['reply']
                elif case_id == 'system-read':
                    verified = completed and bool(commands) and device('getprop ro.build.version.release') in observation['reply'] and device('getprop ro.product.model') in observation['reply']
                elif case_id == 'volume':
                    actual = int(re.search(r'volume is (\d+)', device('cmd media_session volume --stream 3 --get'))[1])
                    observation['actualVolume'] = actual
                    verified = completed and bool(commands) and actual == target_volume
                elif case_id == 'brightness':
                    observation['actualBrightness'] = device('settings get system screen_brightness')
                    observation['actualBrightnessMode'] = device('settings get system screen_brightness_mode')
                    verified = completed and bool(commands) and observation['actualBrightness'] == str(target_brightness) and observation['actualBrightnessMode'] == '0'
                elif case_id in ('open-app', 'clock-gui'):
                    activity = device('dumpsys activity activities')
                    display = device('cat /data/local/agentos/virtual-display/display.id')
                    target = 'com.android.settings' if case_id == 'open-app' else 'com.android.deskclock'
                    block = activity.split(f'Display #{display} ', 1)[-1].split('Display #', 1)[0]
                    observation['targetOnBackgroundDisplay'] = target in block
                    observation['backgroundDisplayId'] = display
                    observation['mainDisplayPreserved'] = 'com.android.settings' in activity.split('Display #0 ', 1)[-1].split('Display #', 1)[0]
                    device('phone-display capture')
                    run([adb, '-s', SERIAL, 'pull', '/data/local/agentos/virtual-display/screen.png', str(OUTPUT / f'{case_id}-background.png')])
                    verified = completed and bool(commands) and observation['targetOnBackgroundDisplay'] and observation['mainDisplayPreserved']
                    if case_id == 'clock-gui':
                        observation['visualReviewRequired'] = True
                        observation['guiInputIssued'] = any(re.search(r'\binput\b.*\b(tap|swipe)\b', x['command']) for x in commands)
                        verified = verified and observation['guiInputIssued']
                elif case_id == 'wallpaper':
                    observation['wallpaperAfter'] = json.loads(device(wallpaper_command))
                    observation['wallpaperBefore'] = wallpaper_before
                    observation['wallpaperImagePresent'] = device('if [ -f /data/system/users/0/wallpaper ]; then echo yes; fi') == 'yes'
                    lock_preserved = observation['wallpaperAfter']['lockId'] == wallpaper_before['lockId'] or (wallpaper_before['lockId'] == -1 and observation['wallpaperAfter']['lockId'] == wallpaper_before['systemId'])
                    observation['originalSharedWallpaperMigratedToLock'] = wallpaper_before['lockId'] == -1 and observation['wallpaperAfter']['lockId'] == wallpaper_before['systemId']
                    observation['lockWallpaperPreserved'] = lock_preserved
                    observation['testImageColorMatched'] = observation['wallpaperAfter'].get('systemCenterRgb') == 0x23587d
                    verified = completed and bool(commands) and observation['wallpaperImagePresent'] and observation['testImageColorMatched'] and observation['wallpaperAfter']['systemId'] != wallpaper_before['systemId'] and lock_preserved
                elif case_id == 'interrupt':
                    observed = observation.get('commandObservedBeforeStop', {})
                    observation['sleepProcessRemaining'] = bool(device("ps -A -o ARGS | grep -E '^(/system/bin/)?sleep 60$' || true"))
                    verified = observation.get('state') == 'cancelled' and observation.get('nativeStatus') == 'interrupted' and observation.get('cancelRequested') and observed.get('status') == 'inProgress' and 'sleep 60' in observed.get('command', '') and not observation['sleepProcessRemaining']
                observation['verified'] = bool(verified)
            report['cases'].append(observation)
            (OUTPUT / 'acceptance.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
            print(f'RESULT {case_id}: {observation["verified"]}', flush=True)
            if observation.get('infrastructureFailure'):
                break
    finally:
        device(f'cmd media_session volume --stream 3 --set {volume}')
        for key, value in [('screen_brightness', brightness), ('screen_brightness_mode', brightness_mode)]:
            device(f'settings {"delete" if value == "null" else "put"} system {key}' + ('' if value == 'null' else f' {value}'))
        after = json.loads(device(wallpaper_command))
        if after['systemId'] != wallpaper_before['systemId']:
            if wallpaper_exists:
                device(f'chown 1000:1000 {DEVICE_OUTPUT}/original-wallpaper; chmod 600 {DEVICE_OUTPUT}/original-wallpaper')
            restore_command = wallpaper_command + ' restore' + (f' {DEVICE_OUTPUT}/original-wallpaper' if wallpaper_exists else '')
            device('su 1000 /system/bin/sh -c ' + shlex.quote(restore_command))
            if wallpaper_before['lockId'] == -1:
                device('su 1000 /system/bin/sh -c ' + shlex.quote(wallpaper_command + ' clear-lock'))
        device('rm -f /sdcard/Pictures/taku-capability-wallpaper.png')
        report['restored'] = {'mediaVolume': int(re.search(r'volume is (\d+)', device('cmd media_session volume --stream 3 --get'))[1]) == volume,
                              'brightness': device('settings get system screen_brightness') == brightness,
                              'brightnessMode': device('settings get system screen_brightness_mode') == brightness_mode,
                              'wallpaperCleanupSucceeded': True}
        (OUTPUT / 'acceptance.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print(json.dumps({'cases': [{k: item.get(k) for k in ('id', 'verified', 'seconds')} for item in report['cases']], 'restored': report['restored']}), flush=True)
    raise SystemExit(0 if all(item.get('verified') for item in report['cases']) and all(report['restored'].values()) else 1)


if __name__ == '__main__':
    os.chdir(ROOT)
    main()
