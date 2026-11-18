#!/usr/bin/env python3
"""Install/status/uninstall the macOS development emulator runtime monitor."""
import argparse
import contextlib
import json
import os
from pathlib import Path
import plistlib
import re
import subprocess
import sys
import tempfile

from watch import AVD, LABEL, MANAGED_BY, SERIAL, atomic_json, digest, load_config

BASE = Path.home() / 'Library/Application Support/TakuOS/emulator-runtime/5582'
PLIST = Path.home() / 'Library/LaunchAgents' / (LABEL + '.plist')
MANIFEST = 'install-manifest.json'


def owned_regular(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_uid != os.getuid():
        raise ValueError('Refusing unowned or non-regular managed file: ' + str(path))


def check_directory(path):
    path = Path(path)
    # An existing ancestor symlink could redirect writes outside the managed location.
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError('Refusing symlink directory: ' + str(part))


def safe_directory(path):
    path = Path(path)
    check_directory(path)
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if path.stat().st_uid != os.getuid():
        raise ValueError('Managed directory is not owned by the current user')


def write_bytes(path, data, mode=0o600):
    path = Path(path)
    fd, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temporary)


def make_plist(base=BASE):
    return {'Label': LABEL,
            'ProgramArguments': [os.path.realpath(sys.executable), str(base / 'watch.py'),
                                 '--config', str(base / 'config.json'),
                                 '--state-dir', str(base / 'state')],
            'RunAtLoad': True, 'KeepAlive': True, 'ThrottleInterval': 15,
            'ProcessType': 'Background',
            'StandardOutPath': '/dev/null', 'StandardErrorPath': '/dev/null'}


def validate_plist(value, base=BASE):
    if value != make_plist(base):
        raise ValueError('Refusing to replace a launchd job with a different definition')
    arguments = value['ProgramArguments']
    if not Path(arguments[0]).is_file() or not os.access(arguments[0], os.X_OK):
        raise ValueError('The fixed Python interpreter is unavailable')
    return value


def read_owned_install(base=BASE, plist=PLIST):
    check_directory(base)
    check_directory(plist.parent)
    manifest = base / MANIFEST
    if any(path.is_symlink() for path in (manifest, plist, base / 'config.json', base / 'watch.py')):
        raise ValueError('Refusing symlink managed file')
    if not manifest.exists():
        if plist.exists() or (base.exists() and any(
                (base / name).exists() for name in ('config.json', 'watch.py'))):
            raise ValueError('Existing files have no managed install manifest; refusing overwrite')
        return None
    owned_regular(manifest)
    record = json.loads(manifest.read_text())
    if (record.get('managedBy'), record.get('schemaVersion'), record.get('serial'),
            record.get('avd'), record.get('label'), record.get('base')) != (
            MANAGED_BY, 1, SERIAL, AVD, LABEL, str(base)):
        raise ValueError('Install manifest belongs to another monitor')
    for name, field in (('config.json', 'configSha256'), ('watch.py', 'watchSha256')):
        path = base / name
        owned_regular(path)
        if digest(path.read_bytes()) != record.get(field):
            raise ValueError('Managed file was changed outside this installer: ' + name)
    config = load_config(base / 'config.json')
    if config['serial'] != SERIAL or config['avd'] != AVD:
        raise ValueError('Installed configuration points at a different target')
    if plist.exists():
        owned_regular(plist)
        current = plistlib.loads(plist.read_bytes())
        if digest(plist.read_bytes()) != record.get('plistSha256'):
            raise ValueError('Managed plist was changed outside this installer')
        # Validate against the interpreter originally recorded, allowing a later Python update.
        expected = make_plist(base)
        expected['ProgramArguments'][0] = record.get('python')
        if current != expected:
            raise ValueError('Existing launchd job definition is not recognized')
    return record


def launchctl(*args, runner=subprocess.run):
    return runner(['/bin/launchctl', *args], capture_output=True, text=True,
                  check=False, timeout=20)


def loaded(runner=subprocess.run):
    return launchctl('print', 'gui/' + str(os.getuid()) + '/' + LABEL,
                     runner=runner).returncode == 0


