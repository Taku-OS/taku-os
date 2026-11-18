#!/usr/bin/env python3
"""Private-data retention boundary tests using fake ADB; no connected devices."""
import copy
import importlib.util
import json
import pathlib
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
SPEC = importlib.util.spec_from_file_location("ota_device", ROOT / "scripts/ota_device.py")
DEVICE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEVICE)
SERIAL = "physical-pixel-9-fixture"
HARDWARE_SERIAL = "private-hardware-serial-fixture"


def baseline(number, timestamp):
    return {"directory": "/unused/fixture/" + number,
            "identity": {"androidRelease": "17", "androidSdk": 37, "productDevice": "tokay",
                         "vendorDevice": "tokay", "buildNumber": number, "buildTimestamp": timestamp,
                         "platformSecurityPatch": "2026-10-05", "buildType": "userdebug",
                         "buildFingerprint": "taku/tokay/tokay:17/B/" + number + ":userdebug/test-keys"},
            "receiptSha256": DEVICE.sha_text("receipt" + number),
            "signatureReportSha256": DEVICE.sha_text("signature" + number),
            "vbmetaDigest": DEVICE.sha_text("vbmeta" + number),
            "targetFiles": {"path": "fixture-target-files.zip", "bytes": 1, "sha256": DEVICE.sha_text("target" + number)},
            "fullOtas": [{"path": "fixture-full.zip", "bytes": 1, "sha256": DEVICE.sha_text("ota" + number)}]}


class FakeAdb:
    """Allow only the named commands the probe needs, and fail unknown mutations."""
    def __init__(self, initial):
        self.calls = []
        self.serial = SERIAL
        self.state = "device"
        self.props = {"ro.kernel.qemu": "0", "ro.boot.qemu": "0", "sys.boot_completed": "1",
                      "ro.serialno": HARDWARE_SERIAL, "ro.bootloader": "fixture-bootloader",
                      "gsm.version.baseband": "fixture-baseband"}
        self.current_user = "0"
        self.user_state = "RUNNING_UNLOCKED"
        self.app_path = "/data/app/~~fixture/org.taku.otaprobe-abc/base.apk"
        self.app_hash = None
        self.app_uid = "10123"
        self.data = {}
        self.nonce = None
        self.install_output = "Success"
        self.launch_output = "Status: ok\nComplete"
        self.initialize = True
        self.failed_operation = None
        self.callback = None
        self.boot_id = "11111111-2222-3333-4444-555555555555"
        self.boot(initial, "_a")

    def boot(self, candidate, slot):
        identity = candidate["identity"]
        for name, prop in DEVICE.PROPERTIES.items():
            if name in identity:
                self.props[prop] = str(identity[name])
        self.props.update({"ro.product.device": identity["productDevice"],
                           "ro.boot.vbmeta.digest": candidate["vbmetaDigest"], "ro.boot.slot_suffix": slot})

    def __call__(self, args, **kwargs):
        assert args[:3] == ["fixture-adb", "-s", self.serial], "ADB must always address the explicit serial"
        assert kwargs == {"capture_output": True, "text": True, "timeout": 120 if args[3] == "install" else 30,
                          "check": False}
        self.calls.append(list(args))
        command = args[3:]
        if self.callback:
            self.callback(self, command)
        if self.failed_operation == command:
            return subprocess.CompletedProcess(args, 1, "", "private diagnostic must not enter evidence")
        output = ""
        code = 0
        if command == ["get-state"]:
            output = self.state
        elif command[:2] == ["shell", "getprop"] and len(command) == 3:
            output = self.props.get(command[2], "")
        elif command == ["shell", "am", "get-current-user"]:
            output = self.current_user
        elif command == ["shell", "am", "get-started-user-state", "0"]:
            output = self.user_state
        elif command == ["shell", "cat", DEVICE.BOOT_ID_PATH]:
            output = self.boot_id
        elif command[:4] == ["install", "-r", "--user", "0"] and len(command) == 5:
            self.app_hash = DEVICE.digest(command[4])
            output = self.install_output
        elif command[:9] == ["shell", "am", "start", "-W", "--user", "0", "-n",
                              DEVICE.PACKAGE + "/.ProbeActivity", "--es"] and len(command) == 11:
            assert command[9] == "nonce"
            self.nonce = command[10]
            if self.initialize:
                self.data = {"preferences": DEVICE.sha_text("preferences:" + self.nonce),
                             "sqlite": DEVICE.sha_text("sqlite:" + self.nonce),
                             "privateFile": DEVICE.sha_text(self.nonce + "\n")}
            output = self.launch_output
        elif command == ["shell", "pm", "path", "--user", "0", DEVICE.PACKAGE]:
            output = "package:" + self.app_path if self.app_hash else ""
        elif command == ["shell", "/system/bin/sha256sum", self.app_path]:
            output = self.app_hash + "  " + self.app_path
        elif command == ["shell", "run-as", DEVICE.PACKAGE, "--user", "0", "/system/bin/id", "-u"]:
            output = self.app_uid
        elif command[:6] == ["shell", "run-as", DEVICE.PACKAGE, "--user", "0", "/system/bin/sha256sum"]:
            assert len(command) == 7
            kind = next((key for key, value in DEVICE.DATA_FILES.items() if value == command[6]), None)
            assert kind is not None
            if kind in self.data:
                output = self.data[kind] + "  " + command[6]
            else:
                code = 1
        else:
            raise AssertionError("Unapproved ADB command: " + repr(command))
        return subprocess.CompletedProcess(args, code, output, "")


