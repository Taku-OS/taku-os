"""Small target-files fixtures for tokay ownership and metadata checks."""

from contextlib import contextmanager
from io import BytesIO
from pathlib import Path
import shlex
import struct
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import mixed_build as mixed


def descriptor(tag, name, location=0, key=b""):
    name = name.encode()
    size, length_offset = {1: (164, 88), 2: (116, 40), 4: (76, 4)}[tag]
    body = bytearray(size)
    struct.pack_into(">I", body, length_offset, len(name))
    if tag == 4:
        struct.pack_into(">I", body, 0, location)
        struct.pack_into(">I", body, 8, len(key))
    body.extend(name + key)
    body.extend(bytes(-len(body) % 8))
    return struct.pack(">QQ", tag, len(body)) + body


def vbmeta(key, descriptors):
    header = bytearray(256)
    header[:4] = b"AVB0"
    struct.pack_into(">QQI", header, 12, 64, len(key) + len(descriptors), 1)
    struct.pack_into(">QQ", header, 64, 0, len(key))
    struct.pack_into(">QQ", header, 96, len(key), len(descriptors))
    return header + bytes(64) + key + descriptors


def fixture():
    locations = dict(boot=2, init_boot=4, vbmeta_system=1, vbmeta_vendor=3)
    keys = {name: name.encode() for name in locations}
    layout = {
        "dynamic_partition_list": " ".join(sorted(mixed.DYNAMIC)),
        "use_dynamic_partitions": "true",
        "super_partition_groups": "group",
        "super_group_group_size": "100000",
        "super_partition_size": "100000",
        "super_partition_error_limit": "90000",
    }
    layout["super_group_partition_list"] = layout["dynamic_partition_list"]
    misc = layout | {
        "ab_update": "true", "default_system_dev_certificate": "testkey",
        "avb_enable": "true", "avb_vbmeta_vendor": "vendor",
        "avb_vbmeta_system": " ".join(sorted(mixed.SYSTEM_CHAIN)),
    } | {f"avb_{name}_rollback_index_location": value for name, value in locations.items()}
    ab = mixed.DYNAMIC | mixed.ROOT_CHAIN | mixed.ROOT_HASHES | {"vbmeta"}
    files = {mixed.image_path(part): b"raw image" for part in ab | {"system_other"}}
    root = b"".join(descriptor(4, name, location, keys[name])
                    for name, location in locations.items())
    root += b"".join(descriptor(2, name) for name in sorted(mixed.ROOT_HASHES))
    root += descriptor(1, "vendor_dlkm")
    files.update({
        "IMAGES/vbmeta.img": vbmeta(b"root", root),
        "IMAGES/vbmeta_system.img": vbmeta(keys["vbmeta_system"], b"".join(
            descriptor(1, name) for name in sorted(mixed.SYSTEM_CHAIN)
        )),
        "IMAGES/vbmeta_vendor.img": vbmeta(keys["vbmeta_vendor"], descriptor(1, "vendor")),
        "META/ab_partitions.txt": "\n".join(sorted(ab)),
        "META/dynamic_partitions_info.txt": "\n".join(f"{k}={v}" for k, v in layout.items()),
        "META/misc_info.txt": "\n".join(f"{k}={v}" for k, v in misc.items()),
        "SYSTEM/etc/selinux/mapping/202604.cil": b"mapping",
        "VENDOR/etc/selinux/plat_sepolicy_vers.txt": b"202604\n",
        "PVMFW/pvmfw": b"firmware",
        "SYSTEM_DLKM/lib/modules/test.ko": b"module",
        "SYSTEM/": b"",
    })
    return files


@contextmanager
def archive(files):
    buffer = BytesIO()
    with zipfile.ZipFile(buffer, "w") as target:
        for name, data in files.items():
            target.writestr(name, data)
    with zipfile.ZipFile(buffer) as target:
        yield target


