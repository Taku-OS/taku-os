#!/usr/bin/env python3
"""Synthetic host-only source gates; never device, signature or OTA acceptance.

All image bytes, payload signatures, APKs and observations here are fixtures.
Real subprocess execution is forbidden. Temporary output is deleted by unittest.
"""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from unittest import mock

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import ota_source as SOURCE
import ota_device as DEVICE

SPEC = importlib.util.spec_from_file_location("synthetic_device_fixtures", ROOT / "tests/ota-device.test.py")
DEVICE_TEST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEVICE_TEST)

NAMES = tuple(sorted("abl bl1 bl2 bl31 boot dtbo gcf gsa gsa_bl1 init_boot ldfw modem pbl product pvmfw system system_dlkm system_ext tzsw vbmeta vbmeta_system vbmeta_vendor vendor vendor_boot vendor_dlkm vendor_kernel_boot".split()))
BOOT_ID = "11111111-2222-3333-4444-555555555555"


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def varint(value):
    output = bytearray()
    while value > 127:
        output.append((value & 127) | 128)
        value >>= 7
    output.append(value)
    return bytes(output)


def integer(number, value):
    return varint(number << 3) + varint(value)


def message(number, value):
    return varint((number << 3) | 2) + varint(len(value)) + value


def info(raw):
    return integer(1, len(raw)) + message(2, hashlib.sha256(raw).digest())


def operation(kind=0, source_extent=False):
    # InstallOperation.type and src_extents are real protobuf field numbers.
    return integer(1, kind) + (message(4, integer(1, 0) + integer(2, 1)) if source_extent else b"")


def part(name, raw, *, old=None, operations=None):
    # Fixed update_metadata.proto SHA256:
    # 5b1ed6abb452996971be3dc4c4db949bb1d82a87221cc63b6867fb95451cef3a.
    # PartitionUpdate.operations is 8; field 2 is bool run_postinstall.
    result = message(1, name.encode("ascii")) + message(7, info(raw))
    if old is not None:
        result += message(6, info(old))
    for op in operations if operations is not None else [operation()]:
        result += message(8, op)
    return message(13, result)


def manifest(images, *, old=None, operations=None, partial=False):
    return integer(16, int(partial)) + b"".join(part(name, raw, old=(old or {}).get(name),
        operations=(operations or {}).get(name)) for name, raw in images.items())


def ota_zip(path, raw, *, version=2, signatures=7, declared_length=None, duplicate=False):
    # This is a framing fixture, not a cryptographically signed OTA.
    header = struct.pack(">4sQQI", b"CrAU", version, len(raw) if declared_length is None else declared_length, signatures)
    with zipfile.ZipFile(path, "w") as archive, warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        archive.writestr("payload.bin", header + raw + b"NOTSIG!")
        if duplicate:
            archive.writestr("payload.bin", header + raw)
    return path


