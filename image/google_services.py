#!/usr/bin/env python3
"""Pinned native Google services dependency and target-files acceptance.

Google binaries stay in ignored build storage. No account or device state is an
input; integrating the product never changes provisioning or user settings.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name('google-services.lock.json')


def digest(path):
    with Path(path).open('rb') as stream:
        return stream_digest(stream)


def stream_digest(stream):
    value = hashlib.sha256()
    for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
        value.update(chunk)
    return value.hexdigest()


def relative(name):
    p = PurePosixPath(name)
    if not isinstance(name, str) or not name or p.is_absolute() or '..' in p.parts or str(p) != name:
        raise ValueError('Invalid Google dependency path')
    return name


def load_lock(path=LOCK):
    raw = Path(path).read_bytes()
    lock = json.loads(raw)
    if (lock.get('schema') != 'taku.google-services-lock/1' or lock.get('androidRelease') != '17'
            or lock.get('architecture') != 'arm64' or lock.get('mountPath') != 'vendor/gapps'
            or lock.get('productMakefile') != 'vendor/gapps/arm64/arm64-vendor.mk'
            or not re.fullmatch('[a-f0-9]{40}', lock.get('commit', ''))
            or lock.get('repository') != 'https://github.com/MindTheGapps/vendor_gapps.git'
            or lock.get('defaultAssistant') != 'org.agentos.agent'
            or lock.get('takuAssistantOverlay') != 'TakuGoogleAssistantOverlay'):
        raise ValueError('Unsupported Google services contract')
    for key in ('sourceFiles', 'installedFiles'):
        entries = lock.get(key, [])
        paths = [relative(i['path']) for i in entries]
        if not entries or paths != sorted(set(paths)):
            raise ValueError('Google contract paths must be unique and sorted: ' + key)
        for entry in entries:
            if (type(entry['bytes']) is not int or entry['bytes'] < 0 or (key == 'installedFiles' and entry['bytes'] == 0)
                    or not re.fullmatch('[a-f0-9]{64}', entry['sha256'])):
                raise ValueError('Invalid Google dependency identity')
            if key == 'sourceFiles' and type(entry.get('executable')) is not bool:
                raise ValueError('Missing source executable identity')
            if key == 'installedFiles' and (type(entry.get('exactBytes')) is not bool
                    or not entry.get('inputs') or any(relative(n) not in source_names(lock) for n in entry['inputs'])):
                raise ValueError('Invalid Google installed-file binding')
    return lock, hashlib.sha256(raw).hexdigest()


def source_names(lock):
    return {i['path'] for i in lock['sourceFiles']}


def verify_source(directory, lock):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Missing Google dependency; run scripts/google-services.py fetch')
    actual = set()
    for parent, dirs, files in os.walk(directory, followlinks=False):
        if Path(parent) == directory:
            dirs[:] = [d for d in dirs if d != '.git']
            files = [f for f in files if f != '.git']
        for name in [*dirs, *files]:
            if (Path(parent) / name).is_symlink():
                raise ValueError('Google source may not contain symlinks')
        actual.update((Path(parent) / name).relative_to(directory).as_posix() for name in files)
    if actual != source_names(lock):
        raise ValueError('Google dependency file set differs from lock')
    for entry in lock['sourceFiles']:
        path = directory / entry['path']
        if (path.stat().st_size != entry['bytes'] or digest(path) != entry['sha256']
                or bool(path.stat().st_mode & 0o111) != entry['executable']):
            raise ValueError('Google dependency differs: ' + entry['path'])
    return {'commit': lock['commit'], 'files': len(actual), 'bytes': sum(i['bytes'] for i in lock['sourceFiles'])}


def cache_path(root, lock):
    return Path(root) / 'out/dependencies/google-services' / ('vendor_gapps-' + lock['commit'])


def build_mount(snapshot, root):
    """Use the frozen candidate's dependency, never the caller's newest lock."""
    snapshot = Path(snapshot)
    if not (snapshot / 'image/google-services.lock.json').is_file():
        return None  # Historical non-GApps runs retain their exact inputs.
    lock, checksum = load_lock(snapshot / 'image/google-services.lock.json')
    path = cache_path(root, lock)
    verify_source(path, lock)
    return {'path': str(path), 'commit': lock['commit'], 'lockSha256': checksum,
            'mountPath': '/src/vendor/gapps', 'readOnly': True}


def artifact_records(archive, lock):
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError('Duplicate target-files entries')
    if any(n.endswith('/30-gapps.sh') or '/overlay/config/config.xml' in n for n in names):
        raise ValueError('Unexpected external GApps restore or overlay ordering; explicit review required')
    def signing_rows(path):
        rows = {}
        for line in archive.read(path).decode().splitlines():
            fields = dict(token.split('=', 1) for token in shlex.split(line) if '=' in token)
            name = fields.get('name')
            if name: rows.setdefault(name, []).append(fields)
        return rows
    def single(rows, name):
        values = rows.get(name, [])
        if len(values) != 1:
            raise ValueError('Missing/duplicate Google signing metadata: ' + name)
        return values[0]
    apk_rows = signing_rows('META/apkcerts.txt')
    apex_rows = signing_rows('META/apexkeys.txt')
    apex_name = 'com.google.android.gmssystem.prodvic.apex'
    apex = single(apex_rows, apex_name)
    if (apex.get('partition') != 'product' or any(apex.get(key) != 'PRESIGNED' for key in
            ('public_key', 'private_key', 'container_certificate', 'container_private_key'))):
        raise ValueError('Google APEX must retain its original signing keys')
    records = []
    for entry in lock['installedFiles']:
        name = entry['path']
        if name.endswith('.apk'):
            signing = single(apk_rows, PurePosixPath(name).name)
            if (signing.get('certificate') != 'PRESIGNED' or signing.get('private_key') != ''
                    or signing.get('partition') != name.split('/')[0].lower()):
                raise ValueError('Google APK must remain presigned: ' + name)
        if name not in names:
            raise ValueError('Missing native Google component: ' + name)
        with archive.open(name) as stream:
            checksum = stream_digest(stream)
        size = archive.getinfo(name).file_size
        if entry['exactBytes'] and (checksum != entry['sha256'] or size != entry['bytes']):
            raise ValueError('Google presigned/package bytes changed: ' + name)
        if not entry['exactBytes']:
            raw = archive.read(name)
            if not raw:
                raise ValueError('Empty processed Google output: ' + name)
            if name.endswith('.so') and (raw[:5] != b'\x7fELF\x02' or raw[5:6] != b'\x01' or raw[18:20] != b'\xb7\x00'):
                raise ValueError('Expected an AArch64 Google shared library')
            if name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(raw)) as jar:
                    if jar.testzip() or not jar.read('classes.dex'):
                        raise ValueError('Invalid Google support dex JAR')
        records.append({'path': name, 'bytes': size, 'sha256': checksum,
                        'exactBytes': entry['exactBytes']})
    for module, prefix in [(i, 'PRODUCT') for i in lock['overlays']] + [(lock['takuAssistantOverlay'], 'SYSTEM_EXT')]:
        candidates = [n for n in names if n.startswith(prefix + '/overlay/') and n.endswith('/' + module + '.apk')]
        if len(candidates) != 1:
            raise ValueError('Missing/ambiguous Google integration RRO: ' + module)
        name = candidates[0]
        with archive.open(name) as stream:
            checksum = stream_digest(stream)
        records.append({'path': name, 'bytes': archive.getinfo(name).file_size, 'sha256': checksum,
                        'module': module})
    forbidden = [n for n in names if n.endswith('.apk') and any(v in n for v in ('NexusLauncher', 'Gemini', 'VelvetTitan'))]
    if forbidden:
        raise ValueError('Unexpected Google launcher/assistant variant')
    assistant_cert = single(apk_rows, 'TakuGoogleAssistantOverlay.apk')
    platform_cert = single(apk_rows, 'framework-res.apk')
    if (not platform_cert.get('certificate') or platform_cert.get('certificate') == 'PRESIGNED'
            or assistant_cert.get('certificate') != platform_cert.get('certificate')
            or assistant_cert.get('partition') != 'system_ext'):
        raise ValueError('Taku assistant overlay must use the platform certificate')
    return sorted(records, key=lambda i: i['path'])


def verify_artifacts(archive, results, run_input, aapt2, root=ROOT):
    root, results = Path(root), Path(results)
    lock, checksum = load_lock(root / 'image/google-services.lock.json')
    binding = run_input.get('googleServices', {})
    if binding != {'commit': lock['commit'], 'lockSha256': checksum,
                   'mountPath': '/src/vendor/gapps', 'readOnly': True}:
        raise ValueError('Google services run-input binding differs')
    verify_source('/src/vendor/gapps', lock)
    records = artifact_records(archive, lock)
    overlay = next(i for i in records if i.get('module') == lock['takuAssistantOverlay'])
    import tempfile
    with tempfile.TemporaryDirectory(prefix='google-overlay-', dir=results) as tmp:
        apk = Path(tmp) / 'overlay.apk'; apk.write_bytes(archive.read(overlay['path']))
        manifest = subprocess.check_output([str(aapt2), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
        resources = subprocess.check_output([str(aapt2), 'dump', 'resources', str(apk)], text=True)
        value = re.search(r'(?m)^ *resource \S+ string/config_defaultAssistant(?P<value>(?:\n(?! *resource ).*)*)', resources)
        values = [] if not value else [line.strip() for line in value.group('value').splitlines() if line.strip()]
        force = re.search(r'(?m)^ *resource \S+ string/config_forceVoiceInteractionServicePackage(?P<value>(?:\n(?! *resource ).*)*)', resources)
        forced_values = [] if not force else [line.strip() for line in force.group('value').splitlines() if line.strip()]
        if ('org.agentos.gapps.overlay' not in manifest
                or not re.search(r'targetPackage[^\n]*="android"', manifest)
                or not re.search(r'isStatic[^\n]*=(?:true|(?:\(type 0x12\))?0xffffffff)(?:\s|$)', manifest)
                or len(values) != 1 or not re.fullmatch(r'\(\) (?:\(string8\) )?"org\.agentos\.agent"', values[0])
                or len(forced_values) != 1 or not re.fullmatch(r'\(\) (?:\(string8\) )?"org\.agentos\.agent"', forced_values[0])):
            raise ValueError('Compiled Taku default-assistant overlay differs')
    report = {'schema': 'taku.google-services-artifacts/1', 'lockSha256': checksum,
              'commit': lock['commit'], 'runInputSha256': digest(results / 'run-input.json'),
              'files': records, 'defaultAssistant': lock['defaultAssistant'],
              'compiledAssistantOverlayVerified': True, 'artifactVerified': True,
              'physicalVerified': False, 'accountLoginVerified': False}
    (results / 'google-services-artifacts.json').write_text(json.dumps(report, indent=2) + '\n')
    return {'report': 'google-services-artifacts.json', 'reportSha256': digest(results / 'google-services-artifacts.json'),
            'lockSha256': checksum, 'commit': lock['commit'], 'artifactVerified': True, 'physicalVerified': False}


def validate_saved(archive, report, lock_path, inputs, entries, proof, report_sha256):
    lock, checksum = load_lock(lock_path)
    if (checksum != entries.get('image/google-services.lock.json')
            or inputs.get('googleServices') != {'commit': lock['commit'], 'lockSha256': checksum,
                                               'mountPath': '/src/vendor/gapps', 'readOnly': True}
            or proof != {'report': 'google-services-artifacts.json', 'reportSha256': report_sha256,
                         'lockSha256': checksum, 'commit': lock['commit'], 'artifactVerified': True, 'physicalVerified': False}
            or report.get('lockSha256') != checksum or report.get('commit') != lock['commit']
            or report.get('schema') != 'taku.google-services-artifacts/1'
            or report.get('files') != artifact_records(archive, lock)
            or report.get('compiledAssistantOverlayVerified') is not True
            or report.get('artifactVerified') is not True or report.get('physicalVerified') is not False
            or report.get('accountLoginVerified') is not False
            or report.get('defaultAssistant') != lock['defaultAssistant']):
        raise ValueError('Archived native Google services proof differs')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('--source', type=Path, required=True)
    args = parser.parse_args()
    contract, _ = load_lock()
    print(json.dumps(verify_source(args.source, contract)))
