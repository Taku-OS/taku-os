#!/usr/bin/env python3
"""Verifier orchestration/negative tests with stub host tools, not real ROM acceptance."""
import hashlib
import io
import json
import os
import pathlib
import runpy
import shutil
import ssl
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "image/verify-signatures.py"
PUBLIC_CERT = runpy.run_path(str(ROOT / "tests/image-artifacts.test.py"))["AOSP_TEST_CERTIFICATE"]
ROOT_PUBLIC = b"contract-fixture-root-public-key"
CHILD_PUBLIC = b"contract-fixture-child-public-key"

FAKE_TOOL = '''#!{python}
# Test double: deliberately does not implement cryptography.
import hashlib, os, pathlib, sys, zipfile
name = pathlib.Path(sys.argv[0]).name
command = name + (":" + sys.argv[1] if name == "avbtool" else "")
if os.environ.get("TAKU_TEST_FAIL") == command:
    print("Injected verification failure")
    raise SystemExit(7)
if name == "check_ota_package_signature":
    with zipfile.ZipFile(sys.argv[2]) as z:
        if z.comment == b"unsigned":
            print("Footer is wrong")
            raise SystemExit(1)
    print("Whole package signature VERIFIED")
    if not os.environ.get("TAKU_TEST_MISSING_PAYLOAD_SUCCESS"):
        print("Payload signatures VERIFIED")
elif name == "avbtool":
    args = sys.argv[1:]
    if args[0] == "extract_public_key":
        pathlib.Path(args[args.index("--output") + 1]).write_bytes(
            pathlib.Path(args[args.index("--key") + 1]).read_bytes())
    elif args[0] == "verify_image":
        print("vbmeta: Successfully verified fixture signature boundary")
    elif args[0] == "calculate_vbmeta_digest":
        print(hashlib.sha256(b"contract-fixture-vbmeta").hexdigest())
'''


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def chain_descriptor(name, key):
    name = name.encode()
    following = (76 + len(name) + len(key) + 7) // 8 * 8
    blob = struct.pack(">QQ4I60s", 4, following, 1, len(name), len(key), 0, b"\0" * 60) + name + key
    return blob + b"\0" * (16 + following - len(blob))


def vbmeta(key, chains=(), algorithm=1, flags=0, footer=False):
    descriptors = b"".join(chain_descriptor(name, public) for name, public in chains)
    key_area = (len(key) + 63) // 64 * 64
    auxiliary = key + b"\0" * (key_area - len(key)) + descriptors
    auxiliary += b"\0" * (-len(auxiliary) % 64)
    header = bytearray(256)
    struct.pack_into(">4sIIQQI", header, 0, b"AVB0", 1, 0, 64, len(auxiliary), algorithm)
    struct.pack_into(">11QII", header, 32, 0, 32, 32, 32, 0, len(key), 0, 0,
                     key_area, len(descriptors), 12345, flags, 0)
    blob = bytes(header) + b"\0" * 64 + auxiliary
    if footer:
        prefix = b"contract boot image" * 16
        return prefix + blob + struct.pack(">4sIIQQQ28s", b"AVBf", 1, 0, len(prefix), len(prefix), len(blob), b"\0" * 28)
    return blob


