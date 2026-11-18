#!/usr/bin/env python3
"""Pin and verify the independently built Home/Agent APK used by the ROM."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import tempfile
import zipfile

APK_PATH = 'system/agent-app/out/TakuAgent.apk'
APK_ENTRY = 'SYSTEM_EXT/app/TakuAgent/TakuAgent.apk'
PACKAGE = 'org.agentos.agent'
HOME = 'org.agentos.agent.home.HomeActivity'
INPUT_FIELDS = ('sha256', 'bytes', 'package', 'versionCode', 'versionName',
                'sourceCommit', 'certificateSha256', 'homeActivity')
LOCAL_PUBLICATION = ('unpublished: upload these bytes unchanged to an immutable Taku-OS/taku-os '
                     'release tag, then add url and githubRelease')
SOURCE_ROOTS = (
    'system/agent-app/', 'system/audio-engine/client/',
    'system/audio-engine/contract/', 'system/assistant/design-system/',
    'system/island/src/org/agentos/island/',
)
ICON_INPUTS = (
    'drawable/ic_launcher_foreground.xml', 'drawable/ic_launcher_monochrome.xml',
    'values/ic_launcher_background.xml', 'mipmap-anydpi-v26/ic_launcher.xml',
    'mipmap-anydpi-v26/ic_launcher_round.xml', 'mipmap-xxxhdpi/ic_launcher.png',
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def config(root):
    return json.loads((root / 'system/agent-app/app.json').read_text())['expo']


def source_paths(files):
    icons = {'system/assistant/res/' + name for name in ICON_INPUTS}
    return sorted(name for name in files if name in icons or (
        name.startswith(SOURCE_ROOTS) and not name.endswith('.md')
        and '/tests/' not in name and '.test.' not in name
        and not name.startswith(('system/agent-app/out/', 'system/agent-app/android/',
                                 'system/agent-app/ios/', 'system/agent-app/node_modules/',
                                 'system/agent-app/public/'))
        and name != 'system/agent-app/Android.bp'))


def source_names(root):
    manifest = root / 'input-manifest.json'
    if manifest.is_file():
        return source_paths([item['path'] for item in json.loads(manifest.read_text())['files']])
    files = subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
    return source_paths(files)


def source_inputs(root):
    names = source_names(root)
    return {name: sha((root / name).read_bytes()) for name in names}


def load_lock(root):
    assets = json.loads((root / 'image/runtime-assets.lock.json').read_text())['assets']
    matches = [item for item in assets if item['path'] == APK_PATH]
    require(len(matches) == 1, 'Missing or duplicate pinned TakuAgent APK input')
    return matches[0]


def validate_inputs(root, lock=None):
    lock = load_lock(root) if lock is None else lock
    app = config(root)
    require(lock.get('path') == APK_PATH and lock.get('package') == PACKAGE,
            'Wrong TakuAgent input identity')
    require(lock.get('versionCode') == app['android']['versionCode']
            and isinstance(lock['versionCode'], int) and not isinstance(lock['versionCode'], bool)
            and lock['versionCode'] >= 8 and lock.get('versionName') == app['version'],
            'TakuAgent version differs from source or cannot replace the old code-7 app')
    require(lock.get('homeActivity') == HOME, 'Wrong TakuAgent HOME activity')
    for field, length in [('sha256', 64), ('certificateSha256', 64), ('sourceCommit', 40)]:
        require(re.fullmatch('[0-9a-f]{' + str(length) + '}', str(lock.get(field, ''))),
                'Invalid TakuAgent ' + field)
    require(isinstance(lock.get('bytes'), int) and not isinstance(lock['bytes'], bool)
            and 0 < lock['bytes'] < 256 * 1024 * 1024, 'Invalid TakuAgent APK size')
    inputs = lock.get('sourceInputs')
    require(isinstance(inputs, dict) and 'system/agent-app/app.json' in inputs
            and 'system/agent-app/package-lock.json' in inputs, 'Missing TakuAgent source binding')
    require(set(inputs) == set(source_names(root)), 'TakuAgent build source file set differs from pinned APK')
    for name, expected in inputs.items():
        path = pathlib.PurePosixPath(name)
        require(not path.is_absolute() and '..' not in path.parts
                and name.startswith('system/'), 'Unsafe TakuAgent source path')
        target = root / name
        require(target.is_file() and not target.is_symlink()
                and target.resolve().is_relative_to(root.resolve()) and sha(target.read_bytes()) == expected,
                'TakuAgent build source differs from pinned APK: ' + name)
    apk = root / APK_PATH
    require(apk.is_file() and not apk.is_symlink(), 'Build or fetch the pinned TakuAgent APK first')
    raw = apk.read_bytes()
    require(len(raw) == lock['bytes'] and sha(raw) == lock['sha256'], 'TakuAgent APK bytes differ from lock')
    return lock


def signer(output):
    values = re.findall(r'^(?:Signer #[0-9]+|V3\.0 Signer):? certificate SHA-256 digest: ([0-9a-f]{64})$', output, re.M)
    require(len(values) == 1, 'TakuAgent must have one actually verified signer')
    return values[0]


def apk_identity(apk, aapt, apksigner):
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    match = re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging)
    require(match is not None and match[1] == PACKAGE, 'Wrong TakuAgent package')
    require('application-debuggable' not in badging, 'TakuAgent must be a release APK')
    require("native-code: 'arm64-v8a'" in badging, 'TakuAgent must contain the reviewed ARM64 build')
    manifest = subprocess.check_output([str(aapt), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
    require(HOME in manifest and 'android.intent.category.HOME' in manifest,
            'TakuAgent APK lacks the new HOME entry')
    require('android:sharedUserId' not in manifest, 'TakuAgent must retain its ordinary application UID')
    certificate = signer(subprocess.check_output([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], text=True))
    with zipfile.ZipFile(apk) as archive:
        require(archive.testzip() is None, 'TakuAgent APK ZIP is corrupt')
        require('assets/index.android.bundle' in archive.namelist(), 'TakuAgent APK lacks its release JS bundle')
        require(any(b'Lorg/agentos/agent/home/HomeActivity;' in archive.read(name)
                    for name in archive.namelist() if re.fullmatch(r'classes[0-9]*\.dex', name)),
                'TakuAgent APK lacks the Home implementation')
    return {'package': match[1], 'versionCode': int(match[2]), 'versionName': match[3],
            'certificateSha256': certificate, 'homeActivity': HOME}


def verify_archive(root, archive, tools, results):
    require(APK_ENTRY in archive.namelist(), 'ROM is missing the pinned TakuAgent UI')
    lock = validate_inputs(root)
    raw = archive.read(APK_ENTRY)
    require(len(raw) == lock['bytes'] and sha(raw) == lock['sha256'],
            'ROM TakuAgent differs from the pinned, tested APK')
    with tempfile.TemporaryDirectory(prefix='agent-ui-', dir=results) as directory:
        apk = pathlib.Path(directory) / 'TakuAgent.apk'
        apk.write_bytes(raw)
        actual = apk_identity(apk, tools / 'aapt2', tools / 'apksigner')
        require(all(actual[key] == lock[key] for key in actual), 'ROM TakuAgent identity differs from lock')
        framework = pathlib.Path(directory) / 'framework-res.apk'
        framework.write_bytes(archive.read('SYSTEM/framework/framework-res.apk'))
        platform = signer(subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(framework)], text=True))
        require(actual['certificateSha256'] == platform, 'TakuAgent cannot access the differently signed platform gateway')
    return {**actual, 'apkEntry': APK_ENTRY, 'apkSha256': sha(raw), 'apkBytes': len(raw),
            'sourceCommit': lock['sourceCommit'], 'sourceInputsSha256': sha(json.dumps(lock['sourceInputs'], sort_keys=True).encode()),
            'platformCertificateSha256': platform,
            'apkSignatureVerified': True, 'physicalDeviceVerified': False}


def input_identity(lock):
    return {key: lock[key] for key in INPUT_FIELDS}


def validate_saved(archive, assets, proof, inputs, entries, platform_certificate):
    matches = [item for item in assets['assets'] if item['path'] == APK_PATH]
    require(len(matches) == 1, 'Missing archived TakuAgent input lock')
    lock = matches[0]
    require(lock.get('sha256') == entries.get(APK_PATH)
            and inputs.get('agentUi') == input_identity(lock), 'Archived TakuAgent input binding differs')
    sources = lock.get('sourceInputs', {})
    require(set(sources) == set(source_paths(entries))
            and all(entries.get(name) == value for name, value in sources.items()),
            'Archived TakuAgent source binding differs')
    require(isinstance(proof, dict) and proof.get('apkEntry') == APK_ENTRY
            and proof.get('apkSignatureVerified') is True
            and proof.get('sourceCommit') == lock['sourceCommit']
            and proof.get('sourceInputsSha256') == sha(json.dumps(sources, sort_keys=True).encode()),
            'Missing or invalid archived TakuAgent proof')
    require(all(proof.get(key) == lock.get(key) for key in
                ('package', 'versionCode', 'versionName', 'homeActivity', 'certificateSha256'))
            and proof.get('platformCertificateSha256') == platform_certificate
            and platform_certificate == lock.get('certificateSha256'),
            'Archived TakuAgent package/platform identity differs')
    raw = archive.read(APK_ENTRY)
    require(len(raw) == proof.get('apkBytes') == lock['bytes']
            and sha(raw) == proof.get('apkSha256') == lock['sha256'], 'Archived TakuAgent APK differs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['check', 'pin'])
    publication = parser.add_mutually_exclusive_group()
    publication.add_argument('--release-tag')
    publication.add_argument('--local', action='store_true',
                             help='pin a locally built candidate with no published release asset')
    parser.add_argument('--tools', type=pathlib.Path)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    if args.action == 'check':
        lock = validate_inputs(root)
        print(json.dumps({key: lock[key] for key in ['path', 'sha256', 'bytes', 'versionCode', 'sourceCommit']}))
        return
    require(args.tools is not None and (args.local or re.fullmatch(r'[A-Za-z0-9._-]+', args.release_tag or '')),
            'pin requires --tools and either an immutable --release-tag or --local')
    inputs = source_inputs(root)
    for name, digest in inputs.items():
        committed = subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=root)
        require(sha(committed) == digest, 'Commit APK source changes before building/pinning: ' + name)
    apk = root / APK_PATH
    identity = apk_identity(apk, args.tools / 'aapt2', args.tools / 'apksigner')
    lock = {'path': APK_PATH, 'bytes': apk.stat().st_size, 'sha256': sha(apk.read_bytes()), **identity,
            'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
            'sourceInputs': inputs}
    if args.local:
        lock['publication'] = LOCAL_PUBLICATION
    else:
        asset_name = f"TakuAgent-{identity['versionName']}-code{identity['versionCode']}-arm64.apk"
        lock['url'] = f'https://github.com/Taku-OS/taku-os/releases/download/{args.release_tag}/{asset_name}'
        lock['githubRelease'] = {'repo': 'Taku-OS/taku-os', 'tag': args.release_tag, 'asset': asset_name}
    validate_inputs(root, lock)
    path = root / 'image/runtime-assets.lock.json'
    data = json.loads(path.read_text())
    data['assets'] = [item for item in data['assets'] if item['path'] != APK_PATH] + [lock]
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    temporary.replace(path)
    print(json.dumps({key: lock[key] for key in ['path', 'sha256', 'bytes', 'versionCode', 'sourceCommit']}))


if __name__ == '__main__':
    main()
