#!/usr/bin/env python3
"""Install an exported ARM64 runtime into the selected phone's debug Termux.

No root, device-wide settings, ADB reverse or existing Termux data removal.
"""
import argparse
import io
from pathlib import Path
import shlex
import subprocess
import sys
import tarfile

ROOT = Path(__file__).resolve().parents[3]
HOME = '/data/data/com.termux/files/home'
PREFIX = '/data/data/com.termux/files/usr'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--rootfs', type=Path)
    parser.add_argument('--personal-map-key', type=Path)
    args = parser.parse_args()
    subprocess.run([sys.executable, str(ROOT / 'tooling/go2-lab/prepare-vision.py')], check=True)
    adb = [args.adb, '-s', args.serial]

    def shell(*command, **kwargs):
        return subprocess.run(adb + ['shell', shlex.join(['run-as', 'com.termux', *command])], check=True, **kwargs)

    destination = HOME + '/taku-go2/rootfs'
    if args.rootfs:
        # Refuse to replace an existing runtime or its recordings/settings.
        shell('/system/bin/sh', '-c', f'test ! -e {destination} && mkdir -p {destination}')
        with args.rootfs.open('rb') as archive:
            shell('/system/bin/env', 'PATH=' + PREFIX + '/bin:/system/bin',
                  PREFIX + '/bin/proot', '--link2symlink', PREFIX + '/bin/tar',
                  '--no-same-owner', '-xz', '-C', destination, stdin=archive)
        shell('/system/bin/sh', '-c', f"printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > {destination}/etc/resolv.conf")
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode='w:gz') as archive:
        source = ROOT / 'tooling/go2-lab'
        for file in sorted(source.rglob('*')):
            if file.is_file() and file.suffix in {'.py', '.js', '.html', '.css', '.svg', '.json', '.sh', '.txt', '.wasm', '.task', '.wav', '.md', '.png', '.webp'} and '__pycache__' not in file.parts:
                archive.add(file, arcname='opt/taku-go2/tooling/go2-lab/' + file.relative_to(source).as_posix())
    shell(PREFIX + '/bin/tar', '--no-same-owner', '-xz', '-C', destination, input=buffer.getvalue())
    script = (ROOT / 'tooling/go2-lab/phone/run.sh').read_bytes()
    shell('/system/bin/sh', '-c', f'cat > {HOME}/taku-go2/run.sh && chmod 700 {HOME}/taku-go2/run.sh', input=script)
    shell('/system/bin/sh', '-c', f'mkdir -p {HOME}/.termux; touch {HOME}/.termux/termux.properties; '
          f"sed -i '/^allow-external-apps[[:space:]]*=/d' {HOME}/.termux/termux.properties; "
          f"printf '\nallow-external-apps=true\n' >> {HOME}/.termux/termux.properties")
    if args.personal_map_key:
        key = args.personal_map_key.read_bytes().strip()
        import re
        if not re.fullmatch(rb'[a-fA-F0-9]{32}', key):
            raise SystemExit('Invalid personal map key file')
        path = destination + '/root/Library/Application Support/TakuGo2'
        shell('/system/bin/sh', '-c', f'umask 077; mkdir -p {shlex.quote(path)}; cat > {shlex.quote(path + "/amap-web-service.key")}', input=key)
    subprocess.run(adb + ['shell', 'am', 'broadcast', '-a', 'com.termux.app.reload_style', '-p', 'com.termux'], check=True)
    print('Phone runtime copied. APK launch and on-device validation are still required.')


if __name__ == '__main__':
    main()
