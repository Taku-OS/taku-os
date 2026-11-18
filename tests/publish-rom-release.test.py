#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Taku AI Inc.
# SPDX-License-Identifier: Apache-2.0
"""Offline tests for ROM recovery boot-stack extraction and GitHub Release packaging."""
from __future__ import annotations

import bz2
import hashlib
import importlib.util
import json
import lzma
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("publish_rom_release", ROOT / "scripts/publish-rom-release.py")
assert SPEC and SPEC.loader
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)


def _varint_bytes(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def _pb_field(number: int, wire: int, payload: bytes | int) -> bytes:
    tag = _varint_bytes((number << 3) | wire)
    if wire == 0:
        assert isinstance(payload, int)
        return tag + _varint_bytes(payload)
    assert isinstance(payload, (bytes, bytearray))
    return tag + _varint_bytes(len(payload)) + bytes(payload)


def _build_synthetic_full_ota(
    path: Path,
    *,
    build: str = "20261118070714",
    device: str = "tokay",
    corrupt_boot_hash: bool = False,
) -> tuple[dict[str, bytes], str]:
    block_size = 4096
    blobs = bytearray()
    part_updates: list[bytes] = []
    expected_images: dict[str, bytes] = {}

    for idx, pname in enumerate(sorted(MOD.EXPECTED_AB_PARTITIONS)):
        if pname == "boot":
            b0 = b"BOOT-HEADER-" + (b"A" * (block_size - 12))
            b1 = b"\x00" * block_size
            b2 = (b"KERNEL-XZ-" * 500)[:block_size]
            full_img = b0 + b1 + b2
            raw0 = bz2.compress(b0)
            off0 = len(blobs)
            blobs.extend(raw0)
            raw2 = lzma.compress(b2)
            off2 = len(blobs)
            blobs.extend(raw2)
            ops = [
                _pb_field(1, 0, MOD.OP_REPLACE_BZ)
                + _pb_field(2, 0, off0)
                + _pb_field(3, 0, len(raw0))
                + _pb_field(6, 2, _pb_field(1, 0, 0) + _pb_field(2, 0, 1))
                + _pb_field(8, 2, hashlib.sha256(raw0).digest()),
                _pb_field(1, 0, MOD.OP_ZERO)
                + _pb_field(6, 2, _pb_field(1, 0, 1) + _pb_field(2, 0, 1)),
                _pb_field(1, 0, MOD.OP_REPLACE_XZ)
                + _pb_field(2, 0, off2)
                + _pb_field(3, 0, len(raw2))
                + _pb_field(6, 2, _pb_field(1, 0, 2) + _pb_field(2, 0, 1))
                + _pb_field(8, 2, hashlib.sha256(raw2).digest()),
            ]
        else:
            full_img = f"{pname}-{idx}-".encode("utf-8").ljust(block_size, b"X")
            off = len(blobs)
            blobs.extend(full_img)
            ops = [
                _pb_field(1, 0, MOD.OP_REPLACE)
                + _pb_field(2, 0, off)
                + _pb_field(3, 0, len(full_img))
                + _pb_field(6, 2, _pb_field(1, 0, 0) + _pb_field(2, 0, 1))
                + _pb_field(8, 2, hashlib.sha256(full_img).digest()),
            ]

        if pname in MOD.RECOVERY_PARTITIONS:
            expected_images[f"{pname}.img"] = full_img

        digest = hashlib.sha256(full_img).digest()
        if corrupt_boot_hash and pname == "boot":
            digest = b"\xff" * 32

        info_msg = _pb_field(1, 0, len(full_img)) + _pb_field(2, 2, digest)
        part_msg = _pb_field(1, 2, pname.encode("utf-8")) + _pb_field(7, 2, info_msg)
        for op_msg in ops:
            part_msg += _pb_field(8, 2, op_msg)
        part_updates.append(_pb_field(13, 2, part_msg))

    manifest_bytes = _pb_field(3, 0, block_size) + b"".join(part_updates) + _pb_field(16, 0, 0)
    sig_bytes = b"SYNTHETIC-SIG"
    payload_bin = b"CrAU" + struct.pack(">QQI", 2, len(manifest_bytes), len(sig_bytes)) + manifest_bytes + sig_bytes + bytes(blobs)

    metadata = (
        "ota-type=AB\n"
        f"post-build=google/tokay/tokay:17/CP2A.260805.005/15828068:user/release-keys\n"
        f"post-build-incremental={build}\n"
        "post-sdk-level=37\n"
        "post-security-patch-level=2026-10-01\n"
        "post-timestamp=1794985647\n"
        f"pre-device={device}\n"
    ).encode("utf-8")

    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("META-INF/com/android/metadata", metadata)
        zf.writestr("payload.bin", payload_bin)
        zf.writestr("payload_properties.txt", b"FILE_HASH=synthetic\n")
        zf.writestr("META-INF/com/android/otacert", b"-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n")

    return expected_images, hashlib.sha256(path.read_bytes()).hexdigest()


class PublishRomReleaseTests(unittest.TestCase):
    def test_url_validation_rejects_untrusted_origins_and_queries(self) -> None:
        valid = (
            "https://worker.taku.ai/os-update/v1/download/taku-os/android/tokay/releases/"
            "20261118070714-22cb53203be9da8f/"
            "fbd50dcafa988357bce12a5e4e0e57879d127f898482d69d6bac913a552fdebe/full.zip"
        )
        parsed = MOD.validate_full_ota_url(valid)
        self.assertEqual(parsed["build"], "20261118070714")

        for bad in (
            valid.replace("https://", "http://"),
            valid.replace("worker.taku.ai", "example.com"),
            valid + "?token=abc",
            valid.replace("/tokay/", "/caiman/"),
        ):
            with self.assertRaises(MOD.ReleasePublishError):
                MOD.validate_full_ota_url(bad)

    def test_reconstructs_all_recovery_partitions_and_writes_deterministic_zip(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            ota_zip = base / "full.zip"
            expected_images, full_sha = _build_synthetic_full_ota(ota_zip)
            url = (
                "https://worker.taku.ai/os-update/v1/download/taku-os/android/tokay/releases/"
                "20261118070714-22cb53203be9da8f/" + ("a" * 64) + "/full.zip"
            )

            out1 = base / "out1"
            out2 = base / "out2"
            res1 = MOD.package_release(
                full_url=url,
                full_bytes=ota_zip.stat().st_size,
                full_sha256=full_sha,
                version="1.4.0",
                output_dir=out1,
                repo="Taku-OS/taku-os",
                source_build="20261111080323",
                local_full_zip=ota_zip,
                verify_full_sha256=True,
            )
            res2 = MOD.package_release(
                full_url=url,
                full_bytes=ota_zip.stat().st_size,
                full_sha256=full_sha,
                version="1.4.0",
                output_dir=out2,
                repo="Taku-OS/taku-os",
                source_build="20261111080323",
                local_full_zip=ota_zip,
            )

            zip1 = Path(res1["recoveryZipPath"]).read_bytes()
            zip2 = Path(res2["recoveryZipPath"]).read_bytes()
            self.assertEqual(zip1, zip2, "Recovery ZIP packaging must be byte-for-byte deterministic")

            with zipfile.ZipFile(res1["recoveryZipPath"]) as zf:
                for img_name, raw_bytes in expected_images.items():
                    self.assertEqual(zf.read(img_name), raw_bytes)
                sums = zf.read("SHA256SUMS.txt").decode("utf-8")
                self.assertIn("TakuOS-1.4.0-tokay-20261118070714-full.zip", sums)

            notes = Path(res1["notesPath"]).read_text(encoding="utf-8")
            self.assertIn("fastboot -s SERIAL flash vendor_boot vendor_boot.img", notes)
            self.assertIn('adb -s SERIAL sideload "TakuOS-1.4.0-tokay-20261118070714-full.zip"', notes)

    def test_rejects_corrupted_partition_hash_or_wrong_device(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            url = (
                "https://worker.taku.ai/os-update/v1/download/taku-os/android/tokay/releases/"
                "20261118070714-22cb53203be9da8f/" + ("b" * 64) + "/full.zip"
            )
            bad_hash_zip = base / "bad-hash.zip"
            _, sha1 = _build_synthetic_full_ota(bad_hash_zip, corrupt_boot_hash=True)
            with self.assertRaises(MOD.ReleasePublishError):
                MOD.package_release(
                    full_url=url,
                    full_bytes=bad_hash_zip.stat().st_size,
                    full_sha256=sha1,
                    version="1.4.0",
                    output_dir=base / "o1",
                    repo="Taku-OS/taku-os",
                    local_full_zip=bad_hash_zip,
                )

            wrong_dev_zip = base / "wrong-dev.zip"
            _, sha2 = _build_synthetic_full_ota(wrong_dev_zip, device="caiman")
            with self.assertRaises(MOD.ReleasePublishError):
                MOD.package_release(
                    full_url=url,
                    full_bytes=wrong_dev_zip.stat().st_size,
                    full_sha256=sha2,
                    version="1.4.0",
                    output_dir=base / "o2",
                    repo="Taku-OS/taku-os",
                    local_full_zip=wrong_dev_zip,
                )


if __name__ == "__main__":
    unittest.main()
