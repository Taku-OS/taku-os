#!/usr/bin/env python3
"""Bounded integration snapshots exclude unrelated WIP and preserve old inputs."""
import hashlib
import contextlib
import io
import importlib.util
import json
import pathlib
import shutil
import sys
import tempfile
import unittest
import subprocess
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("image_controller", ROOT / "scripts/pixel9-image.py")
IMAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(IMAGE)


class Derived(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name).resolve()
        self.work, self.out = self.root / "work", self.root / "out"
        self.work.mkdir(); self.out.mkdir()
        for name in set(IMAGE.UPDATER_OVERLAYS) | set(IMAGE.BRANDING_OVERLAYS):
            path = self.work / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, path)
        # A frozen E-style build has no newer IME/AI release inputs. Later WIP
        # in the developer's entrypoint must never replace this runtime.
        (self.work/'image/build.sh').write_text(
            '#!/bin/bash\nset -e\n'
            'python3 /taku/image/apply-updater-patches.py --source-root /src --results-dir /results\n'
            'python3 /taku/image/apply-updater-patches.py --source-root /src --results-dir /results --apk /src/Updater.apk\n'
            'm -j"$jobs" target-files-package bacon\nset -u\n\nverify_artifacts\n')
        self.files = {"system/assistant/Main.java": b"accepted D assistant",
                      "image/source-lock.json": b"same platform lock",
                      "image/kernel-source-lock.json": b"same kernel lock",
                      "image/build.sh": b"old build controller",
                      "image/taku.mk": b"PRODUCT_PACKAGES_DEBUG += TakuAssistant\nPRODUCT_SYSTEM_EXT_PROPERTIES += ro.taku.image.channel=development\n",
                      "image/verify-artifacts.py": b"old verifier"}
        records = [{"path": name, "sha256": hashlib.sha256(data).hexdigest()}
                   for name, data in sorted(self.files.items())]
        self.base = hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()
        self.previous = self.out / "snapshots" / self.base
        for name, data in self.files.items():
            path = self.previous / name
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        (self.previous / "input-manifest.json").write_text(json.dumps({"digest": self.base, "files": records}))
        dirty = self.work / "system/assistant/Main.java"
        dirty.parent.mkdir(parents=True); dirty.write_text("unrelated v59 WIP")
        self.patches = [mock.patch.object(IMAGE, "ROOT", self.work), mock.patch.object(IMAGE, "OUT", self.out)]
        for patch in self.patches:
            patch.start(); self.addCleanup(patch.stop)

    def test_exact_overlay_preserves_assistant_and_locks(self):
        before = {p.relative_to(self.previous): p.read_bytes() for p in self.previous.rglob("*") if p.is_file()}
        target, key = IMAGE.integration_snapshot(self.base, "updater-canary")
        self.assertNotEqual(key, self.base)
        manifest = json.loads((target / "input-manifest.json").read_text())
        self.assertEqual(manifest["overlayPaths"], sorted(IMAGE.UPDATER_OVERLAYS))
        self.assertEqual(set(manifest["addedPaths"]), IMAGE.UPDATER_ADDITIONS)
        self.assertEqual(manifest["parentSnapshotDigest"], self.base)
        self.assertEqual(manifest['parentDerivedPaths'], ['image/taku.mk'])
        product=(target/'image/taku.mk').read_text()
        self.assertNotIn('TakuSurfaces',product)
        self.assertNotIn('TakuMemory',product)
        self.assertEqual(product,self.files['image/taku.mk'].decode()+
                         'PRODUCT_SYSTEM_EXT_PROPERTIES += lineage.updater.uri=https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}\n')
        for name in ("system/assistant/Main.java", "image/source-lock.json", "image/kernel-source-lock.json"):
            self.assertEqual((target / name).read_bytes(), self.files[name])
        self.assertEqual(before, {p.relative_to(self.previous): p.read_bytes() for p in self.previous.rglob("*") if p.is_file()})
        self.assertEqual(IMAGE.integration_snapshot(self.base, "updater-canary"), (target, key))

    def test_google_feature_preserves_frozen_runtime_gate_and_inputs(self):
        for name in IMAGE.GOOGLE_ADDITIONS:
            p=self.work/name;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,p)
        files={**self.files,
            'image/build.sh':b'#!/bin/bash\n    phase integrate\n    existing_runtime_verification\n',
            'image/verify-artifacts.py':b"branding_boot_properties=None\n    prohibited=['NexusLauncher','Gemini','Velvet']\nreceipt['systemAiIntegration']=system_ai_integration\nreceipt_path=results/'baseline-receipt.json'\n"}
        records=[{'path':n,'sha256':hashlib.sha256(v).hexdigest()} for n,v in sorted(files.items())]
        key=hashlib.sha256(json.dumps(records,sort_keys=True).encode()).hexdigest()
        parent=self.out/'snapshots'/key
        for name,raw in files.items():
            p=parent/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(raw)
        runtime={'readyForBuild':True,'sourceCommit':'accepted runtime'}
        (parent/'input-manifest.json').write_text(json.dumps({'digest':key,'files':records,
            'integrationProfile':'taku-branding-v1','runtimeIntegration':runtime}))
        target,new_key=IMAGE.integration_snapshot(key,'taku-google-services-v1')
        proof=json.loads((target/'input-manifest.json').read_text())
        self.assertEqual(proof['runtimeIntegration'],runtime)
        self.assertEqual(set(proof['addedPaths']),set(IMAGE.GOOGLE_ADDITIONS))
        self.assertIn(b"receipt['systemAiIntegration']=system_ai_integration",(target/'image/verify-artifacts.py').read_bytes())
        for name in ('system/assistant/Main.java','image/source-lock.json','image/kernel-source-lock.json'):
            self.assertEqual((target/name).read_bytes(),files[name])
        self.assertEqual((target/'image/taku.mk').read_bytes(),files['image/taku.mk']+
            b'\n$(call inherit-product, vendor/taku/image/google-services.mk)\n')
        self.assertEqual(IMAGE.integration_snapshot(key,'taku-google-services-v1'),(target,new_key))
        with self.assertRaisesRegex(SystemExit,'already integrated'):
            IMAGE.integration_snapshot(new_key,'taku-google-services-v1')

    def test_repair_allowlist_is_not_expanded(self):
        with self.assertRaisesRegex(SystemExit, "Only explicit build-controller"):
            IMAGE.repair_snapshot(self.base, ["image/taku.mk"])
        with self.assertRaisesRegex(SystemExit, "Unknown image integration"):
            IMAGE.integration_snapshot(self.base, "all-wip")
        with self.assertRaisesRegex(SystemExit, "Invalid repair overlay"):
            IMAGE.repair_snapshot(self.base, ["image/check-incremental.py"])

    def test_memory_privapp_repair_is_exact_and_excludes_other_runtime_work(self):
        files = {**self.files, 'system/memory/Android.bp':
                 (ROOT/'system/memory/Android.bp').read_bytes().replace(b'    privapp_allowlist: "org.agentos.memory.xml",\n', b''),
                 'image/verify-system-ai.py': b'old reviewed verifier'}
        records = [{'path': n, 'sha256': hashlib.sha256(v).hexdigest()} for n, v in sorted(files.items())]
        key = hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()
        parent = self.out/'snapshots'/key
        for name, raw in files.items():
            p=parent/name;p.parent.mkdir(parents=True, exist_ok=True);p.write_bytes(raw)
        manifest=parent/'input-manifest.json'
        manifest.write_text(json.dumps({'digest':key,'files':records,'integrationProfile':'taku-branding-v1',
                                        'runtimeIntegration':{'readyForBuild':True,'sourceCommit':'approved runtime'}}))
        for name in IMAGE.MEMORY_PERMISSION_OVERLAYS:
            p=self.work/name;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,p)
        before={p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()}
        with mock.patch.object(IMAGE,'MEMORY_PERMISSION_PARENT',key), mock.patch.object(IMAGE,'MEMORY_PERMISSION_MANIFEST',IMAGE.digest(manifest)):
            target, _=IMAGE.integration_snapshot(key,'taku-memory-privapp-v1')
            derived=json.loads((target/'input-manifest.json').read_text())
            self.assertEqual(derived['overlayPaths'],sorted(IMAGE.MEMORY_PERMISSION_OVERLAYS))
            self.assertEqual(derived['runtimeIntegration']['sourceCommit'],'approved runtime')
            self.assertEqual((target/'system/assistant/Main.java').read_bytes(),files['system/assistant/Main.java'])
            self.assertEqual((target/'image/taku.mk').read_bytes(),files['image/taku.mk'])
            self.assertEqual(before,{p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()})
            (self.work/'system/memory/Android.bp').write_bytes((self.work/'system/memory/Android.bp').read_bytes()+b'// unrelated\n')
            with self.assertRaisesRegex(SystemExit,'unrelated changes'):IMAGE.integration_snapshot(key,'taku-memory-privapp-v1')
        with self.assertRaisesRegex(SystemExit,'exact reviewed H'):IMAGE.integration_snapshot(self.base,'taku-memory-privapp-v1')

    def test_branding_derives_only_from_frozen_canary_and_never_imports_other_wip(self):
        parent, parent_key = IMAGE.integration_snapshot(self.base, "updater-canary")
        before = {p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()}
        (self.work/'image/build.sh').write_text('new unrelated IME/AI runtime WIP')
        target, key = IMAGE.integration_snapshot(parent_key, "taku-branding-v1")
        manifest = json.loads((target/'input-manifest.json').read_text())
        self.assertNotEqual(key, parent_key)
        self.assertEqual(manifest['overlayPaths'], sorted(IMAGE.BRANDING_OVERLAYS))
        self.assertEqual(manifest['parentSnapshotDigest'], parent_key)
        self.assertEqual(manifest['parentDerivedPaths'], ['image/build.sh', 'image/taku.mk'])
        self.assertIn('image/branding-properties.json', manifest['addedPaths'])
        self.assertEqual((target/'image/branding-properties.json').read_bytes(),
                         (ROOT/'image/branding-properties.json').read_bytes())
        self.assertEqual((target/'system/assistant/Main.java').read_bytes(), self.files['system/assistant/Main.java'])
        product = (target/'image/taku.mk').read_bytes()
        self.assertEqual(product,b'$(call inherit-product, vendor/taku/image/product-branding.mk)\n\n'+before[pathlib.Path('image/taku.mk')])
        self.assertNotIn(b'TakuSurfaces', product)
        self.assertNotIn(b'TakuMemory', product)
        build=(target/'image/build.sh').read_bytes()
        self.assertNotIn(b'IME/AI runtime WIP',build)
        self.assertEqual(build.count(b'python3 /taku/image/apply-product-branding.py'),2)
        self.assertIn(b'--stage-full-ota out/target/product/tokay --build-number "$BUILD_NUMBER"',build)
        self.assertEqual(subprocess.run(['bash','-n',str(target/'image/build.sh')],capture_output=True).returncode,0)
        self.assertEqual(before,{p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()})
        self.assertEqual(IMAGE.integration_snapshot(parent_key,'taku-branding-v1'),(target,key))
        with self.assertRaisesRegex(SystemExit,'frozen Updater canary'):
            IMAGE.integration_snapshot(self.base,'taku-branding-v1')

    def test_branding_rejects_changed_boot_property_policy_before_deriving(self):
        _, parent_key = IMAGE.integration_snapshot(self.base, 'updater-canary')
        policy=self.work/'image/branding-properties.json'
        value=json.loads(policy.read_text())
        value['placements']['ro.build.display.id']='SYSTEM_EXT/etc/build.prop'
        policy.write_text(json.dumps(value))
        with self.assertRaisesRegex(SystemExit, 'Branding boot-property policy differs'):
            IMAGE.integration_snapshot(parent_key, 'taku-branding-v1')

    def legacy_runtime_parent(self):
        old_config={'schema':'taku.product-branding/1','productName':'TakuOS',
                    'productVersion':'1.0','updaterVersionCode':3}
        files={**self.files,
               'image/product-branding.json':(json.dumps(old_config,indent=2)+'\n').encode(),
               'image/branding/manifest.json':(ROOT/'image/branding/legacy-v3-manifest.json').read_bytes(),
               'image/verify-artifacts.py':b"'versionCode':3 if branded else 2\nreceipt['systemAiIntegration']=system_ai_integration\n# retained private runtime gate\n"}
        for name in ('image/apply-product-branding.py','image/apply-updater-patches.py','image/product_branding.py','image/product-branding.mk',
                     'image/updater/manifest.json','image/branding/updater-v3.patch',
                     'image/branding/settings.patch','image/branding-properties.json'):
            files[name]=(ROOT/name).read_bytes()
        files['image/apply-updater-patches.py']=files['image/apply-updater-patches.py'].replace(
            b'version_code in (2, 3, 4, 5, 6, 7, 8, 9, 10)',b'version_code in (2, 3)')
        records=[{'path':name,'sha256':hashlib.sha256(data).hexdigest()} for name,data in sorted(files.items())]
        key=hashlib.sha256(json.dumps(records,sort_keys=True).encode()).hexdigest()
        parent=self.out/'snapshots'/key
        for name,data in files.items():
            path=parent/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        (parent/'input-manifest.json').write_text(json.dumps({'digest':key,'files':records,
            'integrationProfile':'taku-branding-v1','runtimeIntegration':{'readyForBuild':True,'versions':{'ime':10062,'assistant':67,'memory':8}}}))
        return parent,key,files

    def test_version_update_preserves_runtime_gate_build_modules_and_parent(self):
        parent,key,files=self.legacy_runtime_parent()
        before={p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()}
        (self.work/'image/build.sh').write_text('unrelated newer runtime build')
        (self.work/'image/taku.mk').write_text('unrelated additional system app')
        (self.work/'image/verify-artifacts.py').write_text('generic gate without pinned runtime proof')
        target,new_key=IMAGE.integration_snapshot(key,'taku-branding-version-v2')
        manifest=json.loads((target/'input-manifest.json').read_text())
        self.assertEqual(manifest['runtimeIntegration'],json.loads((parent/'input-manifest.json').read_text())['runtimeIntegration'])
        self.assertEqual(manifest['parentSnapshotDigest'],key)
        self.assertEqual(manifest['integrationProfile'],'taku-branding-v1')
        self.assertEqual(manifest['overlayPaths'],sorted(IMAGE.BRANDING_VERSION_OVERLAYS))
        self.assertEqual(set(manifest['addedPaths']),set(IMAGE.BRANDING_VERSION_OVERLAYS)-set(files))
        for name in files:
            if name not in IMAGE.BRANDING_VERSION_OVERLAYS:self.assertEqual((target/name).read_bytes(),files[name])
        self.assertIn(b'retained private runtime gate',(target/'image/verify-artifacts.py').read_bytes())
        self.assertIn(b"'versionCode':config['updaterVersionCode'] if branded else 2",(target/'image/verify-artifacts.py').read_bytes())
        self.assertEqual((target/'image/apply-updater-patches.py').read_bytes(),(ROOT/'image/apply-updater-patches.py').read_bytes())
        self.assertNotEqual((target/'image/apply-updater-patches.py').read_bytes(),files['image/apply-updater-patches.py'])
        self.assertEqual(json.loads((target/'image/product-branding.json').read_text())['productVersion'],json.loads((ROOT/'image/product-branding.json').read_text())['productVersion'])
        self.assertEqual(before,{p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()})
        self.assertEqual(IMAGE.integration_snapshot(key,'taku-branding-version-v2'),(target,new_key))

    def assert_versioned_parent_keeps_runtime(self, version, product_version):
        _, _, files = self.legacy_runtime_parent()
        config = {'schema':'taku.product-branding/2','productName':'TakuOS',
                  'productVersion':product_version,'updaterVersionCode':version}
        files['image/product-branding.json'] = (json.dumps(config,indent=2)+'\n').encode()
        files['image/branding/manifest.json'] = (ROOT/f'image/branding/previous-v{version}-manifest.json').read_bytes()
        files['image/verify-artifacts.py'] = files['image/verify-artifacts.py'].replace(
            b"'versionCode':3 if branded else 2", b"'versionCode':config['updaterVersionCode'] if branded else 2")
        records=[{'path':n,'sha256':hashlib.sha256(v).hexdigest()} for n,v in sorted(files.items())]
        key=hashlib.sha256(json.dumps(records,sort_keys=True).encode()).hexdigest()
        parent=self.out/'snapshots'/key
        for n,v in files.items():
            p=parent/n;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(v)
        runtime={'readyForBuild':True,'versions':{'ime':10062,'assistant':73,'memory':8}}
        (parent/'input-manifest.json').write_text(json.dumps({'digest':key,'files':records,
            'integrationProfile':'taku-branding-v1','runtimeIntegration':runtime}))
        target,_=IMAGE.integration_snapshot(key,'taku-branding-version-v2')
        self.assertEqual(json.loads((target/'input-manifest.json').read_text())['runtimeIntegration'],runtime)
        self.assertEqual((target/'system/assistant/Main.java').read_bytes(),files['system/assistant/Main.java'])

    def test_exact_v4_runtime_parent_keeps_runtime(self):
        self.assert_versioned_parent_keeps_runtime(4, "1.0.1")

    def test_exact_v5_runtime_parent_keeps_runtime(self):
        self.assert_versioned_parent_keeps_runtime(5, "1.1.0")

    def test_exact_v6_runtime_parent_keeps_runtime(self):
        self.assert_versioned_parent_keeps_runtime(6, "1.1.1")

    def test_exact_v7_google_runtime_parent_keeps_runtime(self):
        self.assert_versioned_parent_keeps_runtime(7, "1.2.0")

    def test_exact_v8_internal_apps_parent_keeps_runtime(self):
        self.assert_versioned_parent_keeps_runtime(8, "1.3.0")

    def test_version_update_refuses_unknown_legacy_contract_and_inherited_policy_drift(self):
        with self.assertRaisesRegex(SystemExit,'frozen branded runtime'):
            IMAGE.integration_snapshot(self.base,'taku-branding-version-v2')
        parent,key,_=self.legacy_runtime_parent()
        manifest=parent/'image/branding/manifest.json'; original=manifest.read_bytes();manifest.write_bytes(original+b'\n')
        with self.assertRaisesRegex(SystemExit,'exact accepted predecessor'):
            IMAGE.integration_snapshot(key,'taku-branding-version-v2')
        manifest.write_bytes(original)
        (self.work/'image/branding-properties.json').write_text('{}')
        with self.assertRaisesRegex(SystemExit,'inherited contract'):
            IMAGE.integration_snapshot(key,'taku-branding-version-v2')
        self.assertEqual(len(list((self.out/'snapshots').iterdir())),2)

    def test_existing_derived_snapshot_cannot_hide_drift(self):
        target, _ = IMAGE.integration_snapshot(self.base, "updater-canary")
        (target / "system/assistant/Main.java").write_text("changed after freezing")
        with self.assertRaisesRegex(SystemExit, "Existing derived snapshot input differs"):
            IMAGE.integration_snapshot(self.base, "updater-canary")

    def property_parent(self):
        # The historical system-ext-only repair is frozen. Do not derive its
        # preimage from the current generator, whose product placement differs.
        old_generator=b'generated = "PRODUCT_SYSTEM_EXT_PROPERTIES += "\n'
        old_makefile=b''.join(b'PRODUCT_SYSTEM_EXT_PROPERTIES += fixture.'+str(i).encode()+b'=old\n' for i in range(4))
        (self.work/'image/product_branding.py').write_bytes(old_generator)
        (self.work/'image/product-branding.mk').write_bytes(old_makefile)
        files={**self.files,
               'image/product_branding.py':old_generator.replace(b'PRODUCT_SYSTEM_EXT_PROPERTIES += ',b'PRODUCT_SYSTEM_PROPERTIES += '),
               'image/product-branding.mk':old_makefile.replace(b'PRODUCT_SYSTEM_EXT_PROPERTIES += ',b'PRODUCT_SYSTEM_PROPERTIES += '),
               'image/verify-artifacts.py':b'if any(system.get(key)!=value for key,value in expected_brand.items()):\n    raise SystemExit()\n'}
        records=[{'path':name,'sha256':hashlib.sha256(data).hexdigest()} for name,data in sorted(files.items())]
        key=hashlib.sha256(json.dumps(records,sort_keys=True).encode()).hexdigest()
        parent=self.out/'snapshots'/key
        for name,data in files.items():
            path=parent/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
        metadata={'digest':key,'files':records,'integrationProfile':'taku-branding-v1',
                  'runtimeIntegration':{'readyForBuild':True,'sourceCommit':'fixture-final-runtime'}}
        manifest=parent/'input-manifest.json';manifest.write_text(json.dumps(metadata))
        for field,value in [('BRANDING_PROPERTIES_PARENT',key),('BRANDING_PROPERTIES_MANIFEST',IMAGE.digest(manifest))]:
            patch=mock.patch.object(IMAGE,field,value);patch.start();self.addCleanup(patch.stop)
        return parent,key,metadata

    def test_property_repair_inherits_runtime_and_only_changes_three_exact_files(self):
        parent,key,metadata=self.property_parent()
        before={p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()}
        target,digest=IMAGE.integration_snapshot(key,'taku-branding-system-ext-v1')
        observed=json.loads((target/'input-manifest.json').read_text())
        self.assertEqual(observed['runtimeIntegration'],metadata['runtimeIntegration'])
        self.assertEqual(observed['integrationProfile'],'taku-branding-v1')
        self.assertEqual(observed['parentSnapshotDigest'],key)
        self.assertEqual(observed['overlayPaths'],sorted(IMAGE.BRANDING_PROPERTIES_OVERLAYS))
        self.assertEqual(observed['parentDerivedPaths'],sorted(IMAGE.BRANDING_PROPERTIES_OVERLAYS))
        for name in self.files:
            if name not in IMAGE.BRANDING_PROPERTIES_OVERLAYS:self.assertEqual((target/name).read_bytes(),self.files[name])
        self.assertIn(b"properties('SYSTEM_EXT/etc/build.prop')",(target/'image/verify-artifacts.py').read_bytes())
        self.assertEqual(before,{p.relative_to(parent):p.read_bytes() for p in parent.rglob('*') if p.is_file()})
        self.assertEqual(IMAGE.integration_snapshot(key,'taku-branding-system-ext-v1'),(target,digest))
        observed['runtimeIntegration']['sourceCommit']='tampered'
        (target/'input-manifest.json').write_text(json.dumps(observed))
        with self.assertRaisesRegex(SystemExit,'runtime provenance'):
            IMAGE.integration_snapshot(key,'taku-branding-system-ext-v1')

    def test_property_repair_refuses_other_parents_manifests_and_working_generator_drift(self):
        parent,key,_=self.property_parent()
        with self.assertRaisesRegex(SystemExit,'exact reviewed runtime parent'):
            IMAGE.integration_snapshot(self.base,'taku-branding-system-ext-v1')
        path=self.work/'image/product_branding.py';path.write_bytes(path.read_bytes()+b'\n# unrelated WIP\n')
        with self.assertRaisesRegex(SystemExit,'working generator differs'):
            IMAGE.integration_snapshot(key,'taku-branding-system-ext-v1')
        path.write_bytes((ROOT/'image/product_branding.py').read_bytes())
        (parent/'input-manifest.json').write_text('{}')
        with self.assertRaisesRegex(SystemExit,'parent manifest differs'):
            IMAGE.integration_snapshot(key,'taku-branding-system-ext-v1')

    def test_preview_rebinds_derived_provenance_and_preserves_parent_evidence(self):
        current={'snapshotDigest':self.base,'buildNumber':'fixture-D',
                 'snapshotParentDigest':'old-parent','snapshotOverlayPaths':['old-repair.py']}
        inputs=self.out/'run-input.json';inputs.write_text(json.dumps(current))
        status=self.out/'status.json';status.write_text(json.dumps({'romBuilt':True,'artifactSignaturesVerified':True,
              'artifactsExported':True,'exportDirectory':str(self.out/'archive-D')}))
        before_inputs,before_status=inputs.read_bytes(),status.read_bytes()
        for name in ['resolved-manifest.xml','resolved-kernel-manifest.xml','kernel-artifacts.json','build-workarounds.json']:
            (self.out/name).write_text('fixture checkpoint')
        info={'State':{'Running':False,'ExitCode':0},'HostConfig':{'NanoCpus':4_000_000_000,'Memory':8*1024**3,'MemorySwap':10*1024**3}}
        raw_apk=b'fixture APK, not Android acceptance'
        def docker(command,**kwargs):
            command=list(command)
            if command[:2]==['docker','run']:
                mount=next(value for value in command if value.startswith('type=bind,source=') and value.endswith(',target=/results'))
                result=pathlib.Path(mount.removeprefix('type=bind,source=').removesuffix(',target=/results'))
                (result/'updater-apk-verification.json').write_text(json.dumps({'apkSha256':hashlib.sha256(raw_apk).hexdigest(),
                    'apkSignatureVerified':True,'versionCode':2,'platformCertificateSha256':'a'*64}))
            elif command[:2]==['docker','cp']:
                pathlib.Path(command[-1]).write_bytes(raw_apk)
            else:raise AssertionError('Unexpected command '+str(command))
            return subprocess.CompletedProcess(command,0)
        with mock.patch.object(IMAGE.subprocess,'check_output',return_value=json.dumps([info]).encode()),mock.patch.object(
                IMAGE.subprocess,'run',side_effect=docker),mock.patch('ota_release.load_baseline',return_value={
                    'runInputSha256':hashlib.sha256(before_inputs).hexdigest()}),contextlib.redirect_stdout(io.StringIO()):
            IMAGE.updater_preview(self.base,'updater-canary')
        preview=next((self.out/'updater-previews').iterdir())
        observed=json.loads((preview/'run-input.json').read_text())
        self.assertEqual(observed['snapshotParentDigest'],self.base)
        self.assertEqual(observed['snapshotOverlayPaths'],sorted(IMAGE.UPDATER_OVERLAYS))
        self.assertEqual(observed['buildNumber'],'fixture-D')
        self.assertEqual(inputs.read_bytes(),before_inputs)
        self.assertEqual(status.read_bytes(),before_status)

    def test_immutable_base_and_overlay_symlink_drift_rejected(self):
        (self.previous / "image/source-lock.json").write_bytes(b"unexpected")
        with self.assertRaisesRegex(SystemExit, "Base snapshot input differs"):
            IMAGE.integration_snapshot(self.base, "updater-canary")
        (self.previous / "image/source-lock.json").write_bytes(self.files["image/source-lock.json"])
        path = self.work / "image/taku.mk"
        path.unlink(); path.symlink_to(ROOT / "image/taku.mk")
        with self.assertRaisesRegex(SystemExit, "Invalid repair overlay"):
            IMAGE.integration_snapshot(self.base, "updater-canary")


if __name__ == "__main__":
    unittest.main()