def validate_loaded_job(record, base, runner):
    result = launchctl('print', 'gui/' + str(os.getuid()) + '/' + LABEL, runner=runner)
    block = re.search(r'(?m)^\s*arguments = \{\s*\n(.*?)^\s*\}',
                      result.stdout, re.DOTALL | re.MULTILINE)
    expected = make_plist(base)['ProgramArguments']
    expected[0] = record['python']
    actual = [line.strip() for line in block.group(1).splitlines()] if block else []
    if result.returncode or actual != expected:
        raise ValueError('Loaded launchd job does not match the owned monitor arguments')


def install(config_path, base=BASE, plist=PLIST, runner=subprocess.run):
    config = load_config(config_path)
    source = Path(__file__).with_name('watch.py')
    source_bytes = source.read_bytes()  # Freeze the exact reviewed source before writing.
    config_bytes = (json.dumps(config, ensure_ascii=False, indent=2) + '\n').encode()
    existing = read_owned_install(base, plist)
    is_loaded = loaded(runner)
    if is_loaded and (existing is None or not plist.exists()):
        raise ValueError('An unknown launchd job already uses this label')
    if is_loaded:
        validate_loaded_job(existing, base, runner)
    definition = validate_plist(make_plist(base), base)
    plist_bytes = plistlib.dumps(definition, fmt=plistlib.FMT_XML, sort_keys=True)
    safe_directory(base)
    safe_directory(plist.parent)
    safe_directory(base / 'state')
    # Only a job whose on-disk ownership and hashes were checked may be stopped.
    if is_loaded:
        result = launchctl('bootout', 'gui/' + str(os.getuid()) + '/' + LABEL, runner=runner)
        if result.returncode:
            raise ValueError('Could not stop the existing managed launchd job')
    write_bytes(base / 'watch.py', source_bytes, 0o700)
    write_bytes(base / 'config.json', config_bytes)
    write_bytes(plist, plist_bytes)
    record = {'schemaVersion': 1, 'managedBy': MANAGED_BY, 'serial': SERIAL,
              'avd': AVD, 'label': LABEL, 'base': str(base),
              'python': definition['ProgramArguments'][0],
              'configSha256': digest(config_bytes), 'watchSha256': digest(source_bytes),
              'plistSha256': digest(plist_bytes)}
    atomic_json(base / MANIFEST, record)
    result = launchctl('bootstrap', 'gui/' + str(os.getuid()), str(plist), runner=runner)
    if result.returncode:
        raise ValueError('launchctl bootstrap failed; managed files were retained for inspection')
    return {'installed': True, 'loaded': loaded(runner), 'label': LABEL,
            'config': str(base / 'config.json'), 'state': str(base / 'state/state.json')}


def status(base=BASE, plist=PLIST, runner=subprocess.run):
    record = read_owned_install(base, plist)
    result = {'installed': record is not None and plist.exists(), 'loaded': loaded(runner),
              'label': LABEL, 'serial': SERIAL, 'avd': AVD}
    state = base / 'state/state.json'
    if state.exists():
        owned_regular(state)
        result['runtime'] = json.loads(state.read_text())
    return result


def uninstall(base=BASE, plist=PLIST, runner=subprocess.run):
    record = read_owned_install(base, plist)
    is_loaded = loaded(runner)
    if record is None:
        if is_loaded:
            raise ValueError('Refusing to stop an unknown launchd job')
        return {'installed': False, 'loaded': False, 'label': LABEL}
    if is_loaded:
        if not plist.exists():
            raise ValueError('Refusing to stop a job without its owned plist')
        validate_loaded_job(record, base, runner)
        result = launchctl('bootout', 'gui/' + str(os.getuid()) + '/' + LABEL, runner=runner)
        if result.returncode:
            raise ValueError('Could not stop the managed launchd job')
    if plist.exists():
        plist.unlink()
    return {'installed': False, 'loaded': False, 'label': LABEL,
            'retainedDirectory': str(base)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('install', 'status', 'uninstall'))
    parser.add_argument('--config', help='Required for install; explicit target and device script hashes')
    args = parser.parse_args(argv)
    if sys.platform != 'darwin':
        parser.error('This installer is for a macOS user LaunchAgent')
    if args.action == 'install' and not args.config:
        parser.error('install requires --config')
    try:
        result = install(args.config) if args.action == 'install' else globals()[args.action]()
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (ValueError, OSError, subprocess.TimeoutExpired) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
