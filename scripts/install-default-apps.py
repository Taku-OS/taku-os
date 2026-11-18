#!/usr/bin/env python3
"""Prepare/install default apps on an explicitly selected ARM64 development emulator."""
import argparse
import json
import os
from pathlib import Path
import re
import runpy
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
GOOGLE_IDS = ('pixel_weather', 'youtube_music')
AUDIO_ACTIVITY = 'org.agentos.audio/.AudioHomeActivity'


def run(*args, **kwargs):
    return subprocess.check_output([str(a) for a in args], cwd=ROOT, text=True, **kwargs).strip()


def check_device(adb, serial):
    if not re.fullmatch(r'emulator-[0-9]+', serial):
        raise ValueError('Choose an explicit development emulator; physical devices use the ROM install guide')
    command = [adb, '-s', serial]
    if run(*command, 'get-state') != 'device':
        raise ValueError('Emulator is not ready')
    if int(run(*command, 'shell', 'getprop', 'ro.build.version.sdk')) < 36:
        raise ValueError('Taku Audio requires Android 16 / API 36 or newer')
    if 'arm64-v8a' not in run(*command, 'shell', 'getprop', 'ro.product.cpu.abilist').split(','):
        raise ValueError('The pinned default apps require an ARM64 emulator')
    for package in ('com.google.android.gms', 'com.google.android.gsf', 'com.android.vending'):
        try:
            present = run(*command, 'shell', 'pm', 'path', package).startswith('package:')
        except subprocess.CalledProcessError:
            present = False
        if not present:
            raise ValueError('Missing Google runtime: ' + package + '; use a Google-enabled emulator')
    return command


def prepare(tools, env, apk_dir=None):
    acquisition = runpy.run_path(str(ROOT / 'scripts/google-apps.py'))
    google = acquisition['APPS']
    lock, checksum = google.load_lock()
    destination = ROOT / 'out/dependencies/default-apps' / checksum
    records = []
    # Verify every Google payload before any device install, including original signatures.
    for app in lock['apps']:
        if app['id'] not in GOOGLE_IDS:
            continue
        path = acquisition['stage_apk'](app, destination, apk_dir)
        record = google.inspect_apk(path, app, tools / 'aapt2', tools / 'apksigner', tools / 'zipalign')
        records.append({'id': app['id'], 'package': app['packageName'], 'path': str(path), **record})
    audio = ROOT / 'system/audio-engine'
    try:
        runpy.run_path(str(audio / 'record-build.py'))['verify']()
        manifest = json.loads((audio / 'out/model-pack/manifest.json').read_text())
        if not {'tts/model.onnx', 'asr/tokens.txt', 'asr-zh/tokens.txt'} <= {i['path'] for i in manifest['files']}:
            raise ValueError('Complete local speech pack required')
    except (OSError, ValueError, KeyError):
        subprocess.run(['bash', str(audio / 'build.sh'), '--with-tts'], cwd=ROOT, env=env, check=True)
    audio_apk = audio / 'out/TakuAudioEngine.apk'
    run(tools / 'apksigner', 'verify', audio_apk, env=env)
    badging = run(tools / 'aapt2', 'dump', 'badging', audio_apk, env=env)
    if "package: name='org.agentos.audio'" not in badging or "launchable-activity: name='org.agentos.audio.AudioHomeActivity'" not in badging:
        raise ValueError('Taku Audio APK is missing the expected application entry')
    subprocess.run(['bash', 'system/desktop-widgets/build.sh'], cwd=ROOT, env=env, check=True)
    return destination, records


