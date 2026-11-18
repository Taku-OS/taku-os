#!/usr/bin/env python3
"""Archive/planning gates using explicit fake crypto fixtures, never ROM acceptance."""
import hashlib
import json
import pathlib
import runpy
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest.mock import patch

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import ota_release as release
FIXTURES = runpy.run_path(str(ROOT / "tests/image-signatures.test.py"))
CLI = runpy.run_path(str(ROOT / "scripts/pixel9-ota.py"))


def write(path, data):
    path.write_text(json.dumps(data))


def full_payload(partial=False):
    manifest = b""
    for name in (b"boot", b"system"):
        partition = b"\x0a" + bytes([len(name)]) + name
        manifest += b"\x6a" + bytes([len(partition)]) + partition
    if partial:
        manifest += b"\x80\x01\x01"
    return struct.pack(">4sQQI", b"CrAU", 2, len(manifest), 8) + manifest + b"fixture-signatures-not-crypto"


def exported(test, number="20261020010000", timestamp=1792458000, partial=False, product_version=None):
    fixture = FIXTURES["SignatureBoundary"]()
    fixture.setUp()
    test.addCleanup(fixture.doCleanups)
    root = fixture.base / "export"
    root.mkdir()
    lock_files = {"source-lock.json": {"platform": "fixture-Android17"},
                  "kernel-source-lock.json": {"kernel": "fixture-kernel"}}
    for name, data in lock_files.items():
        write(root / name, data)
    records = [{"path": "image/" + name, "sha256": release.digest(root / name)} for name in sorted(lock_files)]
    brand = None
    if product_version is not None:
        legacy = len(product_version.split('.')) == 2
        config = {'schema': 'taku.product-branding/1' if legacy else 'taku.product-branding/2',
                  'productName': 'TakuOS', 'productVersion': product_version,
                  'updaterVersionCode': 3 if legacy else 4}
        write(root / 'product-branding.json', config)
        brand = release.BRANDING.build_identity({**config, 'displayVersion': 'TakuOS' + product_version,
                'configSha256': release.digest(root / 'product-branding.json')}, number)
        for name in ('product_branding.py', 'branding-properties.json'):
            shutil.copy2(ROOT / 'image' / name, root / name)
            records.append({'path': 'image/' + name, 'sha256': release.digest(root / name)})
        records.append({'path': 'image/product-branding.json', 'sha256': brand['configSha256']})
        write(root / 'branding-manifest.json', {'fixture': 'stub-crypto Updater sources', 'version': config['updaterVersionCode']})
        records.append({'path': 'image/branding/manifest.json', 'sha256': release.digest(root / 'branding-manifest.json')})
    key = hashlib.sha256(json.dumps(records, sort_keys=True).encode()).hexdigest()
    write(root / "input-manifest.json", {"digest": key, "files": records})
    fixture.inputs.update(buildNumber=number, snapshotDigest=key)
    if brand:
        fixture.inputs['integrationProfile'] = 'taku-branding-v1'
    avb_tool = fixture.source / "out/host/linux-x86/bin/avbtool"
    avb_tool.write_text(avb_tool.read_text().replace("contract-fixture-vbmeta", "contract-fixture-vbmeta-" + number))
    fixture.identity.update(buildNumber=number, buildTimestamp=timestamp,
                            productDevice="tokay", vendorDevice="tokay", systemDevice="generic",
                            buildType="userdebug", buildTags="test-keys",
                            buildFingerprint="google/tokay/fixture:17/shared-fingerprint",
                            platformSecurityPatch="2026-10-01")
    fixture.metadata.update({"post-build": fixture.identity["buildFingerprint"],
                             "post-build-incremental": number, "post-timestamp": str(timestamp)})
    with zipfile.ZipFile(fixture.target, "a") as archive:
        properties = {"ro.build.version.release": "17", "ro.build.version.sdk": "37",
                      "ro.build.version.incremental": number, "ro.build.type": "userdebug",
                      "ro.build.fingerprint": fixture.identity["buildFingerprint"],
                      "ro.build.date.utc": str(timestamp), "ro.build.version.security_patch": "2026-10-01"}
        archive.writestr("SYSTEM/build.prop", "".join(k + "=" + v + "\n" for k, v in properties.items()))
        archive.writestr("META/ab_partitions.txt", "boot\nsystem\n")
        archive.writestr("META/apkcerts.txt", "fixture only\n")
        if brand:
            props = {key: brand[field] for key, field in (
                ('ro.taku.product.name', 'productName'), ('ro.taku.product.version', 'productVersion'),
                ('ro.taku.display.version', 'displayVersion'))}
            props['lineage.updater.uri'] = 'https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}'
            archive.writestr('SYSTEM_EXT/etc/build.prop', ''.join(k + '=' + v + '\n' for k, v in props.items()))
            archive.writestr('PRODUCT/etc/build.prop', 'ro.build.display.id=' + brand['buildDisplayId'] + '\n')
            archive.writestr('VENDOR/build.prop', 'fixture.vendor=1\n')
            archive.writestr('SYSTEM_EXT/priv-app/Updater/Updater.apk', b'fixture APK, not a signed Android app')
    # This is an explicit stub-crypto fixture; the signature parser tests separately
    # preserve flags=3 coverage. Planning fixtures need measurable boot configuration.
    with zipfile.ZipFile(fixture.target) as archive:
        contents={name:archive.read(name) for name in archive.namelist()}
    root_vbmeta=bytearray(contents["IMAGES/vbmeta.img"]);struct.pack_into('>I',root_vbmeta,120,0)
    contents["IMAGES/vbmeta.img"]=root_vbmeta
    with zipfile.ZipFile(fixture.target,"w") as archive:
        for name,data in contents.items():archive.writestr(name,data)
    with zipfile.ZipFile(fixture.ota, "w") as archive:
        archive.writestr("META-INF/com/android/metadata", "".join(k + "=" + v + "\n" for k, v in fixture.metadata.items()))
        archive.writestr("payload.bin", full_payload(partial))
    shutil.copy2(fixture.ota, fixture.alias)
    if brand:
        path = fixture.ota.with_name(brand['fullOtaFileName'])
        fixture.ota.rename(path)
        fixture.ota = path
    fixture.seal()
    fixture.receipt["abPartitions"] = ["boot", "system"]
    if brand:
        fixture.receipt['buildInput'] = {k: fixture.inputs[k] for k in ('buildNumber', 'snapshotDigest', 'sourceCommit')}
        fixture.receipt['fullOtas'] = fixture.receipt['fullOtas'][:1]
        fixture.status['artifacts'] = [fixture.artifact(p) for p in (fixture.target, fixture.ota)]
        fixture.receipt['productBranding'] = brand
        with zipfile.ZipFile(fixture.target) as archive:
            fixture.receipt['productBrandingBootProperties'] = release.BRANDING.verify_boot_properties(archive, brand)
            apk = archive.read('SYSTEM_EXT/priv-app/Updater/Updater.apk')
        identity = {k: v for k, v in brand.items() if k not in ('buildDisplayId', 'fullOtaFileName')}
        manifest_sha = release.digest(root / 'branding-manifest.json')
        write(root / 'product-branding-integration.json', {'schema': 'taku.product-branding-integration/1',
              'productBranding': identity, 'sourcePatchVerified': True, 'manifestSha256': manifest_sha})
        apk_proof = {'schema': 'taku.lineage-updater-apk/1', 'packageName': 'org.lineageos.updater',
                     'versionCode': brand['updaterVersionCode'], 'apkSignatureVerified': True,
                     'apkSha256': hashlib.sha256(apk).hexdigest(), 'apkBytes': len(apk),
                     'manifestSha256': manifest_sha, 'platformCertificateSha256': 'a' * 64}
        uri = 'https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}'
        write(root / 'updater-apk-verification.json', apk_proof)
        write(root / 'updater-integration.json', {'schema': 'taku.lineage-updater-integration/1',
              'versionCode': brand['updaterVersionCode'], 'sourcePatchVerified': True,
              'uri': uri, 'manifestSha256': manifest_sha})
        fixture.receipt['updaterIntegration'] = {k: apk_proof[k] for k in
            ('versionCode', 'apkSha256', 'apkBytes', 'manifestSha256', 'platformCertificateSha256', 'apkSignatureVerified')}
        fixture.receipt['updaterIntegration'].update(uri=uri, apkEntry='SYSTEM_EXT/priv-app/Updater/Updater.apk',
            sourceEvidenceSha256=release.digest(root / 'updater-integration.json'),
            apkEvidenceSha256=release.digest(root / 'updater-apk-verification.json'))
    with zipfile.ZipFile(fixture.target) as archive:
        for name in ("META/ab_partitions.txt", "META/apkcerts.txt"):
            fixture.receipt["targetFilesMetadataSha256"][name] = hashlib.sha256(archive.read(name)).hexdigest()
    write(fixture.results / "baseline-receipt.json", fixture.receipt)
    fixture.status["baselineReceipt"]["sha256"] = release.digest(fixture.results / "baseline-receipt.json")
    write(fixture.results / "status.json", fixture.status)
    result = fixture.invoke()
    test.assertEqual(result.returncode, 0, result.stderr)
    for name in ("baseline-receipt.json", "run-input.json"):
        shutil.copy2(fixture.results / name, root / name)
    for artifact in (fixture.target, fixture.ota, fixture.alias):
        shutil.copy2(artifact, root / artifact.name)
    shutil.copytree(fixture.directory, root / "signature-verification")
    shutil.copy2(ROOT / "image/verify-signatures.py", root / "signature-verification/verify-signatures.py")
    # The production builder is mounted at /src. Normalize fixture tool paths;
    # only fixture text is transformed, never a real signature result.
    proof = root / "signature-verification/signature-verification.json"
    proof.write_text(proof.read_text().replace(str(fixture.source.resolve()), "/src").replace(str(fixture.source), "/src"))
    return root


