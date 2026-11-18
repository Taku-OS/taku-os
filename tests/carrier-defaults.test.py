"""Fixed-source policy and provenance checks, not IMS/ROM/device acceptance."""
import hashlib
import importlib.util
import io
import json
import pathlib
import shutil
import runpy
import sys
import subprocess
import tempfile
import unittest
import zipfile
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('carrier_defaults', ROOT / 'image/carrier_defaults.py')
CARRIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CARRIER)


class CarrierDefaults(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.repo = self.root / CARRIER.REPOSITORY
        loader = self.repo / CARRIER.LOADER
        loader.parent.mkdir(parents=True)
        shutil.copy2(ROOT / 'tests/fixtures/telephony/CarrierConfigLoader.java', loader)
        self.results = self.root / 'results'
        revision = mock.patch.object(CARRIER, 'git_revision', return_value=CARRIER.UPSTREAM)
        revision.start()
        self.addCleanup(revision.stop)

    def test_exact_patch_idempotence_and_native_java_scope(self):
        first = CARRIER.apply(self.root, self.results)
        self.assertFalse(first['physicalDeviceVerified'])
        self.assertEqual(first, CARRIER.apply(self.root, self.results))
        loader = (self.repo / CARRIER.LOADER).read_text()
        hook = loader.index('TakuCarrierDefaults.shouldEnableVolte(')
        self.assertLess(loader.index('retConfig.putAll(config);', loader.index('config = mConfigFromCarrierApp[phoneId]')), hook)
        self.assertLess(hook, loader.index('config = mPersistentOverrideConfigs[phoneId]'))
        self.assertLess(hook, loader.index('config = mOverrideConfigs[phoneId]', hook))
        self.assertIn('phoneForDefaults.getSubId() == subscriptionId', loader)
        self.assertIn('if (allConfigsApplied && SubscriptionManager.isValidSubscriptionId(subscriptionId)', loader)
        annotation = self.root / 'com/android/internal/annotations/VisibleForTesting.java'
        annotation.parent.mkdir(parents=True)
        annotation.write_text('package com.android.internal.annotations; public @interface VisibleForTesting {}\n')
        harness = self.root / 'PolicyHarness.java'
        harness.write_text('''package com.android.phone;
public final class PolicyHarness {
  static void check(boolean expected, String device, boolean loaded, String home,
      int id, int specific, boolean voice, boolean explicitCarrier) {
    if (TakuCarrierDefaults.shouldEnableVolte(device, loaded, home, id, specific,
        voice, explicitCarrier, true) != expected) throw new AssertionError(home);
  }
  public static void main(String[] args) {
    check(true,"tokay",true,"46011",2237,2237,true,false);
    check(true,"tokay",true,"46003",2237,2237,true,false);
    check(false,"other",true,"46011",2237,2237,true,false);
    check(false,"tokay",false,"46011",2237,2237,true,false);
    check(false,"tokay",true,null,2237,2237,true,false);
    check(false,"tokay",true,"",2237,2237,true,false);
    check(false,"tokay",true,"460110",2237,2237,true,false);
    for (String other : new String[]{"46000","46001","46015","46060","23410","00101"})
      check(false,"tokay",true,other,2237,2237,true,false);
    check(false,"tokay",true,"46011",-1,2237,true,false);
    check(false,"tokay",true,"46011",2237,-1,true,false);
    check(false,"tokay",true,"46011",2427,2427,true,false);
    check(false,"tokay",true,"46011",2237,9999,true,false);
    check(false,"tokay",true,"46011",2237,2237,false,false);
    check(false,"tokay",true,"46011",2237,2237,true,true);
    if (TakuCarrierDefaults.shouldEnableVolte("tokay",true,"46011",2237,2237,true,false,false))
      throw new AssertionError("empty platform provider must not enable VoLTE");
    System.out.println("Scoped native policy checks passed; no device claims");
  }
}\n''')
        java = pathlib.Path('/opt/homebrew/opt/openjdk@17/bin')
        javac = str(java / 'javac') if (java / 'javac').exists() else 'javac'
        runtime = str(java / 'java') if (java / 'java').exists() else 'java'
        subprocess.run([javac, '-d', str(self.root / 'classes'), str(annotation),
                        str(self.repo / CARRIER.HELPER), str(harness)], check=True, capture_output=True)
        result = subprocess.run([runtime, '-cp', str(self.root / 'classes'),
                                 'com.android.phone.PolicyHarness'], check=True, capture_output=True, text=True)
        self.assertIn('checks passed', result.stdout)

    def test_wrong_upstream_drift_and_partial_application_stop(self):
        with mock.patch.object(CARRIER, 'git_revision', return_value='0' * 40):
            with self.assertRaisesRegex(ValueError, 'HEAD differs'):
                CARRIER.apply(self.root, self.results)
        target = self.repo / CARRIER.LOADER
        original = target.read_bytes()
        target.write_bytes(original + b'// drift\n')
        with self.assertRaisesRegex(ValueError, 'source drift'):
            CARRIER.apply(self.root, self.results)
        target.write_bytes(original)
        CARRIER.apply(self.root, self.results)
        (self.repo / CARRIER.HELPER).unlink()
        with self.assertRaisesRegex(ValueError, 'Partially applied'):
            CARRIER.apply(self.root, self.results)

    def test_frozen_controller_hooks_preserve_existing_gates_and_reject_ambiguity(self):
        hooks = runpy.run_path(str(ROOT / 'image/carrier_snapshot.py'))
        fixtures = ROOT / 'tests/fixtures/carrier-controllers'
        source = json.loads((fixtures / 'source.json').read_text())
        for name, hook in [('build.sh', 'build_hook'), ('verify-artifacts.py', 'artifact_hook')]:
            original = (fixtures / name).read_bytes()
            self.assertEqual(hashlib.sha256(original).hexdigest(), source['files'][name])
            changed = hooks[hook](original)
            self.assertIn(b'carrier_defaults', changed)
            self.assertIn(b'google_apps', changed)
            if name == 'build.sh':
                self.assertIn(b'm -j"$jobs" TeleService Updater', changed)
                self.assertIn(b'm -j"$jobs" target-files-package bacon', changed)
                path = self.root / 'build.sh'
                path.write_bytes(changed)
                subprocess.run(['bash', '-n', str(path)], check=True)
            else:
                self.assertIn(b'runtime_gate', changed)
                self.assertIn(b"receipt['systemAiIntegration']", changed)
            with self.assertRaises(ValueError):
                hooks[hook](original + original)
            with self.assertRaises(ValueError):
                hooks[hook](changed)

    def test_intermediate_mother_cannot_start_before_runtime_rebinding(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        try:
            controller = runpy.run_path(str(ROOT / 'scripts/pixel9-image.py'), run_name='carrier_controller_test')
        finally:
            sys.path.pop(0)
        snap = self.root / 'unbound'
        snap.mkdir()
        (snap / 'input-manifest.json').write_text(json.dumps({'pendingCarrierRepair': True}))
        with self.assertRaisesRegex(SystemExit, 'requires runtime source-repair rebinding'):
            controller['validate_carrier_candidate'](snap)

    def test_fresh_carrier_snapshot_requires_clean_exact_checkout(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        try:
            controller = runpy.run_path(str(ROOT / 'scripts/pixel9-image.py'), run_name='fresh_carrier_test')
        finally:
            sys.path.pop(0)
        snap = self.root / 'fresh'
        (snap / 'image').mkdir(parents=True)
        shutil.copy2(ROOT / 'image/carrier_defaults.py', snap / 'image/carrier_defaults.py')
        shutil.copytree(ROOT / 'image/carrier-defaults', snap / 'image/carrier-defaults')
        files = [{'path': str(p.relative_to(snap)), 'sha256': CARRIER.digest(p)}
                 for p in sorted(snap.rglob('*')) if p.is_file()]
        key = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
        manifest = {'digest': key, 'files': files}
        record = snap / 'input-manifest.json'
        record.write_text(json.dumps(manifest))
        glob = controller['validate_carrier_candidate'].__globals__
        with mock.patch.dict(glob, {'snapshot': lambda: (snap, key)}), \
                mock.patch.object(glob['subprocess'], 'check_output', side_effect=[b'commit', b'', b'', b'commit']):
            self.assertEqual(controller['validate_carrier_candidate'](snap), CARRIER.declaration())
        with mock.patch.dict(glob, {'snapshot': lambda: (snap, key)}), \
                mock.patch.object(glob['subprocess'], 'check_output', side_effect=[b'commit', b' M tracked']):
            with self.assertRaisesRegex(SystemExit, 'clean Git checkout'):
                controller['validate_carrier_candidate'](snap)
        with mock.patch.dict(glob, {'snapshot': lambda: (snap, 'f' * 64)}), \
                mock.patch.object(glob['subprocess'], 'check_output', side_effect=[b'commit', b'']):
            with self.assertRaisesRegex(SystemExit, 'differs from the clean checkout'):
                controller['validate_carrier_candidate'](snap)
        record.write_text(json.dumps({**manifest, 'parentSnapshotDigest': 'a' * 64}))
        with self.assertRaisesRegex(SystemExit, 'requires its rebound source-repair capsule'):
            controller['validate_carrier_candidate'](snap)

    def test_retry_keeps_recorded_frozen_inputs_and_rejects_identity_drift(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        try:
            controller = runpy.run_path(str(ROOT / 'scripts/pixel9-image.py'), run_name='carrier_retry_test')
        finally:
            sys.path.pop(0)
        snap = self.root / 'frozen'
        snap.mkdir()
        key = 'a' * 64
        (snap / 'input-manifest.json').write_text(json.dumps({'digest': key}))
        inputs = {'snapshotPath': str(snap), 'snapshotDigest': key, 'carrierDefaults': CARRIER.declaration()}
        (self.root / 'run-input.json').write_text(json.dumps(inputs))
        failed = {'State': {'Running': False, 'ExitCode': 1}, 'Id': 'fixture', 'HostConfig': {}}
        globals = controller['retry'].__globals__
        start, archive, run = mock.Mock(), mock.Mock(), mock.Mock()
        with mock.patch.dict(globals, {'OUT': self.root, 'start': start, 'archive_run_evidence': archive,
                                      'run': run, 'validate_carrier_candidate': lambda p: CARRIER.declaration()}), \
                mock.patch.object(subprocess, 'check_output', return_value=json.dumps([failed]).encode()):
            controller['retry'](resume=True)
            self.assertEqual(start.call_args.kwargs['snapshot_input'], (snap, key))
            self.assertTrue(start.call_args.kwargs['resume'])
            archive.reset_mock(); run.reset_mock(); start.reset_mock()
            (snap / 'input-manifest.json').write_text(json.dumps({'digest': 'b' * 64}))
            with self.assertRaisesRegex(SystemExit, 'recorded immutable snapshot'):
                controller['retry'](resume=True)
            archive.assert_not_called(); run.assert_not_called(); start.assert_not_called()

    def test_all_module_preview_entries_block_intermediate_mother_before_docker_run(self):
        sys.path.insert(0, str(ROOT / 'scripts'))
        try:
            controller = runpy.run_path(str(ROOT / 'scripts/pixel9-image.py'), run_name='carrier_preview_test')
            import ota_release
        finally:
            sys.path.pop(0)
        key = 'a' * 64
        snap = self.root / 'snapshots' / key
        snap.mkdir(parents=True)
        (snap / 'input-manifest.json').write_text(json.dumps({'pendingCarrierRepair': True}))
        (self.root / 'run-input.json').write_text(json.dumps({'snapshotDigest': key}))
        (self.root / 'status.json').write_text(json.dumps({'romBuilt': True, 'artifactSignaturesVerified': True,
            'artifactsExported': True, 'exportDirectory': 'fixture'}))
        info = {'State': {'Running': False, 'ExitCode': 0}, 'HostConfig': {}}
        accepted = {'runInputSha256': CARRIER.digest(self.root / 'run-input.json')}
        globals = controller['updater_preview'].__globals__
        with mock.patch.dict(globals, {'OUT': self.root, 'integration_snapshot': lambda *a: (snap,key)}), \
                mock.patch.object(ota_release, 'load_baseline', return_value=accepted), \
                mock.patch.object(subprocess, 'check_output', return_value=json.dumps([info]).encode()), \
                mock.patch.object(subprocess, 'run') as docker:
            for invoke in (lambda: controller['updater_preview'](key,'taku-telecom-volte-v1'),
                           lambda: controller['google_apps_preview'](key),
                           lambda: controller['google_apps_preview'](key,telephony=True)):
                with self.assertRaisesRegex(SystemExit, 'requires runtime source-repair rebinding'):
                    invoke()
            docker.assert_not_called()

    def test_policy_contract_and_patch_tamper_stop(self):
        image = self.root / 'image'
        shutil.copytree(ROOT / 'image/carrier-defaults', image / 'carrier-defaults')
        manifest = image / 'carrier-defaults/manifest.json'
        value = json.loads(manifest.read_text())
        value['scope']['simHomeOperators'].append('46000')
        manifest.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'contract differs'):
            CARRIER.load_contract(image)
        shutil.copy2(ROOT / 'image/carrier-defaults/manifest.json', manifest)
        patch = image / 'carrier-defaults/telecom-volte.patch'
        patch.write_bytes(patch.read_bytes() + b'\n')
        with self.assertRaisesRegex(ValueError, 'patch bytes differ'):
            CARRIER.load_contract(image)

    def test_archive_binds_exact_apk_source_and_run(self):
        # Tiny ZIP/dex stand-ins exercise transport gates, never APK signature proof.
        source = CARRIER.apply(self.root, self.results)
        inputs = {'carrierDefaults': CARRIER.declaration(), 'buildNumber': 'fixture'}
        run_path = self.results / 'run-input.json'
        run_path.write_text(json.dumps(inputs))
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as apk:
            apk.writestr('classes.dex', b'Lcom/android/phone/TakuCarrierDefaults;\x00shouldEnableVolte\x0046003\x0046011\x00tokay')
        data = raw.getvalue()
        certificate = 'a' * 64
        (self.results / 'carrier-defaults-apk-signature.txt').write_text(
            'Verifies\nNumber of signers: 1\nSigner #1 certificate SHA-256 digest: ' + certificate + '\n')
        (self.results / 'carrier-defaults-apk-badging.txt').write_text(
            "package: name='com.android.phone' versionCode='1'\ntargetSdkVersion:'37'\n")
        (self.results / 'updater-apk-verification.json').write_text(json.dumps(
            {'platformCertificateSha256': certificate, 'apkSignatureVerified': True}))
        proof = {'schema': 'taku.carrier-defaults-apk/1', **CARRIER.declaration(),
                 'apkSha256': hashlib.sha256(data).hexdigest(), 'apkBytes': len(data),
                 'apkSignatureVerified': True, 'physicalDeviceVerified': False,
                 'platformCertificateSha256': certificate,
                 'signatureLogSha256': CARRIER.digest(self.results / 'carrier-defaults-apk-signature.txt'),
                 'badgingLogSha256': CARRIER.digest(self.results / 'carrier-defaults-apk-badging.txt'),
                 'runInputSha256': CARRIER.digest(run_path),
                 'sourceEvidenceSha256': CARRIER.digest(self.results / 'carrier-defaults-source.json')}
        proof_path = self.results / 'carrier-defaults-apk.json'
        proof_path.write_text(json.dumps(proof))
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, 'w') as target:
            target.writestr('SYSTEM/priv-app/TeleService/TeleService.apk', data)
        with zipfile.ZipFile(outer) as archive:
            self.assertEqual(CARRIER.verify_archive(archive, self.results, inputs)['apkSha256'], proof['apkSha256'])
            run_path.write_text(json.dumps({**inputs, 'buildNumber': 'other'}))
            with self.assertRaisesRegex(ValueError, 'proof differs'):
                CARRIER.verify_archive(archive, self.results, inputs)
            run_path.write_text(json.dumps(inputs))
            wrong = io.BytesIO()
            with zipfile.ZipFile(wrong, 'w') as target:
                target.writestr('PRODUCT/priv-app/TeleService/TeleService.apk', data)
            with zipfile.ZipFile(wrong) as wrong_archive, self.assertRaisesRegex(ValueError, 'pinned target-files'):
                CARRIER.verify_archive(wrong_archive, self.results, inputs)
            original_source = (self.results / 'carrier-defaults-source.json').read_bytes()
            for key, value in [('schema', 'wrong'), ('sourcePatchVerified', False), ('physicalDeviceVerified', True)]:
                changed = {**source, key: value}
                (self.results / 'carrier-defaults-source.json').write_text(json.dumps(changed))
                changed_proof = {**proof, 'sourceEvidenceSha256': CARRIER.digest(self.results / 'carrier-defaults-source.json')}
                proof_path.write_text(json.dumps(changed_proof))
                with self.subTest(source_field=key), self.assertRaisesRegex(ValueError, 'source proof content differs'):
                    CARRIER.verify_archive(archive, self.results, inputs)
            (self.results / 'carrier-defaults-source.json').write_bytes(original_source)
            for key, value in [('platformCertificateSha256', '0' * 64), ('physicalDeviceVerified', True), ('apkSignatureVerified', False)]:
                proof_path.write_text(json.dumps({**proof, key: value}))
                with self.subTest(apk_field=key), self.assertRaises(ValueError):
                    CARRIER.verify_archive(archive, self.results, inputs)
            proof['apkSha256'] = '0' * 64
            proof_path.write_text(json.dumps(proof))
            with self.assertRaisesRegex(ValueError, 'APK differs'):
                CARRIER.verify_archive(archive, self.results, inputs)


if __name__ == '__main__':
    unittest.main()
