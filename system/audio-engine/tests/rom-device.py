#!/usr/bin/env python3
"""Stage/inspect a disposable AOSP AVD; this is not a Pixel ROM build or flash."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import re
import runpy

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
parser.add_argument('--stage', action='store_true', help='Requires a disposable Taku_Audio_ROM_* AVD, rooted and remounted')
parser.add_argument('--allow-unsupported-permissions', action='store_true', help='SDK fixture only: report platform-absent permissions separately; never forgive a denied defined permission')
parser.add_argument('--require-readonly', action='store_true', help='Require a baked system_ext; reject remount/overlay acceptance')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', args.serial):
    parser.error('Only an explicit emulator serial is supported')


def adb(*command):
    return subprocess.check_output([args.adb, '-s', args.serial, *map(str, command)], text=True).strip()


avd = adb('emu', 'avd', 'name').splitlines()[0]
if not avd.startswith('Taku_Audio_ROM_'):
    parser.error('Refuse non-disposable AVD: ' + avd)
args.output.mkdir(parents=True, exist_ok=True)
lock = json.loads((ROOT / 'system/audio-engine/rom-input-lock.json').read_text())
helper = runpy.run_path(str(ROOT / 'system/audio-engine/prepare-rom.py'))
helper['validate_inputs'](ROOT, lock)
if args.stage:
    if adb('shell', 'id', '-u') != '0':
        raise SystemExit('Run adb root, disable-verity, reboot and remount on this disposable AVD first')
    probe = subprocess.run([args.adb, '-s', args.serial, 'shell', 'pm', 'path', 'org.agentos.audio'], text=True, capture_output=True)
    if probe.returncode not in (0, 1):
        raise SystemExit('Cannot inspect installed engine')
    existing = probe.stdout.strip()
    if existing and not existing.startswith('package:/system_ext/'):
        raise SystemExit('Remove the data-installed development engine on this disposable AVD first')
    adb('shell', 'mkdir -p /system_ext/priv-app/TakuAudioEngine /system_ext/etc/taku-audio /system_ext/etc/permissions /system_ext/framework /system_ext/bin /system_ext/etc/init')
    for source, target in [
        ('system/audio-engine/prebuilt/TakuAudioEngine.apk', '/system_ext/priv-app/TakuAudioEngine/TakuAudioEngine.apk'),
        ('system/audio-engine/prebuilt/audio-bridge.jar', '/system_ext/etc/taku-audio/taku-audio-bridge.jar'),
        ('system/audio-engine/prebuilt/models', '/system_ext/etc/taku-audio/'),
        ('system/audio-engine/permissions/org.agentos.audio.xml', '/system_ext/etc/permissions/privapp-permissions-org.agentos.audio.xml'),
        ('system/audio-engine/permissions/org.agentos.assistant.audio.xml', '/system_ext/etc/permissions/privapp-permissions-org.agentos.assistant.audio.xml'),
        ('system/assistant/out/task-broker.jar', '/system_ext/framework/agentos-task-broker.jar'),
        ('image/runtime/start', '/system_ext/bin/taku-runtime-start'),
    ]:
        print(adb('push', ROOT / source, target), flush=True)
    # Production Broker + wrapper, with only its init service on this SDK image.
    # Pixel product integration uses the full existing taku-runtime.rc instead.
    rc = (ROOT / 'image/runtime/taku-runtime.rc').read_text()
    broker = rc[rc.index('service taku_broker'):rc.index('service taku_display')]
    directories = rc[rc.index('on post-fs-data'):rc.index('on property:sys.boot_completed=1')]
    fixture = args.output / 'audio-validation.rc'
    fixture.write_text(broker + '\n' + directories + '\non property:sys.boot_completed=1\n    start taku_broker\n')
    adb('push', fixture, '/system_ext/etc/init/audio-validation.rc')
    adb('shell', 'chmod 0755 /system_ext/bin/taku-runtime-start; chmod 0644 /system_ext/etc/init/audio-validation.rc /system_ext/etc/permissions/privapp-permissions-org.agentos.audio.xml; restorecon -RF /system_ext/priv-app/TakuAudioEngine /system_ext/etc/taku-audio /system_ext/etc/permissions /system_ext/framework /system_ext/bin/taku-runtime-start /system_ext/etc/init/audio-validation.rc')
    print('Staged system components. Reboot before verification; no data model seeding or engine adb install.', flush=True)
    sys.exit(0)

if adb('shell', 'getprop', 'sys.boot_completed') != '1':
    raise SystemExit('Wait for boot completion')
package = adb('shell', 'pm', 'path', 'org.agentos.audio')
if package != 'package:/system_ext/priv-app/TakuAudioEngine/TakuAudioEngine.apk':
    raise SystemExit('Engine is not using the preinstalled APK: ' + package)
for source, installed in [
    ('org.agentos.audio.xml', 'privapp-permissions-org.agentos.audio.xml'),
    ('org.agentos.assistant.audio.xml', 'privapp-permissions-org.agentos.assistant.audio.xml'),
]:
    expected = (ROOT / 'system/audio-engine/permissions' / source).read_text().strip()
    if adb('shell', 'cat', '/system_ext/etc/permissions/' + installed) != expected:
        raise SystemExit('Installed permission policy differs: ' + installed)
assistant = adb('shell', 'dumpsys', 'package', 'org.agentos.assistant')
if 'android.permission.MODIFY_AUDIO_ROUTING: granted=true' not in assistant:
    raise SystemExit('Assistant playback routing permission is missing')
checks = []
verifier = runpy.run_path(str(ROOT / 'system/audio-engine/verify-rom.py'))
for item in lock['assets']:
    name = verifier['installed_path'](item).replace('SYSTEM_EXT/', '/system_ext/', 1)
    checks.append(item['sha256'] + '  ' + name)
hashes = args.output / 'installed.sha256'
hashes.write_text('\n'.join(checks) + '\n')
adb('push', hashes, '/data/local/tmp/audio-rom-checks.sha256')
verification = adb('shell', 'sha256sum -c /data/local/tmp/audio-rom-checks.sha256')
if len(verification.splitlines()) != len(checks) or not all(line.endswith(': OK') for line in verification.splitlines()):
    raise SystemExit('Installed audio hashes differ')
data_models = adb('shell', 'test ! -e /data/user/0/org.agentos.audio/files/models && echo absent')
if data_models != 'absent':
    raise SystemExit('Development models would mask the ROM delivery path')
service = adb('shell', 'getprop', 'init.svc.taku_broker')
if service != 'running':
    raise SystemExit('Production Broker did not start from init: ' + service)
permissions = ['CAPTURE_AUDIO_OUTPUT', 'MODIFY_AUDIO_ROUTING', 'CALL_AUDIO_INTERCEPTION',
               'BYPASS_CONCURRENT_RECORD_AUDIO_RESTRICTION', 'START_FOREGROUND_SERVICES_FROM_BACKGROUND',
               'CAPTURE_VIDEO_OUTPUT']
dump = adb('shell', 'dumpsys', 'package', 'org.agentos.audio')
defined = adb('shell', 'pm', 'list', 'permissions', '-f')
unsupported = []
granted = []
for permission in permissions:
    if f'android.permission.{permission}: granted=true' in dump:
        granted.append(permission)
    elif args.allow_unsupported_permissions and f'permission:android.permission.{permission}\n' not in defined + '\n':
        unsupported.append(permission)
    else:
        raise SystemExit('Privileged permission not granted: ' + permission)
mounts = adb('shell', 'cat', '/proc/mounts')
system_ext = [line.split() for line in mounts.splitlines() if line.split()[1] == '/system_ext']
readonly = len(system_ext) == 1 and system_ext[0][2] != 'overlay' and 'ro' in system_ext[0][3].split(',')
if args.require_readonly and not readonly:
    raise SystemExit('Expected baked read-only system_ext without development overlays')
report = {'passed': True, 'avd': avd, 'sdk': adb('shell', 'getprop', 'ro.build.version.sdk'),
          'bootId': adb('shell', 'cat', '/proc/sys/kernel/random/boot_id'), 'apk': package,
          'sealedAssetsVerified': len(checks), 'modelDataDirectory': data_models,
          'privilegedPermissions': granted, 'platformUnsupportedPermissions': unsupported,
          'allPrivilegedPermissionsVerified': not unsupported, 'brokerStartedByInit': service == 'running', 'permissionPoliciesVerified': True, 'assistantRoutingGranted': True,
          'selinux': adb('shell', 'getenforce'), 'mounts': mounts, 'readonlySystemExt': readonly,
          'scope': 'Disposable AOSP SDK image with system preinstall; not a built Pixel ROM or OTA'}
(args.output / 'boot.json').write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps({k: v for k, v in report.items() if k != 'mounts'}, indent=2))