class ArchiveGate(unittest.TestCase):
    def setUp(self):
        self.a = exported(self)

    def test_valid_export_reuses_all_crypto_evidence_without_claiming_device_acceptance(self):
        baseline = release.load_baseline(self.a)
        self.assertEqual(baseline["identity"]["buildNumber"], "20261020010000")
        self.assertEqual(baseline["abPartitions"], ["boot", "system"])
        self.assertEqual(baseline["targetFiles"]["path"], str((self.a / "target-files.zip").resolve()))

    def test_legacy_and_three_part_archives_plan_an_upgrade_without_rewriting_the_source(self):
        old = exported(self, product_version='1.0')
        new = exported(self, number='20261020020000', timestamp=1792461600, product_version='1.0.1')
        original = {p.relative_to(old): release.digest(p) for p in old.rglob('*') if p.is_file()}
        source, target = release.load_baseline(old), release.load_baseline(new)
        plan = release.create_plan(source, target)
        self.assertEqual(plan['source']['productBranding']['productVersion'], '1.0')
        self.assertEqual(plan['target']['productBranding']['productVersion'], '1.0.1')
        self.assertEqual(target['updaterIntegration']['versionCode'], 4)
        self.assertFalse(plan['physicalDeviceVerified'])
        self.assertEqual(original, {p.relative_to(old): release.digest(p) for p in old.rglob('*') if p.is_file()})

    def test_current_archive_rejects_a_mixed_generation_or_changed_branding_receipt(self):
        root = exported(self, product_version='1.0.1')
        path = root / 'baseline-receipt.json'
        original = release.read_json(path)
        for key, value in (('schema', 'taku.product-branding/1'), ('productVersion', '1.0'),
                           ('updaterVersionCode', 3), ('configSha256', 'f' * 64)):
            value_record = json.loads(json.dumps(original))
            value_record['productBranding'][key] = value
            write(path, value_record)
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                release.load_baseline(root)

    def test_numeric_product_ordering_is_enforced_even_with_a_newer_build(self):
        def baseline(version):
            config = dict(release.BRANDING.load(), productVersion=version, displayVersion='TakuOS' + version)
            return {'productBranding': config}
        release.check_product_upgrade(baseline('1.9.0'), baseline('1.10.0'))
        release.check_product_upgrade(baseline('1.0.1'), baseline('1.0.1'))
        for source, target in ((baseline('1.10.0'), baseline('1.9.0')), (baseline('1.0.1'), {})):
            with self.assertRaises(release.ReleaseError):
                release.check_product_upgrade(source, target)

    def test_changed_target_files_rejected(self):
        with (self.a / "target-files.zip").open("ab") as stream:
            stream.write(b"changed")
        with self.assertRaisesRegex(release.ReleaseError, "hash/size mismatch"):
            release.load_baseline(self.a)

    def test_partial_ota_rejected_even_if_signature_checks_pass(self):
        baseline = exported(self, partial=True)
        with self.assertRaisesRegex(release.ReleaseError, "Partial or incomplete"):
            release.load_baseline(baseline)

    def test_changed_signature_log_rejected(self):
        proof = release.read_json(self.a / "signature-verification/signature-verification.json")
        log = self.a / "signature-verification" / proof["commands"][0]["log"]
        log.write_text("altered verification log")
        with self.assertRaisesRegex(release.ReleaseError, "log differs"):
            release.load_baseline(self.a)

    def test_missing_signature_operation_rejected(self):
        path = self.a / "signature-verification/signature-verification.json"
        proof = release.read_json(path)
        proof["commands"].pop()
        write(path, proof)
        with self.assertRaisesRegex(release.ReleaseError, "command coverage"):
            release.load_baseline(self.a)

    def test_source_lock_must_match_frozen_input_manifest(self):
        (self.a / "source-lock.json").write_text("{}")
        with self.assertRaisesRegex(release.ReleaseError, "Source lock differs"):
            release.load_baseline(self.a)

    def test_input_manifest_tamper_rejected(self):
        path = self.a / "input-manifest.json"
        manifest = release.read_json(path)
        manifest["files"][0]["sha256"] = "0" * 64
        write(path, manifest)
        with self.assertRaisesRegex(release.ReleaseError, "snapshot digest differs"):
            release.load_baseline(self.a)

    def test_symlink_artifact_is_not_an_immutable_delivery(self):
        path = self.a / "target-files.zip"
        path.rename(self.a / "moved.zip")
        path.symlink_to(self.a / "moved.zip")
        with self.assertRaisesRegex(release.ReleaseError, "escaped delivery"):
            release.load_baseline(self.a)


