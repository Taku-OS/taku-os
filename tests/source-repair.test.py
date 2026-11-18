#!/usr/bin/env python3
"""Synthetic source provenance tests; never APK, ROM or device acceptance."""
import copy
import importlib.util
import json
import pathlib
import runpy
import shutil
import struct
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('repair_snapshot', ROOT / 'scripts/image_runtime_snapshot.py')
RUNTIME = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(RUNTIME)
sys.path.insert(0, str(ROOT / 'scripts'))
import ota_release as RELEASE


class SourceRepair(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = pathlib.Path(temp.name).resolve(); self.out = self.root / 'out'; self.bundle = self.root / 'repair'
        self.files = {'Android.bp': b'unrelated modules stay frozen', 'image/build.sh': b'frozen build gate',
                      'image/taku.mk': b'frozen product branding', 'image/source-lock.json': b'platform lock',
                      'image/kernel-source-lock.json': b'kernel lock', 'image/verify-system-ai.py': b'old gate',
                      'system/assistant/Main.java': b'old source', 'system/inputmethod/Android.bp': b'preprocessed: true',
                      'system/inputmethod/rom-input-lock.json': json.dumps({'versionCode': 10062, 'jni': list(range(24)), 'jniSymlinks': [1, 2]}).encode(),
                      'system/inputmethod/prepare-rom.py': b'def validate_inputs(root, lock): return root\ndef native_entries(apk, lock): return lock["jni"]\n'}
        for name, code in [('assistant', 64), ('memory', 8)]:
            self.files['system/' + name + '/AndroidManifest.xml'] = self.manifest(code)
        entries = [{'path': p, 'sha256': RUNTIME.sha(self.write(self.root / 'parent-files' / p, raw))}
                   for p, raw in sorted(self.files.items())]
        self.base = RUNTIME.runpy.run_path(str(ROOT / 'image/source_repair.py'))['checksum'](json.dumps(entries, sort_keys=True).encode())
        self.parent = self.out / 'snapshots' / self.base
        for p, raw in self.files.items(): self.write(self.parent / p, raw)
        self.write(self.parent / 'input-manifest.json', json.dumps({'digest': self.base, 'files': entries,
                   'integrationProfile': 'taku-branding-v1', 'runtimeIntegration': {'schema': 'taku.system-ai-runtime-input/1',
                   'sourceCommit': 'a' * 40, 'readyForBuild': True, 'versions': {'ime': 10062, 'assistant': 64, 'memory': 8}}}).encode())
        self.payload = {'system/assistant/AndroidManifest.xml': self.manifest(66), 'system/assistant/Main.java': b'reviewed source'}
        for p, raw in self.payload.items(): self.write(self.bundle / 'payload' / p, raw)
        self.data = {'schema': 'taku.system-ai-source-repair/1', 'baseSnapshotDigest': self.base,
                     'baseManifestSha256': RUNTIME.sha(self.parent / 'input-manifest.json'),
                     'baseComponentCommit': 'a' * 40, 'sourceCommit': 'b' * 40,
                     'versions': {'ime': 10062, 'assistant': 66, 'memory': 8},
                     'credentialsIncluded': False, 'physicalVerified': False, 'deletions': [],
                     'files': [{'path': p, 'beforeSha256': RUNTIME.sha(self.parent / p),
                                'sha256': RUNTIME.sha(self.bundle / 'payload' / p), 'bytes': len(raw), 'mode': '0o644'}
                               for p, raw in sorted(self.payload.items())]}
        self.packet = self.write(self.root / 'packet.tar.gz', b'synthetic reviewed packet')
        self.floor = {'ime': 10062, 'assistant': 66, 'memory': 8}
        self.resign()

    @staticmethod
    def manifest(code):
        return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="' + str(code) + '"/>').encode()

    @staticmethod
    def write(path, raw):
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw); path.chmod(0o644); return path

    def resign(self):
        self.write(self.bundle / 'manifest.json', json.dumps(self.data).encode())

    def repair(self, **overrides):
        args = dict(out=self.out, root=ROOT, base=self.base, bundle=self.bundle,
                    manifest_sha=RUNTIME.sha(self.bundle / 'manifest.json'), packet=self.packet,
                    packet_sha=RUNTIME.sha(self.packet), minimum_versions=self.floor)
        args.update(overrides); return RUNTIME.repair(**args)

    def test_freezes_only_listed_sources_and_provenance_with_parent_unchanged(self):
        before = {str(p.relative_to(self.parent)): p.read_bytes() for p in self.parent.rglob('*') if p.is_file()}
        target, _ = self.repair(); self.assertEqual(self.repair()[0], target)
        info = json.loads((target / 'input-manifest.json').read_text())
        self.assertEqual(info['runtimeIntegration']['sourceCommit'], 'a' * 40)
        self.assertEqual(info['runtimeIntegration']['sourceRepair']['sourceCommit'], 'b' * 40)
        self.assertEqual((target / 'Android.bp').read_bytes(), before['Android.bp'])
        self.assertEqual(before, {str(p.relative_to(self.parent)): p.read_bytes() for p in self.parent.rglob('*') if p.is_file()})
        self.assertFalse((self.out / 'run-input.json').exists()); self.assertFalse((self.out / 'status.json').exists())

    def test_display_sources_and_exact_build_dependency_are_bound_to_packet(self):
        for name, raw in {'system/display/src/DisplaySlot.java': b'reviewed display lifecycle',
                          'Android.bp': b'reviewed assistant surface dependency',
                          'image/taku.mk': b'reviewed product selection includes TakuSurfaces'}.items():
            path = self.write(self.bundle / 'payload' / name, raw)
            self.data['files'].append({'path': name, 'sha256': RUNTIME.sha(path),
                'beforeSha256': RUNTIME.sha(self.parent / name) if name in self.files else None,
                'bytes': len(raw), 'mode': '0o644'})
        self.resign()
        target, _ = self.repair()
        self.assertEqual((target / 'system/display/src/DisplaySlot.java').read_bytes(), b'reviewed display lifecycle')
        self.assertEqual((target / 'Android.bp').read_bytes(), b'reviewed assistant surface dependency')
        self.assertIn(b'TakuSurfaces', (target / 'image/taku.mk').read_bytes())
        self.assertEqual((target / 'image/source-lock.json').read_bytes(), self.files['image/source-lock.json'])

    def test_packet_parent_preimage_and_unlisted_payload_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'packet differs'): self.repair(packet_sha='0' * 64)
        self.data['files'][0]['beforeSha256'] = '0' * 64; self.resign()
        with self.assertRaisesRegex(ValueError, 'preimage'): self.repair()
        self.data['files'][0]['beforeSha256'] = RUNTIME.sha(self.parent / self.data['files'][0]['path']); self.resign()
        extra = self.write(self.bundle / 'payload/system/assistant/extra.java', b'unlisted')
        with self.assertRaisesRegex(ValueError, 'Unlisted'): self.repair()
        extra.unlink(); self.write(self.parent / 'image/source-lock.json', b'drift')
        with self.assertRaisesRegex(ValueError, 'parent bytes changed'): self.repair()

    def test_private_binary_deleted_or_symlink_payload_is_rejected(self):
        original = copy.deepcopy(self.data)
        for name in ('image/source-lock.json', 'system/assistant/auth.json', 'system/assistant/.env',
                     'system/inputmethod/prebuilt/TakuInputMethod.apk', 'system/ai/data/session.json', '../escape'):
            self.data = copy.deepcopy(original); self.data['files'][0]['path'] = name; self.resign()
            with self.subTest(path=name), self.assertRaisesRegex(ValueError, 'public source'): self.repair()
        self.data = copy.deepcopy(original); self.data['deletions'] = ['system/assistant/Main.java']; self.resign()
        with self.assertRaisesRegex(ValueError, 'identity/scope'): self.repair()
        self.data = original; self.resign()
        p = self.bundle / 'payload/system/assistant/Main.java'; p.unlink(); p.symlink_to(self.parent / 'system/assistant/Main.java')
        with self.assertRaisesRegex(ValueError, 'ordinary contained'): self.repair()

    def test_versions_must_match_source_and_both_reviewed_and_parent_floors(self):
        self.data['versions']['assistant'] = 65; self.resign()
        with self.assertRaisesRegex(ValueError, 'floor'): self.repair()
        self.data['versions']['assistant'] = 67; self.resign()
        with self.assertRaisesRegex(ValueError, 'source manifests'): self.repair()
        self.data['versions']['assistant'] = 66; self.resign()
        with self.assertRaisesRegex(ValueError, 'floor'): self.repair(minimum_versions=dict(self.floor, assistant=68))

    def test_frozen_capsule_and_inherited_inputs_cannot_drift(self):
        target, _ = self.repair(); capsule = target / 'image/source-repair.json'
        capsule.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'Frozen repair bytes differ'): self.repair()

    def test_archived_capsule_helper_and_descriptor_must_all_survive(self):
        target, _ = self.repair(); manifest=json.loads((target/'input-manifest.json').read_text())
        entries={i['path']:i['sha256'] for i in manifest['files']}; runtime=manifest['runtimeIntegration']
        saved=self.root/'export';saved.mkdir()
        for name in ('source-repair.json','source_repair.py'):shutil.copy2(target/'image'/name,saved/name)
        self.assertEqual(RELEASE.load_source_repair(saved,runtime,entries),(saved/'source-repair.json').read_bytes())
        with self.assertRaisesRegex(RELEASE.ReleaseError,'descriptor'):RELEASE.load_source_repair(saved,{},entries)
        helper=saved/'source_repair.py';raw=helper.read_bytes();helper.write_text('corrupt')
        with self.assertRaisesRegex(RELEASE.ReleaseError,'validator differs'):RELEASE.load_source_repair(saved,runtime,entries)
        helper.write_bytes(raw);helper.unlink()
        with self.assertRaisesRegex(RELEASE.ReleaseError,'Missing'):RELEASE.load_source_repair(saved,runtime,entries)
        self.assertIsNone(RELEASE.load_source_repair(saved,{},{}))

    def test_rehashed_capsule_still_rejects_inherited_drift_and_wrong_version_floor(self):
        target,_=self.repair();info=json.loads((target/'input-manifest.json').read_text())
        entries={i['path']:i['sha256'] for i in info['files']};raw=(target/'image/source-repair.json').read_bytes()
        proof=info['runtimeIntegration']['sourceRepair']
        gate=runpy.run_path(str(ROOT/'image/source_repair.py'))
        gate['validate'](raw,proof,entries)
        entries['image/source-lock.json']='0'*64
        with self.assertRaisesRegex(ValueError,'inherited input'):gate['validate'](raw,proof,entries)
        entries['image/source-lock.json']=RUNTIME.sha(self.parent/'image/source-lock.json')
        proof['minimumVersions']['assistant']=67
        with self.assertRaisesRegex(ValueError,'floor'):gate['validate'](raw,proof,entries)

    def public_resource(self, name, raw, kind):
        self.data['schema'] = 'taku.system-ai-source-repair/2'
        for item in self.data['files']:
            item.setdefault('contentType', 'source-text')
        path = self.write(self.bundle / 'payload' / name, raw)
        self.data['files'].append({'path': name, 'beforeSha256': None,
            'sha256': RUNTIME.sha(path), 'bytes': len(raw), 'mode': '0o644', 'contentType': kind})
        self.resign()

    def test_v2_public_font_and_launcher_assets_are_frozen_and_revalidated(self):
        gate = runpy.run_path(str(ROOT / 'image/source_repair.py'))
        self.assertEqual(len(gate['PUBLIC_RESOURCES']), 17)
        for name, kind in gate['PUBLIC_RESOURCES'].items():
            self.public_resource(name, (ROOT / name).read_bytes(), kind)
        target, _ = self.repair()
        info = json.loads((target / 'input-manifest.json').read_text())
        entries = {i['path']: i['sha256'] for i in info['files']}
        RELEASE.load_source_repair(target / 'image', info['runtimeIntegration'], entries)
        for name in gate['PUBLIC_RESOURCES']:
            self.assertEqual((target / name).read_bytes(), (ROOT / name).read_bytes())
        self.assertEqual((target / 'image/source-lock.json').read_bytes(), self.files['image/source-lock.json'])

    def test_v1_cannot_silently_admit_binary_resources(self):
        name = 'system/assistant/res/font/hanken_grotesk_regular.ttf'
        self.public_resource(name, (ROOT / name).read_bytes(), 'font/ttf')
        self.data['schema'] = 'taku.system-ai-source-repair/1'; self.resign()
        with self.assertRaisesRegex(ValueError, 'UTF-8 source text'):
            self.repair()

    def test_public_resource_type_and_mode_cannot_be_relabelled(self):
        name = 'system/assistant/res/font/hanken_grotesk_regular.ttf'
        self.public_resource(name, (ROOT / name).read_bytes(), 'image/png')
        with self.assertRaisesRegex(ValueError, 'resource type differs'):
            self.repair()
        self.data['files'][-1]['contentType'] = 'font/ttf'
        self.data['files'][-1]['mode'] = '0o755'
        (self.bundle / 'payload' / name).chmod(0o755); self.resign()
        with self.assertRaisesRegex(ValueError, 'must not be executable'):
            self.repair()

    def test_binary_policy_rejects_unknown_paths_corruption_and_font_table_escape(self):
        gate = runpy.run_path(str(ROOT / 'image/source_repair.py'))
        name = 'system/assistant/res/mipmap-mdpi/ic_launcher.png'; png = (ROOT / name).read_bytes()
        with self.assertRaisesRegex(ValueError, 'public source/lock'):
            gate['source_content']('system/assistant/res/drawable/account.png', png, True)
        for bad in (b'not a PNG', png[:-1], png + b'private trailing data'):
            with self.subTest(size=len(bad)), self.assertRaises(ValueError):
                gate['source_content'](name, bad, True)
        font = 'system/assistant/res/font/hanken_grotesk_regular.ttf'
        raw = bytearray((ROOT / font).read_bytes()); struct.pack_into('>I', raw, 20, len(raw) + 1)
        with self.assertRaisesRegex(ValueError, 'table escapes'):
            gate['source_content'](font, bytes(raw), True)
        with self.assertRaisesRegex(ValueError, 'UTF-8 source text'):
            gate['source_content']('system/assistant/Main.java', b'\x00binary', True)


if __name__ == '__main__': unittest.main()