class HostBoundary(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.images = {name: ("synthetic partition " + name).encode().ljust(512, b"\0") for name in NAMES}
        self.popen = mock.patch.object(SOURCE.subprocess, "Popen", side_effect=AssertionError("Real process forbidden in source gate tests"))
        self.run = mock.patch.object(SOURCE.subprocess, "run", side_effect=AssertionError("Real command forbidden in source gate tests"))
        self.popen.start(); self.run.start()
        self.addCleanup(self.popen.stop); self.addCleanup(self.run.stop)

    def package(self, raw, **kwargs):
        return ota_zip(self.root / "synthetic-ota.zip", raw, **kwargs)

    def reference(self):
        return {"partitions": {name: {"bytes": len(raw), "sha256": checksum(raw)} for name, raw in self.images.items()}}


class PayloadGates(HostBoundary):
    def test_complete_full_manifest_and_explicit_old_partition_infos(self):
        full = SOURCE.manifest_parts(manifest(self.images))
        self.assertEqual(tuple(sorted(full)), NAMES)
        self.assertEqual(len(full), 26)
        self.assertTrue(all(p["old"] is None for p in full.values()))
        delta = SOURCE.manifest_parts(manifest(self.images, old=self.images, operations={name: [operation(4)] for name in NAMES}))
        for name in NAMES:
            self.assertEqual(delta[name]["old"], self.reference()["partitions"][name])
            self.assertEqual(delta[name]["new"], full[name]["new"])
            self.assertTrue(delta[name]["operations"][0]["readsSource"])

    def test_inventory_missing_unknown_duplicate_and_partial_rejected(self):
        missing = dict(self.images); missing.pop("system")
        unknown = dict(self.images); unknown["userdata"] = b"fixture"
        cases = [manifest(missing), manifest(unknown), manifest(self.images) + part("system", self.images["system"]),
                 manifest(self.images, partial=True), manifest(self.images) + integer(16, 0)]
        for index, raw in enumerate(cases):
            with self.subTest(index=index), self.assertRaises(SOURCE.ReleaseError):
                SOURCE.manifest_parts(raw)

    def test_duplicate_core_wrong_wire_bad_hash_and_malformed_protobuf_rejected(self):
        malformed = [b"\x80", b"\x80" * 11, b"\x00", b"\x0b", message(13, message(1, b"boot") + integer(7, 1))]
        other = b"".join(part(name, raw) for name, raw in self.images.items() if name != "boot")
        for bad in [message(1, b"boot") + message(1, b"boot") + message(7, info(self.images["boot"])),
                    message(1, b"boot") + message(7, integer(1, 512) + message(2, b"too-short")),
                    message(1, b"boot") + message(7, integer(1, 0) + message(2, b"x" * 32)),
                    message(1, b"boot") + message(7, info(self.images["boot"])) * 2]:
            malformed.append(other + message(13, bad))
        for index, raw in enumerate(malformed):
            with self.subTest(index=index), self.assertRaises(SOURCE.ReleaseError):
                SOURCE.manifest_parts(raw)

    def test_unknown_operation_and_duplicate_operation_type_rejected(self):
        for op in (operation(15), operation(255), operation() + integer(1, 4)):
            with self.subTest(op=op), self.assertRaises(SOURCE.ReleaseError):
                SOURCE.manifest_parts(manifest(self.images, operations={"system": [op]}))

    def test_actual_field_2_postinstall_bool_and_field_8_operations(self):
        others = b"".join(part(name, raw) for name, raw in self.images.items() if name != "system")
        # Literal protobuf tags deliberately do not call part()/operation():
        # 0x10 is field 2 VARINT (run_postinstall=true), 0x42 is field 8
        # LEN (operations); its 0x08 0x04 body is SOURCE_COPY type=4.
        system = (message(1, b"system") + b"\x10\x01" +
                  message(6, info(self.images["system"])) +
                  message(7, info(self.images["system"])) + b"\x42\x02\x08\x04")
        raw = others + message(13, system)
        parsed = SOURCE.manifest_parts(raw)
        self.assertEqual(parsed["system"]["operations"], [{"type": 4, "readsSource": True}])
        checked = SOURCE.validate_delta_source(self.package(raw), self.reference(), {"staleSnapshotRecords": []})
        self.assertEqual(checked["sourceReadingPartitions"], ["system"])

    def test_replace_zstd_14_does_not_require_source_partition(self):
        raw = manifest(self.images, operations={"system": [operation(14)]})
        parsed = SOURCE.manifest_parts(raw)
        self.assertEqual(parsed["system"]["operations"], [{"type": 14, "readsSource": False}])
        checked = SOURCE.validate_delta_source(self.package(raw), self.reference(), {"staleSnapshotRecords": []})
        self.assertEqual(checked["sourceReadingPartitions"], [])

    def test_source_reading_operation_cannot_hide_missing_old_info(self):
        # Every currently known source-reading operation is rejected even if it
        # omits src_extents. A stray src_extents also makes REPLACE source-reading.
        for op in [operation(kind) for kind in (2, 3, 4, 5, 9, 10, 11, 12, 13)] + [operation(0, True)]:
            package = self.package(manifest(self.images, operations={"system": [op]}))
            with self.subTest(op=op), self.assertRaisesRegex(SOURCE.ReleaseError, "without old_partition_info"):
                SOURCE.validate_delta_source(package, self.reference(), {"staleSnapshotRecords": []})

    def test_delta_old_info_exact_binding_and_stale_target_coverage(self):
        package = self.package(manifest(self.images, old=self.images, operations={"system": [operation(4)]}))
        result = SOURCE.validate_delta_source(package, self.reference(), {"staleSnapshotRecords": ["system_b"]})
        self.assertEqual(result["sourceReadingPartitions"], ["system"])
        self.assertIs(result["offlinePayloadApplyVerified"], False)
        reference = self.reference(); reference["partitions"]["system"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(SOURCE.ReleaseError, "differs from qualified source"):
            SOURCE.validate_delta_source(package, reference, {"staleSnapshotRecords": []})
        with self.assertRaisesRegex(SOURCE.ReleaseError, "does not cover"):
            SOURCE.validate_delta_source(package, self.reference(), {"staleSnapshotRecords": ["userdata_b"]})

    def test_payload_zip_duplicate_bad_header_and_truncation_rejected(self):
        raw = manifest(self.images)
        for kwargs in ({"duplicate": True}, {"version": 1}, {"signatures": 0},
                       {"declared_length": len(raw) + 100}, {"declared_length": 33 * 1024 * 1024}):
            with self.subTest(kwargs=kwargs), self.assertRaises(SOURCE.ReleaseError):
                SOURCE.payload_manifest(self.package(raw, **kwargs))
        with zipfile.ZipFile(self.root / "short.zip", "w") as archive:
            archive.writestr("payload.bin", b"CrAU")
        with self.assertRaises(SOURCE.ReleaseError):
            SOURCE.payload_manifest(self.root / "short.zip")


class PortablePathGates(HostBoundary):
    def test_canonical_relative_paths_only(self):
        self.assertEqual(SOURCE.relative_path("reference/source-payload-manifest.bin"), "reference/source-payload-manifest.bin")
        for path in ("", "/absolute", "../outside", "a/../b", "a//b", "a/./b", "a/", "."):
            with self.subTest(path=path), self.assertRaises(SOURCE.ReleaseError):
                SOURCE.relative_path(path)

    def test_refs_reject_changed_size_hash_final_symlink_and_escape(self):
        raw = b"synthetic evidence"
        target = self.root / "proof.bin"; target.write_bytes(raw)
        ref = {"path": target.name, "bytes": len(raw), "sha256": checksum(raw)}
        self.assertEqual(SOURCE.checked_ref(self.root, ref), target.resolve())
        for update in ({"bytes": len(raw) + 1}, {"sha256": "0" * 64}, {"path": str(target)}, {"path": "../proof.bin"}):
            with self.subTest(update=update), self.assertRaises(SOURCE.ReleaseError):
                SOURCE.checked_ref(self.root, {**ref, **update})
        linked = self.root / "link.bin"; linked.symlink_to(target)
        with self.assertRaises(SOURCE.ReleaseError):
            SOURCE.checked_ref(self.root, {**ref, "path": linked.name})
        child = self.root / "child"; child.mkdir()
        escape = child / "outside"; escape.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(SOURCE.ReleaseError):
            SOURCE.checked_ref(child, {**ref, "path": "outside/proof.bin"})

    def test_copy_preserves_original_and_never_overwrites_existing_evidence(self):
        src = self.root / "source.json"; src.write_bytes(b'{"synthetic":true}\n')
        destination = self.root / "copied.json"
        result = SOURCE.copy_file(src, destination)
        self.assertEqual(destination.read_bytes(), src.read_bytes())
        self.assertEqual(result["sha256"], checksum(src.read_bytes()))
        with self.assertRaises(FileExistsError):
            SOURCE.copy_file(src, destination)
        self.assertEqual(destination.read_bytes(), src.read_bytes())


class LinearMappingGates(HostBoundary):
    def test_actual_dmctl_header_and_half_open_sector_grammar(self):
        # Exact grammar supplied from an existing read; executing this parser
        # does not observe a device or validate those device bytes.
        SOURCE.linear_table("Targets in the device-mapper table for system_a:\n0-2163912: linear, 259:17 2048", 1107922944, "system_a")
        SOURCE.linear_table("Targets in the device-mapper table for product_a:\n0-8: linear, 259:17 2048\n8-16: linear, 259:17 4096\n", 8192, "product_a")

    def test_mapping_requires_exact_name_header_contiguity_and_linear_type(self):
        prefix = "Targets in the device-mapper table for system_a:\n"
        cases = ["0-16: linear, 259:17 2048", prefix.replace("system_a", "system_b") + "0-16: linear, 259:17 2048",
            prefix + "1-17: linear, 259:17 2048", prefix + "0-8: linear, 259:17 2048\n9-16: linear, 259:17 4096",
            prefix + "0-9: linear, 259:17 2048\n8-16: linear, 259:17 4096", prefix + "0-0: linear, 259:17 2048",
            prefix + "0-16: verity, 1 259:17 259:17 4096 4096 2 2 sha256 fixture fixture",
            prefix + "0-16: snapshot, 259:17 259:18 P 8", prefix + "0-15: linear, 259:17 2048",
            prefix + "0-16: linear, 259:17 2048\nunparsed suffix"]
        for table in cases:
            with self.subTest(table=table), self.assertRaises(SOURCE.ReleaseError):
                SOURCE.linear_table(table, 8192, "system_a")


FAKE_READER = '''"""Synthetic ImageHandler contract fixture, not an Android sparse parser."""
import io
class ImageHandler:
    def __init__(self, path, read_only=False):
        assert read_only is True, "The real interface must be explicitly read-only"
        self._image = open(path, "rb")
        stored = self._image.read()
        self.is_sparse = stored.startswith(b"FIXTURE-SPARSE:")
        raw = stored[len(b"FIXTURE-SPARSE:"):] if self.is_sparse else stored
        self.image_size = len(raw)
        self._logical = io.BytesIO(raw)
    def seek(self, offset):
        return self._logical.seek(offset)
    def read(self, count):
        return self._logical.read(count)
'''


class ReferencePreparation(HostBoundary):
    def setUp(self):
        super().setUp()
        self.archive = self.root / "synthetic-archive"; self.archive.mkdir()
        self.tool = self.root / "fake_avbtool.py"; self.tool.write_text(FAKE_READER)
        self.proto = self.root / "synthetic_update_metadata.proto"
        self.proto.write_text('// SYNTHETIC fixture, not the authenticated Android source.\n'
                              'syntax = "proto2";\n'
                              'message InstallOperation { enum Type { REPLACE = 0; REPLACE_ZSTD = 14; } '
                              'required Type type = 1; }\n'
                              'message PartitionUpdate { optional bool run_postinstall = 2; '
                              'repeated InstallOperation operations = 8; }\n')
        self.proto_hash = SOURCE.digest(self.proto)
        self.proto_pin = mock.patch.object(SOURCE, "PAYLOAD_PROTO_SHA256", self.proto_hash)
        self.proto_pin.start()
        self.addCleanup(self.proto_pin.stop)
        proofdir = self.archive / "signature-verification"; proofdir.mkdir()
        self.proof = proofdir / "signature-verification.json"
        self.proof.write_text(json.dumps({"toolSources": [{"path": "external/avb/avbtool.py", "sha256": SOURCE.digest(self.tool)}]}))
        self.ota = self.archive / "synthetic-full.zip"
        self.target = self.archive / "synthetic-target-files.zip"
        self.stored = {name: (b"FIXTURE-SPARSE:" + raw if name == "system_ext" else raw) for name, raw in self.images.items()}
        self.write_target()
        ota_zip(self.ota, manifest(self.images))
        self.baseline = DEVICE_TEST.baseline("20261024154635", 1792856795)
        self.baseline.update(directory=str(self.archive), abPartitions=list(NAMES),
            targetFiles={"path": str(self.target), "bytes": self.target.stat().st_size, "sha256": SOURCE.digest(self.target)},
            fullOtas=[{"path": str(self.ota), "bytes": self.ota.stat().st_size, "sha256": SOURCE.digest(self.ota)}])
        self.output = self.root / "synthetic-reference"

    def write_target(self, omit=None, duplicate=None):
        with zipfile.ZipFile(self.target, "w") as archive, warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            for name, raw in self.stored.items():
                if name == omit:
                    continue
                entry = ("IMAGES/" if name in SOURCE.DYNAMIC or name in SOURCE.BOOT else "RADIO/") + name + ".img"
                archive.writestr(entry, raw)
                if name == duplicate:
                    archive.writestr(entry, raw)

    def prepare(self):
        with mock.patch.object(SOURCE, "load_baseline", return_value=self.baseline) as load:
            result = SOURCE.prepare_reference(self.archive, self.tool, self.output, payload_proto=self.proto)
            load.assert_called_once_with(self.archive)
            return result

    def test_prepares_26_small_images_via_read_only_reader_and_revalidates(self):
        originals = {p: p.read_bytes() for p in (self.target, self.ota, self.tool, self.proof, self.proto)}
        result = self.prepare()
        value = SOURCE.validate_reference(result["path"], result["sha256"], self.baseline)
        self.assertEqual(set(value["partitions"]), set(NAMES))
        self.assertIs(value["phoneCommandsRun"], False)
        self.assertEqual(value["payloadSchema"], {"path": "update_metadata.proto",
                         "bytes": self.proto.stat().st_size, "sha256": self.proto_hash})
        self.assertEqual((self.output / "update_metadata.proto").read_bytes(), self.proto.read_bytes())
        sparse = value["partitions"]["system_ext"]
        self.assertIs(sparse["sparse"], True)
        self.assertEqual(sparse["sha256"], checksum(self.images["system_ext"]))
        self.assertEqual(sparse["storedImage"]["sha256"], checksum(self.stored["system_ext"]))
        self.assertNotEqual(sparse["sha256"], sparse["storedImage"]["sha256"])
        self.assertEqual(value["partitions"]["modem"]["targetFilesEntry"], "RADIO/modem.img")
        self.assertFalse(list((self.output / "derived-images").iterdir()))
        self.assertFalse((self.root / "__pycache__").exists())
        for path, raw in originals.items():
            self.assertEqual(path.read_bytes(), raw)
        with self.assertRaises(FileExistsError):
            self.prepare()

    def test_prepare_requires_present_regular_proto_with_exact_pinned_hash(self):
        original = self.proto
        wrong = self.root / "wrong.proto"; wrong.write_bytes(original.read_bytes() + b"// changed\n")
        linked = self.root / "linked.proto"; linked.symlink_to(original)
        for index, proto in enumerate((wrong, self.root / "missing.proto", linked)):
            self.proto = proto
            self.output = self.root / ("invalid-proto-" + str(index))
            with self.subTest(proto=proto), \
                    mock.patch.object(SOURCE.importlib.util, "spec_from_file_location",
                                      side_effect=AssertionError("Reader must not import")), \
                    self.assertRaisesRegex(SOURCE.ReleaseError, "Payload protobuf source differs"):
                self.prepare()
            self.assertFalse((self.output / "reference.json").exists())
            self.assertFalse((self.output / "update_metadata.proto").exists())
        with self.assertRaises(TypeError):
            SOURCE.prepare_reference(self.archive, self.tool, self.root / "no-proto-argument")
        self.assertFalse((self.root / "no-proto-argument").exists())

    def test_saved_proto_tampering_rejected_even_with_rehashed_reference(self):
        result = self.prepare()
        proto = self.output / "update_metadata.proto"
        proto.write_bytes(proto.read_bytes() + b"// modified after preparation\n")
        with self.assertRaises(SOURCE.ReleaseError):
            SOURCE.validate_reference(result["path"], result["sha256"], self.baseline)
        path = Path(result["path"])
        value = json.loads(path.read_text())
        value["payloadSchema"].update(bytes=proto.stat().st_size, sha256=SOURCE.digest(proto))
        path.write_text(json.dumps(value))
        with self.assertRaisesRegex(SOURCE.ReleaseError, "schema differs from the reviewed fixed source"):
            SOURCE.validate_reference(path, SOURCE.digest(path), self.baseline)

    def test_reference_requires_proto_binding_and_copied_sidecar(self):
        result = self.prepare()
        path = Path(result["path"])
        original = path.read_bytes()
        value = json.loads(original)
        del value["payloadSchema"]
        path.write_text(json.dumps(value))
        with self.assertRaises(SOURCE.ReleaseError):
            SOURCE.validate_reference(path, SOURCE.digest(path), self.baseline)
        path.write_bytes(original)
        (self.output / "update_metadata.proto").unlink()
        with self.assertRaises((SOURCE.ReleaseError, FileNotFoundError)):
            SOURCE.validate_reference(path, result["sha256"], self.baseline)

    def test_wrong_raw_digest_preserves_failed_derived_copy_and_original_archive(self):
        self.stored["system_ext"] = b"FIXTURE-SPARSE:" + b"wrong".ljust(512, b"\0")
        self.write_target()
        original = self.target.read_bytes()
        with self.assertRaisesRegex(SOURCE.ReleaseError, "Raw image digest differs"):
            self.prepare()
        self.assertFalse((self.output / "reference.json").exists())
        self.assertEqual((self.output / "derived-images/system_ext.img").read_bytes(), self.stored["system_ext"])
        self.assertEqual(self.target.read_bytes(), original)

    def test_wrong_raw_length_rejected(self):
        self.stored["boot"] += b"unexpected tail"
        self.write_target()
        with self.assertRaisesRegex(SOURCE.ReleaseError, "Raw image size differs"):
            self.prepare()
        self.assertFalse((self.output / "reference.json").exists())

    def test_reader_hash_must_match_authenticated_tool_source(self):
        self.tool.write_text(FAKE_READER + "\nraise AssertionError('Untrusted tool must never execute')\n")
        with self.assertRaisesRegex(SOURCE.ReleaseError, "Image reader differs"):
            self.prepare()
        self.assertFalse((self.output / "reference.json").exists())

    def test_missing_or_duplicate_target_partition_rejected(self):
        for index, kwargs in enumerate(({"omit": "boot"}, {"duplicate": "system"})):
            self.write_target(**kwargs)
            self.output = self.root / ("invalid-inventory-" + str(index))
            with self.subTest(kwargs=kwargs), self.assertRaises(SOURCE.ReleaseError):
                self.prepare()
            self.assertFalse((self.output / "reference.json").exists())

    def test_delta_or_partial_is_not_a_full_reference(self):
        for index, raw in enumerate((manifest(self.images, old=self.images), manifest(self.images, partial=True))):
            ota_zip(self.ota, raw)
            self.output = self.root / ("not-full-" + str(index))
            with self.subTest(index=index), self.assertRaises(SOURCE.ReleaseError):
                self.prepare()
            self.assertFalse((self.output / "reference.json").exists())

    def test_full_reference_rejects_source_operations_without_old_info(self):
        operations = [operation(kind) for kind in (2, 3, 4, 5, 9, 10, 11, 12, 13)] + [operation(0, True)]
        for index, op in enumerate(operations):
            ota_zip(self.ota, manifest(self.images, operations={"system": [op]}))
            self.output = self.root / ("source-reading-full-" + str(index))
            with self.subTest(op=op), self.assertRaises(SOURCE.ReleaseError):
                self.prepare()
            self.assertFalse((self.output / "reference.json").exists())

    def test_metadata_mirror_cannot_replace_authenticated_archive_loader(self):
        mirror = self.root / "metadata-only"; mirror.mkdir()
        (mirror / "baseline-binding.json").write_text(json.dumps({"schema": "taku.ota-baseline-binding/1", "baseline": self.baseline}))
        # No mock here: the real loader must demand actual exported evidence.
        with self.assertRaises((SOURCE.ReleaseError, FileNotFoundError)):
            SOURCE.prepare_reference(mirror, self.tool, self.output, payload_proto=self.proto)
        self.assertFalse(self.output.exists())

    def test_reference_output_cannot_modify_immutable_archive(self):
        original = {p.relative_to(self.archive): p.read_bytes()
                    for p in self.archive.rglob("*") if p.is_file()}
        alias = self.root / "archive-alias"
        alias.symlink_to(self.archive, target_is_directory=True)
        for output in (self.archive, self.archive / "new-reference",
                       alias / "nested/new-reference"):
            self.output = output
            with self.subTest(output=output), \
                    mock.patch.object(SOURCE.importlib.util, "spec_from_file_location",
                                      side_effect=AssertionError("Reader must not import")), \
                    self.assertRaisesRegex(SOURCE.ReleaseError, "must not alter the immutable archive"):
                self.prepare()
        self.assertEqual({p.relative_to(self.archive): p.read_bytes()
                          for p in self.archive.rglob("*") if p.is_file()}, original)
        self.assertFalse((self.archive / "new-reference").exists())
        self.assertFalse((self.archive / "nested").exists())

    def test_loader_failure_never_imports_reader_or_creates_reference(self):
        with mock.patch.object(SOURCE, "load_baseline", side_effect=SOURCE.ReleaseError("synthetic archive authentication failed")), \
                mock.patch.object(SOURCE.importlib.util, "spec_from_file_location", side_effect=AssertionError("Reader must not import")), \
                self.assertRaisesRegex(SOURCE.ReleaseError, "authentication failed"):
            SOURCE.prepare_reference(self.archive, self.tool, self.output, payload_proto=self.proto)
        self.assertFalse(self.output.exists())


class FakeSourceAdb(DEVICE_TEST.FakeAdb):
    """Existing fake device plus a small, explicit read-only block/state surface."""
    def __init__(self, initial, images):
        super().__init__(initial)
        self.images = images
        self.snapshot_state = "none"
        self.mapping_type = "linear"
        self.block_mode_hex = "6180"
        self.capacity_extra = 1536
        self.app_hash = checksum(b"synthetic already installed probe")
        self.data = {name: checksum(("synthetic existing " + name).encode()) for name in DEVICE.DATA_FILES}
        self.source_callback = None

    def __call__(self, args, **kwargs):
        command = args[3:]
        if self.source_callback:
            self.source_callback(self, command)
        output = None
        if command == ["shell", "id", "-u"]:
            output = "0"
        elif command == ["shell", "bootctl", "get-current-slot"]:
            output = "0"
        elif command == ["shell", "bootctl", "get-snapshot-merge-status"]:
            output = self.snapshot_state
        elif command == ["shell", "bootctl", "is-slot-marked-successful", "0"]:
            output = "1"
        elif command == ["shell", "snapshotctl", "dump"]:
            output = "Update state: none\nUsing snapuserd: 0\nUsing userspace snapshots: 0\n" + "".join(
                "Snapshot: " + name + "_b\n  state: CREATED\n" for name in SOURCE.DYNAMIC)
        elif command == ["shell", "dmctl", "list", "devices"]:
            output = "\n".join(name + "_a : 253:" + str(index) for index, name in enumerate(SOURCE.DYNAMIC))
        elif command == ["shell", "sh", "-c", "'if [ -e /metadata/ota/snapshot-boot-without-slot-switch ]; then echo present; else echo absent; fi'"]:
            output = "absent"
        elif command[:4] == ["shell", "/system/bin/readlink", "-f", "--"] and len(command) == 5:
            name = Path(command[-1]).name.removesuffix("_a")
            assert name in self.images
            output = "/dev/block/dm-" + str(SOURCE.DYNAMIC.index(name)) if name in SOURCE.DYNAMIC else "/dev/block/sda" + str(NAMES.index(name) + 1)
        elif command[:5] == ["shell", "/system/bin/stat", "-L", "-c", "%f"] and len(command) == 6:
            output = self.block_mode_hex
        elif command[:3] == ["shell", "/system/bin/blockdev", "--getsize64"] and len(command) == 4:
            name = Path(command[-1]).name.removesuffix("_a")
            output = str(len(self.images[name]) + (0 if name in SOURCE.DYNAMIC else self.capacity_extra))
        elif command[:3] == ["shell", "dmctl", "table"] and len(command) == 4:
            name = command[3].removesuffix("_a")
            assert name in SOURCE.DYNAMIC
            output = "Targets in the device-mapper table for " + command[3] + ":\n0-" + str(len(self.images[name]) // 512) + ": " + self.mapping_type + ", 259:17 2048"
        if output is None:
            assert command[0] != "install" and command[:3] != ["shell", "am", "start"], "Qualification must not initialize probe"
            return super().__call__(args, **kwargs)
        assert args[:3] == ["fixture-adb", "-s", DEVICE_TEST.SERIAL]
        assert kwargs == {"capture_output": True, "text": True, "timeout": 30, "check": False}
        self.calls.append(list(args))
        return subprocess.CompletedProcess(args, 0, output, "")


class QualificationReplay(HostBoundary):
    def setUp(self):
        super().setUp()
        self.source = DEVICE_TEST.baseline("20261024154635", 1792856795)
        self.target = DEVICE_TEST.baseline("20261025010000", 1792890000)
        self.fake = FakeSourceAdb(self.source, self.images)
        connection = DEVICE.Adb(DEVICE_TEST.SERIAL, "fixture-adb", self.fake)
        self.before = {"schema": "taku.ota-device-before/2", "syntheticFixture": True,
            "device": DEVICE.inspect_device(connection, DEVICE.baseline_binding(self.source)),
            "probe": DEVICE.probe_state(connection), "captureBootId": BOOT_ID}
        self.fake.calls.clear()
        self.before_path = self.root / "synthetic-before.json"
        self.before_path.write_text(json.dumps(self.before))
        self.before_hash = SOURCE.digest(self.before_path)
        self.reference_value = self.reference()
        self.reference_path = self.root / "synthetic-reference.json"
        self.reference_path.write_text(json.dumps({"syntheticFixture": True, **self.reference_value}))
        self.reference_hash = SOURCE.digest(self.reference_path)
        self.output = self.root / "synthetic-qualification"
        self.reads = []
        self.reader_mode = "exact"

    def prefix_reader(self, adb, path, count, stderr_path):
        name = Path(path).name.removesuffix("_a")
        raw = self.images[name]
        self.assertEqual(count, len(raw))
        self.reads.append((path, count))
        Path(stderr_path).write_bytes(b"")
        result = {"command": [adb.executable, "-s", adb.serial, "exec-out", "/system/bin/head", "-c", str(count), path],
            "exitCode": 0, "bytesRead": len(raw), "sha256": checksum(raw), "offset": 0,
            "stderrSha256": checksum(b""), "stderr": Path(stderr_path).name,
            "rawBytesSaved": False, "readOnly": True}
        if self.reader_mode == "short":
            result.update(bytesRead=count - 1, sha256=checksum(raw[:-1]))
        elif self.reader_mode == "whole-block" and name not in SOURCE.DYNAMIC:
            result.update(bytesRead=count + self.fake.capacity_extra, sha256=checksum(raw + b"\0" * self.fake.capacity_extra))
        elif self.reader_mode == "wrong-hash":
            result["sha256"] = "0" * 64
        elif self.reader_mode == "reboot":
            self.fake.boot_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        return result

    def observe(self):
        # These mocks isolate device transcript parsing; they do not grant a
        # real source installation or authenticate the synthetic baseline.
        with mock.patch.object(SOURCE.ota_device, "validate_before", return_value=self.before), \
                mock.patch.object(SOURCE, "validate_reference", return_value=self.reference_value):
            return SOURCE.observe_partitions(DEVICE_TEST.SERIAL, self.source, self.target,
                self.before_path, self.before_hash, self.reference_path, self.reference_hash,
                self.output, adb="fixture-adb", runner=self.fake, prefix_reader=self.prefix_reader)

    def validate(self, result):
        return SOURCE.validate_qualification(result["path"], result["sha256"], self.reference_value,
            self.reference_hash, self.source, self.before, self.before_hash)

    def test_exact_prefix_roundtrip_replays_real_command_shapes_not_summary_booleans(self):
        result = self.observe()
        value = self.validate(result)
        self.assertEqual(len(self.reads), 26)
        self.assertIs(value["wholePhysicalPartitionTailsVerified"], False)
        self.assertEqual(set(value["staleSnapshotRecords"]), {name + "_b" for name in SOURCE.DYNAMIC})
        self.assertEqual(value["partitions"]["boot"]["bytesRead"], 512)
        self.assertEqual(value["partitions"]["boot"]["blockBytes"], 2048)
        self.assertTrue(all(count == 512 for _, count in self.reads))
        self.assertFalse(any(call[3] == "install" or call[3:6] == ["shell", "am", "start"] for call in self.fake.calls))

    def test_actual_toybox_numeric_block_mode_is_recorded_and_replayed(self):
        result = self.observe()
        value = self.validate(result)
        self.assertTrue(all(part["modeHex"] == "6180" for part in value["partitions"].values()))
        stats = [row["argv"][3:] for row in value["commands"] if "/system/bin/stat" in row["argv"]]
        self.assertEqual(len(stats), len(NAMES))
        self.assertTrue(all(command[:5] == ["shell", "/system/bin/stat", "-L", "-c", "%f"] for command in stats))
        value["partitions"]["abl"]["modeHex"] = "2180"
        path = Path(result["path"]); path.write_text(json.dumps(value))
        with self.assertRaisesRegex(SOURCE.ReleaseError, "summary differs"):
            self.validate({"path": str(path), "sha256": SOURCE.digest(path)})

    def test_nonblock_and_malformed_stat_modes_stop_before_raw_read(self):
        for index, mode in enumerate(("2180", "8180", "a180", "4180", "1180", "c180", "",
                                      "0x6180", "06180", "6180\n8180", "6180 extra", "block device", "-6180", "6g80")):
            self.output = self.root / ("bad-mode-" + str(index))
            self.fake.block_mode_hex = mode
            with self.subTest(mode=mode), self.assertRaisesRegex(SOURCE.ReleaseError, "mode is not a block device"):
                self.observe()
            self.assertEqual(self.reads, [])
            self.assertFalse((self.output / "qualification.json").exists())

    def test_short_wrong_digest_or_whole_block_cannot_qualify_source(self):
        for mode in ("short", "wrong-hash", "whole-block"):
            self.reader_mode = mode
            self.output = self.root / ("synthetic-" + mode)
            with self.subTest(mode=mode), self.assertRaisesRegex(SOURCE.ReleaseError, "image bytes differ"):
                self.observe()
            self.assertFalse((self.output / "qualification.json").exists())
            self.assertTrue(list(self.output.glob("*.stderr")))

    def test_reboot_during_read_or_different_before_boot_rejected(self):
        self.reader_mode = "reboot"
        with self.assertRaisesRegex(SOURCE.ReleaseError, "changed while hashing"):
            self.observe()
        self.assertFalse((self.output / "qualification.json").exists())
        self.output = self.root / "synthetic-other-before-boot"
        self.fake.boot_id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        self.reads.clear()
        with self.assertRaisesRegex(SOURCE.ReleaseError, "same-boot"):
            self.observe()
        self.assertEqual(self.reads, [])

    def test_dynamic_verity_mapping_rejected_before_hashing_that_partition(self):
        self.fake.mapping_type = "verity"
        with self.assertRaisesRegex(SOURCE.ReleaseError, "linear target"):
            self.observe()
        self.assertFalse(any(Path(path).name.removesuffix("_a") in SOURCE.DYNAMIC for path, _ in self.reads))
        self.assertFalse((self.output / "qualification.json").exists())

    def test_active_merge_rejected_before_any_raw_reads(self):
        self.fake.snapshot_state = "merging"
        with self.assertRaisesRegex(SOURCE.ReleaseError, "snapshot state"):
            self.observe()
        self.assertEqual(self.reads, [])

    def test_summary_and_raw_command_tampering_rejected_with_recomputed_report_hash(self):
        result = self.observe()
        path = Path(result["path"])
        original = json.loads(path.read_text())
        mutations = [lambda x: x["partitions"]["system"]["command"].__setitem__(-2, "2048"),
            lambda x: x["partitions"]["system"].update(resolvedPath="/dev/block/dm-999"),
            lambda x: x["partitions"]["system"].update(dmTable="fake table summary"),
            lambda x: x["partitions"]["boot"].update(blockBytes=9999),
            lambda x: x["partitions"]["boot"].update(rawBytesSaved=True),
            lambda x: x["partitions"]["boot"].update(stderr="../outside.stderr"),
            lambda x: x["commands"][0].update(exitCode=1),
            lambda x: x["commands"][0].update(stdout="unauthorized"),
            lambda x: x["commands"][0]["argv"].__setitem__(2, "other-device"),
            lambda x: x["commands"].pop(),
            lambda x: x["commands"].append(dict(x["commands"][-1]))]
        for index, mutate in enumerate(mutations):
            value = copy.deepcopy(original); mutate(value)
            path.write_text(json.dumps(value))
            with self.subTest(index=index), self.assertRaises((SOURCE.ReleaseError, DEVICE.DeviceVerificationError)):
                self.validate({"path": str(path), "sha256": SOURCE.digest(path)})


if __name__ == "__main__":
    unittest.main()