class PlanningGate(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Build one pair of explicit stub-crypto fixtures for pure planning tests.
        cls.fixture_owner = unittest.TestCase()
        cls.a_path = exported(cls.fixture_owner)
        cls.b_path = exported(cls.fixture_owner, "20261020020000", 1792461600)
        cls.a = release.load_baseline(cls.a_path)
        cls.b = release.load_baseline(cls.b_path)

    @classmethod
    def tearDownClass(cls):
        cls.fixture_owner.doCleanups()

    def setUp(self):
        self.a, self.b = json.loads(json.dumps(self.__class__.a)), json.loads(json.dumps(self.__class__.b))
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)

    def test_stock_fingerprint_may_stay_same_but_candidate_must_change(self):
        plan = release.create_plan(self.a, self.b)
        self.assertEqual(self.a["identity"]["buildFingerprint"], self.b["identity"]["buildFingerprint"])
        self.assertFalse(plan["physicalDeviceVerified"])
        self.assertFalse(plan["dataPreservationVerified"])

    def test_older_build_rejected(self):
        with self.assertRaisesRegex(release.ReleaseError, "newer build"):
            release.create_plan(self.b, self.a)
    def test_disabled_boot_verification_rejected_for_either_candidate(self):
        for candidate in (self.a,self.b):
            root=next(node for node in candidate['avbNodes'] if node['partition']=='vbmeta')
            for flags in (2,3,4):
                root['flags']=flags
                with self.assertRaisesRegex(release.ReleaseError,'bootloader vbmeta measurement'):
                    release.create_plan(self.a,self.b)
            root['flags']=0

    def test_changed_source_or_kernel_rejected_for_first_demo(self):
        for key in ("sourceLockSha256", "kernelSourceLockSha256"):
            changed = dict(self.b, **{key: "0" * 64})
            with self.assertRaisesRegex(release.ReleaseError, key):
                release.create_plan(self.a, changed)

    def test_changed_trust_rejected(self):
        self.b["trustedOtaCertificates"] = ["0" * 64]
        with self.assertRaisesRegex(release.ReleaseError, "trustedOtaCertificates"):
            release.create_plan(self.a, self.b)

    def test_avb_rollback_index_decrease_rejected(self):
        self.b["avbNodes"][0]["rollbackIndex"] = 0
        with self.assertRaisesRegex(release.ReleaseError, "rollback index decreased"):
            release.create_plan(self.a, self.b)

    def test_avb_key_rotation_rejected(self):
        self.b["avbNodes"][0]["publicKeySha256"] = "0" * 64
        with self.assertRaisesRegex(release.ReleaseError, "AVB signing"):
            release.create_plan(self.a, self.b)

    def test_sealed_plan_revalidates_all_archived_bytes(self):
        path = self.root / "plan.json"
        plan = release.create_plan(self.a, self.b)
        write(path, plan)
        self.assertEqual(release.load_plan(path, release.digest(path), revalidate=True), plan)
        with self.assertRaisesRegex(release.ReleaseError, "plan SHA-256"):
            release.load_plan(path, "0" * 64)

    def test_plan_cannot_promote_itself_to_device_verified(self):
        path = self.root / "plan.json"
        plan = release.create_plan(self.a, self.b)
        plan["physicalDeviceVerified"] = True
        write(path, plan)
        with self.assertRaisesRegex(release.ReleaseError, "plan fields differ"):
            release.load_plan(path, release.digest(path))

    def test_no_before_evidence_cannot_generate(self):
        path = self.root / "before.json"
        write(path, {"schema": "taku.ota-device-before/1", "result": "pending"})
        with self.assertRaisesRegex(release.ReleaseError, "another A/B transition"):
            release.validate_before(path, release.digest(path), release.create_plan(self.a, self.b))

    def test_output_file_never_overwrites_existing_plan(self):
        path = self.root / "plan.json"
        CLI["write_json"](path, {"first": True})
        with self.assertRaises(FileExistsError):
            CLI["write_json"](path, {"second": True})
        self.assertEqual(release.read_json(path), {"first": True})

    def test_one_verified_baseline_can_be_bound_before_future_candidate_exists(self):
        path=self.root/'baseline-binding.json'
        bound=release.create_baseline_binding(self.a)
        write(path,bound)
        self.assertEqual(release.load_baseline_binding(path,release.digest(path)),self.a)
        self.assertFalse(bound['physicalDeviceVerified'])
        self.assertNotIn('target',bound)

    def test_binding_rejects_tampering_and_self_promoted_acceptance(self):
        path=self.root/'baseline-binding.json';bound=release.create_baseline_binding(self.a);write(path,bound)
        with self.assertRaisesRegex(release.ReleaseError,'binding SHA-256'):
            release.load_baseline_binding(path,'0'*64)
        bound['physicalDeviceVerified']=True;write(path,bound)
        with self.assertRaisesRegex(release.ReleaseError,'binding fields'):
            release.load_baseline_binding(path,release.digest(path))

    def test_binding_rejects_unmeasurable_or_invalid_full_ota(self):
        root=next(n for n in self.a['avbNodes'] if n['partition']=='vbmeta');root['flags']=3
        with self.assertRaisesRegex(release.ReleaseError,'bootloader vbmeta measurement'):
            release.create_baseline_binding(self.a)
        root['flags']=0;self.a['fullOtas'][0]['bytes']=0
        with self.assertRaisesRegex(release.ReleaseError,'Invalid bound full OTA'):
            release.create_baseline_binding(self.a)

    def test_running_builder_cannot_start_delta_generation(self):
        state = json.dumps([{"State": {"Running": True, "ExitCode": 0}}]).encode()
        with patch("subprocess.check_output", return_value=state):
            with self.assertRaisesRegex(release.ReleaseError, "Wait for the full-image"):
                CLI["current_builder"](self.b)

    def install_args(self):
        evidence = self.root / "review"
        evidence.mkdir()
        (evidence / "device.json").write_text('{"fixture":"not a real firmware assessment"}')
        package = pathlib.Path(self.a["fullOtas"][0]["path"])
        assessment = {"schema": "taku.ota-firmware-assessment/1", "result": "compatible",
                      "baselineReceiptSha256": self.a["receiptSha256"],
                      "adbSerialSha256": hashlib.sha256(b"FIXTURE_PIXEL9").hexdigest(),
                      "fullOtaSha256": release.digest(package),
                      "checks": {"deviceModelVerified": True, "firmwarePathCompatible": True,
                                 "bootloaderAcceptsPackage": True},
                      "evidence": [{"path": "device.json", "sha256": release.digest(evidence / "device.json")}]}
        write(evidence / "assessment.json", assessment)
        return SimpleNamespace(package=package, serial="FIXTURE_PIXEL9", adb="fake-adb",
                               assessment=evidence / "assessment.json",
                               assessment_sha256=release.digest(evidence / "assessment.json"),
                               output=self.root / "install")

    def test_full_installation_records_exact_source_package_and_review(self):
        args = self.install_args()
        plan = release.create_plan(self.a, self.b)
        with patch("subprocess.check_output", return_value="sideload\n"), patch(
                "subprocess.run", return_value=SimpleNamespace(returncode=0)) as run:
            result = CLI["baseline_sideload"](args, self.a)
        self.assertEqual(run.call_args.args[0], ["fake-adb", "-s", "FIXTURE_PIXEL9", "sideload", str(args.package)])
        before = {"device": {"adbSerialSha256": hashlib.sha256(b"FIXTURE_PIXEL9").hexdigest()}}
        record = CLI["validate_source_installation"](pathlib.Path(result["path"]), result["sha256"], plan, before)
        self.assertTrue(record["commandCompleted"])
        self.assertFalse(record["targetBootVerified"])
        self.assertFalse(record["dataPreservationVerified"])

    def test_full_installation_refuses_unassessed_firmware_before_adb(self):
        args = self.install_args()
        args.assessment_sha256 = "0" * 64
        with patch("subprocess.check_output") as adb:
            with self.assertRaisesRegex(release.ReleaseError, "Firmware assessment SHA"):
                CLI["baseline_sideload"](args, self.a)
            adb.assert_not_called()

    def test_full_installation_never_enters_recovery_itself(self):
        args = self.install_args()
        with patch("subprocess.check_output", return_value="device\n"), patch("subprocess.run") as run:
            with self.assertRaisesRegex(release.ReleaseError, "recovery ADB sideload"):
                CLI["baseline_sideload"](args, self.a)
            run.assert_not_called()

    def test_full_installation_failure_keeps_record_without_acceptance(self):
        args = self.install_args()
        with patch("subprocess.check_output", return_value="sideload\n"), patch(
                "subprocess.run", return_value=SimpleNamespace(returncode=7)):
            with self.assertRaisesRegex(release.ReleaseError, "needs investigation"):
                CLI["baseline_sideload"](args, self.a)
        record = release.read_json(args.output / "installation.json")
        self.assertFalse(record["commandCompleted"])
        self.assertEqual(record["exitCode"], 7)

    def test_full_installation_rejects_package_not_in_accepted_source(self):
        args = self.install_args()
        args.package = pathlib.Path(self.b["fullOtas"][0]["path"])
        with self.assertRaisesRegex(release.ReleaseError, "not the accepted source"):
            CLI["baseline_sideload"](args, self.a)

    def test_interrupted_generator_cache_copy_can_be_retried(self):
        source, target = self.root / "helper.py", self.root / "cache/helper.py"
        source.write_text("fixture helper bytes")
        def interrupted(incoming, output, *args):
            output.write(b"bad")
            raise OSError("injected copy interruption")
        with patch("shutil.copyfileobj", side_effect=interrupted):
            with self.assertRaisesRegex(OSError, "copy interruption"):
                CLI["freeze_helper"](source, target)
        self.assertFalse(target.exists())
        CLI["freeze_helper"](source, target)
        self.assertEqual(target.read_bytes(), source.read_bytes())
        self.assertTrue(list(target.parent.glob("*.interrupted-*")))

    def test_old_bad_helper_cache_is_preserved_and_repaired(self):
        source, target = self.root / "helper.py", self.root / "cache/helper.py"
        source.write_text("fixture helper bytes")
        target.parent.mkdir()
        target.write_text("old partial")
        CLI["freeze_helper"](source, target)
        self.assertEqual(target.read_bytes(), source.read_bytes())
        self.assertEqual(next(target.parent.glob("*.invalid-*")).read_text(), "old partial")



