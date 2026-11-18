"""Pinned TeleService integration; source and APK proofs never imply phone acceptance."""
import argparse
import hashlib
import json
import os
import pathlib
import re
import runpy
import ssl
import subprocess
import zipfile

UPSTREAM = '2d0e6fb0059b8c1cab748f6f04453c9955a14fe3'
REPOSITORY = 'packages/services/Telephony'
LOADER = 'src/com/android/phone/CarrierConfigLoader.java'
HELPER = 'src/com/android/phone/TakuCarrierDefaults.java'
PATCH_SHA = '3c20670a97effebe6a39d9dfa58d82468bb8260729d4944ba449a1b9f8725779'
ORIGINAL_SHA = '701d68fdbdae4d84fd4cc0f78b0cfde6849861ee79783b26e2da69fa761daede'
PROFILE = 'tokay-china-telecom-volte-v1'
APK_ENTRY = 'SYSTEM/priv-app/TeleService/TeleService.apk'
PRODUCT_APK = 'system/priv-app/TeleService/TeleService.apk'
SCOPE = {'device': 'tokay', 'simHomeOperators': ['46003', '46011'],
         'carrierId': 2237, 'specificCarrierId': 2237, 'requiresLoadedVoiceSim': True,
         'changedKeys': ['carrier_volte_available_bool'],
         'preserveExplicitCarrierApp': True, 'preserveRuntimeOverrides': True,
         'requiresMatchingActiveSubscription': True, 'requiresNonemptyPlatformConfig': True}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def ordinary(path, root):
    path, root = pathlib.Path(path).absolute(), pathlib.Path(root).absolute()
    require(path.is_relative_to(root) and root.is_dir() and not root.is_symlink(),
            'Carrier input must stay inside its root: ' + str(path))
    current = root
    linked = False
    for part in path.relative_to(root).parts:
        current = current / part
        linked = linked or current.is_symlink()
    require(path.resolve().is_relative_to(root.resolve()) and path.is_file() and not linked,
            'Carrier input must be an ordinary in-root file: ' + str(path))
    return path


def load_contract(root=None):
    root = pathlib.Path(root or pathlib.Path(__file__).parent)
    path = ordinary(root / 'carrier-defaults/manifest.json', root)
    manifest = json.loads(path.read_text())
    require(manifest.get('schema') == 'taku.carrier-defaults-patch/1'
            and manifest.get('profile') == PROFILE and manifest.get('repository') == REPOSITORY
            and manifest.get('upstreamCommit') == UPSTREAM and manifest.get('module') == 'TeleService'
            and manifest.get('packageName') == 'com.android.phone' and manifest.get('scope') == SCOPE
            and manifest.get('patch') == 'telecom-volte.patch'
            and manifest.get('patchSha256') == PATCH_SHA, 'Carrier policy contract differs')
    files = manifest.get('files', [])
    require(len(files) == 2 and {i.get('path') for i in files} == {LOADER, HELPER},
            'Carrier target set differs')
    for item in files:
        require(item.get('beforeSha256') == (ORIGINAL_SHA if item['path'] == LOADER else None)
                and re.fullmatch('[0-9a-f]{64}', item.get('afterSha256', '')),
                'Carrier target hashes differ')
    patch = ordinary(path.parent / manifest['patch'], root)
    require(digest(patch) == PATCH_SHA, 'Carrier patch bytes differ')
    return manifest, path, patch


def declaration(root=None):
    manifest, path, _ = load_contract(root)
    return {'profile': PROFILE, 'manifestSha256': digest(path), 'patchSha256': PATCH_SHA,
            'upstreamCommit': UPSTREAM, 'scope': manifest['scope']}


def source_record(root=None):
    manifest, _, _ = load_contract(root)
    return {'schema': 'taku.carrier-defaults-source/1', **declaration(root),
            'files': manifest['files'], 'sourcePatchVerified': True, 'physicalDeviceVerified': False}


def git_revision(repository):
    return subprocess.check_output(['git', '-C', str(repository), 'rev-parse', 'HEAD'], text=True).strip()