class DeviceBoundary(unittest.TestCase):
    def test_legacy_source_and_three_part_target_keep_distinct_exact_bindings(self):
        source = copy.deepcopy(self.source)
        target = copy.deepcopy(self.target)
        old = {'schema':'taku.product-branding/1','productName':'TakuOS','productVersion':'1.0',
               'displayVersion':'TakuOS1.0','updaterVersionCode':3,'configSha256':DEVICE.sha_text('legacy fixture')}
        new = {'schema':'taku.product-branding/2','productName':'TakuOS','productVersion':'1.0.1',
               'displayVersion':'TakuOS1.0.1','updaterVersionCode':4,'configSha256':DEVICE.sha_text('current fixture')}
        source.update(productBranding=old, updaterIntegration={'versionCode':3})
        target.update(productBranding=new, updaterIntegration={'versionCode':4})
        binding = DEVICE.transition_bindings(source, target)
        self.assertEqual(binding['from']['productBranding'], old)
        self.assertEqual(binding['to']['productBranding'], new)
        target['productBranding'] = dict(new, productVersion='0.9.9', displayVersion='TakuOS0.9.9')
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, 'Product version downgrade'):
            DEVICE.transition_bindings(source, target)

    def test_legacy_binding_shape_is_unchanged_and_new_brand_carries_updater_contract(self):
        source=baseline('20261022160657',1792685217)
        legacy=DEVICE.baseline_binding(source)
        self.assertEqual(set(legacy),{'receiptSha256','signatureReportSha256','targetFilesSha256',
                                     'fullOtaSha256s','identity','vbmetaDigest'})
        brand={'schema':'taku.product-branding/1','productName':'TakuOS','productVersion':'1.0',
               'updaterVersionCode':3,'displayVersion':'TakuOS1.0','configSha256':DEVICE.sha_text('fixture config')}
        integration={'versionCode':3,'apkSha256':DEVICE.sha_text('fixture signed v3')}
        source.update(productBranding=brand,updaterIntegration=integration)
        branded=DEVICE.baseline_binding(source)
        self.assertEqual(branded,{**legacy,'productBranding':brand,'updaterIntegration':integration})
        source.pop('updaterIntegration')
        with self.assertRaises(DEVICE.DeviceVerificationError):DEVICE.baseline_binding(source)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.evidence = self.root / "evidence"
        self.apk = self.root / "probe.apk"
        # Command fixture, deliberately not a runnable/signed Android APK.
        self.apk.write_bytes(b"fake-adb-probe-apk-not-a-real-app")
        self.source = baseline("2026102001", 1792454400)
        self.target = baseline("2026102002", 1792458000)
        self.fake = FakeAdb(self.source)

    def before(self, **kwargs):
        return DEVICE.capture_before(kwargs.pop("serial", SERIAL), self.source, self.target,
                                     self.evidence, self.apk, adb="fixture-adb", runner=self.fake, **kwargs)

    def after(self, before, **kwargs):
        return DEVICE.capture_after(kwargs.pop("serial", SERIAL), self.source, self.target,
                                    self.evidence, before["sha256"], adb="fixture-adb", runner=self.fake, **kwargs)

    def prepared(self):
        before = self.before()
        self.fake.boot(self.target, "_b")
        return before

    def no_report(self, name="post-upgrade.json"):
        self.assertFalse((self.evidence / name).exists())

    def test_success_binds_images_device_and_all_three_app_private_stores(self):
        before = self.prepared()
        after = self.after(before)
        original = json.loads(pathlib.Path(before["path"]).read_text())
        result = json.loads(pathlib.Path(after["path"]).read_text())
        self.assertEqual(after["sha256"], DEVICE.digest(after["path"]))
        self.assertEqual(result["schema"], "taku.ota-device-after/1")
        self.assertEqual(result["beforeEvidenceSha256"], before["sha256"])
        self.assertEqual(result["baselines"], original["baselines"])
        self.assertEqual(result["device"]["vbmetaDigest"], self.target["vbmetaDigest"])
        self.assertEqual(result["probe"], original["probe"])
        self.assertEqual(set(result["probe"]["dataSha256"]), {"preferences", "sqlite", "privateFile"})
        self.assertEqual(result["checks"], {"sameDeviceVerified": True, "targetBootVerified": True,
                                         "slotChanged": True, "probePrivateDataPreserved": True})
        self.assertIs(result["allUserDataVerified"], False)

    def test_explicit_serial_and_emulator_prefix_rejected_without_adb(self):
        for serial in (None, "", "emulator-5554", "serial with space", "serial;echo-secret"):
            with self.subTest(serial=serial), self.assertRaises(DEVICE.DeviceVerificationError):
                self.before(serial=serial)
        self.assertEqual(self.fake.calls, [])

    def test_mismatched_live_build_rejected_before_install(self):
        cases = {"ro.product.device": "comet", "ro.build.version.release": "16", "ro.build.version.sdk": "36",
                 "ro.build.version.incremental": "old-partial-build", "ro.build.fingerprint": "other/fingerprint",
                 "ro.build.date.utc": "1", "ro.build.version.security_patch": "2026-09-05",
                 "ro.boot.vbmeta.digest": "f" * 64, "ro.boot.slot_suffix": "", "ro.kernel.qemu": "1",
                 "ro.boot.qemu": "1", "sys.boot_completed": "0", "ro.serialno": "unknown"}
        for prop, invalid in cases.items():
            with self.subTest(property=prop):
                self.fake = FakeAdb(self.source)
                self.fake.props[prop] = invalid
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    self.before()
                self.assertFalse(any(call[3] == "install" for call in self.fake.calls))
                self.no_report("pre-upgrade.json")

    def test_offline_locked_or_nonprimary_device_rejected_before_install(self):
        for key, value in (("state", "unauthorized"), ("current_user", "10"), ("user_state", "RUNNING_LOCKED")):
            with self.subTest(field=key):
                self.fake = FakeAdb(self.source)
                setattr(self.fake, key, value)
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    self.before()
                self.assertFalse(any(call[3] == "install" for call in self.fake.calls))

    def test_invalid_baseline_rejected_before_adb(self):
        for field, value in (("androidSdk", 36), ("androidRelease", "16"), ("buildNumber", None),
                             ("buildNumber", "non-numeric"),
                             ("buildTimestamp", True), ("buildTimestamp", "1792454400"),
                             ("platformSecurityPatch", None), ("buildFingerprint", "")):
            with self.subTest(field=field, value=value):
                source = copy.deepcopy(self.source)
                source["identity"][field] = value
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    DEVICE.capture_before(SERIAL, source, self.target, self.evidence, self.apk,
                                          adb="fixture-adb", runner=self.fake)
        self.assertEqual(self.fake.calls, [])

    def test_missing_or_identical_image_binding_rejected(self):
        for key, value in (("fullOtas", []), ("vbmetaDigest", None), ("receiptSha256", "invalid")):
            with self.subTest(field=key):
                source = copy.deepcopy(self.source)
                source[key] = value
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    DEVICE.transition_bindings(source, self.target)
        with self.assertRaises(DEVICE.DeviceVerificationError):
            DEVICE.transition_bindings(self.source, self.source)
        with self.assertRaises(DEVICE.DeviceVerificationError):
            DEVICE.transition_bindings(self.target, self.source)

    def test_target_number_and_time_must_increase_without_security_patch_downgrade(self):
        for key, value in (("buildTimestamp", self.source["identity"]["buildTimestamp"]),
                           ("buildNumber", "2026102000"), ("platformSecurityPatch", "2026-09-05")):
            target = copy.deepcopy(self.target)
            target["identity"][key] = value
            with self.subTest(field=key), self.assertRaises(DEVICE.DeviceVerificationError):
                DEVICE.transition_bindings(self.source, target)

    def test_empty_install_output_is_clean_failure(self):
        self.fake.install_output = ""
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "install"):
            self.before()
        self.no_report("pre-upgrade.json")

    def test_failed_adb_and_launch_do_not_create_success_report(self):
        self.fake.failed_operation = ["get-state"]
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "ADB operation"):
            self.before()
        self.fake.failed_operation = None
        self.fake.launch_output = "Error: activity missing"
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "initialization"):
            self.before()
        self.no_report("pre-upgrade.json")

    def test_probe_partial_initialization_rejected(self):
        self.fake.initialize = False
        with mock.patch.object(DEVICE.time, "sleep"), self.assertRaisesRegex(DEVICE.DeviceVerificationError, "finish initializing"):
            self.before()
        self.no_report("pre-upgrade.json")

    def test_source_device_swap_during_initialization_rejected(self):
        def swap(fake, command):
            if command[:3] == ["shell", "am", "start"]:
                fake.props["ro.serialno"] = "different-device"
        self.fake.callback = swap
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "changed during before"):
            self.before()
        self.no_report("pre-upgrade.json")

    def test_before_evidence_is_immutable_and_never_reinitializes(self):
        before = self.before()
        original = pathlib.Path(before["path"]).read_bytes()
        count = len(self.fake.calls)
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "already exists"):
            self.before()
        self.assertEqual(len(self.fake.calls), count)
        self.assertEqual(pathlib.Path(before["path"]).read_bytes(), original)

    def test_evidence_excludes_serial_nonce_and_contents(self):
        before = self.prepared()
        after = self.after(before)
        for item in (before, after):
            text = pathlib.Path(item["path"]).read_text()
            for sensitive in (SERIAL, HARDWARE_SERIAL, self.fake.nonce, "fake-adb-probe-apk", "private diagnostic"):
                self.assertNotIn(sensitive, text)
            report = json.loads(text)
            self.assertEqual(report["device"]["adbSerialSha256"], DEVICE.sha_text(SERIAL))
            self.assertEqual(report["device"]["hardwareSerialSha256"], DEVICE.sha_text(HARDWARE_SERIAL))
            self.assertIs(report["allUserDataVerified"], False)

    def test_after_is_read_only_and_completed_report_is_immutable(self):
        before = self.prepared()
        self.fake.calls.clear()
        after = self.after(before)
        for call in self.fake.calls:
            command = call[3:]
            self.assertTrue(command == ["get-state"] or command[0] == "shell")
            self.assertFalse(command[:3] == ["shell", "am", "start"])
        count = len(self.fake.calls)
        original = pathlib.Path(after["path"]).read_bytes()
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "already exists"):
            self.after(before)
        self.assertEqual(len(self.fake.calls), count)
        self.assertEqual(pathlib.Path(after["path"]).read_bytes(), original)

    def test_different_physical_device_rejected(self):
        before = self.prepared()
        self.fake.props["ro.serialno"] = "different-physical-phone"
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "different physical device"):
            self.after(before)
        self.no_report()

    def test_wrong_target_image_or_unchanged_slot_rejected(self):
        before = self.prepared()
        for prop, value in (("ro.boot.slot_suffix", "_a"), ("ro.boot.vbmeta.digest", self.source["vbmetaDigest"]),
                            ("ro.build.version.incremental", self.source["identity"]["buildNumber"])):
            with self.subTest(property=prop):
                self.fake.boot(self.target, "_b")
                self.fake.props[prop] = value
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    self.after(before)
                self.no_report()

    def test_missing_probe_and_each_private_data_store_rejected(self):
        before = self.prepared()
        original_hash = self.fake.app_hash
        self.fake.app_hash = None
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "Missing or split"):
            self.after(before)
        self.fake.app_hash = original_hash
        for kind in DEVICE.DATA_FILES:
            original = self.fake.data.pop(kind)
            with self.subTest(store=kind), self.assertRaises(DEVICE.DeviceVerificationError):
                self.after(before)
            self.fake.data[kind] = original
        self.no_report()

    def test_changed_probe_identity_or_each_private_store_rejected(self):
        before = self.prepared()
        for kind in DEVICE.DATA_FILES:
            original = self.fake.data[kind]
            self.fake.data[kind] = DEVICE.sha_text("changed " + kind)
            with self.subTest(store=kind), self.assertRaisesRegex(DEVICE.DeviceVerificationError, "not preserved"):
                self.after(before)
            self.fake.data[kind] = original
        for field, value in (("app_hash", DEVICE.sha_text("different apk")), ("app_uid", "10124")):
            original = getattr(self.fake, field)
            setattr(self.fake, field, value)
            with self.subTest(identity=field), self.assertRaisesRegex(DEVICE.DeviceVerificationError, "not preserved"):
                self.after(before)
            setattr(self.fake, field, original)
        self.no_report()

    def test_before_hash_tampering_rejected_without_adb(self):
        before = self.prepared()
        self.fake.calls.clear()
        pathlib.Path(before["path"]).write_text("{}")
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "hash differs"):
            self.after(before)
        self.assertEqual(self.fake.calls, [])

    def test_another_a_b_pair_rejected_without_adb(self):
        before = self.prepared()
        self.fake.calls.clear()
        self.target = baseline("2026102003", 1792461600)
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "another A/B transition"):
            self.after(before)
        self.assertEqual(self.fake.calls, [])

    def test_incomplete_before_record_rejected_even_with_recomputed_hash(self):
        before = self.prepared()
        self.fake.calls.clear()
        path = pathlib.Path(before["path"])
        original = json.loads(path.read_text())
        mutations = [lambda x: x.pop("device"), lambda x: x["device"].pop("hardwareSerialSha256"),
                     lambda x: x["device"].update(bootCompleted=False),
                     lambda x: x["device"].update(primaryUserUnlocked=False),
                     lambda x: x["device"].update(buildNumber="wrong-build"),
                     lambda x: x["device"].update(vbmetaDigest="0" * 64),
                     lambda x: x["device"].update(slotSuffix="unknown"),
                     lambda x: x.pop("probe"), lambda x: x["probe"].update(appUid=0),
                     lambda x: x["probe"]["dataSha256"].pop("sqlite"),
                     lambda x: x["probe"].update(apkSha256=""),
                     lambda x: x["checks"].update(probeInitialized=False),
                     lambda x: x["checks"].update(probeInitialized=1),
                     lambda x: x.update(allUserDataVerified=True)]
        for index, mutate in enumerate(mutations):
            with self.subTest(mutation=index):
                report = copy.deepcopy(original)
                mutate(report)
                path.write_text(json.dumps(report))
                changed = {"path": str(path), "sha256": DEVICE.digest(path)}
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    self.after(changed)
        self.assertEqual(self.fake.calls, [])
        self.no_report()

    def test_after_device_swap_or_before_mutation_during_read_rejected(self):
        before = self.prepared()
        def swap(fake, command):
            if command == ["shell", "pm", "path", "--user", "0", DEVICE.PACKAGE]:
                fake.props["ro.serialno"] = "swapped-during-read"
        self.fake.callback = swap
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "changed during after"):
            self.after(before)
        self.fake.props["ro.serialno"] = HARDWARE_SERIAL
        def tamper(fake, command):
            if command == ["shell", "pm", "path", "--user", "0", DEVICE.PACKAGE]:
                pathlib.Path(before["path"]).write_text("tampered")
        self.fake.callback = tamper
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "Before evidence changed"):
            self.after(before)
        self.no_report()


