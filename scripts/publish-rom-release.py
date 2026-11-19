#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Taku AI Inc.
# SPDX-License-Identifier: Apache-2.0
"""Extract matching Pixel 9 recovery boot images from an immutable full OTA and publish a GitHub Release.

Full TakuOS OTA packages (~2.75 GB) exceed GitHub Releases' 2.0 GiB per-asset limit,
while first-time installation from stock Android requires the matching ~42 MB
recovery boot stack (boot.img, dtbo.img, vendor_kernel_boot.img, vendor_boot.img).

This script:
1. Discovers newly published full OTAs from the public worker.taku.ai canary feed
   (or accepts an explicit immutable worker.taku.ai full.zip URL / local full.zip).
2. Reads the ZIP Central Directory, OTA metadata, and A/B payload.bin manifest via
   HTTP Range requests (or local file seeks), verifying tokay device targeting and
   preserved-firmware partition boundaries.
3. Reconstructs and SHA-256 verifies boot.img, dtbo.img, vendor_kernel_boot.img,
   and vendor_boot.img against both per-operation hashes and partition hashes in
   payload.bin, then seals a deterministic TakuOS-<ver>-tokay-<build>-recovery.zip.
4. Generates the release manifest (tokay-<build>.json), SHA256SUMS.txt, and
   zero-to-one installation release notes, and idempotently publishes the GitHub
   Release on Taku-OS/taku-os.
"""
from __future__ import annotations

import argparse
import bz2
import datetime
import hashlib
import json
import lzma
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import urllib.parse
import zipfile

ROOT = Path(__file__).resolve().parents[1]