def inspect_sources(source, manifest):
    repository = pathlib.Path(source).resolve() / REPOSITORY
    require(repository.is_dir() and not repository.is_symlink(), 'Missing Telephony checkout')
    revision = git_revision(repository)
    require(revision == UPSTREAM, 'Telephony HEAD differs from source lock')
    states = []
    for item in manifest['files']:
        path = repository / item['path']
        current = digest(ordinary(path, repository)) if path.exists() or path.is_symlink() else None
        require(current in (item['beforeSha256'], item['afterSha256']),
                'Telephony source drift: ' + item['path'])
        states.append(current == item['afterSha256'])
    require(all(states) or not any(states), 'Partially applied carrier patch')
    return repository, all(states)


def apply(source, results):
    manifest, path, patch = load_contract()
    repository, applied = inspect_sources(source, manifest)
    if not applied:
        command = ['git', '-C', str(repository), 'apply', '--no-index', '--whitespace=error']
        subprocess.run([*command, '--check', str(patch.resolve())], check=True)
        subprocess.run([*command, str(patch.resolve())], check=True)
    require(inspect_sources(source, manifest)[1], 'Carrier patch did not produce expected source')
    proof = source_record()
    output = pathlib.Path(results) / 'carrier-defaults-source.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        require(json.loads(ordinary(output, output.parent).read_text()) == proof,
                'Existing carrier source proof belongs to different inputs')
    else:
        with output.open('x') as stream:
            stream.write(json.dumps(proof, indent=2) + '\n')
    return proof


def verify_dex(raw):
    import io
    with zipfile.ZipFile(io.BytesIO(raw)) as apk:
        dex = b''.join(apk.read(n) for n in apk.namelist() if re.fullmatch(r'classes[0-9]*\.dex', n))
    for marker in (b'Lcom/android/phone/TakuCarrierDefaults;', b'shouldEnableVolte',
                   b'46003', b'46011', b'tokay'):
        require(marker in dex, 'TeleService is missing retained carrier policy: ' + marker.decode())


def verify_badging(badging):
    packages = re.findall(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.M)
    require(len(packages) == 1 and packages[0][0] == 'com.android.phone'
            and re.findall(r"^targetSdkVersion:'([0-9]+)'", badging, re.M) == ['37'],
            'TeleService package/platform differs')


