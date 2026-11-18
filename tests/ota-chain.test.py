#!/usr/bin/env python3
"""Source-chain boundary tests with explicit fake prior-validation callbacks.

These prove no ROM signatures, device boot or network installation.
"""
import copy
import hashlib
import json
import pathlib
import runpy
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import ota_chain as chain
import ota_device as device
import ota_release as release

CLI = runpy.run_path(str(ROOT / "scripts/pixel9-ota.py"))
FIXTURE = runpy.run_path(str(ROOT / "tests/ota-device.test.py"))


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value))
    return release.digest(path)


class IncrementalSource(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.a = FIXTURE["baseline"]("20261025010000", 1792880000)
        self.b = FIXTURE["baseline"]("20261026010000", 1792966400)
        self.c = FIXTURE["baseline"]("20261027010000", 1793052800)
        self.prior_plan = {"source": self.a, "target": self.b}
        self.plan = {"source": self.b, "target": self.c}
        def phone(baseline, slot):
            identity = baseline["identity"]
            return {"device": "tokay", **{k: str(identity[k]) for k in
                ("androidRelease", "androidSdk", "buildNumber", "buildTimestamp", "buildFingerprint", "platformSecurityPatch")},
                "vbmetaDigest": baseline["vbmetaDigest"], "slotSuffix": slot,
                "adbSerialSha256": device.sha_text("fixture serial"),
                "hardwareSerialSha256": device.sha_text("fixture hardware"),
                "bootCompleted": True, "primaryUserUnlocked": True}
        self.probe = {"packageName": device.PACKAGE, "apkSha256": device.sha_text("fake APK"),
                      "appUid": 10123, "dataSha256": {k: device.sha_text(k) for k in device.DATA_FILES}}
        self.old_before = {"device": phone(self.a, "_a"), "probe": copy.deepcopy(self.probe)}
        self.before = {"device": phone(self.b, "_b"), "probe": copy.deepcopy(self.probe)}
        self.before["probe"]["dataSha256"] = {k: device.sha_text("new test " + k) for k in device.DATA_FILES}
        self.old_hash = device.sha_text("fake previous before")
        self.new_hash = device.sha_text("fake current before")
        self.delivery = self.root / "prior-delivery/delivery.json"
        self.installation = self.root / "prior-installation/installation.json"
        self.after = self.root / "prior-device/post-upgrade.json"
        self.acceptance = self.root / "prior-device/incremental-acceptance.json"
        ph = write(self.delivery.parent / "plan.json", self.prior_plan)
        dh = write(self.delivery, {"beforeSha256": self.old_hash})
        ih = write(self.installation, {"method": "adb-sideload"})
        write(self.delivery.parent / "source-installation/installation.json", {"schema": "taku.ota-baseline-installation/1"})
        ah = write(self.after, {"schema": "taku.ota-device-after/1", "result": "verified",
            "baselines": device.transition_bindings(self.a, self.b), "beforeEvidenceSha256": self.old_hash,
            "device": copy.deepcopy(self.before["device"]), "probe": copy.deepcopy(self.probe),
            "checks": {"sameDeviceVerified": True, "targetBootVerified": True, "slotChanged": True,
                       "probePrivateDataPreserved": True}, "allUserDataVerified": False, "dataScope": device.PROBE_DATA_SCOPE})
        acc = {"schema": "taku.ota-acceptance/1", "planSha256": ph, "deliverySha256": dh,
               "installationSha256": ih, "beforeSha256": self.old_hash, "afterSha256": ah,
               "incrementalSideloadCompleted": True, "targetBootVerified": True,
               "probePrivateDataPreserved": True, "allUserDataVerified": False}
        ach = write(self.acceptance, acc)
        self.args = SimpleNamespace(output=self.root / "bound", before_sha256=self.new_hash,
            previous_plan_sha256=ph, previous_delivery=self.delivery, previous_delivery_sha256=dh,
            previous_installation=self.installation, previous_installation_sha256=ih,
            previous_after=self.after, previous_after_sha256=ah,
            previous_acceptance=self.acceptance, previous_acceptance_sha256=ach)
        # Deliberate unit boundary: crypto/delivery gate is exercised by the
        # existing release suite and by real receipts, not these fake records.
        self.prior = {"plan": self.prior_plan, "before": self.old_before,
                      "delivery": {"beforeSha256": self.old_hash}, "installation": {"method": "adb-sideload"}}
        self.validate_prior = mock.Mock(return_value=self.prior)

    def bind(self):
        return chain.bind(self.args, self.plan, self.before, self.validate_prior)

    def validate(self, path=None):
        path = pathlib.Path(path or self.args.output / "installation.json")
        return chain.validate_source(path, release.digest(path), self.b, self.before, self.new_hash, self.validate_prior)

    def alter_bound(self, change):
        path = self.args.output / "installation.json"
        record = release.read_json(path); change(record); write(path, record)

    def test_prior_upgrade_is_preserved_portably_and_not_relabelled_as_full(self):
        original = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        result = self.bind(); value = self.validate(result["path"])
        self.assertEqual(value["method"], chain.METHOD)
        self.assertTrue(value["notFullBaselineInstallation"])
        self.assertNotIn("commandCompleted", value)
        self.assertFalse(value["allUserDataVerified"])
        self.assertEqual(value["chainDepth"], 1)
        self.assertTrue(all(p.read_bytes() == raw for p, raw in original.items()))
        moved = self.root / "portable"
        chain.copy_tree(self.args.output, moved)
        self.assertEqual(self.validate(moved / "installation.json"), value)

    def test_current_probe_data_may_be_new_independent_samples_but_identity_cannot_change(self):
        self.bind()
        for key, value in (("apkSha256", "f" * 64), ("appUid", 10124), ("packageName", "other")):
            before = copy.deepcopy(self.before); before["probe"][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(release.ReleaseError, "probe identity"):
                chain.validate_source(self.args.output / "installation.json", release.digest(self.args.output / "installation.json"),
                                      self.b, before, self.new_hash, self.validate_prior)

    def test_another_current_phone_or_slot_is_rejected(self):
        self.bind()
        for key in ("hardwareSerialSha256", "adbSerialSha256", "slotSuffix", "vbmetaDigest"):
            before = copy.deepcopy(self.before); before["device"][key] = "different"
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):
                chain.validate_source(self.args.output / "installation.json", release.digest(self.args.output / "installation.json"),
                                      self.b, before, self.new_hash, self.validate_prior)

    def test_previous_target_must_equal_the_exact_new_source_archive(self):
        self.prior["plan"] = {"source": self.a, "target": self.c}
        with self.assertRaisesRegex(release.ReleaseError, "Previous target"):
            self.bind()
        self.assertFalse(self.args.output.exists())

    def test_previous_crypto_or_installation_rejection_cannot_be_ignored(self):
        self.validate_prior.side_effect = release.ReleaseError("prior crypto gate rejected")
        with self.assertRaisesRegex(release.ReleaseError, "prior crypto"):
            self.bind()
        self.assertFalse(self.args.output.exists())

    def test_changed_private_evidence_cannot_hide_behind_an_unchanged_binding(self):
        self.bind()
        path = self.args.output / chain.REFERENCES["after"]; path.write_text(path.read_text() + "\n")
        with self.assertRaisesRegex(release.ReleaseError, "evidence changed"):
            self.validate()

    def test_resigned_metadata_without_target_boot_or_probe_acceptance_is_rejected(self):
        self.bind()
        path = self.args.output / chain.REFERENCES["acceptance"]
        original = path.read_bytes()
        for key, value in (("targetBootVerified", False), ("probePrivateDataPreserved", False),
                           ("incrementalSideloadCompleted", False), ("afterSha256", "f" * 64),
                           ("allUserDataVerified", True)):
            record = json.loads(original); record[key] = value; write(path, record)
            self.alter_bound(lambda r: r["previous"]["acceptance"].update(sha256=release.digest(path)))
            with self.subTest(key=key), self.assertRaisesRegex(release.ReleaseError, "acceptance"):
                self.validate()

    def test_wrong_after_source_or_probe_and_self_promoted_scope_are_rejected(self):
        after = release.read_json(self.after)
        for change in (lambda x: x.update(baselines={}), lambda x: x["checks"].update(slotChanged=False),
                       lambda x: x.update(allUserDataVerified=True),
                       lambda x: x["probe"].update(appUid=99999),
                       lambda x: x["device"].update(buildNumber=self.a["identity"]["buildNumber"])):
            value = copy.deepcopy(after); change(value)
            with self.assertRaises(release.ReleaseError):
                chain.validate_after(value, self.old_before, self.prior_plan, self.old_hash)

    def test_binding_rejects_current_before_relabelled_method_extra_fields_and_depth(self):
        self.bind(); path = self.args.output / "installation.json"; original = path.read_bytes()
        for key, value in (("beforeSha256", "f" * 64), ("baselineReceiptSha256", "f" * 64),
                           ("method", "adb-sideload"), ("chainDepth", 2), ("chainDepth", True),
                           ("notFullBaselineInstallation", False), ("commandCompleted", True)):
            record = json.loads(original); record[key] = value; write(path, record)
            with self.subTest(key=key), self.assertRaises(release.ReleaseError):self.validate()

    def test_cyclic_context_and_missing_fresh_before_hash_are_rejected_by_cli_dispatch(self):
        self.bind(); path = self.args.output / "installation.json"
        with self.assertRaisesRegex(release.ReleaseError, "Cyclic"):
            CLI["validate_source_installation"](path, release.digest(path), self.plan, self.before,
                                                 self.new_hash, ancestry=(path.resolve(),))
        with self.assertRaisesRegex(release.ReleaseError, "current before"):
            CLI["validate_source_installation"](path, release.digest(path), self.plan, self.before)

    def test_existing_source_binding_cannot_be_overwritten(self):
        self.bind(); before = (self.args.output / "installation.json").read_bytes()
        with self.assertRaisesRegex(release.ReleaseError, "Preserve existing"):
            self.bind()
        self.assertEqual((self.args.output / "installation.json").read_bytes(), before)

    def test_private_tree_rejects_symlinks_and_bounded_scope_before_copy(self):
        (self.delivery.parent / "leak").symlink_to(self.after)
        with self.assertRaisesRegex(release.ReleaseError, "Symlink"):
            chain.copy_tree(self.delivery.parent, self.root / "copy")
        self.assertFalse((self.root / "copy").exists())
        (self.delivery.parent / "leak").unlink()
        with mock.patch.object(chain, "MAX_FILES", 1), self.assertRaisesRegex(release.ReleaseError, "bounded scope"):
            chain.copy_tree(self.delivery.parent, self.root / "copy")

    def test_nested_or_identical_destination_cannot_modify_accepted_source_tree(self):
        source = self.delivery.parent
        original = {p.relative_to(source): p.read_bytes() for p in source.rglob("*") if p.is_file()}
        for target in (source, source / "nested-copy", source.parent):
            with self.subTest(target=target), self.assertRaisesRegex(release.ReleaseError, "overlap"):
                chain.copy_tree(source, target)
            self.assertEqual({p.relative_to(source): p.read_bytes() for p in source.rglob("*") if p.is_file()}, original)
        self.assertFalse((source / "nested-copy").exists())

    def test_chain_depth_limit_cannot_be_removed_by_copying_evidence(self):
        write(self.delivery.parent / "source-installation/installation.json",
              {"schema": chain.SCHEMA, "chainDepth": chain.MAX_DEPTH})
        with self.assertRaisesRegex(release.ReleaseError, "reviewed checkpoint"):
            self.bind()
        self.assertFalse(self.args.output.exists())

    def test_generator_rejects_nested_output_before_docker_or_input_mutation(self):
        self.bind()
        source = self.args.output
        original = {p.relative_to(source): p.read_bytes() for p in source.rglob("*") if p.is_file()}
        args = SimpleNamespace(plan=self.root / "fake-plan.json", plan_sha256="a" * 64,
            before=self.root / "fake-before.json", before_sha256=self.new_hash,
            source_installation=source / "installation.json",
            source_installation_sha256=release.digest(source / "installation.json"),
            output=source / "nested-delta")
        fn = CLI["generate"]
        with mock.patch.dict(fn.__globals__, {"OUT": self.root / "isolated-controller",
            "current_builder": mock.Mock(return_value={}), "load_plan": mock.Mock(),
            "validate_before": mock.Mock(return_value=self.before), "validate_source_installation": mock.Mock()}), \
             mock.patch("subprocess.run") as run:
            with self.assertRaisesRegex(release.ReleaseError, "source-installation evidence"):
                fn(args, self.plan)
            run.assert_not_called()
        self.assertEqual({p.relative_to(source): p.read_bytes() for p in source.rglob("*") if p.is_file()}, original)
        self.assertFalse(args.output.exists())

    def test_real_previous_validator_reenters_delivery_gate_once_not_exponentially(self):
        paths = {key: path for key, path in (("plan", self.delivery.parent / "plan.json"),
                 ("delivery", self.delivery), ("installation", self.installation))}
        refs = {key: {"sha256": release.digest(path)} for key, path in paths.items()}
        fn = CLI["validate_prior_upgrade"]
        with mock.patch.dict(fn.__globals__, {
            "load_plan": mock.Mock(return_value=self.prior_plan),
            "validate_before": mock.Mock(return_value=self.old_before),
            "validate_delivery": mock.Mock(return_value=(self.prior["delivery"], self.root / "fake.zip")),
            "validate_installation_record": mock.Mock(return_value=self.prior["installation"]),
        }):
            result = fn(paths, refs, ancestry=(self.root / "outer.json",))
            fn.__globals__["validate_delivery"].assert_called_once()
            fn.__globals__["validate_installation_record"].assert_called_once()
        self.assertEqual(result["before"], self.old_before)


if __name__ == "__main__":
    unittest.main()
