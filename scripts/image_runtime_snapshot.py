#!/usr/bin/env python3
"""Bounded, content-addressed runtime handoff preparation; never build or deploy."""
import hashlib
import json
import pathlib
import re
import shutil
import sys
import runpy
import xml.etree.ElementTree as ET

PARENT = '553af3d23f1db36326ab3617e2aa024f58edd7f3fecf0799e51da91a1bc91afe'
PARENT_MANIFEST = 'e60d047be90821b01a438a97315102d20759c6212a10669d4229e8a330668261'
PAYLOAD_PREFIXES = ('system/assistant/', 'system/inputmethod/', 'system/memory/',
                    'system/ai/', 'system/surfaces/')
PAYLOAD_FILES = {'adapters/magpie-runtime/magpie', 'adapters/magpie-runtime/taku-magpie.rc',
                 'image/runtime/taku-runtime.rc'}
CONTROLLER_PATHS = {'image/build.sh', 'image/taku.mk', 'scripts/pixel9-image.py'}
FINAL_MANIFEST = '53502abe387930ae0541c0faddea301835798d25fc418f4961420e13d7dcd4dc'
FINAL_PACKET = 'ed8549050bcdbe58aadee249c22fffa23dd4b88df538fdcada56fd785d25e658'
FINAL_COMMIT = '5fc006aa09c315642d448ce1f05c4b54a7286919'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    value = hashlib.sha256()
    with pathlib.Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def ordinary(path, root):
    path, root = pathlib.Path(path), pathlib.Path(root).resolve()
    require(path.is_file() and not path.is_symlink() and path.resolve().is_relative_to(root)
            and not any(parent.is_symlink() for parent in path.parents if parent != root.parent),
            'Runtime input must be an ordinary contained file: ' + str(path))
    return path


def load_handoff(parent, bundle, manifest_sha, packet, packet_sha, expected_parent=PARENT,
                 expected_parent_manifest=PARENT_MANIFEST):
    """Caller supplies the independently reviewed exact packet/manifest hashes."""
    parent, bundle, packet = pathlib.Path(parent), pathlib.Path(bundle), pathlib.Path(packet)
    require(all(re.fullmatch(r'[0-9a-f]{64}', value or '') for value in
                (manifest_sha, packet_sha, expected_parent, expected_parent_manifest)), 'Expected runtime input hashes')
    ordinary(packet, packet.parent)
    require(sha(packet) == packet_sha, 'Runtime packet differs from reviewed bytes')
    manifest_path = ordinary(bundle / 'manifest.json', bundle)
    require(sha(manifest_path) == manifest_sha, 'Runtime manifest differs from reviewed bytes')
    manifest = json.loads(manifest_path.read_text())
    require(manifest.get('schema') == 'taku.system-ai-rom-handoff/1'
            and manifest.get('baseSnapshotDigest') == expected_parent
            and manifest.get('baseManifestSha256') == expected_parent_manifest
            and re.fullmatch(r'[0-9a-f]{40}', manifest.get('sourceCommit', '')) is not None
            and manifest.get('credentialsIncluded') is False and manifest.get('physicalVerified') is False
            and manifest.get('deletions') == []
            and set(manifest.get('mergeOnlyControllerEdits', [])) == CONTROLLER_PATHS,
            'Unexpected runtime handoff identity/scope')
    frozen_path = ordinary(parent / 'input-manifest.json', parent)
    require(sha(frozen_path) == expected_parent_manifest, 'Frozen runtime parent manifest differs')
    frozen = json.loads(frozen_path.read_text())
    require(frozen.get('digest') == expected_parent
            and hashlib.sha256(json.dumps(frozen['files'], sort_keys=True).encode()).hexdigest() == expected_parent
            and frozen.get('integrationProfile') == 'taku-branding-v1', 'Runtime parent must be the approved branding snapshot')
    previous = {item['path']: item['sha256'] for item in frozen['files']}
    require(len(previous) == len(frozen['files']), 'Duplicate frozen input path')
    for name, checksum in previous.items():
        require(sha(ordinary(parent / name, parent)) == checksum, 'Frozen parent bytes changed: ' + name)
    replacements, inherited = manifest.get('ownedReplacementsAndAdds'), manifest.get('unchangedOwnedInputs')
    require(isinstance(replacements, list) and replacements and isinstance(inherited, list), 'Missing runtime input closure')
    paths, payloads = set(), {}
    for replacing, records in ((True, replacements), (False, inherited)):
        for item in records:
            name = item.get('path', '')
            pure = pathlib.PurePosixPath(name)
            require(name and not pure.is_absolute() and '..' not in pure.parts and name == str(pure)
                    and name not in paths and (name in PAYLOAD_FILES or name.startswith(PAYLOAD_PREFIXES))
                    and re.fullmatch(r'[0-9a-f]{64}', item.get('sha256', '')) is not None,
                    'Unexpected or duplicate runtime payload path: ' + name)
            paths.add(name)
            if replacing:
                require(previous.get(name) == item.get('beforeSha256'), 'Runtime parent preimage differs: ' + name)
                source = ordinary(bundle / 'payload' / name, bundle / 'payload')
                require(type(item.get('bytes')) is int and source.stat().st_size == item['bytes']
                        and sha(source) == item['sha256'], 'Runtime payload changed: ' + name)
                payloads[name] = source
            else:
                require(previous.get(name) == item['sha256'], 'Unchanged runtime dependency differs: ' + name)
    present = {str(path.relative_to(bundle / 'payload')) for path in (bundle / 'payload').rglob('*')
               if path.is_file() or path.is_symlink()}
    require(present == set(payloads), 'Unlisted or missing runtime payload inputs')
    return manifest, frozen, payloads


