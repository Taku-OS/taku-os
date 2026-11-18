#!/usr/bin/env python3
"""Synthetic review-only handoffs; no builder, image or device acceptance."""
import hashlib
import importlib.util
import json
import pathlib
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('runtime_snapshot', ROOT / 'scripts/image_runtime_snapshot.py')
RUNTIME = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNTIME)


class RuntimePreparation(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = pathlib.Path(temporary.name).resolve()
        self.out, self.bundle = self.root / 'out', self.root / 'handoff'
        self.bundle.mkdir()
        self.files = {'Android.bp': b'preserved modules', 'image/build.sh': b'preserved branding build',
                      'image/taku.mk': b'branding and URI remain\nPRODUCT_PACKAGES_DEBUG += \\\n    TakuAssistant \\\n    agentos-task-broker\n',
                      'image/source-lock.json': b'platform stays pinned',
                      'image/kernel-source-lock.json': b'kernel stays pinned',
                      'system/assistant/Main.java': b'old accepted assistant'}
        entries = [{'path': name, 'sha256': hashlib.sha256(raw).hexdigest()} for name, raw in sorted(self.files.items())]
        self.key = hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()
        self.parent = self.out / 'snapshots' / self.key
        for name, raw in self.files.items():
            self.write(self.parent / name, raw)
        self.write(self.parent / 'input-manifest.json', json.dumps({'digest': self.key, 'files': entries,
                   'integrationProfile': 'taku-branding-v1'}).encode())
        self.parent_sha = RUNTIME.sha(self.parent / 'input-manifest.json')
        self.payloads = {'system/assistant/Main.java': b'reviewed assistant source',
                         'system/inputmethod/prebuilt/TakuInputMethod.apk': b'synthetic independently signed APK'}
        for name, raw in self.payloads.items():
            self.write(self.bundle / 'payload' / name, raw)
        self.manifest = {'schema': 'taku.system-ai-rom-handoff/1', 'sourceCommit': 'a' * 40,
                         'baseSnapshotDigest': self.key, 'baseManifestSha256': self.parent_sha,
                         'credentialsIncluded': False, 'physicalVerified': False, 'deletions': [],
                         'mergeOnlyControllerEdits': sorted(RUNTIME.CONTROLLER_PATHS),
                         'ownedReplacementsAndAdds': [{'path': name, 'beforeSha256': hashlib.sha256(self.files[name]).hexdigest() if name in self.files else None,
                              'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)} for name, raw in sorted(self.payloads.items())],
                         'unchangedOwnedInputs': []}
        self.packet = self.root / 'handoff.tar.gz'; self.packet.write_bytes(b'opaque exact synthetic packet')
        self.resign()
        for name, value in [('PARENT', self.key), ('PARENT_MANIFEST', self.parent_sha)]:
            patch = mock.patch.object(RUNTIME, name, value); patch.start(); self.addCleanup(patch.stop)

    def write(self, path, raw):
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw)

    def resign(self):
        self.write(self.bundle / 'manifest.json', json.dumps(self.manifest).encode())
        self.manifest_sha = RUNTIME.sha(self.bundle / 'manifest.json')

    def prepare(self, **kwargs):
        values = dict(out=self.out, base=self.key, bundle=self.bundle, manifest_sha=self.manifest_sha,
                      packet=self.packet, packet_sha=RUNTIME.sha(self.packet)); values.update(kwargs)
        return RUNTIME.prepare(**values)

    def test_freezes_reviewed_payload_and_parent_product_without_building_or_importing_wip(self):
        before = {p.relative_to(self.parent): p.read_bytes() for p in self.parent.rglob('*') if p.is_file()}
        # An unrelated mutable checkout is deliberately not an input to prepare.
        self.write(self.root / 'main-WIP/Android.bp', b'unreviewed module expansion')
        target, key = self.prepare()
        self.assertEqual(target, self.out / 'runtime-preparations' / key)
        manifest = json.loads((target / 'input-manifest.json').read_text())
        self.assertFalse(manifest['runtimeIntegration']['readyForBuild'])
        self.assertEqual(manifest['runtimeIntegration']['sourceCommit'], 'a' * 40)
        self.assertEqual((target / 'Android.bp').read_bytes(), self.files['Android.bp'])
        self.assertEqual((target / 'image/build.sh').read_bytes(), self.files['image/build.sh'])
        self.assertEqual((target / 'image/taku.mk').read_bytes(), RUNTIME.product_hook(self.files['image/taku.mk']))
        for name, raw in self.payloads.items(): self.assertEqual((target / name).read_bytes(), raw)
        self.assertEqual(before, {p.relative_to(self.parent): p.read_bytes() for p in self.parent.rglob('*') if p.is_file()})
        self.assertEqual(self.prepare(), (target, key))
        self.assertFalse((self.out / 'status.json').exists())
        self.assertFalse((self.out / 'run-input.json').exists())

    def test_packet_manifest_and_parent_hashes_must_be_exact(self):
        for values in ({'packet_sha': '0' * 64}, {'manifest_sha': '0' * 64}, {'base': '0' * 64}):
            with self.subTest(values=values), self.assertRaises(ValueError): self.prepare(**values)
        self.write(self.parent / 'image/source-lock.json', b'unexpected platform change')
        with self.assertRaisesRegex(ValueError, 'parent bytes changed'): self.prepare()

    def test_before_sha_and_inherited_dependencies_are_checked(self):
        old = json.loads(json.dumps(self.manifest))
        self.manifest['ownedReplacementsAndAdds'][0]['beforeSha256'] = '0' * 64; self.resign()
        with self.assertRaisesRegex(ValueError, 'preimage differs'): self.prepare()
        self.manifest = json.loads(json.dumps(old))
        self.manifest['unchangedOwnedInputs'] = [{'path': 'system/assistant/Other.java', 'sha256': '0' * 64}]; self.resign()
        with self.assertRaisesRegex(ValueError, 'dependency differs'): self.prepare()
        self.assertFalse((self.out / 'runtime-preparations').exists())

    def test_scope_deletion_unlisted_payload_and_symlink_are_rejected(self):
        old = json.loads(json.dumps(self.manifest))
        self.manifest['deletions'] = ['image/source-lock.json']; self.resign()
        with self.assertRaisesRegex(ValueError, 'identity/scope'): self.prepare()
        self.manifest = json.loads(json.dumps(old))
        self.manifest['ownedReplacementsAndAdds'][0]['path'] = 'Android.bp'; self.resign()
        with self.assertRaisesRegex(ValueError, 'payload path'): self.prepare()
        self.manifest = json.loads(json.dumps(old)); self.resign()
        extra = self.bundle / 'payload/system/assistant/unlisted.java'; extra.write_bytes(b'unlisted')
        with self.assertRaisesRegex(ValueError, 'Unlisted'): self.prepare()
        extra.unlink()
        apk = self.bundle / 'payload/system/inputmethod/prebuilt/TakuInputMethod.apk'
        apk.unlink(); apk.symlink_to(self.parent / 'Android.bp')
        with self.assertRaisesRegex(ValueError, 'ordinary contained'): self.prepare()

    def test_existing_preparation_must_not_hide_drift_or_different_provenance(self):
        target, _ = self.prepare()
        apk = target / 'system/inputmethod/prebuilt/TakuInputMethod.apk'; original = apk.read_bytes()
        apk.write_bytes(b'drift')
        with self.assertRaisesRegex(ValueError, 'bytes differ'): self.prepare()
        apk.write_bytes(original); self.packet.write_bytes(b'different packet containing same approved source inputs')
        with self.assertRaisesRegex(ValueError, 'provenance differs'): self.prepare()

    def final_handoff(self):
        # Synthetic final closure exercises controller identity, not ROM acceptance.
        files = dict(self.files)
        files['image/build.sh'] = b'integrate_taku() {\n    phase integrate\n    rsync -a --delete /taku/ vendor/taku/\n}\n'
        files['image/verify-artifacts.py'] = b"product_branding=None\n    prohibited=['NexusLauncher'\nif product_branding:receipt['productBranding']=product_branding\n"
        entries = [{'path': n, 'sha256': RUNTIME.hashlib.sha256(b).hexdigest()} for n, b in sorted(files.items())]
        key = RUNTIME.hashlib.sha256(json.dumps(entries, sort_keys=True).encode()).hexdigest()
        parent = self.out / 'snapshots' / key
        for name, raw in files.items(): self.write(parent / name, raw)
        self.write(parent / 'input-manifest.json', json.dumps({'digest': key, 'files': entries, 'integrationProfile': 'taku-branding-v1'}).encode())
        extra = {'system/assistant/AndroidManifest.xml': b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="67"/>',
                 'system/memory/AndroidManifest.xml': b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="8"/>',
                 'system/inputmethod/Android.bp': b'locked presigned keyboard',
                 'system/inputmethod/rom-input-lock.json': json.dumps({'versionCode':10062,'jni':[{}]*24,'jniSymlinks':[{},{}]}).encode()}
        for name, raw in extra.items():
            self.write(self.bundle / 'payload' / name, raw)
            self.manifest['ownedReplacementsAndAdds'].append({'path': name,'beforeSha256':None,'sha256':RUNTIME.hashlib.sha256(raw).hexdigest(),'bytes':len(raw)})
        self.manifest.update(baseSnapshotDigest=key, baseManifestSha256=RUNTIME.sha(parent/'input-manifest.json'),
                             versions={'ime':10062,'assistant':67,'memory':8})
        self.resign()
        policy={'schema':'taku.reviewed-runtime-handoff/1','baseSnapshotDigest':key,
                'baseManifestSha256':self.manifest['baseManifestSha256'],'handoffManifestSha256':self.manifest_sha,
                'packetSha256':RUNTIME.sha(self.packet),'sourceCommit':self.manifest['sourceCommit'],
                'versions':self.manifest['versions'],'replacementCount':len(self.manifest['ownedReplacementsAndAdds']),
                'unchangedCount':1,'cachePolicy':'checksum-changed-inputs-fresh-mtime'}
        # One complete inherited dependency belongs to the reviewed component scope.
        self.manifest['unchangedOwnedInputs']=[{'path':'system/assistant/Main.java','sha256':RUNTIME.hashlib.sha256(files['system/assistant/Main.java']).hexdigest()}]
        # Avoid duplicate ownership: replace the old Main.java payload with another owned new file.
        record=self.manifest['ownedReplacementsAndAdds'][0]
        old=self.bundle/'payload'/record['path'];old.unlink()
        record.update(path='system/assistant/New.java',beforeSha256=None)
        self.write(self.bundle/'payload'/record['path'],self.payloads['system/assistant/Main.java'])
        self.resign();policy['handoffManifestSha256']=self.manifest_sha
        path=self.root/'reviewed-contract.json';path.write_text(json.dumps(policy))
        values=dict(out=self.out,root=ROOT,base=key,bundle=self.bundle,manifest_sha=self.manifest_sha,
                    packet=self.packet,packet_sha=RUNTIME.sha(self.packet),contract=path,contract_sha=RUNTIME.sha(path))
        return values,policy

    def test_reviewed_final_handoff_uses_exact_new_parent_and_keeps_legacy_lock(self):
        args,policy=self.final_handoff()
        with self.assertRaisesRegex(ValueError,'Only the reviewed'):
            RUNTIME.derive(**{k:v for k,v in args.items() if k not in ('contract','contract_sha')})
        stage={'validate_inputs':lambda *a:self.packet,'native_entries':lambda *a:range(24)}
        with mock.patch.object(RUNTIME.runpy,'run_path',return_value=stage):
            target,key=RUNTIME.derive(**args)
        result=json.loads((target/'input-manifest.json').read_text())
        binding=result['runtimeIntegration'];review=binding['reviewedHandoff']
        self.assertEqual(binding['versions'],{'ime':10062,'assistant':67,'memory':8})
        self.assertEqual(json.loads(review['json']),policy)
        self.assertEqual(review['sha256'],args['contract_sha'])
        self.assertIn(b'rsync -a --checksum --no-times --delete', (target/'image/build.sh').read_bytes())
        self.assertEqual((target/'image/source-lock.json').read_bytes(),self.files['image/source-lock.json'])
        args['contract'].write_text(args['contract'].read_text()+' ')
        with self.assertRaisesRegex(ValueError,'contract changed'):RUNTIME.derive(**args)

    def test_reviewed_handoff_rejects_wrong_closure_and_manifest_version(self):
        args,policy=self.final_handoff()
        for field,value in [('baseSnapshotDigest','0'*64),('replacementCount',1),
                            ('versions',{'ime':10062,'assistant':99,'memory':8})]:
            changed=dict(policy);changed[field]=value;args['contract'].write_text(json.dumps(changed))
            args['contract_sha']=RUNTIME.sha(args['contract'])
            with self.subTest(field=field),self.assertRaises(ValueError):RUNTIME.derive(**args)
        # Matching packet labels cannot conceal a source manifest with an older code.
        raw=b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="66"/>'
        self.write(self.bundle/'payload/system/assistant/AndroidManifest.xml',raw)
        for record in self.manifest['ownedReplacementsAndAdds']:
            if record['path']=='system/assistant/AndroidManifest.xml':record.update(sha256=RUNTIME.hashlib.sha256(raw).hexdigest(),bytes=len(raw))
        self.resign();policy['handoffManifestSha256']=self.manifest_sha
        args.update(manifest_sha=self.manifest_sha);args['contract'].write_text(json.dumps(policy));args['contract_sha']=RUNTIME.sha(args['contract'])
        with self.assertRaisesRegex(ValueError,'versions differ from source'):RUNTIME.derive(**args)

    @unittest.skipUnless(shutil.which('rsync'), 'rsync required for actual cache invalidation regression')
    def test_changed_old_timestamp_input_refreshes_without_invalidating_unchanged_cache(self):
        source,destination=self.root/'snapshot',self.root/'vendor';source.mkdir();destination.mkdir()
        for folder in (source,destination):
            (folder/'manifest').write_bytes(b'old');(folder/'unchanged').write_bytes(b'keep')
            for path in folder.iterdir():os.utime(path,(1700000000,1700000000))
        (source/'manifest').write_bytes(b'new');os.utime(source/'manifest',(1700000000,1700000000))
        subprocess.run(['rsync','-a','--delete',str(source)+'/',str(destination)+'/'],check=True,capture_output=True)
        self.assertEqual((destination/'manifest').read_bytes(),b'old')
        subprocess.run(['rsync','-a','--checksum','--no-times','--delete',str(source)+'/',str(destination)+'/'],check=True,capture_output=True)
        self.assertEqual((destination/'manifest').read_bytes(),b'new')
        self.assertGreater((destination/'manifest').stat().st_mtime,1700000001)
        self.assertEqual((destination/'unchanged').stat().st_mtime,1700000000)


if __name__ == '__main__':
    unittest.main()