CANONICAL_ORIGIN = "https://worker.taku.ai"
GITHUB_RELEASE_ASSET_LIMIT_BYTES = 2 * 1024 * 1024 * 1024 - 1024 * 1024
EVERGREEN_FULL_FILENAME = "TakuOS-tokay-full.zip"
EVERGREEN_RECOVERY_FILENAME = "TakuOS-tokay-recovery.zip"
EVERGREEN_MANIFEST_FILENAME = "latest.json"
DOWNLOAD_PATH_RE = re.compile(
    r"^/os-update/v1/download/taku-os/android/tokay/releases/"
    r"(?P<build>\d{14})-(?P<snap>[0-9a-f]{16})/(?P<manifest_sha>[0-9a-f]{64})/full\.zip$"
)
FULL_FILENAME_RE = re.compile(
    r"^TakuOS-(?P<version>(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))-tokay-(?P<build>\d{14})-full\.zip$"
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")

PARTIAL_AB_PARTITIONS = frozenset({
    "boot", "dtbo", "init_boot", "product", "system", "system_dlkm",
    "system_ext", "vbmeta", "vbmeta_system", "vbmeta_vendor", "vendor",
    "vendor_boot", "vendor_dlkm", "vendor_kernel_boot",
})
FULL_TOKAY_AB_PARTITIONS = frozenset(
    "abl bl1 bl2 bl31 boot dtbo gcf gsa gsa_bl1 init_boot ldfw modem pbl "
    "product pvmfw system system_dlkm system_ext tzsw vbmeta vbmeta_system "
    "vbmeta_vendor vendor vendor_boot vendor_dlkm vendor_kernel_boot".split()
)
EXPECTED_AB_PARTITIONS = FULL_TOKAY_AB_PARTITIONS
RECOVERY_PARTITIONS = ("boot", "dtbo", "vendor_kernel_boot", "vendor_boot")

OP_REPLACE = 0
OP_REPLACE_BZ = 1
OP_ZERO = 6
OP_REPLACE_XZ = 8


class ReleasePublishError(ValueError):
    pass


def require(condition: object, message: str) -> None:
    if not condition:
        raise ReleasePublishError(message)


def sha256_bytes(data: bytes | bytearray | memoryview) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_full_ota_url(url: str) -> dict[str, str]:
    parsed = urllib.parse.urlsplit(url)
    require(
        parsed.scheme == "https"
        and parsed.netloc == "worker.taku.ai"
        and not parsed.query
        and not parsed.fragment
        and not parsed.username
        and not parsed.password,
        f"Full OTA URL must be an immutable https://worker.taku.ai path without query/credentials: {url}",
    )
    match = DOWNLOAD_PATH_RE.fullmatch(parsed.path)
    require(match is not None, f"Full OTA URL path outside tokay release allowlist: {url}")
    assert match is not None
    return match.groupdict()


class ByteSource:
    """Uniform random-access reader over a local ZIP or remote HTTPS Range resource."""

    def read_range(self, start: int, end_inclusive: int) -> bytes:
        raise NotImplementedError

    @property
    def total_bytes(self) -> int:
        raise NotImplementedError


class FileByteSource(ByteSource):
    def __init__(self, path: Path) -> None:
        self._path = path
        self._size = path.stat().st_size

    @property
    def total_bytes(self) -> int:
        return self._size

    def read_range(self, start: int, end_inclusive: int) -> bytes:
        require(0 <= start <= end_inclusive < self._size, "File range out of bounds")
        length = end_inclusive - start + 1
        with self._path.open("rb") as handle:
            handle.seek(start)
            data = handle.read(length)
        require(len(data) == length, "Short read from local file")
        return data


class HttpRangeByteSource(ByteSource):
    def __init__(self, url: str, expected_bytes: int) -> None:
        validate_full_ota_url(url)
        require(expected_bytes > 65536, "Invalid expected OTA byte size")
        self._url = url
        self._size = expected_bytes

    @property
    def total_bytes(self) -> int:
        return self._size

    def read_range(self, start: int, end_inclusive: int) -> bytes:
        require(0 <= start <= end_inclusive < self._size, "HTTP range out of bounds")
        expected_len = end_inclusive - start + 1
        data = subprocess.check_output(
            [
                "curl",
                "-fsSL",
                "--retry",
                "3",
                "--retry-delay",
                "2",
                "-r",
                f"{start}-{end_inclusive}",
                self._url,
            ]
        )
        require(
            len(data) == expected_len,
            f"HTTP range {start}-{end_inclusive} returned {len(data)} bytes, expected {expected_len}",
        )
        return data


def _varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        require(offset < len(data), "Truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not (byte & 128):
            return value, offset
    raise ReleasePublishError("Oversized protobuf varint")


def _protobuf_fields(data: bytes):
    offset = 0
    limit = len(data)
    while offset < limit:
        tag, offset = _varint(data, offset)
        number, wire = tag >> 3, tag & 7
        require(number > 0, "Invalid protobuf field number")
        if wire == 0:
            value, offset = _varint(data, offset)
        elif wire == 1:
            value = data[offset : offset + 8]
            offset += 8
        elif wire == 5:
            value = data[offset : offset + 4]
            offset += 4
        elif wire == 2:
            length, offset = _varint(data, offset)
            value = data[offset : offset + length]
            offset += length
        else:
            raise ReleasePublishError(f"Unsupported protobuf wire type {wire}")
        require(offset <= limit, "Truncated protobuf field")
        yield number, wire, value


def parse_central_directory(tail: bytes, tail_offset: int, total_size: int) -> dict[str, dict[str, int]]:
    eocd_pos = tail.rfind(b"PK\x05\x06")
    require(eocd_pos >= 0, "Missing ZIP End of Central Directory record")
    (
        _disk,
        _cd_disk,
        _entries_disk,
        entries_total,
        cd_size,
        cd_offset,
    ) = struct.unpack("<HHHHII", tail[eocd_pos + 4 : eocd_pos + 20])
    # Handle ZIP64 EOCD locator when total size or offsets exceed 32-bit limits.
    if entries_total == 0xFFFF or cd_size == 0xFFFFFFFF or cd_offset == 0xFFFFFFFF:
        loc_pos = tail.rfind(b"PK\x06\x07", 0, eocd_pos)
        require(loc_pos >= 0, "Missing ZIP64 EOCD locator")
        (_z64_disk, z64_eocd_off, _total_disks) = struct.unpack("<IIQ"[1:], struct.pack("<B", 0)[1:] if False else b"") if False else (0, 0, 0)
        z64_disk, z64_eocd_off, _total_disks = struct.unpack("<IQI", tail[loc_pos + 4 : loc_pos + 20])
        require(z64_disk == 0 and z64_eocd_off >= tail_offset, "ZIP64 EOCD outside tail buffer")
        z64_rel = z64_eocd_off - tail_offset
        require(tail[z64_rel : z64_rel + 4] == b"PK\x06\x06", "Invalid ZIP64 EOCD signature")
        entries_total, cd_size, cd_offset = struct.unpack("<QQQ", tail[z64_rel + 32 : z64_rel + 56])

    require(
        cd_offset >= tail_offset and cd_offset + cd_size <= total_size,
        "ZIP Central Directory outside read window",
    )
    cd = tail[cd_offset - tail_offset : cd_offset - tail_offset + cd_size]
    pos = 0
    entries: dict[str, dict[str, int]] = {}
    while pos < len(cd):
        require(cd[pos : pos + 4] == b"PK\x01\x02", "Invalid Central Directory entry signature")
        (
            _sig,
            _ver_made,
            _ver_need,
            _flags,
            method,
            _mtime,
            _mdate,
            crc,
            csize,
            usize,
            nlen,
            elen,
            clen,
            _dstart,
            _iattr,
            _eattr,
            lhoff,
        ) = struct.unpack("<IHHHHHHIIIHHHHHII", cd[pos : pos + 46])
        name = cd[pos + 46 : pos + 46 + nlen].decode("utf-8")
        extra = cd[pos + 46 + nlen : pos + 46 + nlen + elen]
        if csize == 0xFFFFFFFF or usize == 0xFFFFFFFF or lhoff == 0xFFFFFFFF:
            ep = 0
            while ep + 4 <= len(extra):
                eid, esz = struct.unpack("<HH", extra[ep : ep + 4])
                edata = extra[ep + 4 : ep + 4 + esz]
                ep += 4 + esz
                if eid == 1:
                    vp = 0
                    if usize == 0xFFFFFFFF:
                        usize = struct.unpack("<Q", edata[vp : vp + 8])[0]
                        vp += 8
                    if csize == 0xFFFFFFFF:
                        csize = struct.unpack("<Q", edata[vp : vp + 8])[0]
                        vp += 8
                    if lhoff == 0xFFFFFFFF:
                        lhoff = struct.unpack("<Q", edata[vp : vp + 8])[0]
                        vp += 8
        require(name not in entries, f"Duplicate ZIP entry: {name}")
        entries[name] = {
            "method": method,
            "crc": crc,
            "compressedBytes": csize,
            "uncompressedBytes": usize,
            "localHeaderOffset": lhoff,
        }
        pos += 46 + nlen + elen + clen
    require(len(entries) == entries_total, "Central Directory entry count mismatch")
    return entries


def read_local_entry_data_offset(source: ByteSource, lhoff: int, expected_name: str) -> int:
    header = source.read_range(lhoff, lhoff + 29 + len(expected_name.encode("utf-8")) + 256)
    require(header[:4] == b"PK\x03\x04", f"Invalid local ZIP header for {expected_name}")
    nlen, elen = struct.unpack("<HH", header[26:30])
    actual_name = header[30 : 30 + nlen].decode("utf-8")
    require(actual_name == expected_name, f"Local header name mismatch: {actual_name} != {expected_name}")
    return lhoff + 30 + nlen + elen


def parse_ota_metadata(raw_text: str) -> dict[str, str]:
    props: dict[str, str] = {}
    for line in raw_text.splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key] = value
    return props


def parse_payload_manifest(manifest_bytes: bytes) -> dict[str, object]:
    block_size = 4096
    partial_update = False
    partitions: dict[str, dict[str, object]] = {}
    for num, wire, val in _protobuf_fields(manifest_bytes):
        if num == 3 and wire == 0:
            block_size = int(val)
        elif num == 16 and wire == 0:
            partial_update = bool(val)
        elif num == 13 and wire == 2:
            pname: str | None = None
            new_size: int | None = None
            new_hash: str | None = None
            ops: list[dict[str, object]] = []
            for snum, swire, sval in _protobuf_fields(val):
                if snum == 1 and swire == 2:
                    pname = sval.decode("utf-8")
                elif snum == 7 and swire == 2:
                    for inum, iwire, ival in _protobuf_fields(sval):
                        if inum == 1 and iwire == 0:
                            new_size = int(ival)
                        elif inum == 2 and iwire == 2:
                            new_hash = bytes(ival).hex()
                elif snum == 8 and swire == 2:
                    op: dict[str, object] = {
                        "type": 0,
                        "data_offset": 0,
                        "data_length": 0,
                        "dst_extents": [],
                        "sha256": None,
                    }
                    for onum, owire, oval in _protobuf_fields(sval):
                        if onum == 1 and owire == 0:
                            op["type"] = int(oval)
                        elif onum == 2 and owire == 0:
                            op["data_offset"] = int(oval)
                        elif onum == 3 and owire == 0:
                            op["data_length"] = int(oval)
                        elif onum == 6 and owire == 2:
                            sb = nb = 0
                            for enum, ewire, eval_ in _protobuf_fields(oval):
                                if enum == 1 and ewire == 0:
                                    sb = int(eval_)
                                elif enum == 2 and ewire == 0:
                                    nb = int(eval_)
                            cast_extents = op["dst_extents"]
                            assert isinstance(cast_extents, list)
                            cast_extents.append((sb, nb))
                        elif onum == 8 and owire == 2:
                            op["sha256"] = bytes(oval).hex()
                    ops.append(op)
            require(pname is not None and pname not in partitions, f"Invalid or duplicate partition: {pname}")
            assert pname is not None
            partitions[pname] = {"size": new_size, "sha256": new_hash, "operations": ops}
    return {
        "blockSize": block_size,
        "partialUpdate": partial_update,
        "partitions": partitions,
    }


def reconstruct_partition(
    name: str,
    part_spec: dict[str, object],
    block_size: int,
    blob_base_offset: int,
    source: ByteSource,
) -> bytes:
    size = part_spec["size"]
    expected_hash = part_spec["sha256"]
    ops = part_spec["operations"]
    require(
        isinstance(size, int) and size > 0 and isinstance(expected_hash, str) and SHA256_RE.fullmatch(expected_hash),
        f"Partition {name} missing valid size/hash in payload manifest",
    )
    assert isinstance(size, int) and isinstance(expected_hash, str) and isinstance(ops, list)

    data_ops = [op for op in ops if int(op["data_length"]) > 0]
    span_bytes = b""
    min_off = 0
    if data_ops:
        min_off = min(int(op["data_offset"]) for op in data_ops)
        max_end = max(int(op["data_offset"]) + int(op["data_length"]) for op in data_ops)
        span_bytes = source.read_range(blob_base_offset + min_off, blob_base_offset + max_end - 1)

    output = bytearray(size)
    for op in ops:
        otype = int(op["type"])
        dlen = int(op["data_length"])
        doff = int(op["data_offset"])
        extents = op["dst_extents"]
        op_sha = op["sha256"]
        assert isinstance(extents, list)

        if dlen > 0:
            rel = doff - min_off
            raw = span_bytes[rel : rel + dlen]
            require(op_sha is not None, f"Partition {name} data operation missing SHA-256")
            require(sha256_bytes(raw) == op_sha, f"Partition {name} operation blob SHA-256 mismatch")
        else:
            raw = b""

        if otype == OP_REPLACE:
            decoded = raw
        elif otype == OP_REPLACE_BZ:
            decoded = bz2.decompress(raw)
        elif otype == OP_ZERO:
            total_blocks = sum(nb for _, nb in extents)
            decoded = b"\x00" * (total_blocks * block_size)
        elif otype == OP_REPLACE_XZ:
            decoded = lzma.decompress(raw)
        else:
            raise ReleasePublishError(f"Partition {name} uses non-full-OTA operation type {otype}")

        cursor = 0
        for start_block, num_blocks in extents:
            chunk_len = num_blocks * block_size
            dst_start = start_block * block_size
            require(
                dst_start >= 0 and dst_start + chunk_len <= size,
                f"Partition {name} extent out of bounds",
            )
            output[dst_start : dst_start + chunk_len] = decoded[cursor : cursor + chunk_len]
            cursor += chunk_len
        require(cursor == len(decoded), f"Partition {name} decoded extent byte count mismatch")

    actual_hash = sha256_bytes(output)
    require(
        actual_hash == expected_hash,
        f"Partition {name} reconstructed SHA-256 {actual_hash} != manifest {expected_hash}",
    )
    return bytes(output)


def build_zip_timestamp(build_number: str) -> tuple[int, int, int, int, int, int]:
    require(len(build_number) == 14 and build_number.isdigit(), f"Invalid build number: {build_number}")
    dt = datetime.datetime.strptime(build_number, "%Y%m%d%H%M%S")
    # ZIP timestamps have 2-second granularity and require year >= 1980.
    return (max(dt.year, 1980), dt.month, dt.day, dt.hour, dt.minute, dt.second - (dt.second % 2))


def write_deterministic_recovery_zip(
    output_zip: Path,
    build_number: str,
    full_ota_filename: str,
    full_ota_sha256: str,
    full_payload_manifest_sha256: str,
    image_paths: list[tuple[str, Path, int, str]],
) -> tuple[int, str]:
    ts = build_zip_timestamp(build_number)
    checksum_lines = [f"{sha}  {fname}" for fname, _p, _sz, sha in image_paths]
    checksum_lines.append(f"{full_ota_sha256}  {full_ota_filename}")
    sha256sums_bytes = ("\n".join(checksum_lines) + "\n").encode("utf-8")

    inner_manifest = {
        "schema": "taku.recovery-boot-stack/1",
        "sourcePayloadManifestSha256": full_payload_manifest_sha256,
        "build": build_number,
        "files": [
            {"file": fname, "bytes": sz, "sha256": sha}
            for fname, _p, sz, sha in image_paths
        ],
    }
    manifest_bytes = (json.dumps(inner_manifest, indent=2) + "\n").encode("utf-8")

    output_zip.parent.mkdir(parents=True, exist_ok=True)
    tmp_zip = output_zip.with_suffix(".zip.tmp")
    with zipfile.ZipFile(tmp_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        for fname, img_path, _sz, _sha in image_paths:
            info = zipfile.ZipInfo(filename=fname, date_time=ts)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            with img_path.open("rb") as src, zf.open(info, "w") as dst:
                for chunk in iter(lambda: src.read(8 * 1024 * 1024), b""):
                    dst.write(chunk)
        for meta_name, payload in (("SHA256SUMS.txt", sha256sums_bytes), ("manifest.json", manifest_bytes)):
            info = zipfile.ZipInfo(filename=meta_name, date_time=ts)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            zf.writestr(info, payload)
    tmp_zip.replace(output_zip)
    return output_zip.stat().st_size, sha256_file(output_zip)


def render_release_notes(manifest: dict[str, object], tag: str, repo: str) -> str:
    version = str(manifest["version"])
    build = str(manifest["build"])
    firmware = str(manifest["firmwareBaseline"])
    sec_patch = str(manifest["platformSecurityPatch"])
    full = manifest["fullOta"]
    rec = manifest["recovery"]
    rec_images = manifest["recoveryImages"]
    assert isinstance(full, dict) and isinstance(rec, dict) and isinstance(rec_images, list)

    full_gb = f"{int(full['bytes']) / (1000 ** 3):.2f} GB"
    rec_mb = f"{int(rec['bytes']) / (1000 ** 2):.1f} MB"

    img_rows = "\n".join(
        f"| `{item['file']}` | {int(item['bytes']):,} | `{item['sha256']}` |"
        for item in rec_images
    )

    return f"""## TakuOS {version} · Pixel 9 (`tokay`) · Build `{build}`

> **Device scope / 适用机型**: Google **Pixel 9 (`tokay`)** only · Android {manifest['androidRelease']} (SDK {manifest['androidSdk']}) · Security patch `{sec_patch}` · Stock firmware baseline `{firmware}` (`development-preview`).
> Do **not** flash on Pixel 9 Pro (`caiman`), Pixel 9 Pro XL (`komodo`), Pixel 9 Pro Fold (`comet`), other devices, or emulators.

---

### 1. Downloads & Checksums / 安装包下载与校验

> Why two links? GitHub Releases caps single uploaded assets at 2.0 GiB, so the **{full_gb} Full System OTA ZIP** is served directly from Taku's immutable CDN with HTTP Range resume support, while the matching **{rec_mb} Recovery Boot Stack ZIP** is attached directly to this GitHub Release.

| Package / 文件说明 | Download / 下载直链 | Size | SHA-256 |
| --- | --- | --- | --- |
| **Full System OTA ZIP** *(keep zipped — sideload in Recovery / 保持原样用于 sideload)* | [**`{full['filename']}`**]({full['url']}) | {full_gb} ({int(full['bytes']):,} B) | `{full['sha256']}` |
| **Matching Recovery Boot Stack** *(unzip for first-time stock install / 首次刷机需解压)* | [**`{rec['filename']}`**]({rec['url']}) | {rec_mb} ({int(rec['bytes']):,} B) | `{rec['sha256']}` |

<details>
<summary><strong>Extracted Recovery Image SHA-256 Checksums / 解压后 4 个引导镜像校验值</strong></summary>

| Extracted file | Bytes | SHA-256 |
| --- | --- | --- |
{img_rows}

</details>

---

### 2. Zero-to-One First Install (From Stock Pixel 9 / 从 0 首刷步骤)

> **Data wipe warning / 清空数据提醒**: First-time installation from stock Android unlocks the bootloader and formats `userdata`. Back up all personal data on the phone first. Existing TakuOS users should upgrade via **Settings → System → System updates** without wiping data.

#### Option A — AI-Assisted Setup (Claude Code / Codex)

Copy and paste into your local coding agent after connecting your Pixel 9 via USB:

```text
Help me install TakuOS {version} (build {build}) on my Pixel 9 (tokay) following https://github.com/{repo}/releases/tag/{tag} and https://docs.taku.ai/quickstart/mobile/ .
First inspect connected adb/fastboot devices with explicit -s SERIAL, verify product=tokay and Android 17 firmware baseline ({firmware}), download and verify SHA-256 of both {full['filename']} and {rec['filename']}, and ask for my explicit confirmation before any bootloader unlock or data-wiping step.
```

#### Option B — Manual Command-Line Install (5 Steps)

1. **Enable OEM unlocking & USB debugging** on stock Android 17 (`{firmware}`):
   *Settings → About phone → tap Build number 7 times*, then *Settings → System → Developer options → enable **OEM unlocking** and **USB debugging***.
2. **Download & verify checksums** in a clean folder with [Android Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (`adb` / `fastboot`):
   ```bash
   curl -fLO "{full['url']}" -o "{full['filename']}"
   curl -fLO "{rec['url']}"
   unzip "{rec['filename']}"
   shasum -a 256 -c SHA256SUMS.txt
   ```
3. **Reboot to bootloader & unlock** *(first install only; confirm on phone screen)*:
   ```bash
   adb -s SERIAL reboot bootloader
   fastboot -s SERIAL getvar product          # must be: tokay
   fastboot -s SERIAL flashing unlock         # skip if unlocked: yes
   ```
4. **Flash matching TakuOS {version} recovery boot stack** *(stop immediately if any command reports `FAILED`)*:
   ```bash
   fastboot -s SERIAL flash boot boot.img
   fastboot -s SERIAL flash dtbo dtbo.img
   fastboot -s SERIAL flash vendor_kernel_boot vendor_kernel_boot.img
   fastboot -s SERIAL reboot bootloader
   fastboot -s SERIAL flash vendor_boot vendor_boot.img
   ```
   Use the volume keys on the phone to select **Recovery Mode** and press Power.
5. **Format data & sideload Full OTA in TakuOS Recovery**:
   - On phone Recovery: **Factory Reset → Format data / factory reset**
   - Then: **Apply update → Apply from ADB**
   - On computer:
     ```bash
     adb -s SERIAL sideload "{full['filename']}"
     ```
   - Wait until the phone reports `Install from ADB complete`, then select **Reboot system now**.

---

### 3. Documentation & Links

- **Full Mobile Quickstart (中文)**: https://docs.taku.ai/zh/quickstart/mobile/
- **Full Mobile Quickstart (English)**: https://docs.taku.ai/quickstart/mobile/
- **Machine-readable Release Manifest**: [`tokay-{build}.json`](https://github.com/{repo}/releases/download/{tag}/tokay-{build}.json)
"""


def list_github_release_builds(repo: str) -> set[str]:
    proc = subprocess.run(
        ["gh", "release", "list", "-R", repo, "--limit", "50", "--json", "tagName"],
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        return set()
    builds: set[str] = set()
    for row in json.loads(proc.stdout or "[]"):
        tag = str(row.get("tagName", ""))
        m = re.match(r"^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)-(\d{14})$", tag)
        if m:
            builds.add(m.group(1))
    return builds


def discover_canary_candidates(
    releases_dir: Path,
    repo: str | None = None,
    canary_origin: str = CANONICAL_ORIGIN,
) -> list[dict[str, object]]:
    seed_builds: set[str] = set()
    for path in sorted(releases_dir.glob("tokay-*.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        build = data.get("build")
        if isinstance(build, str) and len(build) == 14 and build.isdigit():
            seed_builds.add(build)
    if repo:
        seed_builds.update(list_github_release_builds(repo))

    require(bool(seed_builds), f"No seed tokay-*.json manifests found in {releases_dir}")
    queue = sorted(seed_builds)
    queried: set[str] = set()
    discovered: dict[str, dict[str, object]] = {}

    while queue:
        src_build = queue.pop(0)
        if src_build in queried:
            continue
        queried.add(src_build)
        feed_url = f"{canary_origin.rstrip('/')}/os-update/v1/canary/tokay/UNOFFICIAL/{src_build}"
        raw = subprocess.check_output(["curl", "-fsSL", "--retry", "3", feed_url], text=True)
        items = json.loads(raw)
        if not isinstance(items, list):
            continue
        for item in items:
            if not isinstance(item, dict):
                continue
            files = item.get("files")
            if not isinstance(files, list) or not files:
                continue
            first = files[0]
            if not isinstance(first, dict):
                continue
            filename = str(first.get("filename", ""))
            match = FULL_FILENAME_RE.fullmatch(filename)
            if not match:
                continue
            target_build = match.group("build")
            version = match.group("version")
            if target_build not in queried:
                queue.append(target_build)
            if target_build not in discovered:
                discovered[target_build] = {
                    "version": version,
                    "build": target_build,
                    "filename": filename,
                    "url": str(first["url"]),
                    "bytes": int(first["size"]),
                    "sha256": str(first["sha256"]),
                    "sourceBuild": src_build,
                }
    return [discovered[k] for k in sorted(discovered)]


def package_release(
    *,
    full_url: str,
    full_bytes: int,
    full_sha256: str,
    version: str,
    output_dir: Path,
    repo: str,
    source_commit: str | None = None,
    source_build: str | None = None,
    local_full_zip: Path | None = None,
    preextracted_dir: Path | None = None,
    verify_full_sha256: bool = False,
    include_full_ota: bool = False,
) -> dict[str, object]:
    url_parts = validate_full_ota_url(full_url)
    build = url_parts["build"]
    require(
        re.fullmatch(r"(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)", version) is not None,
        f"Invalid semantic version: {version}",
    )
    require(SHA256_RE.fullmatch(full_sha256) is not None, f"Invalid full OTA SHA-256: {full_sha256}")
    if source_commit is not None:
        require(COMMIT_RE.fullmatch(source_commit) is not None, f"Invalid sourceCommit: {source_commit}")

    full_filename = f"TakuOS-{version}-tokay-{build}-full.zip"
    attach_full = include_full_ota and full_bytes <= GITHUB_RELEASE_ASSET_LIMIT_BYTES
    if include_full_ota and not attach_full:
        print(
            f"Note: {full_filename} ({full_bytes} bytes) exceeds GitHub 2 GiB asset limit; skipping full OTA attachment.",
            file=sys.stderr,
        )
    must_verify_full = verify_full_sha256 or attach_full
    staged_full_zip: Path | None = None

    if local_full_zip is not None:
        require(local_full_zip.is_file(), f"Missing local full OTA: {local_full_zip}")
        require(local_full_zip.stat().st_size == full_bytes, "Local full OTA size mismatch")
        if must_verify_full:
            require(sha256_file(local_full_zip) == full_sha256, "Local full OTA SHA-256 mismatch")
        if attach_full:
            output_dir.mkdir(parents=True, exist_ok=True)
            staged_full_zip = output_dir / full_filename
            if local_full_zip.resolve() != staged_full_zip.resolve():
                staged_full_zip.unlink(missing_ok=True)
                try:
                    os.link(local_full_zip, staged_full_zip)
                except OSError:
                    shutil.copy2(local_full_zip, staged_full_zip)
        source: ByteSource = FileByteSource(local_full_zip)
    else:
        if must_verify_full:
            tmp_dl = output_dir / full_filename
            output_dir.mkdir(parents=True, exist_ok=True)
            subprocess.run(["curl", "-fSL", "--retry", "3", "-o", str(tmp_dl), full_url], check=True)
            require(tmp_dl.stat().st_size == full_bytes, "Downloaded full OTA size mismatch")
            require(sha256_file(tmp_dl) == full_sha256, "Downloaded full OTA SHA-256 mismatch")
            if attach_full:
                staged_full_zip = tmp_dl
            source = FileByteSource(tmp_dl)
        else:
            source = HttpRangeByteSource(full_url, full_bytes)

    tail_len = min(65536, source.total_bytes)
    tail_offset = source.total_bytes - tail_len
    tail = source.read_range(tail_offset, source.total_bytes - 1)
    entries = parse_central_directory(tail, tail_offset, source.total_bytes)

    for required_entry in (
        "META-INF/com/android/metadata",
        "META-INF/com/android/otacert",
        "payload.bin",
        "payload_properties.txt",
    ):
        require(required_entry in entries, f"Full OTA missing required entry: {required_entry}")

    meta_entry = entries["META-INF/com/android/metadata"]
    require(meta_entry["method"] == 0, "Expected uncompressed META-INF/com/android/metadata")
    meta_off = read_local_entry_data_offset(source, meta_entry["localHeaderOffset"], "META-INF/com/android/metadata")
    meta_raw = source.read_range(meta_off, meta_off + meta_entry["uncompressedBytes"] - 1).decode("utf-8")
    meta_props = parse_ota_metadata(meta_raw)

    require(meta_props.get("ota-type") == "AB", "Expected A/B OTA package")
    require(meta_props.get("pre-device") == "tokay", "Expected tokay pre-device")
    require(
        meta_props.get("post-build-incremental") == build,
        f"Metadata post-build-incremental {meta_props.get('post-build-incremental')} != {build}",
    )
    sdk_level = int(meta_props.get("post-sdk-level", "37"))
    sec_patch = meta_props.get("post-security-patch-level", "")
    require(re.fullmatch(r"\d{4}-\d{2}-\d{2}", sec_patch) is not None, f"Invalid security patch: {sec_patch}")
    post_build = meta_props.get("post-build", "")
    fw_match = re.search(r":(?P<rel>\d+)/(?P<fw>[A-Z0-9.]+)/", post_build)
    require(fw_match is not None, f"Cannot parse firmware baseline from post-build: {post_build}")
    assert fw_match is not None
    android_release = fw_match.group("rel")
    firmware_baseline = fw_match.group("fw")

    payload_entry = entries["payload.bin"]
    require(payload_entry["method"] == 0, "Expected uncompressed ZIP_STORED payload.bin")
    payload_off = read_local_entry_data_offset(source, payload_entry["localHeaderOffset"], "payload.bin")
    header = source.read_range(payload_off, payload_off + 23)
    require(header[:4] == b"CrAU", "Invalid payload.bin magic")
    p_version, manifest_len, sig_len = struct.unpack(">QQI", header[4:])
    require(p_version == 2 and 0 < manifest_len <= 32 * 1024 * 1024, "Unsupported payload.bin header")
    manifest_bytes = source.read_range(payload_off + 24, payload_off + 24 + manifest_len - 1)
    payload_manifest_sha256 = sha256_bytes(manifest_bytes)
    parsed_manifest = parse_payload_manifest(manifest_bytes)

    partitions = parsed_manifest["partitions"]
    assert isinstance(partitions, dict)
    actual_parts = frozenset(partitions.keys())
    valid_full_contract = (actual_parts == FULL_TOKAY_AB_PARTITIONS and parsed_manifest["partialUpdate"] is False)
    valid_partial_contract = (actual_parts == PARTIAL_AB_PARTITIONS and parsed_manifest["partialUpdate"] is True)
    require(
        valid_full_contract or valid_partial_contract,
        "Refusing OTA: partition set does not match tokay full or preserved-firmware A/B contract",
    )

    blob_base = payload_off + 24 + manifest_len + sig_len
    images_dir = output_dir / f"images-{build}"
    images_dir.mkdir(parents=True, exist_ok=True)
    image_records: list[tuple[str, Path, int, str]] = []

    for part_name in RECOVERY_PARTITIONS:
        part_spec = partitions[part_name]
        assert isinstance(part_spec, dict)
        expected_sz = int(part_spec["size"])
        expected_sha = str(part_spec["sha256"])
        out_img = images_dir / f"{part_name}.img"

        if preextracted_dir is not None and (preextracted_dir / f"{part_name}.img").is_file():
            candidate = preextracted_dir / f"{part_name}.img"
            require(
                candidate.stat().st_size == expected_sz and sha256_file(candidate) == expected_sha,
                f"Pre-extracted {part_name}.img failed payload manifest hash/size verification",
            )
            if candidate.resolve() != out_img.resolve():
                out_img.write_bytes(candidate.read_bytes())
        else:
            raw_img = reconstruct_partition(
                part_name,
                part_spec,
                int(parsed_manifest["blockSize"]),
                blob_base,
                source,
            )
            out_img.write_bytes(raw_img)

        image_records.append((f"{part_name}.img", out_img, expected_sz, expected_sha))

    tag = f"v{version}-{build}"
    recovery_filename = f"TakuOS-{version}-tokay-{build}-recovery.zip"
    recovery_zip_path = output_dir / recovery_filename
    rec_bytes, rec_sha256 = write_deterministic_recovery_zip(
        recovery_zip_path,
        build,
        full_filename,
        full_sha256,
        payload_manifest_sha256,
        image_records,
    )

    recovery_url = f"https://github.com/{repo}/releases/download/{tag}/{recovery_filename}"
    release_manifest: dict[str, object] = {
        "schemaVersion": 1,
        "product": "TakuOS",
        "version": version,
        "build": build,
        "device": "tokay",
        "model": "Pixel 9",
        "androidRelease": android_release,
        "androidSdk": sdk_level,
        "platformSecurityPatch": sec_patch,
        "firmwareBaseline": firmware_baseline,
        "releaseType": "development-preview",
        "githubReleaseTag": tag,
        "fullOta": {
            "filename": full_filename,
            "url": full_url,
            "bytes": full_bytes,
            "sha256": full_sha256,
        },
        "recovery": {
            "filename": recovery_filename,
            "url": recovery_url,
            "bytes": rec_bytes,
            "sha256": rec_sha256,
        },
        "recoveryImages": [
            {"file": fname, "bytes": sz, "sha256": sha}
            for fname, _p, sz, sha in image_records
        ],
        "verification": {
            **({"nativeUpdateSourceBuild": source_build} if source_build else {}),
            "nativeUpdateTargetBuild": build,
            "nativeUpdateAndRebootVerified": False,
            "firstInstallFromStockVerified": False,
            "emulatorImagePublished": False,
            "recoverySignerMatchesOta": True,
        },
        **({"sourceCommit": source_commit} if source_commit else {}),
        "documentationUrl": "https://docs.taku.ai/zh/quickstart/mobile/",
        "licenseScope": "Original Taku source is Apache-2.0. Bundled third-party components retain their own terms.",
    }

    manifest_path = output_dir / f"tokay-{build}.json"
    manifest_path.write_text(json.dumps(release_manifest, indent=2) + "\n", encoding="utf-8")

    sha256sums_lines = [f"{sha}  {fname}" for fname, _p, _sz, sha in image_records]
    sha256sums_lines.append(f"{rec_sha256}  {recovery_filename}")
    sha256sums_lines.append(f"{full_sha256}  {full_filename}")
    sha256sums_path = output_dir / "SHA256SUMS.txt"
    sha256sums_path.write_text("\n".join(sha256sums_lines) + "\n", encoding="utf-8")

    notes_path = output_dir / f"RELEASE_NOTES-{build}.md"
    notes_path.write_text(render_release_notes(release_manifest, tag, repo), encoding="utf-8")

    return {
        "tag": tag,
        "title": f"TakuOS {version} (Pixel 9 / tokay · {build})",
        "manifest": release_manifest,
        "manifestPath": manifest_path,
        "recoveryZipPath": recovery_zip_path,
        "fullZipPath": staged_full_zip,
        "sha256SumsPath": sha256sums_path,
        "notesPath": notes_path,
    }


def _link_or_copy_alias(source_path: Path, alias_dir: Path, alias_name: str) -> Path:
    alias_dir.mkdir(parents=True, exist_ok=True)
    dest = alias_dir / alias_name
    dest.unlink(missing_ok=True)
    try:
        os.link(source_path, dest)
    except OSError:
        shutil.copy2(source_path, dest)
    return dest


def stage_release_assets(packaged: dict[str, object], *, prerelease: bool = False) -> list[Path]:
    recovery_path = Path(packaged["recoveryZipPath"])  # type: ignore[arg-type]
    manifest_path = Path(packaged["manifestPath"])  # type: ignore[arg-type]
    sha256_path = Path(packaged["sha256SumsPath"])  # type: ignore[arg-type]
    full_path = packaged.get("fullZipPath")

    assets: list[Path] = [recovery_path, manifest_path, sha256_path]
    if isinstance(full_path, Path) and full_path.is_file():
        assets.append(full_path)

    if not prerelease:
        alias_dir = recovery_path.parent / "evergreen-aliases"
        assets.append(_link_or_copy_alias(recovery_path, alias_dir, EVERGREEN_RECOVERY_FILENAME))
        assets.append(_link_or_copy_alias(manifest_path, alias_dir, EVERGREEN_MANIFEST_FILENAME))
        if isinstance(full_path, Path) and full_path.is_file():
            assets.append(_link_or_copy_alias(full_path, alias_dir, EVERGREEN_FULL_FILENAME))

    return assets


def github_release_exists(repo: str, tag: str) -> bool:
    proc = subprocess.run(
        ["gh", "release", "view", tag, "-R", repo, "--json", "tagName"],
        capture_output=True,
        text=True,
    )
    return proc.returncode == 0


def publish_to_github(
    *,
    repo: str,
    packaged: dict[str, object],
    prerelease: bool = False,
    clobber: bool = False,
) -> str:
    tag = str(packaged["tag"])
    title = str(packaged["title"])
    notes_path = Path(packaged["notesPath"])  # type: ignore[arg-type]
    assets = [str(p) for p in stage_release_assets(packaged, prerelease=prerelease)]

    if github_release_exists(repo, tag):
        if clobber:
            subprocess.run(
                ["gh", "release", "upload", tag, *assets, "--clobber", "-R", repo],
                check=True,
            )
            subprocess.run(
                ["gh", "release", "edit", tag, "-R", repo, "--title", title, "--notes-file", str(notes_path)],
                check=True,
            )
        view = subprocess.check_output(
            ["gh", "release", "view", tag, "-R", repo, "--json", "url"],
            text=True,
        )
        return str(json.loads(view)["url"])

    cmd = [
        "gh",
        "release",
        "create",
        tag,
        *assets,
        "-R",
        repo,
        "--title",
        title,
        "--notes-file",
        str(notes_path),
    ]
    if prerelease:
        cmd.append("--prerelease")
    else:
        cmd.append("--latest")
    out = subprocess.check_output(cmd, text=True).strip()
    return out


def write_repo_manifests(releases_dir: Path, manifest: dict[str, object]) -> tuple[Path, Path]:
    releases_dir.mkdir(parents=True, exist_ok=True)
    build = str(manifest["build"])
    versioned = releases_dir / f"tokay-{build}.json"
    latest = releases_dir / "latest.json"
    serialized = json.dumps(manifest, indent=2) + "\n"
    versioned.write_text(serialized, encoding="utf-8")

    existing_builds = sorted(
        p.stem.removeprefix("tokay-")
        for p in releases_dir.glob("tokay-*.json")
        if re.fullmatch(r"tokay-\d{14}\.json", p.name)
    )
    if not existing_builds or build >= existing_builds[-1]:
        latest.write_text(serialized, encoding="utf-8")
    return versioned, latest


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-canary", action="store_true", help="Discover unpublished full OTAs from worker.taku.ai canary feed")
    parser.add_argument("--full-url", help="Explicit immutable https://worker.taku.ai/.../full.zip URL")
    parser.add_argument("--full-bytes", type=int, help="Expected full.zip size in bytes")
    parser.add_argument("--full-sha256", help="Expected full.zip SHA-256 hex digest")
    parser.add_argument("--version", help="Product version X.Y.Z (e.g. 1.4.0)")
    parser.add_argument("--source-commit", help="Optional 40-char source commit SHA")
    parser.add_argument("--source-build", help="Optional 14-digit OTA source build number")
    parser.add_argument("--local-full-zip", type=Path, help="Optional local full.zip path instead of HTTP Range")
    parser.add_argument("--preextracted-dir", type=Path, help="Optional directory with pre-extracted .img files (verified against payload manifest)")
    parser.add_argument("--verify-full-sha256", action="store_true", help="Download/stream entire full.zip and verify its outer SHA-256")
    parser.add_argument("--include-full-ota", action="store_true", help="Verify and attach full OTA ZIP (+ TakuOS-tokay-full.zip alias) when within GitHub 2 GiB asset limit")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "out/rom-releases", help="Ignored output directory for generated ZIPs and notes")
    parser.add_argument("--releases-dir", type=Path, default=ROOT / "docs/releases", help="Tracked releases manifest directory")
    parser.add_argument("--write-repo-manifests", action="store_true", help="Write docs/releases/tokay-<build>.json and latest.json")
    parser.add_argument("--publish-github", action="store_true", help="Create or update the GitHub Release via gh CLI")
    parser.add_argument("--clobber", action="store_true", help="Overwrite assets/notes if the GitHub Release tag already exists")
    parser.add_argument("--prerelease", action="store_true", help="Mark newly created GitHub Release as prerelease")
    parser.add_argument("--repo", default="Taku-OS/taku-os", help="Target GitHub owner/repo")
    args = parser.parse_args(argv)

    output_dir = args.output_dir if args.output_dir.is_absolute() else (ROOT / args.output_dir)
    releases_dir = args.releases_dir if args.releases_dir.is_absolute() else (ROOT / args.releases_dir)

    targets: list[dict[str, object]] = []
    if args.from_canary:
        for cand in discover_canary_candidates(releases_dir, repo=args.repo if args.publish_github else None):
            tag = f"v{cand['version']}-{cand['build']}"
            if args.publish_github:
                if not args.clobber and github_release_exists(args.repo, tag):
                    continue
            elif not args.clobber and (releases_dir / f"tokay-{cand['build']}.json").is_file():
                continue
            targets.append(cand)
        if not targets:
            print(json.dumps({"status": "up-to-date", "published": []}))
            return 0
    else:
        require(
            args.full_url and args.full_bytes and args.full_sha256 and args.version,
            "Pass either --from-canary or all of --full-url, --full-bytes, --full-sha256, --version",
        )
        targets.append({
            "version": args.version,
            "url": args.full_url,
            "bytes": args.full_bytes,
            "sha256": args.full_sha256,
            "sourceBuild": args.source_build,
        })

    published_summaries: list[dict[str, object]] = []
    for item in targets:
        build_out = output_dir / str(item.get("build") or validate_full_ota_url(str(item["url"]))["build"])
        packaged = package_release(
            full_url=str(item["url"]),
            full_bytes=int(item["bytes"]),
            full_sha256=str(item["sha256"]),
            version=str(item["version"]),
            output_dir=build_out,
            repo=args.repo,
            source_commit=args.source_commit,
            source_build=str(item["sourceBuild"]) if item.get("sourceBuild") else None,
            local_full_zip=args.local_full_zip,
            preextracted_dir=args.preextracted_dir,
            verify_full_sha256=args.verify_full_sha256,
            include_full_ota=args.include_full_ota,
        )
        if args.write_repo_manifests:
            write_repo_manifests(releases_dir, packaged["manifest"])  # type: ignore[arg-type]
        release_url = None
        if args.publish_github:
            release_url = publish_to_github(
                repo=args.repo,
                packaged=packaged,
                prerelease=args.prerelease,
                clobber=args.clobber,
            )
        manifest = packaged["manifest"]
        assert isinstance(manifest, dict)
        published_summaries.append({
            "tag": packaged["tag"],
            "version": manifest["version"],
            "build": manifest["build"],
            "recoveryZip": str(packaged["recoveryZipPath"]),
            "recoveryBytes": manifest["recovery"]["bytes"],  # type: ignore[index]
            "recoverySha256": manifest["recovery"]["sha256"],  # type: ignore[index]
            "manifestPath": str(packaged["manifestPath"]),
            "releaseUrl": release_url,
        })

    print(json.dumps({"status": "ok", "published": published_summaries}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"{type(exc).__name__}: {exc}", file=sys.stderr)
        raise SystemExit(1)
