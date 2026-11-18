#!/usr/bin/env python3
"""Incremental generator contract tests with fake tools; NOT real OTA acceptance."""
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import runpy
import subprocess
import sys
import tempfile
import unittest
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "image/generate-incremental-ota.py"
SPEC = importlib.util.spec_from_file_location("incremental_ota", SCRIPT)
HELPER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(HELPER)
PUBLIC_CERT = runpy.run_path(str(ROOT / "tests/image-artifacts.test.py"))["AOSP_TEST_CERTIFICATE"]
FINGERPRINT = "google/tokay/tokay:17/CP2A.260805.005/stock-id:user/release-keys"

FAKE_TOOL = '''#!PYTHON
# Test double only: does not generate an installable or signed OTA payload.
import json,os,pathlib,struct,sys,zipfile
name=pathlib.Path(sys.argv[0]).name
options=json.loads(os.environ.get("TAKU_TEST_OPTIONS", "{}"))
def props(z,name):
    return dict(line.split("=",1) for line in z.read(name).decode().splitlines() if "=" in line and not line.startswith("#"))
def varint(v):
    result=bytearray()
    while v>127: result.append((v&127)|128);v>>=7
    result.append(v);return bytes(result)
def scalar(n,v): return varint(n<<3)+varint(v)
def data(n,v): return varint((n<<3)|2)+varint(len(v))+v
if name=="ota_from_target_files":
    assert len(sys.argv)==9 and sys.argv[1]=="-i" and sys.argv[3]=="-k" and sys.argv[5]=="--java_path",sys.argv
    assert pathlib.Path(sys.argv[6]).is_file() and pathlib.Path(sys.argv[6]).parent.parent==pathlib.Path(os.environ["JAVA_HOME"])
    old,new,out=map(pathlib.Path,(sys.argv[2],sys.argv[7],sys.argv[8]))
    if options.get("generationFails"):
        out.write_bytes(b"deliberate partial output from fake generator")
        print("injected upstream generation failure");raise SystemExit(7)
    with zipfile.ZipFile(old) as z: a=props(z,"SYSTEM/build.prop")
    with zipfile.ZipFile(new) as z:
        b=props(z,"SYSTEM/build.prop");partitions=z.read("META/ab_partitions.txt").decode().split()
    metadata={"ota-type":"AB","pre-device":"tokay","pre-build":a["ro.build.fingerprint"],
        "pre-build-incremental":a["ro.build.version.incremental"],"post-build":b["ro.build.fingerprint"],
        "post-build-incremental":b["ro.build.version.incremental"],"post-sdk-level":"37",
        "post-timestamp":b["ro.build.date.utc"],"post-security-patch-level":b["ro.build.version.security_patch"]}
    metadata.update(options.get("metadata",{}))
    metadata={k:v for k,v in metadata.items() if v is not None}
    manifest=scalar(4,1)+scalar(5,8)+scalar(12,options.get("minorVersion",8))
    manifest+=scalar(14,options.get("payloadTimestamp",int(b["ro.build.date.utc"])))
    if options.get("partial"): manifest+=scalar(16,1)
    for part in options.get("partitions",partitions): manifest+=data(13,data(1,part.encode()))
    signature_size=options.get("metadataSignatureBytes",8)
    payload=struct.pack(">4sQQI",b"CrAU",2,len(manifest),signature_size)+manifest+b"s"*signature_size+b"fake-data-and-signature"
    with zipfile.ZipFile(out,"w") as z:
        z.writestr("META-INF/com/android/metadata", "".join(k+"="+v+"\\n" for k,v in metadata.items()))
        z.writestr("payload.bin",payload)
        z.writestr("payload_properties.txt","".join(k+"="+v+"\\n" for k,v in options.get("payloadProperties",{}).items()))
    if options.get("mutateInput"):
        with old.open("ab") as stream:stream.write(b"changed during generation")
    print("fixture generated, NOT a real payload")
elif name=="check_ota_package_signature":
    if options.get("verificationFails"):
        print("injected signature failure");raise SystemExit(7)
    print("Whole package signature VERIFIED")
    if not options.get("omitPayloadConfirmation"):print("Payload signatures VERIFIED")
    if options.get("mutatePackage"):
        with pathlib.Path(sys.argv[2]).open("ab") as stream:stream.write(b"changed during verification")
'''


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class IncrementalPackage(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.base = pathlib.Path(temp.name).resolve()
        self.source = self.base / "source"
        self.source.mkdir()
        host = self.source / "out/host/linux-x86/bin"
        host.mkdir(parents=True)
        for name in HELPER.HOST_TOOLS:
            tool = host / name
            tool.write_text(FAKE_TOOL.replace("PYTHON", sys.executable, 1))
            tool.chmod(0o755)
        for name in HELPER.TOOL_SOURCES:
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("upstream source identity fixture only\n")
        self.java_home = self.source / "prebuilts/jdk/current-build-test/linux-x86"
        (self.java_home / "bin").mkdir(parents=True)
        (self.java_home / "bin/java").write_text("#!/bin/sh\nexit 0\n")
        (self.java_home / "bin/java").chmod(0o755)
        (self.java_home / "release").write_text('JAVA_VERSION="fixture"\n')
        self.environment = self.source / "out/soong/soong.environment.available"
        self.environment.parent.mkdir(parents=True)
        self.environment.write_text(json.dumps([{"Key": "ANDROID_JAVA_HOME", "Value": str(self.java_home.relative_to(self.source))}]))
        signapk = self.source / "out/host/linux-x86/framework/signapk.jar"
        signapk.parent.mkdir(parents=True)
        signapk.write_bytes(b"not a real signapk JAR")
        self.cert_prefix = self.source / "build/make/target/product/security/testkey"
        self.cert_prefix.parent.mkdir(parents=True)
        pathlib.Path(str(self.cert_prefix) + ".x509.pem").write_text(PUBLIC_CERT)
        pathlib.Path(str(self.cert_prefix) + ".pk8").write_bytes(b"public test fixture, NOT a private key")
        self.old, self.new = self.base / "A-target.zip", self.base / "B-target.zip"
        self.output = self.base / "A-to-B"
        self.write_target(self.old, "20261020010000", "1792458000")
        self.write_target(self.new, "20261020020000", "1792461600")

    def write_target(self, path, number="20261020020000", timestamp="1792461600", system=None,
                     product=None, misc=None, partitions=None, trust=True, duplicate=False):
        props = {"ro.product.system.device": "generic", "ro.build.type": "userdebug",
                 "ro.build.version.release": "17", "ro.build.version.sdk": "37",
                 "ro.build.version.incremental": number, "ro.build.date.utc": timestamp,
                 "ro.build.fingerprint": FINGERPRINT, "ro.build.version.security_patch": "2026-10-01"}
        props.update(system or {})
        product_props = {"ro.product.product.device": "tokay", "ro.product.build.type": "userdebug"}
        product_props.update(product or {})
        declarations = {"ab_update": "true", "avb_enable": "true", "avb_building_vbmeta_image": "true",
                        "default_system_dev_certificate": "build/make/target/product/security/testkey",
                        "avb_vbmeta_key_path": "external/avb/test/data/testkey_rsa4096.pem",
                        "avb_vbmeta_algorithm": "SHA256_RSA4096", "use_dynamic_partitions": "true", "virtual_ab": "true"}
        declarations.update(misc or {})
        parts = partitions if partitions is not None else ["boot", "system", "vbmeta"]
        def text(p):return "".join(k + "=" + v + "\n" for k, v in p.items() if v is not None)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("SYSTEM/build.prop", text(props))
            archive.writestr("PRODUCT/etc/build.prop", text(product_props))
            archive.writestr("VENDOR/build.prop", "ro.product.vendor.device=tokay\n")
            archive.writestr("META/misc_info.txt", text(declarations))
            archive.writestr("META/ab_partitions.txt", "\n".join(parts) + "\n")
            for name in set(parts):
                archive.writestr("IMAGES/" + name + ".img", b"not a real partition image")
            if trust:
                memory = io.BytesIO()
                with zipfile.ZipFile(memory, "w") as certificates:
                    certificates.writestr("testkey.x509.pem", PUBLIC_CERT)
                archive.writestr("SYSTEM/etc/security/otacerts.zip", memory.getvalue())
            if duplicate:
                archive.writestr("SYSTEM/build.prop", text(props))

    def invoke(self, options=None, old=None, output=None):
        return subprocess.run([sys.executable, str(SCRIPT), "--source-root", str(self.source),
                               "--source-target-files", str(old or self.old), "--target-target-files", str(self.new),
                               "--output-dir", str(output or self.output)], capture_output=True, text=True,
                              env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1", TAKU_TEST_OPTIONS=json.dumps(options or {})))

    def assert_failure(self, result, message):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn(message, result.stderr)
        self.assertFalse((self.output / "incremental-ota.json").exists())
        self.assertFalse((self.output / "incremental-ota.zip").exists())

    def test_success_exact_upstream_cli_and_honest_acceptance_scope(self):
        before = (sha(self.old), sha(self.new))
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        response = json.loads(result.stdout)
        path = pathlib.Path(response["path"])
        report = json.loads(path.read_text())
        self.assertEqual(report["schema"], "taku.incremental-ota/1")
        self.assertEqual(sha(path), response["sha256"])
        self.assertEqual(sha(pathlib.Path(response["package"]["path"])), response["package"]["sha256"])
        self.assertEqual(report["commands"][0]["argv"][1:], ["-i", str(self.old), "-k", str(self.cert_prefix),
                         "--java_path", str(self.java_home / "bin/java"), str(self.new), str(self.output / "incremental-ota.partial.zip")])
        self.assertEqual(report["tools"]["java"]["buildEnvironment"]["sha256"], sha(self.environment))
        self.assertEqual(report["metadata"]["pre-build"], report["metadata"]["post-build"])
        self.assertNotEqual(report["metadata"]["pre-build-incremental"], report["metadata"]["post-build-incremental"])
        self.assertEqual(report["payload"]["partitions"], ["boot", "system", "vbmeta"])
        self.assertGreater(report["payload"]["minorVersion"], 0)
        for key in ("physicalDeviceVerified", "dataPreservationVerified", "avbSignatureVerified"):
            self.assertIs(report[key], False)
        self.assertTrue(all(report["checks"].values()))
        self.assertEqual(before, (sha(self.old), sha(self.new)))
        for command in report["commands"]:
            self.assertEqual(sha(self.output / command["log"]), command["logSha256"])
        self.assertFalse((self.output / "work").exists())
        self.assertFalse((self.output / "incremental-ota.partial.zip").exists())
        self.assertFalse(list(self.output.rglob("*.pk8")))

    def test_completed_output_cannot_be_overwritten(self):
        self.assertEqual(self.invoke().returncode, 0)
        before = {str(p): (sha(p), p.stat().st_mtime_ns) for p in self.output.rglob("*") if p.is_file()}
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(before, {str(p): (sha(p), p.stat().st_mtime_ns) for p in self.output.rglob("*") if p.is_file()})

    def test_existing_empty_output_is_also_refused(self):
        self.output.mkdir()
        self.assert_failure(self.invoke(), "File exists")
        self.assertEqual(list(self.output.iterdir()), [])

    def test_java_must_come_from_captured_build_not_host(self):
        self.environment.write_text(json.dumps([{"Key": "ANDROID_JAVA_HOME", "Value": "/usr/lib/jvm/host"}]))
        self.assert_failure(self.invoke(), "escapes source-root")

    def test_missing_captured_java_is_rejected(self):
        self.environment.write_text("[]")
        self.assert_failure(self.invoke(), "captured ANDROID_JAVA_HOME")

    def test_java_executable_is_required(self):
        (self.java_home / "bin/java").unlink()
        self.assert_failure(self.invoke(), "Java executable")

    def test_relative_input_rejected(self):
        self.assert_failure(self.invoke(old=pathlib.Path("A-target.zip")), "absolute path")

    def test_same_input_rejected(self):
        self.assert_failure(self.invoke(old=self.new), "paths must differ")

    def test_wrong_device_platform_and_variant_rejected(self):
        variants = [({"ro.build.version.release": "16"}, None, "Android 17"),
                    ({"ro.build.version.sdk": "36"}, None, "Android 17"),
                    ({"ro.build.type": "user"}, None, "userdebug"),
                    (None, {"ro.product.product.device": "komodo"}, "tokay")]
        for index, (system, product, message) in enumerate(variants):
            with self.subTest(system=system, product=product):
                self.output = self.base / ("invalid-platform-" + str(index))
                self.write_target(self.new, system=system, product=product)
                self.assert_failure(self.invoke(), message)

    def test_build_number_and_time_must_both_increase(self):
        for index, (number, timestamp) in enumerate([("20261020010000", "1792461600"),
                                                   ("20261019010000", "1792461600"),
                                                   ("20261020020000", "1792458000")]):
            with self.subTest(number=number, timestamp=timestamp):
                self.output = self.base / ("invalid-monotonic-" + str(index))
                self.write_target(self.new, number, timestamp)
                self.assert_failure(self.invoke(), "strictly increase")

    def test_spl_downgrade_rejected(self):
        self.write_target(self.new, system={"ro.build.version.security_patch": "2026-09-05"})
        self.assert_failure(self.invoke(), "Security patch downgrade")

    def test_signing_declaration_change_rejected(self):
        self.write_target(self.new, misc={"avb_vbmeta_key_path": "new-key.pem"})
        self.assert_failure(self.invoke(), "signingDeclarations")

    def test_missing_image_trust_rejected(self):
        self.write_target(self.new, trust=False)
        self.assert_failure(self.invoke(), "trust certificates")

    def test_partition_change_rejected(self):
        self.write_target(self.new, partitions=["boot", "system"])
        self.assert_failure(self.invoke(), "abPartitions")

    def test_userdata_partition_rejected(self):
        self.write_target(self.new, partitions=["boot", "system", "vbmeta", "userdata"])
        self.assert_failure(self.invoke(), "user-data")

    def test_not_ab_rejected(self):
        self.write_target(self.new, misc={"ab_update": "false"})
        self.assert_failure(self.invoke(), "A/B")

    def test_layout_change_rejected(self):
        self.write_target(self.new, misc={"virtual_ab": "false"})
        self.assert_failure(self.invoke(), "layout")

    def test_generation_failure_retains_partial_and_log(self):
        self.assert_failure(self.invoke({"generationFails": True}), "Upstream command failed: generate")
        self.assertTrue((self.output / "incremental-ota.partial.zip").exists())
        failure = json.loads((self.output / "failure.json").read_text())
        self.assertEqual(failure["commands"][0]["exitCode"], 7)
        self.assertTrue((self.output / "generate.log").exists())

    def test_metadata_identity_must_match_exactly(self):
        for key in ("pre-build", "pre-build-incremental", "pre-device", "post-build", "post-build-incremental",
                    "post-timestamp", "post-sdk-level", "post-security-patch-level"):
            with self.subTest(key=key):
                self.output = self.base / ("wrong-" + key)
                self.assert_failure(self.invoke({"metadata": {key: "wrong"}}), "identity differs: " + key)

    def test_full_metadata_cannot_masquerade_as_incremental(self):
        self.assert_failure(self.invoke({"metadata": {"pre-build-incremental": None}}), "identity differs")

    def test_wipe_downgrade_and_retrofit_metadata_rejected(self):
        for key in ("ota-wipe", "ota-downgrade", "spl-downgrade", "ota-retrofit-dynamic-partitions"):
            with self.subTest(key=key):
                self.output = self.base / key
                self.assert_failure(self.invoke({"metadata": {key: "yes"}}), "wipe/downgrade/retrofit")

    def test_payload_powerwash_and_spl_downgrade_rejected(self):
        for key in ("POWERWASH", "SPL_DOWNGRADE"):
            with self.subTest(key=key):
                self.output = self.base / key
                self.assert_failure(self.invoke({"payloadProperties": {key: "1"}}), "wipe/downgrade payload")

    def test_full_payload_rejected(self):
        self.assert_failure(self.invoke({"minorVersion": 0}), "not a full payload")

    def test_partial_payload_rejected(self):
        self.assert_failure(self.invoke({"partial": True}), "Partial OTA")

    def test_payload_timestamp_rejected(self):
        self.assert_failure(self.invoke({"payloadTimestamp": 1}), "Payload timestamp")

    def test_payload_partition_omission_rejected(self):
        self.assert_failure(self.invoke({"partitions": ["boot", "system"]}), "Payload A/B partitions")

    def test_unsigned_payload_header_rejected(self):
        self.assert_failure(self.invoke({"metadataSignatureBytes": 0}), "signed v2 payload")

    def test_signature_failure_keeps_partial_without_success_report(self):
        self.assert_failure(self.invoke({"verificationFails": True}), "Upstream command failed: verify-signatures")
        self.assertTrue((self.output / "incremental-ota.partial.zip").exists())
        self.assertTrue((self.output / "verify-signatures.log").exists())

    def test_payload_success_confirmation_required(self):
        self.assert_failure(self.invoke({"omitPayloadConfirmation": True}), "both OTA and payload signatures")

    def test_changed_input_during_generation_rejected(self):
        self.assert_failure(self.invoke({"mutateInput": True}), "input changed during generation")

    def test_changed_package_during_signature_verification_rejected(self):
        self.assert_failure(self.invoke({"mutatePackage": True}), "OTA changed during signature verification")


if __name__ == "__main__":
    unittest.main()
