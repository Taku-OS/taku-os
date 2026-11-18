#!/usr/bin/env python3
"""Bind actual baked runtime/component verification to this immutable image run."""
import hashlib
import json
import os
import pathlib
import re
import runpy
import stat
import subprocess
import sys
import tempfile


def require(value, message):
    if not value:
        raise ValueError(message)


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def signer(output):
    counts = re.findall(r'^Number of signers: ([0-9]+)$', output, re.M)
    certs = re.findall(r'^(?:Signer #[0-9]+|V3\.0 Signer):? certificate SHA-256 digest: ([0-9a-f]{64})$', output, re.M)
    require(counts == ['1'] and len(certs) == 1, 'IME must have exactly one actually verified signer')
    return certs[0]


def validate_saved(archive, report, handoff, lock, descriptor, run_sha, entries, source_repair=None, permission_helper=None):
    # Historical immutable receipts retain their original verifier. A derived
    # permission repair must bind and re-read the installed policy on export.
    policy_source = 'system/memory/org.agentos.memory.xml'
    if policy_source in entries:
        helper_path = pathlib.Path(permission_helper) if permission_helper else pathlib.Path(__file__).parent / 'privapp_permissions.py'
        require(not helper_path.is_symlink() and checksum(helper_path.read_bytes()) == entries.get('image/privapp_permissions.py'),
                'Frozen permission policy validator differs')
        helper = runpy.run_path(str(helper_path))
        policy = report.get('memoryPrivappAllowlist', {})
        actual = helper['verify'](archive, entries[policy_source], policy.get('requestedPermissions'))
        require(policy == actual, 'Saved Memory permission policy proof differs')
        helper['validate_audit'](archive, report.get('privilegedPermissionAudit', {}))
        require(report['privilegedPermissionAudit']['applications']['org.agentos.memory']['requestedPermissions']
                == policy['requestedPermissions'], 'Memory permission request proofs disagree')
        require(re.fullmatch(r'[0-9a-f]{64}', report.get('permissionExtractionToolSha256', '')) is not None,
                'Missing actual APK permission extraction tool identity')
    else:
        require('memoryPrivappAllowlist' not in report and 'privilegedPermissionAudit' not in report,
                'Unexpected Memory permission policy proof')
    review = descriptor.get('reviewedHandoff')
    if review is not None:
        require(isinstance(review, dict) and set(review) == {'json', 'sha256'}
                and isinstance(review['json'], str) and checksum(review['json'].encode()) == review['sha256'],
                'Reviewed runtime contract hash differs')
        policy = json.loads(review['json'])
        require(policy.get('schema') == 'taku.reviewed-runtime-handoff/1'
                and policy.get('baseSnapshotDigest') == descriptor.get('baseSnapshotDigest') == handoff.get('baseSnapshotDigest')
                and policy.get('baseManifestSha256') == handoff.get('baseManifestSha256')
                and policy.get('handoffManifestSha256') == descriptor.get('handoffManifestSha256')
                and policy.get('packetSha256') == descriptor.get('packetSha256')
                and policy.get('sourceCommit') == descriptor.get('sourceCommit') == handoff.get('sourceCommit')
                and policy.get('versions') == descriptor.get('versions') == handoff.get('versions')
                and policy.get('replacementCount') == len(handoff.get('ownedReplacementsAndAdds', []))
                and policy.get('unchangedCount') == len(handoff.get('unchangedOwnedInputs', []))
                and policy.get('cachePolicy') == 'checksum-changed-inputs-fresh-mtime',
                'Reviewed runtime contract identity/closure differs')
    require(descriptor.get('schema') == 'taku.system-ai-runtime-input/1' and descriptor.get('readyForBuild') is True
            and descriptor.get('sourceCommit') == handoff.get('sourceCommit')
            and descriptor.get('baseSnapshotDigest') == handoff.get('baseSnapshotDigest')
            and re.fullmatch(r'[0-9a-f]{64}', descriptor.get('packetSha256', '')) is not None,
            'Runtime handoff identity differs')
    require(report.get('schema') == 'taku.system-ai-rom-verification/1' and report.get('status') == 'artifact_verified'
            and report.get('physicalVerified') is False and report.get('runtimeBindRequired') is False
            and report.get('credentialFilesIncluded') is False and report.get('runtimeIntegration') == descriptor
            and report.get('runInputSha256') == run_sha
            and report.get('handoffManifestSha256') == entries.get('image/system-ai-handoff.json') == descriptor.get('handoffManifestSha256')
            and report.get('imeInputLockSha256') == entries.get('system/inputmethod/rom-input-lock.json'),
            'Runtime report is missing or belongs to another run/input')
    components = report.get('components', {})
    component_versions = descriptor.get('sourceRepair', {}).get('versions', handoff['versions'])
    required = {'app/TakuInputMethod/TakuInputMethod.apk', 'priv-app/TakuAssistant/TakuAssistant.apk',
                'priv-app/TakuMemory/TakuMemory.apk', 'framework/agentos-task-broker.jar', 'bin/magpie',
                'bin/taku-runtime-start', 'etc/init/taku-magpie.rc', 'etc/init/taku-runtime.rc', 'bin/magpie-runtime'}
    if component_versions['assistant'] >= 73:
        required |= {'framework/agentos-virtual-display.jar', 'bin/phone-display', 'priv-app/TakuSurfaces/TakuSurfaces.apk'}
    require(set(components) == required, 'Runtime component proof set differs')
    for name, item in components.items():
        path = 'SYSTEM_EXT/' + name
        require(archive.namelist().count(path) == 1 and not stat.S_ISLNK(archive.getinfo(path).external_attr >> 16),
                'Runtime component missing, duplicated or symlinked: ' + path)
        raw = archive.read(path)
        require(item.get('sha256') == checksum(raw) and item.get('bytes') == len(raw), 'Runtime component hash/size differs: ' + path)
    repair = descriptor.get('sourceRepair')
    require(bool(repair) == (source_repair is not None) == ('image/source-repair.json' in entries),
            'Runtime source repair proof is missing or unexpected')
    versions = handoff['versions']
    if repair:
        contract = runpy.run_path(str(pathlib.Path(__file__).parent / 'source_repair.py'), run_name='runtime_source_repair')
        contract['validate'](source_repair, repair, entries)
        versions = repair['versions']
    for name, package, code in [('app/TakuInputMethod/TakuInputMethod.apk', lock['package'], versions['ime']),
                              ('priv-app/TakuAssistant/TakuAssistant.apk', 'org.agentos.assistant', versions['assistant']),
                              ('priv-app/TakuMemory/TakuMemory.apk', 'org.agentos.memory', versions['memory'])]:
        require(components[name].get('package') == package and components[name].get('versionCode') == code,
                'Runtime APK version/package differs from the reviewed handoff')
    ime = components['app/TakuInputMethod/TakuInputMethod.apk']
    require(ime['sha256'] == lock['sha256'] and ime['bytes'] == lock['bytes']
            and report.get('imeSignerSha256') == lock['signerSha256'] and report.get('imePackagingVerified') is True,
            'Runtime IME input differs')
    signature = report.get('imeSignatureVerification', {})
    require(signature.get('actualExit') == 0 and signature.get('signerSha256') == lock['signerSha256']
            and re.fullmatch(r'[0-9a-f]{64}', signature.get('toolSha256', '')) is not None
            and signer(signature.get('stdout', '')) == lock['signerSha256'], 'Runtime IME signature proof differs')
    native, links = report.get('nativeComponents', {}), report.get('nativeSymlinks', {})
    require(set(native) == {item['installPath'].removeprefix('SYSTEM_EXT/') for item in lock['jni']}
            and links == {item['path'].removeprefix('SYSTEM_EXT/'): item['target'] for item in lock['jniSymlinks']},
            'Runtime native proof closure differs')
    fs = {fields[0]: fields[1:4] for line in archive.read('META/system_ext_filesystem_config.txt').decode().splitlines()
          if len(fields := line.split()) >= 4}
    for item in lock['jni']:
        path = item['installPath']; raw = archive.read(path); proof = native[path.removeprefix('SYSTEM_EXT/')]
        require(archive.namelist().count(path) == 1 and not stat.S_ISLNK(archive.getinfo(path).external_attr >> 16)
                and checksum(raw) == item['sha256'] == proof.get('sha256') and len(raw) == item['bytes'] == proof.get('bytes'),
                'Runtime native bytes differ')
        mode = '0755' if item['executable'] else '0644'
        actual = fs.get('system_ext/' + path.removeprefix('SYSTEM_EXT/'))
        require(actual is not None and actual[0] == '0' and actual[1] in (('0', '2000') if item['executable'] else ('0',))
                and int(actual[2], 8) & 0o7777 == int(mode, 8) and proof.get('mode') == mode
                and proof.get('uid') == int(actual[0]) and proof.get('gid') == int(actual[1]),
                'Runtime native ownership/mode differs')
    for item in lock['jniSymlinks']:
        path = item['path']
        require(archive.namelist().count(path) == 1 and stat.S_ISLNK(archive.getinfo(path).external_attr >> 16)
                and archive.read(path).decode() == item['target'], 'Runtime native symlink differs')
    return True


