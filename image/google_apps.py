#!/usr/bin/env python3
"""Exact Google-signed, standalone application inputs for internal testing only.

This layer does not replace MindTheGapps, re-sign APKs, merge splits, grant
privileged permissions, or include account/device state in a build.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name('google-apps.lock.json')
DISTRIBUTION = 'internal-test-only'
PACKAGES = {
    'gmail': 'com.google.android.gm',
    'maps': 'com.google.android.apps.maps',
    'youtube': 'com.google.android.youtube',
    'chrome': 'com.android.chrome',
    'photos': 'com.google.android.apps.photos',
    'pixel_weather': 'com.google.android.apps.weather',
    'youtube_music': 'com.google.android.apps.youtube.music',
}
LEGACY_PACKAGES = set(PACKAGES) - {'pixel_weather', 'youtube_music'}
GOOGLE = 'f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83'
GOOGLE_2 = '1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00'
GOOGLE_MEDIA = '3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a'
GOOGLE_MEDIA_2 = '7fd2cea30c3a6022eb29419ce8f6f92ce8a4bd35b0cc879ed3cca6cbf5e9992d'
# Google-owned domains declare these public application signing certificates,
# except Pixel Weather, whose original signing identity is independently catalogued.
# Chrome uses the same Google-owned signing identity as Search/GMS; its actual
# package name and original bytes are additionally pinned, never inferred from a filename.
PUBLISHERS = {
    'gmail': ('https://mail.google.com/.well-known/assetlinks.json', {GOOGLE, GOOGLE_2}),
    'maps': ('https://maps.google.com/.well-known/assetlinks.json', {GOOGLE, GOOGLE_2, GOOGLE_MEDIA}),
    'youtube': ('https://www.youtube.com/.well-known/assetlinks.json', {GOOGLE_MEDIA, GOOGLE_MEDIA_2,
        '5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6'}),
    'chrome': ('https://www.google.com/.well-known/assetlinks.json', {GOOGLE}),
    'photos': ('https://photos.google.com/.well-known/assetlinks.json', {GOOGLE_MEDIA, GOOGLE_MEDIA_2}),
    'pixel_weather': ('https://www.apkmirror.com/apk/google-inc/pixel-weather/pixel-weather-1-1-20260716-979549101-release-release/',
        {'7751c458da05ba9100ad73926e3e50aae3ca63183c6aaeb21d225883439f8aa1'}),
    'youtube_music': ('https://music.youtube.com/.well-known/assetlinks.json',
        {'a2a1ad7ba7f41dfca4514e2afeb90691719af6d0fdbed4b09bbf0ed897701ceb',
         'ca7cdf89092b2c185fd34135c27af890364890063d88474780df65a5685cd311'}),
}


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def relative(name):
    p = PurePosixPath(name)
    require(isinstance(name, str) and name and not p.is_absolute() and '..' not in p.parts
            and str(p) == name, 'Invalid internal Google app path')
    return name


def load_lock(path=LOCK, core_lock=None, allow_legacy=False):
    path = Path(path)
    raw = path.read_bytes()
    value = json.loads(raw)
    core_lock = Path(core_lock) if core_lock else path.with_name('google-services.lock.json')
    legacy = allow_legacy and value.get('schema') == 'taku.internal-google-apps/1'
    require((value.get('schema') == 'taku.internal-google-apps/2' or legacy)
            and value.get('distribution') == DISTRIBUTION
            and value.get('androidRelease') == '17' and value.get('architecture') == 'arm64'
            and value.get('mountPath') == '/src/vendor/taku-google-apps'
            and value.get('coreLockSha256') == digest(core_lock),
            'Internal Google apps scope/platform/core dependency differs')
    apps = value.get('apps', [])
    expected = LEGACY_PACKAGES if legacy else set(PACKAGES)
    require(len(apps) == len(expected) and {a.get('id') for a in apps} == expected,
            'Exactly the reviewed Google app set is required')
    require(len({a.get('module') for a in apps}) == len(expected), 'Duplicate internal Google module')
    for app in apps:
        require(app.get('packageName') == PACKAGES[app['id']]
                and re.fullmatch(r'TakuInternal[A-Za-z]+', app.get('module', ''))
                and app.get('apk') == 'apks/' + app['id'] + '.apk'
                and type(app.get('versionCode')) is int and app['versionCode'] > 0
                and isinstance(app.get('versionName'), str) and app['versionName']
                and type(app.get('minSdk')) is int and 1 <= app['minSdk'] <= 37
                and type(app.get('targetSdk')) is int and app['targetSdk'] >= app['minSdk']
                and type(app.get('bytes')) is int and app['bytes'] > 0
                and re.fullmatch('[a-f0-9]{64}', app.get('sha256', '')),
                'Invalid Google app identity: ' + str(app.get('id')))
        evidence, approved = PUBLISHERS[app['id']]
        require(app.get('signerSha256') and set(app['signerSha256']) <= approved
                and app.get('publisherEvidenceUrl') == evidence,
                'Missing approved Google publisher certificate')
        for key in ('nativeAbis', 'usesLibraries', 'optionalUsesLibraries'):
            require(isinstance(app.get(key), list) and len(set(app[key])) == len(app[key])
                    and all(re.fullmatch(r'[A-Za-z0-9_.-]+', n) for n in app[key]),
                    'Invalid APK dependency identity')
        require(not app['nativeAbis'] or 'arm64-v8a' in app['nativeAbis'],
                'Google app has no ARM64 implementation')
        source = app.get('source', {})
        require(source.get('url', '').startswith('https://')
                and re.fullmatch('[a-f0-9]{64}', source.get('sha256', ''))
                and type(source.get('bytes')) is int and source['bytes'] > 0,
                'Missing pinned app acquisition source')
    records = value.get('sourceFiles', [])
    require([r['path'] for r in records] == sorted({r['path'] for r in records})
            and {r['path'] for r in records} == {a['apk'] for a in apps}
                | {'Android.bp', 'modules.mk', 'NOTICE.internal.txt'},
            'Internal app dependency file set differs')
    for record in records:
        relative(record['path'])
        require(type(record.get('bytes')) is int and record['bytes'] > 0
                and re.fullmatch('[a-f0-9]{64}', record.get('sha256', '')),
                'Invalid internal dependency file identity')
    declared = {r['path']: r for r in records}
    for app in apps:
        require(declared[app['apk']] == {'path': app['apk'], 'bytes': app['bytes'], 'sha256': app['sha256']},
                'App/source file binding differs')
    for name, content in generated_files(value).items():
        require(declared[name] == {'path': name, 'bytes': len(content),
                'sha256': hashlib.sha256(content).hexdigest()}, 'Generated module policy differs')
    return value, hashlib.sha256(raw).hexdigest()


def generated_files(lock):
    def blueprint(value, indent=0):
        if isinstance(value, dict):
            return '{\n' + ''.join(' ' * (indent + 4) + key + ': ' + blueprint(v, indent + 4)
                    + ',\n' for key, v in value.items()) + ' ' * indent + '}'
        if isinstance(value, list):
            return '[' + ''.join(json.dumps(v) + ', ' for v in value) + ']'
        return json.dumps(value)
    modules = []
    for app in lock['apps']:
        props = {'name': app['module'], 'apk': app['apk'], 'filename': app['module'] + '.apk',
                 'product_specific': True, 'presigned': True, 'preprocessed': True,
                 'dex_preopt': {'enabled': False}, 'enforce_uses_libs': True,
                 'uses_libs': app['usesLibraries'], 'optional_uses_libs': app['optionalUsesLibraries']}
        modules.append('android_app_import ' + blueprint(props) + '\n')
    bp = ('// Internal test inputs. Preserve original Google signatures and APK bytes.\n'
          'soong_namespace {}\n\n'
          'package { default_applicable_licenses: ["taku_internal_google_apps_license"], }\n'
          'license {\n    name: "taku_internal_google_apps_license",\n'
          '    license_kinds: ["legacy_proprietary"],\n'
          '    license_text: ["NOTICE.internal.txt"],\n}\n\n' + '\n'.join(modules))
    mk = 'PRODUCT_PACKAGES += ' + ' '.join(a['module'] for a in lock['apps']) + '\n'
    notice = ('Google applications remain proprietary and retain their original notices and signatures.\n'
              'These inputs and resulting images are restricted to internal testing.\n'
              'No permission for public redistribution is asserted. No account state is included.\n'
              'Only the listed original APK entries are imported; upstream installer, removal and overlay scripts are excluded.\n')
    return {name: text.encode() for name, text in
            [('Android.bp', bp), ('modules.mk', mk), ('NOTICE.internal.txt', notice)]}


def cache_path(root, checksum):
    return Path(root) / 'out/dependencies/google-apps-internal' / checksum


def verify_source(directory, lock):
    directory = Path(directory)
    require(directory.is_dir() and not directory.is_symlink(), 'Internal apps cache is missing')
    files = list(directory.rglob('*'))
    require(not any(p.is_symlink() for p in files), 'Symlink in internal app inputs')
    actual = {p.relative_to(directory).as_posix() for p in files if p.is_file()}
    require(actual == {r['path'] for r in lock['sourceFiles']}, 'Internal app source file set differs')
    for item in lock['sourceFiles']:
        p = directory / item['path']
        require(p.stat().st_size == item['bytes'] and digest(p) == item['sha256']
                and not p.stat().st_mode & 0o111, 'Internal app source changed: ' + item['path'])
    return {'files': len(actual), 'bytes': sum(i['bytes'] for i in lock['sourceFiles']), 'distribution': DISTRIBUTION}


def binding(lock, checksum):
    return {'lockSha256': checksum, 'coreLockSha256': lock['coreLockSha256'],
            'mountPath': lock['mountPath'], 'readOnly': True, 'distribution': DISTRIBUTION}


def build_mount(snapshot, root):
    path = Path(snapshot) / 'image/google-apps.lock.json'
    if not path.exists():
        return None
    lock, checksum = load_lock(path)
    cache = cache_path(root, checksum)
    verify_source(cache, lock)
    return {'path': str(cache), **binding(lock, checksum)}


def manifest_record(badging, xml, app):
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']*)'", badging, re.M)
    minimum = re.search(r"^(?:sdkVersion|minSdkVersion):'([0-9]+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'([0-9]+)'", badging, re.M)
    native = re.search(r'^native-code:(.*)$', badging, re.M)
    libraries = lambda kind: re.findall(r"^" + kind + r":\s*(?:name=)?'([^']+)'", badging, re.M)
    launcher = 'launchable-activity:' in badging or (
        'android.intent.action.MAIN' in xml and 'android.intent.category.LAUNCHER' in xml)
    require(package and minimum and target and launcher,
            'Missing launchable Google app identity')
    require(not re.search(r"^package:.*\bsplit='", badging, re.M)
            and not re.search(r'isSplitRequired[^\n]*=(?:true|(?:\(type 0x12\))?0xffffffff)', xml)
            and 'uses-split' not in xml and 'uses-static-library' not in xml,
            'Standalone app requires missing splits/static library; obtain the complete original variant')
    record = {'packageName': package[1], 'versionCode': int(package[2]), 'versionName': package[3],
              'minSdk': int(minimum[1]), 'targetSdk': int(target[1]),
              'nativeAbis': sorted(re.findall(r"'([^']+)'", native[1])) if native else [],
              'usesLibraries': libraries('uses-library'),
              'optionalUsesLibraries': libraries('uses-library-not-required')}
    require(record == {k: app[k] for k in record}, 'Google app manifest differs: ' + app['id'])
    return record


def signer_record(output, app):
    signers = sorted({s.lower() for s in re.findall(
        r'(?m)^(?:Signer #[0-9]+|V3\.[01] Signer:) certificate SHA-256 digest: ([a-fA-F0-9]{64})$', output)})
    require(signers and set(signers) <= set(app['signerSha256']),
            'APK is not signed by an approved Google publisher: ' + app['id'])
    return signers


def inspect_apk(path, app, aapt2, apksigner, zipalign):
    path = Path(path)
    require(path.stat().st_size == app['bytes'] and digest(path) == app['sha256'], 'Google APK bytes differ')
    def call(*args):
        return subprocess.check_output([str(x) for x in args], text=True, stderr=subprocess.STDOUT)
    badging = call(aapt2, 'dump', 'badging', path)
    xml = call(aapt2, 'dump', 'xmltree', path, '--file', 'AndroidManifest.xml')
    signing = call(apksigner, 'verify', '--verbose', '--print-certs', path)
    alignment = call(zipalign, '-c', '-P', '4', '4', path)
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate APK entries')
        for item in archive.infolist():
            if item.filename.endswith('.so'):
                require(item.compress_type == zipfile.ZIP_STORED,
                        'Original APK has compressed JNI; obtain a complete preinstall-compatible original variant')
            if item.filename.startswith('lib/arm64-v8a/') and item.filename.endswith('.so'):
                with archive.open(item) as stream:
                    elf = stream.read(20)
                require(elf[:6] == b'\x7fELF\x02\x01' and elf[18:20] == b'\xb7\x00', 'Invalid ARM64 native library')
    return {'manifest': manifest_record(badging, xml, app), 'signers': signer_record(signing, app),
            'signatureVerified': True, 'zipAligned4KiB': True,
            'aaptBadging': badging, 'aaptManifest': xml, 'signatureOutput': signing,
            'alignmentOutputSha256': hashlib.sha256(alignment.encode()).hexdigest()}


def apk_tools(source_root):
    root = Path(source_root) / 'out/host/linux-x86/bin'
    tools = [root / n for n in ('aapt2', 'apksigner', 'zipalign')]
    require(all(p.is_file() for p in tools), 'Missing native APK verification tools')
    return tools


def verify_tree(product, results, run_input, source_root='/src', root=ROOT):
    lock, checksum = load_lock(Path(root) / 'image/google-apps.lock.json')
    require(run_input.get('googleApps') == binding(lock, checksum)
            and run_input.get('distribution') == DISTRIBUTION, 'Internal app run-input differs')
    verify_source(lock['mountPath'], lock)
    records = []
    for app in lock['apps']:
        name = 'PRODUCT/app/' + app['module'] + '/' + app['module'] + '.apk'
        path = Path(product) / name.lower().split('/')[0] / Path(name).relative_to('PRODUCT')
        records.append({'id': app['id'], 'path': name, 'bytes': app['bytes'], 'sha256': app['sha256'],
                        **inspect_apk(path, app, *apk_tools(source_root))})
    return lock, checksum, records


def artifact_records(archive, lock):
    names = archive.namelist()
    require(len(names) == len(set(names)), 'Duplicate target-files entries')
    require('PRODUCT/etc/build.prop' in names, 'Missing internal distribution boot property')
    properties = dict(line.split('=', 1) for line in archive.read('PRODUCT/etc/build.prop').decode().splitlines()
                      if '=' in line and not line.startswith('#'))
    require(properties.get('ro.taku.image.distribution') == DISTRIBUTION,
            'Internal Google apps cannot lose their device distribution marker')
    expected_paths = {'PRODUCT/app/' + a['module'] + '/' + a['module'] + '.apk' for a in lock['apps']}
    require({n for n in names if '/TakuInternal' in n and n.endswith('.apk')} == expected_paths,
            'Missing or undeclared internal Google app')
    rows = []
    for line in archive.read('META/apkcerts.txt').decode().splitlines():
        rows.append(dict(token.split('=', 1) for token in shlex.split(line) if '=' in token))
    result = []
    for app in lock['apps']:
        name = 'PRODUCT/app/' + app['module'] + '/' + app['module'] + '.apk'
        matches = [r for r in rows if r.get('name') == app['module'] + '.apk']
        require(len(matches) == 1 and matches[0].get('certificate') == 'PRESIGNED'
                and matches[0].get('private_key') == '' and matches[0].get('partition') == 'product',
                'Google app must remain PRESIGNED: ' + app['id'])
        require(name in names and archive.getinfo(name).file_size == app['bytes'], 'Missing Google app: ' + app['id'])
        with archive.open(name) as stream:
            checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        require(checksum == app['sha256'], 'Archived Google app bytes differ: ' + app['id'])
        result.append({'id': app['id'], 'path': name, 'bytes': app['bytes'], 'sha256': checksum})
    return result


def verify_artifacts(archive, results, run_input, aapt2, root=ROOT):
    results = Path(results)
    lock, checksum = load_lock(Path(root) / 'image/google-apps.lock.json')
    require(run_input.get('googleApps') == binding(lock, checksum)
            and run_input.get('distribution') == DISTRIBUTION, 'Internal Google app inputs differ')
    verify_source(lock['mountPath'], lock)
    records = artifact_records(archive, lock)
    apksigner = Path(aapt2).with_name('apksigner'); zipalign = Path(aapt2).with_name('zipalign')
    with tempfile.TemporaryDirectory(prefix='internal-google-apps-', dir=results) as temp:
        for record, app in zip(records, lock['apps']):
            path = Path(temp) / (app['id'] + '.apk')
            with archive.open(record['path']) as incoming, path.open('xb') as outgoing:
                import shutil
                shutil.copyfileobj(incoming, outgoing, 8 * 1024 * 1024)
            record.update(inspect_apk(path, app, aapt2, apksigner, zipalign))
    report = {'schema': 'taku.internal-google-apps-artifacts/1', 'distribution': DISTRIBUTION,
              'lockSha256': checksum, 'coreLockSha256': lock['coreLockSha256'],
              'runInputSha256': digest(results / 'run-input.json'), 'files': records,
              'artifactVerified': True, 'physicalVerified': False}
    (results / 'google-apps-artifacts.json').write_text(json.dumps(report, indent=2) + '\n')
    return {'report': 'google-apps-artifacts.json', 'reportSha256': digest(results / 'google-apps-artifacts.json'),
            **binding(lock, checksum), 'artifactVerified': True, 'physicalVerified': False}


def validate_saved(archive, report, lock_path, inputs, entries, proof, report_hash):
    # Historical five-app releases remain verifiable; new builds require the v2 set.
    lock, checksum = load_lock(lock_path, allow_legacy=True)
    require(checksum == entries.get('image/google-apps.lock.json')
            and inputs.get('googleApps') == binding(lock, checksum) and inputs.get('distribution') == DISTRIBUTION
            and proof == {'report': 'google-apps-artifacts.json', 'reportSha256': report_hash,
                **binding(lock, checksum), 'artifactVerified': True, 'physicalVerified': False},
            'Archived internal Google app binding differs')
    require(report.get('schema') == 'taku.internal-google-apps-artifacts/1'
            and report.get('distribution') == DISTRIBUTION and report.get('artifactVerified') is True
            and report.get('physicalVerified') is False and report.get('lockSha256') == checksum
            and report.get('coreLockSha256') == lock['coreLockSha256'], 'Archived internal app report differs')
    expected = artifact_records(archive, lock)
    require(len(report.get('files', [])) == len(lock['apps']), 'Incomplete internal Google app report')
    for record, raw, app in zip(report['files'], expected, lock['apps']):
        require(all(record.get(k) == v for k, v in raw.items())
                and record.get('signatureVerified') is True and record.get('zipAligned4KiB') is True
                and record.get('manifest') == manifest_record(record['aaptBadging'], record['aaptManifest'], app)
                and record.get('signers') == signer_record(record['signatureOutput'], app),
                'Archived original Google signer/manifest evidence differs')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', type=Path)
    parser.add_argument('--preview-product', type=Path)
    parser.add_argument('--results', type=Path)
    parser.add_argument('--modules', action='store_true')
    args = parser.parse_args()
    contract, checksum = load_lock()
    if args.modules:
        print(' '.join(app['module'] for app in contract['apps']))
    elif args.source:
        print(json.dumps(verify_source(args.source, contract)))
    elif args.preview_product and args.results:
        inputs = json.loads((args.results / 'run-input.json').read_text())
        lock, checksum, files = verify_tree(args.preview_product, args.results, inputs)
        report = {'schema': 'taku.internal-google-apps-preview/1', 'distribution': DISTRIBUTION,
                  'lockSha256': checksum, 'files': files, 'romBuilt': False, 'physicalVerified': False}
        (args.results / 'google-apps-preview.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'originalGoogleApksVerified': True, 'apps': len(files), 'romBuilt': False}))
    else:
        parser.error('Choose --source or --preview-product with --results')