class ReusedProbeBoundary(unittest.TestCase):
    """Synthetic anchor files and fake ADB only; these are not device evidence."""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = pathlib.Path(self.temporary.name)
        self.source = baseline("20261024154635", 1792856795)
        self.target = baseline("20261025010000", 1792890000)
        self.bindings = DEVICE.transition_bindings(self.source, self.target)
        self.fake = FakeAdb(self.source)
        self.fake.app_hash = DEVICE.sha_text("synthetic existing probe APK")
        self.fake.data = {kind: DEVICE.sha_text("synthetic existing " + kind) for kind in DEVICE.DATA_FILES}
        self.probe = {"packageName": DEVICE.PACKAGE, "apkSha256": self.fake.app_hash,
                      "appUid": int(self.fake.app_uid), "dataSha256": dict(self.fake.data)}
        self.anchor_dir = self.root / "synthetic-anchor"
        self.anchor_dir.mkdir()
        self.anchor = self.anchor_dir / "report.json"
        self.evidence = self.root / "reuse-before"
        self.rows = []

        def command(args, output):
            stdout = output + "\n"
            self.rows.append({"sequence": len(self.rows) + 1, "deviceReadOnly": True,
                "argv": ["fixture-adb", "-s", HARDWARE_SERIAL, "shell", shlex.join(args)], "exitCode": 0,
                "stdout": stdout, "stdoutBytes": len(stdout.encode()), "stdoutSha256": DEVICE.sha_text(stdout),
                "stderr": "", "stderrBytes": 0, "stderrSha256": DEVICE.sha_text("")})

        command(["getprop", "ro.serialno"], HARDWARE_SERIAL)
        command(["pm", "path", "--user", "0", DEVICE.PACKAGE], "package:" + self.fake.app_path)
        command(["/system/bin/sha256sum", self.fake.app_path], self.fake.app_hash + "  " + self.fake.app_path)
        command(["run-as", DEVICE.PACKAGE, "--user", "0", "/system/bin/id", "-u"], self.fake.app_uid)
        for kind, path in DEVICE.DATA_FILES.items():
            command(["run-as", DEVICE.PACKAGE, "--user", "0", "/system/bin/sha256sum", path], self.fake.data[kind] + "  " + path)
        command(["getprop", "ro.serialno"], HARDWARE_SERIAL)
        self.inputs = {"baselineReceipt": {"sha256": self.source["receiptSha256"]},
                       "signatureReport": {"sha256": self.source["signatureReportSha256"]}}
        self.report = {"schema": DEVICE.PROBE_ANCHOR_SCHEMA, "candidate": "I", "observerRevision": 2,
            "scriptSha256": DEVICE.PROBE_ANCHOR_OBSERVER_SHA256,
            "result": "verified-scoped-I-postboot-observation", "actualExitCode": 0,
            "observationMethod": "read-only-adb-after-direct-fastboot-image-install",
            "phoneDataWritten": False, "probeSeeded": False, "allUserDataVerified": False,
            "notOtaReceipt": True, "isOtaInstallationReceipt": False,
            "checks": {key: True for key in ("newISystemBootVerified", "samePhysicalDevice", "primaryUserUnlocked", "existingProbeUnchanged")},
            "inputs": self.inputs, "archiveBinding": {"metadataBindingsVerified": True,
                "buildNumber": self.source["identity"]["buildNumber"], "vbmetaDigest": self.source["vbmetaDigest"],
                "targetFiles": dict(self.source["targetFiles"])},
            "device": {"device": "tokay", "androidRelease": "17", "androidSdk": "37",
                "buildNumber": self.source["identity"]["buildNumber"], "vbmetaDigest": self.source["vbmetaDigest"],
                "slotSuffix": "_a", "adbSerialSha256": DEVICE.sha_text(HARDWARE_SERIAL)},
            "probe": self.probe}
        self.save_anchor()

    def save_anchor(self):
        commands = self.anchor_dir / "commands.jsonl"
        commands.write_text("".join(json.dumps(row) + "\n" for row in self.rows))
        self.report["commands"] = {"path": "/historical/absolute/commands.jsonl",
                                   "sha256": DEVICE.digest(commands), "bytes": commands.stat().st_size}
        (self.anchor_dir / "input-bindings.json").write_text(json.dumps(self.inputs, ensure_ascii=False, indent=2) + "\n")
        self.anchor.write_text(json.dumps(self.report))
        self.anchor_hash = DEVICE.digest(self.anchor)

    def capture(self):
        return DEVICE.capture_before_reusing_probe(SERIAL, self.source, self.target, self.evidence,
            self.anchor, self.anchor_hash, adb="fixture-adb", runner=self.fake)

    def test_reuse_and_after_preserve_existing_probe_without_install_launch_or_seed(self):
        initial = {name: (self.anchor_dir / name).read_bytes() for name in DEVICE.PROBE_ANCHOR_FILES.values()}
        before = self.capture()
        report = DEVICE.validate_before(before["path"], before["sha256"], self.bindings)
        self.assertEqual(report["schema"], "taku.ota-device-before/2")
        self.assertEqual(report["probe"], self.probe)
        self.assertIs(report["probeInitializedByThisCapture"], False)
        self.assertNotIn("probeInitialized", report["checks"])
        self.assertEqual(report["device"]["hardwareSerialSha256"], DEVICE.sha_text(HARDWARE_SERIAL))
        self.assertEqual(report["device"]["adbSerialSha256"], DEVICE.sha_text(SERIAL))
        self.fake.boot(self.target, "_b")
        after = DEVICE.capture_after(SERIAL, self.source, self.target, self.evidence, before["sha256"],
                                     adb="fixture-adb", runner=self.fake)
        self.assertEqual(json.loads(pathlib.Path(after["path"]).read_text())["probe"], self.probe)
        self.assertIsNone(self.fake.nonce)
        self.assertFalse(any(call[3] == "install" or call[3:6] == ["shell", "am", "start"] for call in self.fake.calls))
        for name, raw in initial.items():
            self.assertEqual((self.anchor_dir / name).read_bytes(), raw)
        count = len(self.fake.calls)
        with self.assertRaises(FileExistsError):
            self.capture()
        self.assertEqual(len(self.fake.calls), count)

    def test_unknown_or_identical_target_rejected_before_adb_or_output(self):
        for target in (None, self.source):
            with self.subTest(target=target), self.assertRaises(DEVICE.DeviceVerificationError):
                DEVICE.capture_before_reusing_probe(SERIAL, self.source, target, self.evidence,
                    self.anchor, self.anchor_hash, adb="fixture-adb", runner=self.fake)
        self.assertFalse(self.evidence.exists())
        self.assertEqual(self.fake.calls, [])

    def test_read_only_connection_rejects_mutations_without_dispatch(self):
        adb = DEVICE.ReadOnlyProbeAdb(SERIAL, "fixture-adb", self.fake)
        for command in (("install", "-r", "probe.apk"), ("root",), ("reboot",),
                        ("shell", "am", "start", "-n", DEVICE.PACKAGE + "/.ProbeActivity"),
                        ("shell", "run-as", DEVICE.PACKAGE, "--user", "0", "rm", "files/ota_probe.bin")):
            with self.subTest(command=command), self.assertRaises(DEVICE.DeviceVerificationError):
                adb.call(*command)
        self.assertEqual(self.fake.calls, [])

    def test_anchor_summary_and_source_mismatches_rejected_even_with_new_report_hash(self):
        mutations = [lambda r: r.update(schema="taku.ota-device-after/1"),
            lambda r: r.update(result="failed"), lambda r: r.update(actualExitCode=1),
            lambda r: r.update(actualExitCode=False), lambda r: r.update(probeSeeded=True),
            lambda r: r.update(phoneDataWritten=True), lambda r: r.update(scriptSha256="0" * 64),
            lambda r: r["checks"].update(existingProbeUnchanged=False),
            lambda r: r["inputs"]["baselineReceipt"].update(sha256="1" * 64),
            lambda r: r["archiveBinding"]["targetFiles"].update(sha256="2" * 64),
            lambda r: r["device"].update(vbmetaDigest="3" * 64),
            lambda r: r["probe"].update(appUid=10124)]
        original = copy.deepcopy(self.report)
        for index, mutate in enumerate(mutations):
            self.report = copy.deepcopy(original)
            mutate(self.report)
            self.inputs = self.report["inputs"]
            self.save_anchor()
            with self.subTest(index=index), self.assertRaises(DEVICE.DeviceVerificationError):
                self.capture()
            self.assertFalse(self.evidence.exists())
        self.assertEqual(self.fake.calls, [])

    def test_anchor_raw_output_exit_sequence_and_duplicate_probe_reads_rejected(self):
        original = copy.deepcopy(self.rows)
        mutations = [lambda rows: rows[2].update(stdout="0" * 64 + "  " + self.fake.app_path + "\n"),
            lambda rows: rows[2].update(exitCode=1), lambda rows: rows[2].update(sequence=999),
            lambda rows: rows[2]["argv"].__setitem__(2, "another-phone"),
            lambda rows: rows.append({**rows[2], "sequence": len(rows) + 1})]
        for index, mutate in enumerate(mutations):
            self.rows = copy.deepcopy(original)
            mutate(self.rows)
            self.save_anchor()
            with self.subTest(index=index), self.assertRaises(DEVICE.DeviceVerificationError):
                DEVICE.load_probe_anchor(self.anchor, self.anchor_hash, self.bindings["from"])

    def test_anchor_original_sidecars_cannot_be_missing_tampered_or_symlinks(self):
        for name in DEVICE.PROBE_ANCHOR_FILES.values():
            path = self.anchor_dir / name
            raw = path.read_bytes()
            with self.subTest(name=name):
                path.write_bytes(raw + b" ")
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    DEVICE.load_probe_anchor(self.anchor, self.anchor_hash, self.bindings["from"])
                path.unlink()
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    DEVICE.load_probe_anchor(self.anchor, self.anchor_hash, self.bindings["from"])
                other = self.root / (name + ".saved")
                other.write_bytes(raw)
                path.symlink_to(other)
                with self.assertRaises(DEVICE.DeviceVerificationError):
                    DEVICE.load_probe_anchor(self.anchor, self.anchor_hash, self.bindings["from"])
                path.unlink()
                path.write_bytes(raw)

    def test_changed_existing_probe_and_different_hardware_rejected_without_repair(self):
        changes = [("app_hash", DEVICE.sha_text("changed APK")), ("app_uid", "10124"), ("app_hash", None)]
        for index, (field, value) in enumerate(changes):
            old = getattr(self.fake, field)
            setattr(self.fake, field, value)
            self.evidence = self.root / ("changed-identity-" + str(index))
            with self.subTest(field=field), self.assertRaises(DEVICE.DeviceVerificationError):
                self.capture()
            self.assertFalse((self.evidence / "pre-upgrade.json").exists())
            setattr(self.fake, field, old)
        for kind in DEVICE.DATA_FILES:
            old = self.fake.data[kind]
            self.fake.data[kind] = DEVICE.sha_text("changed " + kind)
            self.evidence = self.root / ("changed-data-" + kind)
            with self.subTest(kind=kind), self.assertRaises(DEVICE.DeviceVerificationError):
                self.capture()
            self.fake.data[kind] = old
        self.fake.props["ro.serialno"] = "another-physical-phone"
        self.evidence = self.root / "changed-hardware"
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "physical device"):
            self.capture()
        self.assertIsNone(self.fake.nonce)

    def test_boot_change_during_read_keeps_sidecars_but_no_success(self):
        def reboot(fake, command):
            if command[:3] == ["shell", "run-as", DEVICE.PACKAGE]:
                fake.boot_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        self.fake.callback = reboot
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "changed during"):
            self.capture()
        self.assertTrue((self.evidence / "probe-anchor/report.json").is_file())
        self.assertFalse((self.evidence / "pre-upgrade.json").exists())

    def test_v2_cannot_claim_initialization_or_change_probe_anchor_paths(self):
        result = self.capture()
        path = pathlib.Path(result["path"])
        original = json.loads(path.read_text())
        mutations = [lambda x: x["checks"].update(probeInitialized=True),
            lambda x: x.update(probeInitialized=True), lambda x: x.update(probeInitializedByThisCapture=True),
            lambda x: x.update(phoneDataWritten=True), lambda x: x.update(captureMethod="seeded"),
            lambda x: x.update(captureBootId=""), lambda x: x["probe"].update(appUid=10124),
            lambda x: x["device"].update(hardwareSerialSha256=DEVICE.sha_text("other device")),
            lambda x: x["probeAnchor"]["report"].update(path="../synthetic-anchor/report.json"),
            lambda x: x["probeAnchor"].pop("commands"), lambda x: x["probeAnchor"]["report"].update(bytes=1)]
        for index, mutate in enumerate(mutations):
            report = copy.deepcopy(original)
            mutate(report)
            path.write_text(json.dumps(report))
            with self.subTest(index=index), self.assertRaises(DEVICE.DeviceVerificationError):
                DEVICE.validate_before(path, DEVICE.digest(path), self.bindings)

    def test_portable_copy_revalidates_without_original_paths_and_refuses_overwrite(self):
        before = self.capture()
        destination = self.root / "delivery"
        result = DEVICE.copy_before_evidence(before["path"], before["sha256"], destination, self.bindings)
        self.assertEqual(result, {"path": str(destination / "pre-upgrade.json"), "sha256": before["sha256"]})
        self.evidence.rename(self.root / "old-before")
        self.anchor_dir.rename(self.root / "old-anchor")
        original = (destination / "pre-upgrade.json").read_bytes()
        self.assertEqual(DEVICE.validate_before(result["path"], result["sha256"], self.bindings)["probe"], self.probe)
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "already exists"):
            DEVICE.copy_before_evidence(result["path"], result["sha256"], destination, self.bindings)
        self.assertEqual((destination / "pre-upgrade.json").read_bytes(), original)
        (destination / "probe-anchor/commands.jsonl").write_bytes(b"tampered")
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "anchor bytes"):
            DEVICE.validate_before(result["path"], result["sha256"], self.bindings)

    def test_copy_fsync_failure_preserves_partial_sidecars_without_publishing_before(self):
        before = self.capture()
        destination = self.root / "interrupted-delivery"
        real_fsync = DEVICE.os.fsync
        count = 0
        def fail_file_fsync(fd):
            nonlocal count
            count += 1
            if count == 3:
                raise OSError("synthetic file fsync failure")
            return real_fsync(fd)
        with mock.patch.object(DEVICE.os, "fsync", side_effect=fail_file_fsync), self.assertRaises(OSError):
            DEVICE.copy_before_evidence(before["path"], before["sha256"], destination, self.bindings)
        partial = destination / "probe-anchor/report.json"
        self.assertTrue(partial.is_file())
        original = partial.read_bytes()
        self.assertFalse((destination / "pre-upgrade.json").exists())
        with self.assertRaises(FileExistsError):
            DEVICE.copy_before_evidence(before["path"], before["sha256"], destination, self.bindings)
        self.assertEqual(partial.read_bytes(), original)

    def test_legacy_seeded_bundle_copies_without_changing_shape(self):
        apk = self.root / "synthetic-probe.apk"
        apk.write_bytes(b"synthetic APK fixture, not runnable")
        before = DEVICE.capture_before(SERIAL, self.source, self.target, self.evidence, apk,
                                       adb="fixture-adb", runner=self.fake)
        original = pathlib.Path(before["path"]).read_bytes()
        copied = DEVICE.copy_before_evidence(before["path"], before["sha256"], self.root / "legacy-delivery", self.bindings)
        self.assertEqual(pathlib.Path(copied["path"]).read_bytes(), original)
        self.assertEqual(json.loads(original)["schema"], "taku.ota-device-before/1")
        self.assertFalse((self.root / "legacy-delivery/probe-anchor").exists())


if __name__ == "__main__":
    unittest.main()