def verify(root, archive, results, run_input_bytes):
    # Child importlib loaders must never add caches to immutable build inputs.
    sys.dont_write_bytecode = True
    root, results = pathlib.Path(root), pathlib.Path(results)
    inputs = json.loads(run_input_bytes); descriptor = inputs.get('runtimeIntegration', {})
    handoff_bytes = (root / 'image/system-ai-handoff.json').read_bytes()
    handoff = json.loads(handoff_bytes)
    lock_bytes = (root / 'system/inputmethod/rom-input-lock.json').read_bytes(); lock = json.loads(lock_bytes)
    require(checksum(handoff_bytes) == descriptor.get('handoffManifestSha256'), 'Unbound runtime handoff')
    manifest = json.loads((root / 'input-manifest.json').read_text()); entries = {item['path']: item['sha256'] for item in manifest['files']}
    require(manifest['digest'] == inputs['snapshotDigest'] and manifest.get('runtimeIntegration') == descriptor,
            'Runtime run differs from its immutable snapshot')
    stage = runpy.run_path(str(root / 'system/inputmethod/prepare-rom.py'), run_name='runtime_ime_inputs')
    apk = stage['validate_inputs'](root, lock)
    require(len(list(stage['native_entries'](apk, lock))) == len(lock['jni']) == 24,
            'Runtime IME native packaging does not match its 24 locked inputs')
    source = pathlib.Path.cwd(); host = source / 'out/host/linux-x86/bin'
    env = dict(os.environ); env['JAVA_HOME'] = str(source / 'prebuilts/jdk/jdk21/linux-x86')
    env['PATH'] = str(pathlib.Path(env['JAVA_HOME']) / 'bin') + os.pathsep + str(host) + os.pathsep + os.defpath
    def tool(name, *args):
        return subprocess.check_output([str(host / name), *map(str, args)], text=True, env=env)
    xml = tool('aapt2', 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml')
    badging = tool('aapt2', 'dump', 'badging', apk)
    require(re.search(r':extractNativeLibs\([^\n]+\)=false', xml) is not None
            and 'android.permission.INTERNET' not in badging and "native-code: 'arm64-v8a'" in badging,
            'Runtime IME manifest/ABI/extraction contract differs')
    signing = tool('apksigner', 'verify', '--verbose', '--print-certs', apk)
    require(signer(signing) == lock['signerSha256'], 'Runtime IME independent signer differs')
    def apk_badging(raw):
        with tempfile.TemporaryDirectory(prefix='system-ai-apk-', dir=results) as folder:
            path = pathlib.Path(folder) / 'component.apk'; path.write_bytes(raw)
            return tool('aapt2', 'dump', 'badging', path)
    def apk_manifest(raw):
        with tempfile.TemporaryDirectory(prefix='system-ai-manifest-', dir=results) as folder:
            path = pathlib.Path(folder) / 'component.apk'; path.write_bytes(raw)
            return tool('aapt2', 'dump', 'xmltree', path, '--file', 'AndroidManifest.xml')
    gate = runpy.run_path(str(root / 'system/ai/verify-rom.py'), run_name='actual_baked_system_ai')
    report = gate['verify'](archive, root, apk_badging)
    policy = runpy.run_path(str(root / 'image/privapp_permissions.py'))
    memory_badging = apk_badging(archive.read(policy['APK']))
    requested = policy['requested_permissions'](memory_badging)
    report['memoryPrivappAllowlist'] = policy['verify'](archive, entries.get(policy['SOURCE']), requested)
    definitions = policy['permission_definitions'](apk_manifest(archive.read(policy['FRAMEWORK'])))
    report['privilegedPermissionAudit'] = policy['audit'](archive, definitions, apk_badging)
    report['permissionExtractionToolSha256'] = checksum((host / 'aapt2').read_bytes())
    report.update(schema='taku.system-ai-rom-verification/1', runtimeIntegration=descriptor,
                  runInputSha256=checksum(run_input_bytes), handoffManifestSha256=checksum(handoff_bytes),
                  imeInputLockSha256=checksum(lock_bytes), imePackagingVerified=True,
                  imeSignatureVerification={'actualExit': 0, 'signerSha256': signer(signing),
                      'toolSha256': checksum((host / 'apksigner').read_bytes()), 'stdout': signing})
    repair_path = root / 'image/source-repair.json'
    require(bool(descriptor.get('sourceRepair')) == repair_path.is_file(), 'Runtime repair capsule presence differs')
    repair_raw = None
    if descriptor.get('sourceRepair'):
        require(not repair_path.is_symlink()
                and checksum((root / 'image/source_repair.py').read_bytes()) == entries.get('image/source_repair.py'),
                'Runtime repair validator differs from frozen input')
        repair_raw = repair_path.read_bytes()
    validate_saved(archive, report, handoff, lock, descriptor, checksum(run_input_bytes), entries, repair_raw)
    path = results / 'system-ai-rom.json'
    if path.exists():
        require(not path.is_symlink() and json.loads(path.read_text()) == report, 'Existing runtime proof belongs to another input/run')
    else:
        with path.open('x') as output:
            json.dump(report, output, indent=2); output.write('\n'); output.flush(); os.fsync(output.fileno())
    return {'report': path.name, 'reportSha256': checksum(path.read_bytes()),
            'runtimeIntegration': descriptor, 'imeInputLockSha256': checksum(lock_bytes),
            'artifactVerified': True, 'physicalVerified': False}
