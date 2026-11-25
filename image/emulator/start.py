#!/usr/bin/env python3
"""Launch a verified custom image in its own AVD; never modify another instance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import time


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify_images(directory):
    receipt = directory / 'images.json'
    data = json.loads(receipt.read_text())
    if data.get('schema') != 'taku.emulator-images/1' or data.get('abi') != 'arm64-v8a':
        raise ValueError('Expected the ARM64 Taku emulator build receipt')
    required = {'system-qemu.img', 'vendor-qemu.img', 'ramdisk-qemu.img', 'kernel-ranchu',
                'userdata.img', 'encryptionkey.img', 'advancedFeatures.ini', 'kernel_cmdline.txt', 'build.prop'}
    if {r['path'] for r in data['files']} != required or len(data['files']) != len(required):
        raise ValueError('Unexpected or missing emulator image files')
    for record in data['files']:
        p = directory / 'images' / record['path']
        if p.is_symlink() or not p.is_file() or p.stat().st_size != record['bytes'] or sha(p) != record['sha256']:
            raise ValueError('Emulator image differs: ' + record['path'])
    return sha(receipt)


def sdk_names(image):
    # SDK-mode AvdInfo resolves these names even when -system/-vendor are explicit.
    # Keep the original Goldfish bytes and receipt; only add local name aliases.
    for alias, original in (('system.img', 'system-qemu.img'),
                            ('vendor.img', 'vendor-qemu.img'),
                            ('ramdisk.img', 'ramdisk-qemu.img')):
        target = image / alias
        if target.exists() or target.is_symlink():
            if not target.is_symlink() or target.resolve() != (image / original).resolve():
                raise ValueError('Conflicting SDK image name: ' + alias)
        else:
            target.symlink_to(original)


def prepare_avd(home, name, directory, digest):
    avd = home / (name + '.avd')
    ini = home / (name + '.ini')
    identity = {'schema': 'taku.custom-avd/1', 'images': str(directory), 'receiptSha256': digest}
    if avd.exists() or ini.exists():
        marker = avd / 'taku-image.json'
        if avd.is_symlink() or ini.is_symlink() or not marker.is_file() or json.loads(marker.read_text()) != identity:
            raise ValueError('AVD already exists with another identity; choose a new name')
        return avd
    home.mkdir(parents=True, exist_ok=True)
    avd.mkdir()
    config = {
        'AvdId': name, 'avd.ini.displayname': name, 'avd.ini.encoding': 'UTF-8',
        'abi.type': 'arm64-v8a', 'hw.cpu.arch': 'arm64', 'hw.cpu.ncore': '4',
        'hw.ramSize': '4096', 'hw.gpu.enabled': 'yes', 'hw.gpu.mode': 'host',
        'hw.keyboard': 'yes', 'hw.lcd.width': '1080', 'hw.lcd.height': '2424',
        'hw.lcd.density': '420', 'hw.lcd.depth': '32', 'hw.lcd.vsync': '60',
        'hw.mainKeys': 'no', 'hw.audioInput': 'yes', 'hw.camera.back': 'virtualscene',
        'hw.camera.front': 'emulated', 'hw.accelerometer': 'yes', 'hw.gps': 'yes',
        'hw.battery': 'yes', 'disk.dataPartition.size': '8G', 'PlayStore.enabled': 'false',
        'image.sysdir.1': str(directory / 'images') + '/', 'tag.id': 'default',
        'tag.display': 'Taku Lineage', 'skin.dynamic': 'yes', 'skin.name': '1080x2424',
        'showDeviceFrame': 'no', 'fastboot.forceColdBoot': 'yes',
    }
    (avd / 'config.ini').write_text(''.join(k + '=' + v + '\n' for k, v in config.items()))
    (avd / 'taku-image.json').write_text(json.dumps(identity, indent=2) + '\n')
    ini.write_text('avd.ini.encoding=UTF-8\npath=' + str(avd) + '\ntarget=android-37\n')
    return avd


def default_sdk() -> Path:
    for env_key in ('ANDROID_SDK_ROOT', 'ANDROID_HOME'):
        if val := os.environ.get(env_key):
            return Path(val)
    mac_sdk = Path.home() / 'Library/Android/sdk'
    return mac_sdk if mac_sdk.exists() else Path.home() / 'Android/Sdk'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--images', type=Path, required=True, help='Directory containing images.json and images/')
    parser.add_argument('--avd-home', type=Path, required=True, help='Dedicated AVD directory; no existing AVD is cloned')
    parser.add_argument('--name', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--sdk', type=Path, default=default_sdk())
    parser.add_argument('--allow-host-audio', action='store_true',
                        help='Allow host microphone input; enable routing with adb emu avd hostmicon after boot')
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.-]*', args.name):
        parser.error('Invalid AVD name')
    if args.port % 2 or not 5554 <= args.port <= 5682:
        parser.error('Choose an unused even console port from 5554 to 5682')
    for port in (args.port, args.port + 1):
        with socket.socket() as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(('127.0.0.1', port))
            except OSError:
                parser.error('Selected port is occupied; no device was changed')
    directory, home = args.images.resolve(), args.avd_home.resolve()
    digest = verify_images(directory)
    sdk_names(directory / 'images')
    avd = prepare_avd(home, args.name, directory, digest)
    image = directory / 'images'
    command = [str(args.sdk / 'emulator/emulator'), '-avd', args.name, '-port', str(args.port),
               '-sysdir', str(image), '-system', str(image / 'system-qemu.img'),
               '-vendor', str(image / 'vendor-qemu.img'), '-ramdisk', str(image / 'ramdisk-qemu.img'),
               '-kernel', str(image / 'kernel-ranchu'), '-initdata', str(image / 'userdata.img'),
               '-gpu', 'host', '-accel', 'on', '-no-snapshot', '-no-boot-anim',
               '-no-metrics', '-crash-report-mode', 'disabled']
    if args.allow_host_audio:
        command.append('-allow-host-audio')
    env = {**os.environ, 'ANDROID_AVD_HOME': str(home), 'ANDROID_SDK_ROOT': str(args.sdk)}
    with (avd / 'emulator.log').open('ab') as log:
        process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    time.sleep(1)
    if process.poll() is not None:
        raise RuntimeError('Emulator exited during startup; see ' + str(avd / 'emulator.log'))
    print(json.dumps({'pid': process.pid, 'serial': 'emulator-' + str(args.port),
                      'avd': str(avd), 'log': str(avd / 'emulator.log')}))


if __name__ == '__main__':
    main()
