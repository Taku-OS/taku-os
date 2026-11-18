#!/usr/bin/env python3
"""Stage/verify private internal-test Google APK inputs; never installs or publishes."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('google_apps', ROOT / 'image/google_apps.py')
APPS = importlib.util.module_from_spec(spec); spec.loader.exec_module(APPS)


def stage_apk(app, destination, imported=None):
    """Extract only the pinned original APK; never execute archive installer scripts."""
    target = Path(destination) / app['apk']
    if target.exists():
        APPS.require(not target.is_symlink() and target.stat().st_size == app['bytes']
                     and APPS.digest(target) == app['sha256'], 'Existing internal app differs')
        return target
    source = Path(imported) / (app['id'] + '.apk') if imported else None
    if source and source.is_file() and not source.is_symlink():
        APPS.require(source.stat().st_size == app['bytes'] and APPS.digest(source) == app['sha256'],
                     'Imported original APK differs: ' + app['id'])
        incoming = source.open('rb')
    else:
        record = app['source']
        APPS.require(record.get('entry'), 'Original Chrome APK must be imported from its approved browser download')
        transport = ROOT / 'out/dependencies/google-apps-downloads' / (record['sha256'] + '.zip')
        transport.parent.mkdir(parents=True, exist_ok=True)
        if not transport.exists():
            partial = transport.with_suffix('.zip.partial')
            with urllib.request.urlopen(record['url'], timeout=120) as response, partial.open('xb') as out:
                shutil.copyfileobj(response, out, 1024 * 1024)
            APPS.require(partial.stat().st_size == record['bytes'] and APPS.digest(partial) == record['sha256'],
                         'Original app transport differs')
            partial.rename(transport)
        APPS.require(not transport.is_symlink() and transport.stat().st_size == record['bytes']
                     and APPS.digest(transport) == record['sha256'], 'App transport changed')
        archive = zipfile.ZipFile(transport)
        APPS.require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate transport entries')
        incoming = archive.open(record['entry'])
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix('.apk.partial')
    with incoming, partial.open('xb') as out:
        shutil.copyfileobj(incoming, out, 1024 * 1024)
    APPS.require(partial.stat().st_size == app['bytes'] and APPS.digest(partial) == app['sha256'],
                 'Original staged Google APK differs: ' + app['id'])
    partial.chmod(0o644); partial.rename(target)
    return target


def stage(lock, checksum, imported=None):
    destination = APPS.cache_path(ROOT, checksum)
    destination.mkdir(parents=True, exist_ok=True)
    for name, raw in APPS.generated_files(lock).items():
        path = destination / name
        if path.exists():
            APPS.require(not path.is_symlink() and path.read_bytes() == raw, 'Existing generated app policy differs')
        else:
            with path.open('xb') as out: out.write(raw)
            path.chmod(0o644)
    for app in lock['apps']:
        stage_apk(app, destination, imported)
    return destination


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('stage', 'verify'))
    parser.add_argument('--apk-dir', type=Path, help='Original APKs; hashes/package names remain mandatory')
    args = parser.parse_args()
    lock, checksum = APPS.load_lock()
    directory = stage(lock, checksum, args.apk_dir) if args.action == 'stage' else APPS.cache_path(ROOT, checksum)
    print(json.dumps({**APPS.verify_source(directory, lock), 'lockSha256': checksum, 'cache': str(directory)}))