def existing_google_app(command, record, tools, destination):
    """Keep a verified equal/newer Google variant; never downgrade or remove user data."""
    package = record['package']
    # `pm path` exits 1 for a missing package; that is expected on a fresh install.
    if 'package:' + package not in run(*command, 'shell', 'pm', 'list', 'packages', package).splitlines():
        return None
    paths = run(*command, 'shell', 'pm', 'path', package).splitlines()
    if not paths:
        return None
    remote = next((p.removeprefix('package:') for p in paths if p.startswith('package:') and p.endswith('/base.apk')), None)
    remote = remote or paths[0].removeprefix('package:')
    local = destination / ('installed-' + record['id'] + '.apk')
    run(*command, 'pull', remote, local)
    badging = run(tools / 'aapt2', 'dump', 'badging', local)
    identity = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
    if not identity or identity[1] != package:
        raise ValueError('Existing Google app identity differs: ' + package)
    google = runpy.run_path(str(ROOT / 'image/google_apps.py'))
    expected = {'id': record['id'], 'signerSha256': list(google['PUBLISHERS'][record['id']][1])}
    signers = google['signer_record'](run(tools / 'apksigner', 'verify', '--print-certs', local), expected)
    if int(identity[2]) >= record['manifest']['versionCode']:
        return {'action': 'kept-existing', 'versionCode': int(identity[2]), 'signers': signers}
    if set(signers) != set(record['signers']):
        raise ValueError('Older Google signing variant installed; update through Play Store: ' + package)
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument('--serial', help='Explicit ARM64 Google-enabled development emulator')
    target.add_argument('--prepare-only', action='store_true', help='Download/verify/build without changing a device')
    parser.add_argument('--adb', type=Path)
    parser.add_argument('--apk-dir', type=Path, help='Optional original pixel_weather.apk and youtube_music.apk inputs')
    args = parser.parse_args()
    # Reuse the repository toolchain selection; no npm/npx or downloaded installer is run.
    selected = run('bash', '-ec', 'source scripts/android-env.sh; printf "%s\\n" "$TOOLS" "$JAVA_HOME" "$ADB"').splitlines()
    tools, java, default_adb = selected[-3:]
    tools = Path(tools)
    env = dict(os.environ, JAVA_HOME=java)
    os.environ['JAVA_HOME'] = java  # APK verification invokes the SDK's Java launcher.
    adb = args.adb or Path(default_adb)
    command = check_device(adb, args.serial) if args.serial else None
    if command:
        run(*command, 'root')
        run(*command, 'wait-for-device')
        if run(*command, 'shell', 'id', '-u') != '0':
            raise ValueError('Audio model installation requires a root-capable development emulator')
    destination, records = prepare(tools, env, args.apk_dir)
    report = {'googleApps': records, 'audioActivity': AUDIO_ACTIVITY, 'installed': False}
    if command:
        # Check all installed identities before mutating any package.
        retained = {r['id']: existing_google_app(command, r, tools, destination) for r in records}
        for record in records:
            record['deviceInstallation'] = retained[record['id']]
            if record['deviceInstallation'] is None:
                print(run(*command, 'install', '--no-incremental', '-r', record['path']))
                record['deviceInstallation'] = {'action': 'installed-pinned',
                    'versionCode': record['manifest']['versionCode'], 'signers': record['signers']}
        subprocess.run([sys.executable, 'system/audio-engine/install.py', '--serial', args.serial, '--adb', str(adb)],
                       cwd=ROOT, env=env, check=True)
        for name in ('TakuClock', 'TakuWeather'):
            print(run(*command, 'install', '--no-incremental', '-r', ROOT / 'system/desktop-widgets/out' / (name + '.apk')))
        # Verify the installed, launchable application set rather than just adb exit codes.
        launchers = {}
        for package in ('com.google.android.apps.weather', 'com.google.android.apps.youtube.music', 'org.agentos.audio'):
            component = run(*command, 'shell', 'cmd', 'package', 'resolve-activity', '--brief',
                            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.LAUNCHER', package)
            if package + '/' not in component:
                raise ValueError('Installed app has no launcher entry: ' + package)
            launchers[package] = component.splitlines()[-1]
        report.update(installed=True, serial=args.serial, launchers=launchers)
    report_path = destination / 'installation-report.json'
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(('Installed' if command else 'Prepared') + ' Pixel Weather, YouTube Music, Taku Audio and widget providers.')
    print('No accounts or API keys are configured. Report: ' + str(report_path))


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error))
