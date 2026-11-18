#!/usr/bin/env python3
"""Read-only development prerequisites. Never installs, logs in or touches devices."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', action='store_true')
    parser.add_argument('--rom', action='store_true')
    parser.add_argument('--network', action='store_true', help='Anonymously probe locked ROM asset URLs (one byte each)')
    args = parser.parse_args()
    if args.network and not args.rom:
        parser.error('--network requires --rom')
    checks = []

    def record(name, ok, hint):
        checks.append({'name': name, 'ok': bool(ok), 'hint': hint})

    def command(name, argv, minimum, hint):
        try:
            result = subprocess.run(argv, capture_output=True, text=True, timeout=10)
            version = re.search(r'(\d+)(?:\.\d+)+', result.stdout + result.stderr)
            ok = result.returncode == 0 and version and int(version.group(1)) >= minimum
        except (OSError, subprocess.TimeoutExpired):
            ok = False
        record(name, ok, hint)

    record('Python', sys.version_info >= (3, 11), 'Use Python 3.11+ (3.12 recommended); macOS /usr/bin/python3 may be older.')
    command('Node.js', ['node', '--version'], 22, 'Install Node.js 22+ and add it to PATH.')
    java_home = os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
    command('JDK', [javac, '-version'], 17, 'Set JAVA_HOME to a JDK 17+ installation, not a JRE.')
    record('Git checkout', (ROOT / '.git').exists(), 'Use a Git clone; source ZIPs do not contain the metadata required by verify.')
    if args.android or args.rom:
        sdk = os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
        if not sdk:
            sdk = next((str(p) for p in [Path.home() / 'Library/Android/sdk', Path.home() / 'Android/Sdk'] if p.is_dir()), '')
        platform = Path(sdk) / 'platforms' / ('android-' + os.environ.get('TAKU_ANDROID_API', '36')) / 'android.jar'
        build_tools = Path(sdk) / 'build-tools' / os.environ.get('TAKU_BUILD_TOOLS', '36.0.0')
        record('Android SDK', bool(sdk) and platform.is_file() and (build_tools / 'aapt2').is_file(), 'Install platforms;android-36 and build-tools;36.0.0; set ANDROID_SDK_ROOT.')
        record('adb', shutil.which('adb') or (Path(sdk) / 'platform-tools/adb').is_file(), 'Install Android platform-tools. This check does not connect to a device.')
    if args.rom:
        config = json.loads((ROOT / 'image/build-config.json').read_text())
        image = os.environ.get('TAKU_BUILDER_IMAGE', config['builderImage'])
        try:
            result = subprocess.run(['docker', 'image', 'inspect', image], capture_output=True, timeout=15)
            ok = result.returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            ok = False
        record('ROM builder image', ok, 'See image/builder/README.md; set TAKU_BUILDER_IMAGE to your verified local image ID.')
        assets = json.loads((ROOT / 'image/runtime-assets.lock.json').read_text())['assets']
        for asset in assets:
            if not asset.get('url'):
                continue
            name = asset.get('path', 'runtime asset')
            if not args.network:
                continue
            try:
                request = urllib.request.Request(asset['url'], headers={'Range': 'bytes=0-0', 'User-Agent': 'TakuDoctor/1'})
                with urllib.request.urlopen(request, timeout=15) as response:
                    response.read(1)
                    status = response.status
                record(name, status in (200, 206), 'Anonymous HTTP ' + str(status) + '; checksum validation still belongs to asset preparation.')
            except urllib.error.HTTPError as error:
                record(name, False, 'Anonymous HTTP ' + str(error.code) + '; publish a permitted input or build it from source. No credentials were used.')
            except (OSError, urllib.error.URLError):
                record(name, False, 'Anonymous download probe failed; check the network and asset source.')
    print(json.dumps({'ok': all(c['ok'] for c in checks), 'checks': checks,
                     'scope': 'Prerequisites only; not a ROM build, signature check or device acceptance.'}, indent=2))
    return 0 if all(c['ok'] for c in checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