class DirectSourceEvidenceGate(unittest.TestCase):
    """Synthetic historical logs; archive/probe validators are explicit test doubles."""
    def setUp(self):
        import ota_source
        import ota_device
        self.source_module, self.device = ota_source, ota_device
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        device_fixture = runpy.run_path(str(ROOT / "tests/ota-device.test.py"))
        self.source = device_fixture["baseline"]("20261024154635", 1792856795)
        self.target = device_fixture["baseline"]("20261025154635", 1792943195)
        self.serial = "FIXTURE_DIRECT_PIXEL"
        self.before = {"schema": "taku.ota-device-before/2", "device": {
            "hardwareSerialSha256": ota_device.sha_text(self.serial),
            "adbSerialSha256": ota_device.sha_text(self.serial)}, "probe": {"fixture": "not real probe acceptance"}}
        self.before_path = self.root / "pre-upgrade.json"; write(self.before_path, self.before)
        self.before_hash = release.digest(self.before_path)
        self.refs = {n: {"bytes": 512, "sha256": ota_device.sha_text("fixture image " + n)}
                     for n in ota_source.PARTITIONS}
        self.reference = {"partitions": self.refs, "manifest": {"path": "source-payload-manifest.bin"}}
        manifest_path = self.root / "source-payload-manifest.bin"
        manifest_path.write_bytes(b"synthetic not a signed manifest")
        self.reference["manifest"].update(bytes=manifest_path.stat().st_size, sha256=release.digest(manifest_path))
        proto_path = self.root / "update_metadata.proto"; proto_path.write_text("synthetic proto not actual pinned schema")
        self.reference["payloadSchema"] = {"path": proto_path.name, "bytes": proto_path.stat().st_size,
                                           "sha256": release.digest(proto_path)}
        self.ref_path = self.root / "reference.json"; write(self.ref_path, self.reference)
        self.qualification = {"partitions": {}}
        for n in ota_source.PARTITIONS:
            path = self.root / (n + ".stderr"); path.write_bytes(b"")
            self.qualification["partitions"][n] = {"stderr": path.name, "stderrSha256": release.digest(path)}
        self.qual_path = self.root / "qualification.json"; write(self.qual_path, self.qualification)
        originals = self.root / "originals"; originals.mkdir()
        def original(name, value):
            path = originals / name; write(path, value)
            return {"path": str(path), "sha256": release.digest(path)}
        receipt = original("receipt.json", {"buildIdentity": self.source["identity"]})
        inputs = original("run.json", {"fixture": "run"})
        signature = original("signature.json", {"fixture": "NOT cryptographic acceptance"})
        self.source.update(receiptSha256=receipt["sha256"], runInputSha256=inputs["sha256"], signatureReportSha256=signature["sha256"])
        super_input = original("super-input.json", {"receiptSha256": receipt["sha256"],
            "runInputSha256": inputs["sha256"], "signatureReportSha256": signature["sha256"],
            "vbmetaDigest": self.source["vbmetaDigest"], "targetFiles": self.source["targetFiles"]})
        super_image = {"bytes": 64, "sha256": ota_device.sha_text("fixture super image")}
        producer = original("producer.json", {"result": "generated", "inputSha256": super_input["sha256"],
            "commands": [{"actualExitCode": 0}]})
        producer_controller = original("producer-controller.json", {"actualDockerExitCode": 0, "reportSha256": producer["sha256"]})
        verified = original("verified.json", {"schema": "taku.I-super-verify/1", "result": "verified",
            "buildNumber": self.source["identity"]["buildNumber"], "snapshotDigest": "1"*64,
            "receiptSha256": receipt["sha256"], "inputSha256": super_input["sha256"],
            "producer": {"reportSha256": producer["sha256"], "actualDockerExitCode": 0,
                         "containerResultSha256": producer_controller["sha256"]},
            "userdataIncluded": False, "metadataPhysicalPartitionIncluded": False, "pvmfwOrFirmwareIncluded": False,
            "commands": [{"actualExitCode": 0}], "superImage": super_image,
            "logicalPartitions": {n+"_a": self.refs[n] for n in ota_source.DYNAMIC},
            "bootImages": {n: self.refs[n] for n in ota_source.BOOT}})
        verify_controller = original("verify-controller.json", {"actualDockerExitCode": 0, "reportSha256": verified["sha256"]})
        self.source["snapshotDigest"] = "1"*64
        plan = {"schema": "taku.pixel9-branded-runtime-install-plan/1", "candidate": "I",
            "buildNumber": self.source["identity"]["buildNumber"], "snapshotDigest": self.source["snapshotDigest"],
            "sourceTargetFilesSha256": self.source["targetFiles"]["sha256"], "finalized": True,
            "templateOnly": False, "userdataAndMetadataExcluded": True, "selectedSerial": self.serial,
            "writeOrder": list(ota_source.FLASH_ORDER), "images": {}, "proofs": {
            "baselineReceipt": receipt, "runInput": inputs, "signatureReport": signature,
            "superVerification": verified, "superProducer": producer, "superVerificationInput": super_input,
            "superProducerInput": super_input, "superVerifyController": verify_controller,
            "superProducerController": producer_controller}}
        for n in ota_source.FLASH_ORDER:
            plan["images"][n] = {"path": "/fixture/"+n+".img", **(super_image if n == "super" else self.refs[n[:-2]])}
        install_plan = original("installation-plan.json", plan)
        result = original("result.json", {"selectedSerial": self.serial, "planSha256": install_plan["sha256"],
            "mode": "execute", "flashCommandsCompleted": True, "completedFlashSteps": list(ota_source.FLASH_ORDER),
            "status": "flash-complete-await-boot-validation", "deviceBootVerified": False, "dataPreservationVerified": False})
        rows = []
        for n in ota_source.FLASH_ORDER:
            log = originals / (n+".log"); log.write_text("Sending OKAY\nWriting OKAY\nFinished\n")
            argv = ["/fixture/fastboot", "-s", self.serial, "flash", n, plan["images"][n]["path"]]
            rows.extend([{"argv": argv, "phase": "starting-flash"}, {"argv": argv, "exitCode": 0,
                "log": str(log), "logSha256": release.digest(log), "imageSha256": plan["images"][n]["sha256"]}])
        commands = originals / "commands.jsonl"; commands.write_text("".join(json.dumps(r)+"\n" for r in rows))
        postboot = originals / "postboot"; postboot.mkdir()
        for n in ("report.json", "commands.jsonl", "input-bindings.json"):
            (postboot / n).write_text('{"fixture":"anchor parser tested separately"}')
        self.input = self.root / "direct-input.json"
        write(self.input, {"schema": "taku.direct-image-source-input/1", "files": {
            "installationPlan": install_plan, "flashResult": result,
            "flashCommands": {"path": str(commands), "sha256": release.digest(commands)},
            "postbootReport": {"path": str(postboot/"report.json"), "sha256": release.digest(postboot/"report.json")}}})
        self.plan = {"source": self.source, "target": self.target}
        for name, returned in [("ota_device.validate_before", self.before),
                ("ota_source.validate_reference", self.reference), ("ota_source.validate_qualification", self.qualification),
                ("ota_device.load_probe_anchor", {"probe": self.before["probe"], "hardwareSerialSha256": ota_device.sha_text(self.serial)})]:
            p = patch(name, return_value=returned); p.start(); self.addCleanup(p.stop)
        self.output = self.root / "bound"
        self.record = ota_source.bind_direct_source(self.plan, self.before_path, self.before_hash,
            self.ref_path, release.digest(self.ref_path), self.qual_path, release.digest(self.qual_path),
            self.input, release.digest(self.input), self.output)

    def validate(self):
        return CLI["validate_source_installation"](self.output / "installation.json",
            release.digest(self.output / "installation.json"), self.plan, self.before, self.before_hash)

    def test_actual_separate_log_shape_and_portable_historical_proofs(self):
        record = self.validate()
        self.assertEqual(record["method"], "direct-fastboot-images")
        self.assertFalse(record["sourceInstallationWasOta"])
        self.assertFalse(record["allUserDataVerified"])
        self.source_module.copy_direct_source(self.output/"installation.json", self.record["sha256"],
            self.root/"portable", self.source, self.before, self.before_hash)
        self.assertEqual(release.digest(self.root/"portable/installation.json"), self.record["sha256"])

    def test_delivery_revalidates_actual_delta_source_and_keeps_api(self):
        self.source_module.copy_direct_source(self.output/"installation.json", self.record["sha256"],
            self.root/"delivery/source-installation", self.source, self.before, self.before_hash)
        directory=self.root/"delivery"; self.plan["id"]="fixture-I-to-J"
        write(directory/"plan.json", self.plan); write(directory/"pre-upgrade.json", self.before)
        (directory/"generate-incremental-ota.py").write_text("fixture not executable crypto")
        (directory/"incremental-ota.zip").write_bytes(b"fixture package")
        commands=[]
        for operation in ("generate", "verify-signatures"):
            log=directory/(operation+".log");log.write_text("fixture command result")
            commands.append({"operation":operation,"exitCode":0,"log":log.name,"logSha256":release.digest(log)})
        report={"schema":"taku.incremental-ota/1","result":"verified","checks":{
            k:True for k in ("exactSourceTargetIdentityVerified","noWipeOrDowngrade","otaPackageSignatureVerified","payloadSignatureVerified")},
            "generatorSha256":release.digest(directory/"generate-incremental-ota.py"),
            "sourceTargetFiles":self.source["targetFiles"],"targetTargetFiles":self.target["targetFiles"],
            "commands":commands,"package":{"bytes":(directory/"incremental-ota.zip").stat().st_size,
                                         "sha256":release.digest(directory/"incremental-ota.zip")}}
        write(directory/"incremental-ota.json",report)
        expected_source={"synthetic":"parsed old partition source result"}
        delivery={"schema":"taku.ota-delivery/1","id":self.plan["id"],"planSha256":release.digest(directory/"plan.json"),
            "beforeSha256":self.before_hash,"sourceInstallationSha256":self.record["sha256"],
            "generatorReportSha256":release.digest(directory/"incremental-ota.json"),
            "package":{"path":"incremental-ota.zip",**report["package"]},"sourcePartitionValidation":expected_source}
        path=directory/"delivery.json";write(path,delivery)
        with patch("ota_source.validate_delta_source",return_value=expected_source) as parser:
            checked,package=CLI["validate_delivery"](path,release.digest(path),self.plan,delivery["planSha256"],self.before_hash)
            self.assertEqual(checked,delivery);self.assertEqual(parser.call_args.args[0],package)
            del delivery["sourcePartitionValidation"];write(path,delivery)
            with self.assertRaisesRegex(release.ReleaseError,"source-partition"):
                CLI["validate_delivery"](path,release.digest(path),self.plan,delivery["planSha256"],self.before_hash)

    def test_direct_source_requires_explicit_before_hash(self):
        with self.assertRaisesRegex(release.ReleaseError, "explicit before"):
            CLI["validate_source_installation"](self.output/"installation.json", self.record["sha256"], self.plan, self.before)

    def test_real_flash_log_tamper_is_rejected_even_when_summary_says_complete(self):
        (self.output/"historical/flash-logs/super.log").write_text("FAILED")
        with self.assertRaises(release.ReleaseError): self.validate()

    def test_missing_controller_is_not_accepted(self):
        path = self.output/"installation.json"; record = release.read_json(path)
        del record["proofs"]["superVerifyController"]; write(path, record)
        with self.assertRaisesRegex(release.ReleaseError, "proof roles"): self.validate()

    def test_flash_command_cannot_hide_a_wipe(self):
        record_path = self.output/"installation.json"; record = release.read_json(record_path)
        path = self.output/record["historicalEvidence"]["flashCommands"]["path"]
        with path.open("a") as stream: stream.write(json.dumps({"argv":["fastboot","-s",self.serial,"-w"],"exitCode":0})+"\n")
        record["historicalEvidence"]["flashCommands"].update(bytes=path.stat().st_size,sha256=release.digest(path));write(record_path,record)
        with self.assertRaises(release.ReleaseError): self.validate()

    def test_historical_image_hash_mismatch_is_rejected(self):
        record_path=self.output/"installation.json";record=release.read_json(record_path)
        path=self.output/record["historicalEvidence"]["flashCommands"]["path"]
        rows=[json.loads(line) for line in path.read_text().splitlines()];rows[1]["imageSha256"]="0"*64
        path.write_text("".join(json.dumps(row)+"\n" for row in rows))
        record["historicalEvidence"]["flashCommands"].update(bytes=path.stat().st_size,sha256=release.digest(path));write(record_path,record)
        with self.assertRaisesRegex(release.ReleaseError,"Historical exact flash"):self.validate()

    def test_no_ota_sideload_claim_can_use_direct_schema(self):
        path=self.output/"installation.json";record=release.read_json(path);record["method"]="adb-sideload";write(path,record)
        with self.assertRaisesRegex(release.ReleaseError,"Direct-image source"):self.validate()

if __name__ == "__main__":
    unittest.main()