def verify_apk(source, product, results):
    source, product, results = map(pathlib.Path, (source, product, results))
    manifest, contract, _ = load_contract()
    require(inspect_sources(source, manifest)[1], 'Telephony policy source is not applied')
    apks = [p for p in product.glob('*/priv-app/TeleService/TeleService.apk') if p.is_file()]
    require(len(apks) == 1, 'Expected one product TeleService APK')
    require(apks[0] == product / PRODUCT_APK,
            'TeleService must be in the pinned system priv-app entry')
    apk = ordinary(apks[0], product)
    verify_dex(apk.read_bytes())
    host = source / 'out/host/linux-x86/bin'
    env = dict(os.environ)
    env['JAVA_HOME'] = str(source / 'prebuilts/jdk/jdk21/linux-x86')
    env['PATH'] = str(pathlib.Path(env['JAVA_HOME']) / 'bin') + os.pathsep + str(host) + os.pathsep + os.defpath
    tools = {}
    for name in ('aapt2', 'apksigner'):
        tool = host / name
        require(tool.is_file() and os.access(tool, os.X_OK), 'Missing native carrier verification tool: ' + name)
        tools[name] = {'path': str(tool), 'sha256': digest(tool)}
    badging = subprocess.check_output([str(host / 'aapt2'), 'dump', 'badging', str(apk)], env=env, text=True)
    verify_badging(badging)
    signatures = subprocess.check_output([str(host / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], env=env, text=True)
    pem = (source / 'build/make/target/product/security/platform.x509.pem').read_text()
    certificate = hashlib.sha256(ssl.PEM_cert_to_DER_cert(pem)).hexdigest()
    base = runpy.run_path(str(pathlib.Path(__file__).parent / 'apply-updater-patches.py'))
    base['verify_platform_signer'](signatures, certificate)
    source_proof = ordinary(results / 'carrier-defaults-source.json', results)
    require(json.loads(source_proof.read_text()) == source_record(), 'Carrier source proof differs')
    run_input = ordinary(results / 'run-input.json', results)
    require(json.loads(run_input.read_text()).get('carrierDefaults') == declaration(),
            'Carrier APK is not declared in these run inputs')
    (results / 'carrier-defaults-apk-signature.txt').write_text(signatures)
    (results / 'carrier-defaults-apk-badging.txt').write_text(badging)
    proof = {'schema': 'taku.carrier-defaults-apk/1', **declaration(),
             'apkSha256': digest(apk), 'apkBytes': apk.stat().st_size,
             'platformCertificateSha256': certificate, 'apkSignatureVerified': True,
             'sourceEvidenceSha256': digest(source_proof), 'runInputSha256': digest(run_input),
             'signatureLogSha256': digest(results / 'carrier-defaults-apk-signature.txt'),
             'badgingLogSha256': digest(results / 'carrier-defaults-apk-badging.txt'),
             'tools': tools,
             'physicalDeviceVerified': False}
    (results / 'carrier-defaults-apk.json').write_text(json.dumps(proof, indent=2) + '\n')
    return proof


def verify_archive(archive, results, run_input, contract_root=None):
    results = pathlib.Path(results)
    expected = declaration(contract_root)
    require(run_input.get('carrierDefaults') == expected, 'Archive carrier declaration differs')
    proof_path = ordinary(results / 'carrier-defaults-apk.json', results)
    proof = json.loads(proof_path.read_text())
    require(all(proof.get(k) == v for k, v in expected.items())
            and proof.get('schema') == 'taku.carrier-defaults-apk/1'
            and proof.get('apkSignatureVerified') is True
            and proof.get('physicalDeviceVerified') is False
            and proof.get('runInputSha256') == digest(results / 'run-input.json')
            and proof.get('sourceEvidenceSha256') == digest(results / 'carrier-defaults-source.json'),
            'Carrier APK/source proof differs from this run')
    require(json.loads(ordinary(results / 'carrier-defaults-source.json', results).read_text())
            == source_record(contract_root), 'Carrier source proof content differs')
    signature = ordinary(results / 'carrier-defaults-apk-signature.txt', results)
    badging = ordinary(results / 'carrier-defaults-apk-badging.txt', results)
    require(digest(signature) == proof.get('signatureLogSha256')
            and digest(badging) == proof.get('badgingLogSha256'), 'Carrier native verification log differs')
    updater = json.loads(ordinary(results / 'updater-apk-verification.json', results).read_text())
    platform = updater.get('platformCertificateSha256', '')
    require(re.fullmatch('[0-9a-f]{64}', platform) and updater.get('apkSignatureVerified') is True
            and proof.get('platformCertificateSha256') == platform,
            'Carrier signer differs from this image platform trust')
    base = runpy.run_path(str(pathlib.Path(__file__).parent / 'apply-updater-patches.py'))
    base['verify_platform_signer'](signature.read_text(), platform)
    verify_badging(badging.read_text())
    entries = [n for n in archive.namelist() if n.endswith('/priv-app/TeleService/TeleService.apk')]
    require(entries == [APK_ENTRY], 'Expected pinned target-files TeleService entry')
    raw = archive.read(entries[0])
    require(len(raw) == proof.get('apkBytes') and hashlib.sha256(raw).hexdigest() == proof.get('apkSha256'),
            'Archived TeleService APK differs from verified bytes')
    verify_dex(raw)
    return {**proof, 'apkEntry': entries[0], 'apkEvidenceSha256': digest(proof_path)}


def validate_saved(archive, root, run_input, entries, receipt):
    root = pathlib.Path(root)
    require(digest(ordinary(root / 'carrier_defaults.py', root)) == entries.get('image/carrier_defaults.py'),
            'Archived carrier verifier differs from snapshot')
    for name in ('manifest.json', 'telecom-volte.patch'):
        path = 'carrier-defaults/' + name
        require(digest(ordinary(root / path, root)) == entries.get('image/' + path),
                'Archived carrier contract differs from snapshot')
    report = verify_archive(archive, root, run_input, root)
    require(report == receipt, 'Carrier receipt differs from archive evidence')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['apply', 'verify-apk'])
    parser.add_argument('--source-root', type=pathlib.Path, required=True)
    parser.add_argument('--results', type=pathlib.Path, required=True)
    parser.add_argument('--product', type=pathlib.Path)
    args = parser.parse_args()
    if args.action == 'verify-apk' and not args.product:
        parser.error('verify-apk requires --product')
    print(json.dumps(apply(args.source_root, args.results) if args.action == 'apply'
                     else verify_apk(args.source_root, args.product, args.results), indent=2))