class MixedBuildTest(unittest.TestCase):
    def verify(self, files):
        with archive(files) as target:
            return mixed.audit(target)[0]

    def test_valid(self):
        files = fixture()
        report = self.verify(files)
        self.assertEqual(report["members"], len(files) - 1)
        self.assertEqual(report["directories"], 1)
        self.assertEqual(report["expanded_dynamic_bytes"], 54)

    def test_overlap(self):
        real = mixed.manifest
        with patch.object(mixed, "manifest", side_effect=lambda name: real(name) + (
            ["IMAGES/pvmfw.img"] if name == mixed.FRAMEWORK else []
        )):
            with self.assertRaisesRegex(ValueError, "ownership overlap"):
                self.verify(fixture())

    def test_unknown_file_or_directory(self):
        for name in ("META/unknown.txt", "UNKNOWN/"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "unclassified"):
                self.verify(fixture() | {name: b""})

    def test_vendor_partitions_cannot_move_to_framework(self):
        real = mixed.manifest
        for partition in ("pvmfw", "system_dlkm"):
            moved = [mixed.image_path(partition), f"{partition.upper()}/*"]
            with self.subTest(partition=partition), patch.object(
                mixed, "manifest", side_effect=lambda name: (
                    real(name) + moved if name == mixed.FRAMEWORK else
                    [item for item in real(name) if item not in moved]
                )
            ):
                with self.assertRaisesRegex(ValueError, f"{partition} must remain"):
                    self.verify(fixture())

    def test_sparse_expansion_exceeds_threshold(self):
        sparse = struct.pack("<I4H4I", 0xED26FF3A, 1, 0, 28, 12, 4096, 23, 1, 0)
        sparse += struct.pack("<2H2I", 0xCAC3, 0, 23, 12)
        with self.assertRaisesRegex(ValueError, "exceeds error limit"):
            self.verify(fixture() | {"IMAGES/system.img": sparse})

    def test_ab_duplicates(self):
        files = fixture()
        files["META/ab_partitions.txt"] += "\nsystem\n"
        with self.assertRaisesRegex(ValueError, "repeated A/B"):
            self.verify(files)

    def test_missing_sepolicy_mapping(self):
        with self.assertRaisesRegex(ValueError, "SELinux mapping"):
            self.verify(fixture() | {"VENDOR/etc/selinux/plat_sepolicy_vers.txt": b"99.0"})

    def test_incomplete_avb_chain(self):
        image = vbmeta(b"vbmeta_system", b"".join(
            descriptor(1, name) for name in sorted(mixed.SYSTEM_CHAIN - {"system_dlkm"})
        ))
        with self.assertRaisesRegex(ValueError, "incomplete AVB chain"):
            self.verify(fixture() | {"IMAGES/vbmeta_system.img": image})

    def test_wrong_avb_chain_key(self):
        image = vbmeta(b"other key", descriptor(1, "vendor"))
        with self.assertRaisesRegex(ValueError, "AVB chain key mismatch"):
            self.verify(fixture() | {"IMAGES/vbmeta_vendor.img": image})

    def test_merge_publishes_only_verified_output(self):
        with tempfile.TemporaryDirectory() as scratch:
            root = Path(scratch)
            source = root / "A.zip"
            output = root / "merged.zip"
            args = SimpleNamespace(framework=source, vendor=source, output=output,
                                   otatools=root, source_root=None, dry_run=False)
            files = fixture()
            with archive(files) as target:
                source.write_bytes(target.fp.getvalue())

            def materialize(command, **kwargs):
                with archive(merged_files) as target:
                    Path(command[-1]).write_bytes(target.fp.getvalue())

            with patch.object(mixed.sys, "platform", "linux"), patch.object(
                mixed.subprocess, "run", side_effect=materialize
            ) as run, patch("builtins.print"):
                merged_files = files | {"IMAGES/pvmfw.img": b"changed"}
                with self.assertRaisesRegex(ValueError, "frozen Vendor SHA-256 mismatch"):
                    mixed.merge(args)
                self.assertFalse(output.exists())
                merged_files = files | {"META/combined_sepolicy": b"compiled",
                                        "META/shareduid_violation_modules.json": b"{}"}
                args.source_root = root / "source"
                mixed.merge(args)
                self.assertTrue(output.exists())
                self.assertEqual(run.call_args.kwargs["cwd"], args.source_root)

    def test_otatools_command(self):
        args = SimpleNamespace(
            framework=Path("framework input.zip"), vendor=Path("vendor input.zip"),
            otatools=Path("ota tools"),
        )
        output = Path("/tmp/output package.zip")
        command = mixed.merge_command(args, output)
        self.assertEqual(shlex.split(shlex.join(command)), command)
        self.assertEqual(command[1::2], [
            "--framework-target-files", "--vendor-target-files", "--framework-item-list",
            "--vendor-item-list", "--framework-misc-info-keys", "--output-target-files",
        ])
        self.assertEqual(command[-1], str(output))


if __name__ == "__main__":
    unittest.main()