def product_hook(data):
    """Only add the two requested modules to frozen product bytes, never WIP."""
    anchor = b'    TakuAssistant \\\n'
    require(data.count(anchor) == 1 and b'TakuInputMethod' not in data and b'TakuMemory' not in data,
            'Frozen product module anchor differs')
    return data.replace(anchor, b'    TakuMemory \\\n    TakuInputMethod \\\n' + anchor)


def prepare(out, base, bundle, manifest_sha, packet, packet_sha):
    """Freeze a review-only candidate. Final native/runtime gates are mandatory later."""
    require(base == PARENT, 'Runtime integration requires the exact reviewed branding parent')
    out, bundle = pathlib.Path(out), pathlib.Path(bundle)
    parent = out / 'snapshots' / base
    manifest, frozen, payloads = load_handoff(parent, bundle, manifest_sha, packet, packet_sha,
                                             expected_parent=base, expected_parent_manifest=PARENT_MANIFEST)
    sources = {item['path']: parent / item['path'] for item in frozen['files']}
    sources.update(payloads)
    sources['image/taku.mk'] = product_hook((parent / 'image/taku.mk').read_bytes())
    sources['image/system-ai-handoff.json'] = (bundle / 'manifest.json').read_bytes()
    entries = [{'path': name, 'sha256': hashlib.sha256(source).hexdigest() if isinstance(source, bytes) else sha(source)}
               for name, source in sorted(sources.items())]
    key = hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()
    descriptor = {'schema': 'taku.system-ai-runtime-input/1', 'sourceCommit': manifest['sourceCommit'],
                  'handoffManifestSha256': manifest_sha, 'packetSha256': packet_sha,
                  'baseSnapshotDigest': base, 'readyForBuild': False,
                  'note': 'Review-only framework: final native locks and success/export gates are not yet connected'}
    metadata = {'digest': key, 'files': entries, 'parentSnapshotDigest': base,
                'overlayPaths': sorted(set(payloads) | {'image/taku.mk', 'image/system-ai-handoff.json'}),
                'parentDerivedPaths': ['image/taku.mk'], 'integrationProfile': 'taku-branding-v1',
                'runtimeIntegration': descriptor}
    destination = out / 'runtime-preparations' / key
    if destination.exists():
        require(json.loads((destination / 'input-manifest.json').read_text()) == metadata,
                'Existing runtime preparation provenance differs')
        for item in entries:
            require(sha(ordinary(destination / item['path'], destination)) == item['sha256'],
                    'Existing runtime preparation bytes differ')
    else:
        destination.mkdir(parents=True)
        for item in entries:
            target = destination / item['path']; target.parent.mkdir(parents=True, exist_ok=True)
            source = sources[item['path']]
            if isinstance(source, bytes):
                target.write_bytes(source)
            else:
                shutil.copy2(source, target)
            require(sha(target) == item['sha256'], 'Runtime input changed while freezing')
        (destination / 'input-manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
    return destination, key


def build_hook(data, content_cache=False):
    anchor = b'    phase integrate\n'
    require(data.count(anchor) == 1 and b'prepare-rom.py' not in data, 'Frozen build integration anchor differs')
    data = data.replace(anchor, anchor + b'    python3 /taku/system/inputmethod/prepare-rom.py --verify-only\n')
    if content_cache:
        old = b'    rsync -a --delete /taku/ vendor/taku/\n'
        require(data.count(old) == 1, 'Frozen source synchronization anchor differs')
        # A restored snapshot can have timestamps older than Ninja outputs.
        # Compare bytes and give only changed inputs a fresh destination mtime.
        data = data.replace(old, b'    rsync -a --checksum --no-times --delete /taku/ vendor/taku/\n')
    return data


def reviewed_contract(path, expected_sha):
    """Explicit review binding for a new handoff; legacy pinned inputs stay fixed."""
    path = pathlib.Path(path)
    require(re.fullmatch(r'[0-9a-f]{64}', expected_sha or '') is not None,
            'Expected independently reviewed runtime contract hash')
    raw = ordinary(path, path.parent).read_bytes()
    require(hashlib.sha256(raw).hexdigest() == expected_sha, 'Reviewed runtime contract changed')
    value = json.loads(raw)
    fields = {'schema', 'baseSnapshotDigest', 'baseManifestSha256', 'handoffManifestSha256',
              'packetSha256', 'sourceCommit', 'versions', 'replacementCount', 'unchangedCount', 'cachePolicy'}
    require(isinstance(value, dict) and set(value) == fields
            and value['schema'] == 'taku.reviewed-runtime-handoff/1'
            and all(re.fullmatch(r'[0-9a-f]{64}', value[k] or '') is not None for k in
                    ('baseSnapshotDigest', 'baseManifestSha256', 'handoffManifestSha256', 'packetSha256'))
            and re.fullmatch(r'[0-9a-f]{40}', value['sourceCommit'] or '') is not None
            and set(value['versions']) == {'ime', 'assistant', 'memory'}
            and all(type(n) is int and n > 0 for n in value['versions'].values())
            and all(type(value[k]) is int and value[k] > 0 for k in ('replacementCount', 'unchangedCount'))
            and value['cachePolicy'] == 'checksum-changed-inputs-fresh-mtime',
            'Unexpected reviewed runtime contract')
    return value, raw


def artifact_hook(data):
    text = data.decode()
    anchors = ['product_branding=None\n', "    prohibited=['NexusLauncher'", "if product_branding:receipt['productBranding']=product_branding\n"]
    require(all(text.count(anchor) == 1 for anchor in anchors), 'Frozen artifact verifier anchors differ')
    text = text.replace(anchors[0], anchors[0] + 'system_ai_integration=None\n')
    gate = """    runtime_root=pathlib.Path(__file__).resolve().parents[1]
    runtime_present=(runtime_root/'image/system-ai-handoff.json').is_file()
    if runtime_present != bool(run_input.get('runtimeIntegration')):
        raise SystemExit('Runtime snapshot/run identity is missing or inconsistent')
    if runtime_present:
        runtime_gate=runpy.run_path(str(runtime_root/'image/verify-system-ai.py'),run_name='baked_system_ai_gate')
        system_ai_integration=runtime_gate['verify'](runtime_root,archive,results,run_input_bytes)
"""
    text = text.replace(anchors[1], gate + anchors[1])
    return text.replace(anchors[2], anchors[2] + "if system_ai_integration:receipt['systemAiIntegration']=system_ai_integration\n").encode()


def derive(out, root, base, bundle, manifest_sha, packet, packet_sha, contract=None, contract_sha=None):
    """Only the independently reviewed final native/component closure is buildable."""
    require((contract is None) == (contract_sha is None), 'Runtime contract requires its exact reviewed hash')
    policy, policy_raw = reviewed_contract(contract, contract_sha) if contract is not None else ({
        'baseSnapshotDigest': PARENT, 'baseManifestSha256': PARENT_MANIFEST,
        'handoffManifestSha256': FINAL_MANIFEST, 'packetSha256': FINAL_PACKET,
        'sourceCommit': FINAL_COMMIT, 'replacementCount': 107, 'unchangedCount': 113,
        'versions': {'ime': 10062, 'assistant': 64, 'memory': 8}}, None)
    require(base == policy['baseSnapshotDigest'] and manifest_sha == policy['handoffManifestSha256']
            and packet_sha == policy['packetSha256'],
            'Only the reviewed final runtime handoff may produce build inputs')
    out, root, bundle = pathlib.Path(out), pathlib.Path(root), pathlib.Path(bundle)
    parent = out / 'snapshots' / base
    handoff, frozen, payloads = load_handoff(parent, bundle, manifest_sha, packet, packet_sha,
                                            expected_parent=base, expected_parent_manifest=policy['baseManifestSha256'])
    require(handoff['sourceCommit'] == policy['sourceCommit'] and len(payloads) == policy['replacementCount']
            and len(handoff['unchangedOwnedInputs']) == policy['unchangedCount']
            and handoff.get('versions') == policy['versions'],
            'Final runtime closure/version identity differs')
    sources = {item['path']: parent / item['path'] for item in frozen['files']}; sources.update(payloads)
    sources.update({'image/taku.mk': product_hook((parent / 'image/taku.mk').read_bytes()),
                    'image/build.sh': build_hook((parent / 'image/build.sh').read_bytes(), content_cache=policy_raw is not None),
                    'image/verify-artifacts.py': artifact_hook((parent / 'image/verify-artifacts.py').read_bytes()),
                    'image/system-ai-handoff.json': (bundle / 'manifest.json').read_bytes(),
                    'image/verify-system-ai.py': ordinary(root / 'image/verify-system-ai.py', root)})
    def content(name):
        value = sources[name]
        return value if isinstance(value, bytes) else value.read_bytes()
    actual = {'ime': json.loads(content('system/inputmethod/rom-input-lock.json'))['versionCode']}
    for name in ('assistant', 'memory'):
        actual[name] = int(ET.fromstring(content('system/' + name + '/AndroidManifest.xml')).get('{http://schemas.android.com/apk/res/android}versionCode'))
    require(actual == policy['versions'], 'Runtime versions differ from source manifests/IME lock')
    entries = [{'path': name, 'sha256': hashlib.sha256(source).hexdigest() if isinstance(source, bytes) else sha(source)}
               for name, source in sorted(sources.items())]
    key = hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()
    descriptor = {'schema': 'taku.system-ai-runtime-input/1', 'sourceCommit': policy['sourceCommit'],
                  'handoffManifestSha256': manifest_sha, 'packetSha256': packet_sha, 'baseSnapshotDigest': base,
                  'versions': handoff['versions'], 'readyForBuild': True}
    if policy_raw is not None:
        descriptor['reviewedHandoff'] = {'json': policy_raw.decode('utf-8'), 'sha256': contract_sha}
    overlays = sorted(set(payloads) | {'image/taku.mk', 'image/build.sh', 'image/verify-artifacts.py',
                                      'image/system-ai-handoff.json', 'image/verify-system-ai.py'})
    original = {item['path'] for item in frozen['files']}
    metadata = {'digest': key, 'files': entries, 'parentSnapshotDigest': base, 'overlayPaths': overlays,
                'parentDerivedPaths': ['image/build.sh', 'image/taku.mk', 'image/verify-artifacts.py'],
                'addedPaths': sorted(set(overlays) - original), 'integrationProfile': 'taku-branding-v1',
                'runtimeIntegration': descriptor}
    destination = out / 'snapshots' / key
    if destination.exists():
        require(json.loads((destination / 'input-manifest.json').read_text()) == metadata,
                'Existing runtime snapshot provenance differs')
        for item in entries:
            require(sha(ordinary(destination / item['path'], destination)) == item['sha256'], 'Runtime snapshot bytes differ')
    else:
        destination.mkdir(parents=True)
        for item in entries:
            target = destination / item['path']; target.parent.mkdir(parents=True, exist_ok=True)
            source = sources[item['path']]
            if isinstance(source, bytes): target.write_bytes(source)
            else: shutil.copy2(source, target)
            require(sha(target) == item['sha256'], 'Runtime input changed while freezing')
        (destination / 'input-manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
    import runpy
    previous_bytecode = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        stage = runpy.run_path(str(destination / 'system/inputmethod/prepare-rom.py'), run_name='frozen_runtime_input_check')
        lock = json.loads((destination / 'system/inputmethod/rom-input-lock.json').read_text())
        apk = stage['validate_inputs'](destination, lock)
        require(len(list(stage['native_entries'](apk, lock))) == len(lock['jni']) == 24
                and len(lock['jniSymlinks']) == 2, 'Final runtime native closure differs')
    finally:
        sys.dont_write_bytecode = previous_bytecode
    require('skip_preprocessed_apk_checks' not in (destination / 'system/inputmethod/Android.bp').read_text(),
            'Runtime must not bypass the fixed-source preprocessed APK checker')
    return destination, key


def repair(out, root, base, bundle, manifest_sha, packet, packet_sha, minimum_versions):
    """Apply one reviewed source packet to frozen runtime inputs, never working-tree components."""
    out, root, bundle, packet = map(pathlib.Path, (out, root, bundle, packet))
    contract = runpy.run_path(str(root / 'image/source_repair.py'), run_name='source_repair_contract')
    contract['versions'](minimum_versions)
    require(all(re.fullmatch('[0-9a-f]{64}', x or '') for x in (base, manifest_sha, packet_sha)),
            'Expected independently reviewed repair hashes')
    parent = out / 'snapshots' / base
    parent_raw = ordinary(parent / 'input-manifest.json', parent).read_bytes()
    frozen = json.loads(parent_raw)
    require(frozen.get('digest') == base and frozen.get('integrationProfile') == 'taku-branding-v1'
            and frozen.get('runtimeIntegration', {}).get('readyForBuild') is True, 'Repair requires frozen runtime inputs')
    previous = {i['path']: i['sha256'] for i in frozen['files']}
    require(len(previous) == len(frozen['files'])
            and contract['checksum'](json.dumps(frozen['files'], sort_keys=True).encode()) == base,
            'Repair parent input digest differs')
    sources = {name: ordinary(parent / name, parent) for name in previous}
    require(all(sha(source) == previous[name] for name, source in sources.items()), 'Repair parent bytes changed')
    require(sha(ordinary(packet, packet.parent)) == packet_sha, 'Repair packet differs from reviewed bytes')
    manifest_raw = ordinary(bundle / 'manifest.json', bundle).read_bytes()
    require(contract['checksum'](manifest_raw) == manifest_sha, 'Repair manifest differs from reviewed bytes')
    manifest = json.loads(manifest_raw)
    listed = set()
    for item in manifest.get('files', []):
        name = item.get('path', ''); contract['source_path'](name)
        require(name not in listed, 'Duplicate repair payload')
        listed.add(name)
        source = ordinary(bundle / 'payload' / name, bundle / 'payload')
        raw = source.read_bytes()
        require(len(raw) == item.get('bytes')
                and contract['checksum'](raw) == item.get('sha256')
                and oct(source.stat().st_mode & 0o777) == item.get('mode'), 'Repair source bytes/mode differ: ' + name)
        kind = contract['source_content'](name, raw,
            allow_resources=manifest.get('schema') == 'taku.system-ai-source-repair/2')
        require(manifest.get('schema') != 'taku.system-ai-source-repair/2' or item.get('contentType') == kind,
                'Repair resource type differs: ' + name)
        sources[name] = source
    present = {str(p.relative_to(bundle / 'payload')) for p in (bundle / 'payload').rglob('*') if p.is_file() or p.is_symlink()}
    require(present == listed, 'Unlisted or missing repair payload')
    capsule = (json.dumps({'schema': 'taku.source-repair-capsule/1', 'manifestJson': manifest_raw.decode(),
                          'parentManifestJson': parent_raw.decode(), 'packetSha256': packet_sha}, indent=2) + '\n').encode()
    proof = {'capsuleSha256': contract['checksum'](capsule), 'manifestSha256': manifest_sha,
             'packetSha256': packet_sha, 'parentSnapshotDigest': base,
             'parentManifestSha256': contract['checksum'](parent_raw), 'sourceCommit': manifest.get('sourceCommit'),
             'versions': manifest.get('versions'), 'minimumVersions': minimum_versions}
    sources.update({contract['CAPSULE']: capsule,
                    contract['HELPER']: ordinary(root / contract['HELPER'], root),
                    'image/verify-system-ai.py': ordinary(root / 'image/verify-system-ai.py', root)})
    entries = [{'path': name, 'sha256': contract['checksum'](source) if isinstance(source, bytes) else sha(source)}
               for name, source in sorted(sources.items())]
    contract['validate'](capsule, proof, {i['path']: i['sha256'] for i in entries})
    # Version claims must be actual source manifest/lock values, not packet labels.
    def content(name):
        value = sources[name]
        return value if isinstance(value, bytes) else value.read_bytes()
    actual = {'ime': json.loads(content('system/inputmethod/rom-input-lock.json'))['versionCode']}
    for name in ('assistant', 'memory'):
        actual[name] = int(ET.fromstring(content('system/' + name + '/AndroidManifest.xml')).get('{http://schemas.android.com/apk/res/android}versionCode'))
    require(actual == manifest['versions'], 'Repair versions differ from source manifests/IME lock')
    key = contract['checksum'](json.dumps(entries, sort_keys=True).encode())
    descriptor = dict(frozen['runtimeIntegration'], sourceRepair=proof)
    metadata = {'digest': key, 'files': entries, 'parentSnapshotDigest': base,
                'overlayPaths': sorted(listed | contract['CONTROLS']),
                'addedPaths': sorted(set(sources) - set(previous)), 'parentDerivedPaths': [],
                'integrationProfile': frozen['integrationProfile'], 'runtimeIntegration': descriptor}
    destination = out / 'snapshots' / key
    if destination.exists():
        require(json.loads((destination / 'input-manifest.json').read_text()) == metadata, 'Existing repair provenance differs')
    else:
        destination.mkdir(parents=True)
        for item in entries:
            target = destination / item['path']; target.parent.mkdir(parents=True, exist_ok=True)
            source = sources[item['path']]
            if isinstance(source, bytes): target.write_bytes(source)
            else: shutil.copy2(source, target)
        (destination / 'input-manifest.json').write_text(json.dumps(metadata, indent=2) + '\n')
    require(all(sha(ordinary(destination / i['path'], destination)) == i['sha256'] for i in entries), 'Frozen repair bytes differ')
    old_bytecode = sys.dont_write_bytecode; sys.dont_write_bytecode = True
    try:
        stage = runpy.run_path(str(destination / 'system/inputmethod/prepare-rom.py'), run_name='repaired_ime_inputs')
        lock = json.loads((destination / 'system/inputmethod/rom-input-lock.json').read_text())
        apk = stage['validate_inputs'](destination, lock)
        require(len(list(stage['native_entries'](apk, lock))) == len(lock['jni']) == 24
                and len(lock['jniSymlinks']) == 2, 'Repair native closure differs')
    finally:
        sys.dont_write_bytecode = old_bytecode
    require('skip_preprocessed_apk_checks' not in (destination / 'system/inputmethod/Android.bp').read_text(),
            'Repair must not bypass preprocessed APK checks')
    return destination, key