class SignatureBoundary(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = pathlib.Path(temp.name)
        self.source = self.base / "source"
        self.results = self.base / "results"
        self.directory = self.results / "crypto/20261020010000-aaaaaaaaaaaaaaaa"
        self.source.mkdir()
        self.results.mkdir()
        host = self.source / "out/host/linux-x86/bin"
        host.mkdir(parents=True)
        for name in ("check_ota_package_signature", "delta_generator", "avbtool", "avb_openssl", "fec"):
            tool = host / name
            tool.write_text(FAKE_TOOL.format(python=sys.executable))
            tool.chmod(0o755)
        for name in ("build/make/tools/releasetools/check_ota_package_signature.py",
                     "system/update_engine/payload_generator/payload_signer.cc", "external/avb/avbtool.py"):
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("contract source identity fixture\n")
        self.cert = self.source / "build/make/target/product/security/testkey.x509.pem"
        self.cert.parent.mkdir(parents=True)
        self.cert.write_text(PUBLIC_CERT)
        self.root_key = self.source / "external/avb/test/data/root.pem"
        self.root_key.parent.mkdir(parents=True)
        # Public fixture bytes only; these tests do not create any private keys.
        self.root_key.write_bytes(ROOT_PUBLIC)
        self.target = self.source / "out/target-files.zip"
        self.ota = self.source / "out/full-ota.zip"
        self.alias = self.source / "out/lineage-alias.zip"
        self.misc = {"ab_update": "true", "avb_enable": "true", "avb_building_vbmeta_image": "true",
                     "default_system_dev_certificate": "build/make/target/product/security/testkey",
                     "avb_vbmeta_key_path": "external/avb/test/data/root.pem", "avb_vbmeta_algorithm": "SHA256_RSA4096"}
        self.metadata = {"pre-device": "tokay", "ota-type": "AB", "post-build": "google/tokay/test-fingerprint",
                         "post-build-incremental": "20261020010000", "post-sdk-level": "37",
                         "post-timestamp": "1792458000", "post-security-patch-level": "2026-10-01"}
        self.inputs = {"buildNumber": "20261020010000", "snapshotDigest": "a" * 64, "sourceCommit": "b" * 40}
        self.identity = {"buildNumber": self.inputs["buildNumber"], "androidRelease": "17", "androidSdk": 37}
        self.write_target()
        self.write_ota()
        self.seal()

    def write_target(self, root_algorithm=1, root_key=ROOT_PUBLIC, child_key=CHILD_PUBLIC,
                     chain_key=CHILD_PUBLIC, child_algorithm=1, child_footer=False, chain=True, nested=False, cycle=False):
        memory = io.BytesIO()
        with zipfile.ZipFile(memory, "w") as certificates:
            certificates.writestr("testkey.x509.pem", PUBLIC_CERT)
        with zipfile.ZipFile(self.target, "w") as archive:
            archive.writestr("META/misc_info.txt", "".join(k + "=" + v + "\n" for k, v in self.misc.items()))
            archive.writestr("SYSTEM/etc/security/otacerts.zip", memory.getvalue())
            archive.writestr("IMAGES/vbmeta.img", vbmeta(root_key, [("vbmeta_system", chain_key)] if chain else [], root_algorithm, 3))
            child_chains = [("vbmeta", ROOT_PUBLIC)] if cycle else [("boot", CHILD_PUBLIC)] if nested else []
            archive.writestr("IMAGES/vbmeta_system.img", vbmeta(child_key, child_chains, algorithm=child_algorithm, footer=child_footer))
            if nested:
                archive.writestr("IMAGES/boot.img", vbmeta(CHILD_PUBLIC, footer=True))
            archive.writestr("IMAGES/system.img", b"contract partition, not a real filesystem")

    def write_ota(self, signature_size=8, signed=True, version=2):
        with zipfile.ZipFile(self.ota, "w") as archive:
            archive.writestr("META-INF/com/android/metadata", "".join(k + "=" + v + "\n" for k, v in self.metadata.items()))
            archive.writestr("payload.bin", struct.pack(">4sQQI", b"CrAU", version, 4, signature_size) + b"meta" + b"s" * signature_size + b"data-and-signature")
            if not signed:
                archive.comment = b"unsigned"
        shutil.copy2(self.ota, self.alias)

    def artifact(self, path):
        return {"path": str(path.relative_to(self.source)), "bytes": path.stat().st_size, "sha256": sha(path)}

    def seal(self):
        (self.results / "run-input.json").write_text(json.dumps(self.inputs))
        with zipfile.ZipFile(self.target) as archive:
            misc_hash = hashlib.sha256(archive.read("META/misc_info.txt")).hexdigest()
        trusted = [{"archive": "SYSTEM/etc/security/otacerts.zip", "certificate": "testkey.x509.pem",
                    "derSha256": hashlib.sha256(ssl.PEM_cert_to_DER_cert(PUBLIC_CERT)).hexdigest()}]
        self.receipt = {"schema": "taku.image-baseline/1", "buildIdentity": self.identity, "buildInput": self.inputs,
                        "runInputSha256": sha(self.results / "run-input.json"), "targetFiles": self.artifact(self.target),
                        "fullOtas": [self.artifact(p) | {"metadata": self.metadata} for p in (self.ota, self.alias)],
                        "targetFilesMetadataSha256": {"META/misc_info.txt": misc_hash},
                        "signingEvidence": {"trustedOtaCertificates": trusted}}
        path = self.results / "baseline-receipt.json"
        path.write_text(json.dumps(self.receipt))
        self.status = {"phase": "artifact-verified", "romBuilt": True, "identity": self.identity,
                       "artifacts": [self.artifact(p) for p in (self.target, self.ota, self.alias)],
                       "baselineReceipt": {"path": str(path), "sha256": sha(path)}}
        (self.results / "status.json").write_text(json.dumps(self.status))

    def invoke(self, **env):
        return subprocess.run([sys.executable, str(SCRIPT), "--source-root", str(self.source),
                               "--results-dir", str(self.results), "--verification-dir", str(self.directory)],
                              env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1", **env), capture_output=True, text=True)

    def report(self):
        return json.loads((self.directory / "signature-verification.json").read_text())

    def assert_failed(self, result, reason):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(reason, result.stderr)
        self.assertFalse((self.directory / "signature-verification.json").exists())

    def test_success_is_bound_to_receipt_and_unique_ota_bytes_and_never_phone_acceptance(self):
        baseline = (self.results / "baseline-receipt.json").read_bytes()
        status = (self.results / "status.json").read_bytes()
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = self.report()
        self.assertEqual(report["baselineReceiptSha256"], hashlib.sha256(baseline).hexdigest())
        self.assertEqual(report["checks"], {key: True for key in ("otaPackageSignatureVerified", "payloadSignatureVerified", "avbSignatureVerified")})
        self.assertEqual(len(report["otas"]), 1)
        self.assertEqual(len(report["otas"][0]["paths"]), 2)
        self.assertEqual(len([c for c in report["commands"] if c["argv"][0].endswith("check_ota_package_signature")]), 1)
        self.assertEqual({n["partition"] for n in report["avb"]["nodes"]}, {"vbmeta", "vbmeta_system"})
        self.assertEqual(next(n["flags"] for n in report["avb"]["nodes"] if n["partition"] == "vbmeta"), 3)
        for name in ("physicalDeviceVerified", "coldBootVerified", "incrementalOtaReady"):
            self.assertIs(report[name], False)
        self.assertEqual((self.results / "baseline-receipt.json").read_bytes(), baseline)
        self.assertEqual((self.results / "status.json").read_bytes(), status)
        for command in report["commands"]:
            self.assertFalse(pathlib.Path(command["log"]).is_absolute())
            self.assertEqual(sha(self.directory / command["log"]), command["logSha256"])
        self.assertFalse(list(self.directory.rglob("*.img")))
        self.assertFalse(list(self.directory.rglob("*.pem")))
        for command in report["commands"]:
            for arg in command["argv"]:
                if "taku-signatures-" in arg and arg.startswith("/"):
                    self.assertFalse(pathlib.Path(arg).exists())

    def test_completed_report_reuse_preserves_all_evidence_bytes_and_timestamps(self):
        self.assertEqual(self.invoke().returncode, 0)
        before = {str(p): (sha(p), p.stat().st_mtime_ns) for p in self.directory.rglob("*") if p.is_file()}
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(json.loads(result.stdout)["reused"])
        after = {str(p): (sha(p), p.stat().st_mtime_ns) for p in self.directory.rglob("*") if p.is_file()}
        self.assertEqual(before, after)

    def test_different_full_ota_bytes_are_each_verified(self):
        with zipfile.ZipFile(self.alias, "a") as archive:
            archive.comment = b"different full OTA container bytes"
        self.seal()
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        report = self.report()
        self.assertEqual(len(report["otas"]), 2)
        self.assertEqual(len([c for c in report["commands"] if c["argv"][0].endswith("check_ota_package_signature")]), 2)

    def test_one_failed_distinct_ota_prevents_the_whole_report(self):
        with zipfile.ZipFile(self.alias, "a") as archive:
            archive.comment = b"unsigned"
        self.seal()
        self.assert_failed(self.invoke(), "Verification command failed")

    def test_changed_receipt_is_rejected_before_verifying(self):
        path = self.results / "baseline-receipt.json"
        path.write_text(path.read_text() + " ")
        self.assert_failed(self.invoke(), "Baseline receipt hash")

    def test_changed_run_input_is_rejected(self):
        (self.results / "run-input.json").write_text("{}")
        self.assert_failed(self.invoke(), "run-input hash")

    def test_wrong_package_bytes_are_rejected(self):
        self.ota.write_bytes(b"another candidate")
        self.assert_failed(self.invoke(), "artifact hash/size mismatch")

    def test_unsigned_ota_cannot_create_success_report(self):
        self.write_ota(signed=False)
        self.seal()
        self.assert_failed(self.invoke(), "Verification command failed")
        failures = list(self.directory.rglob("failure.json"))
        self.assertEqual(len(failures), 1)
        self.assertEqual(json.loads(failures[0].read_text())["commands"][0]["exitCode"], 1)

    def test_zero_metadata_signature_is_rejected(self):
        self.write_ota(signature_size=0)
        self.seal()
        self.assert_failed(self.invoke(), "Missing signed v2 payload metadata")

    def test_unsupported_payload_version_is_rejected(self):
        self.write_ota(version=1)
        self.seal()
        self.assert_failed(self.invoke(), "Missing signed v2 payload metadata")

    def test_checker_must_report_both_layers(self):
        self.assert_failed(self.invoke(TAKU_TEST_MISSING_PAYLOAD_SUCCESS="1"), "both signature layers")

    def test_avb_crypto_failure_cannot_create_success_report(self):
        self.assert_failed(self.invoke(TAKU_TEST_FAIL="avbtool:verify_image"), "Verification command failed")

    def test_unsigned_root_is_rejected_even_if_host_tool_would_return_success(self):
        self.write_target(root_algorithm=0)
        self.seal()
        self.assert_failed(self.invoke(), "Unsigned AVB node")

    def test_empty_root_key_is_rejected(self):
        self.write_target(root_key=b"")
        self.seal()
        self.assert_failed(self.invoke(), "Unsigned AVB node")

    def test_unsigned_child_is_rejected(self):
        self.write_target(child_algorithm=0)
        self.seal()
        self.assert_failed(self.invoke(), "Unsigned AVB node")

    def test_empty_chain_key_is_rejected(self):
        self.write_target(chain_key=b"")
        self.seal()
        self.assert_failed(self.invoke(), "Empty or invalid AVB chain public key")

    def test_child_key_must_match_parent_descriptor(self):
        self.write_target(child_key=b"different child public key")
        self.seal()
        self.assert_failed(self.invoke(), "AVB parent/root public key mismatch")

    def test_root_key_must_match_expected_source_identity(self):
        self.root_key.write_bytes(b"different expected root public key")
        self.assert_failed(self.invoke(), "AVB parent/root public key mismatch")

    def test_boot_style_footer_is_supported(self):
        self.write_target(child_footer=True)
        self.seal()
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_nested_chains_are_all_verified(self):
        self.write_target(nested=True)
        self.seal()
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({n["partition"] for n in self.report()["avb"]["nodes"]}, {"vbmeta", "vbmeta_system", "boot"})

    def test_cycle_in_chain_is_rejected(self):
        self.write_target(cycle=True)
        self.seal()
        self.assert_failed(self.invoke(), "Cycle in AVB chain")

    def test_host_tool_must_exist_in_current_source_tree(self):
        (self.source / "out/host/linux-x86/bin/delta_generator").unlink()
        self.assert_failed(self.invoke(), "Missing current-build host tool")

    def test_missing_image_trust_certificate_is_rejected(self):
        self.cert.write_text(PUBLIC_CERT.replace("MIIEqD", "NIIEqD"))
        self.assert_failed(self.invoke(), "not trusted by candidate image")

    def test_existing_log_tampering_prevents_report_reuse(self):
        self.assertEqual(self.invoke().returncode, 0)
        report = self.report()
        (self.directory / report["commands"][0]["log"]).write_text("changed verification evidence")
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("verification log differs", result.stderr)

    def test_existing_report_for_wrong_candidate_cannot_be_reused(self):
        self.assertEqual(self.invoke().returncode, 0)
        self.inputs["buildNumber"] = "different-build"
        self.identity["buildNumber"] = "different-build"
        self.seal()
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("another candidate", result.stderr)

    def test_incomplete_existing_report_cannot_be_reused(self):
        self.assertEqual(self.invoke().returncode, 0)
        path = self.directory / "signature-verification.json"
        report = self.report()
        report["otas"] = []
        path.write_text(json.dumps(report))
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Missing signature report evidence", result.stderr)

    def test_changed_host_tool_cannot_reuse_existing_evidence(self):
        self.assertEqual(self.invoke().returncode, 0)
        tool = self.source / "out/host/linux-x86/bin/delta_generator"
        tool.write_text(tool.read_text() + "\n# changed tool bytes\n")
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tool identity differs", result.stderr)

    def test_changed_expected_root_file_cannot_reuse_success(self):
        self.assertEqual(self.invoke().returncode, 0)
        self.root_key.write_bytes(b"changed expected root key after verification")
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("another candidate", result.stderr)

    def test_changed_certificate_file_even_with_same_public_identity_prevents_reuse(self):
        self.assertEqual(self.invoke().returncode, 0)
        self.cert.write_text(self.cert.read_text() + "\n")
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("another candidate", result.stderr)

    def test_removed_avb_commands_prevent_reuse(self):
        self.assertEqual(self.invoke().returncode, 0)
        report = self.report()
        report["commands"] = report["commands"][:1]
        (self.directory / "signature-verification.json").write_text(json.dumps(report))
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("command coverage", result.stderr)

    def test_changed_recorded_command_arguments_prevent_reuse(self):
        self.assertEqual(self.invoke().returncode, 0)
        report = self.report()
        next(c for c in report["commands"] if c["operation"] == "avb-node")["argv"].append("--accept_zeroed_hashtree")
        (self.directory / "signature-verification.json").write_text(json.dumps(report))
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("command arguments differ", result.stderr)

    def test_footer_selects_the_same_vbmeta_blob_as_upstream_crypto(self):
        prefix = vbmeta(ROOT_PUBLIC)
        suffix = vbmeta(b"different actual signature key")
        combined = prefix + suffix + struct.pack(">4sIIQQQ28s", b"AVBf", 1, 0, len(prefix), len(prefix), len(suffix), b"\0" * 28)
        with zipfile.ZipFile(self.target) as archive:
            entries = {name: archive.read(name) for name in archive.namelist()}
        entries["IMAGES/vbmeta.img"] = combined
        with zipfile.ZipFile(self.target, "w") as archive:
            for name, content in entries.items():
                archive.writestr(name, content)
        self.seal()
        self.assert_failed(self.invoke(), "AVB parent/root public key mismatch")

    def test_status_receipt_artifact_set_must_match(self):
        self.status["artifacts"].pop()
        (self.results / "status.json").write_text(json.dumps(self.status))
        self.assert_failed(self.invoke(), "Status/receipt artifacts differ")


if __name__ == "__main__":
    unittest.main()
