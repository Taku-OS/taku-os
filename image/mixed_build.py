#!/usr/bin/env python3
"""Audit complete tokay target files and wrap the Linux releasetools merge."""

import argparse
from collections import Counter
from fnmatch import fnmatchcase
import hashlib
import json
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import tempfile
import zipfile

LISTS = Path(__file__).resolve().parent / "mixed-build" / "tokay"
FRAMEWORK = "framework-item-list.txt"
VENDOR = "vendor-item-list.txt"
KEYS = "framework-misc-info-keys.txt"
SHARED = {
    "ab_partitions.txt", "apkcerts.txt", "apexkeys.txt", "dynamic_partitions_info.txt",
    "file_contexts.bin", "framework_file_contexts.bin", "vendor_file_contexts.bin",
    "misc_info.txt", "update_engine_config.txt",
}
SHARED = {f"META/{name}" for name in SHARED}
DERIVED = {
    "IMAGES/super_empty.img", "IMAGES/unsparse_super_empty.img", "IMAGES/userdata.img",
    "IMAGES/vbmeta.img", "IMAGES/vbmeta_system.img",
    "META/apex_info.pb", "META/care_map.pb", "META/vbmeta_digest.txt",
    "META/combined_sepolicy", "META/shareduid_violation_modules.json",
}
SYSTEM_CHAIN = {"system", "system_ext", "product", "system_dlkm"}
DYNAMIC = SYSTEM_CHAIN | {"vendor", "vendor_dlkm"}
ROOT_HASHES = {"dtbo", "pvmfw", "vendor_boot", "vendor_kernel_boot"}
ROOT_CHAIN = {"boot", "init_boot", "vbmeta_system", "vbmeta_vendor"}
LIMIT = 8006926336


def require(condition, message):
    if not condition:
        raise ValueError(message)


def manifest(name):
    lines = (LISTS / name).read_text().splitlines()
    require(all(lines) and len(set(lines)) == len(lines), f"invalid manifest: {name}")
    return lines


def classify(archive):
    framework, vendor = manifest(FRAMEWORK), manifest(VENDOR)
    owners = {}
    directories = set()
    seen = set()
    for entry in archive.infolist():
        name = entry.filename
        path = name.removesuffix("/")
        require(name not in seen and not set(path.split("/")) & {"", ".", ".."}
                and "\\" not in name, f"duplicate or invalid ZIP member: {name}")
        seen.add(name)
        if entry.is_dir():
            directories.add(path)
            continue
        candidates = [
            side for side, patterns in (("framework", framework), ("vendor", vendor))
            if any(fnmatchcase(name, pattern) for pattern in patterns)
        ]
        if name in DERIVED:
            candidates.append("derived")
        if name in SHARED:
            candidates.append("shared")
        require(candidates, f"unclassified member: {name}")
        require(len(candidates) == 1, f"ownership overlap: {name}")
        owners[name] = candidates[0]
    known_dirs = {"META", "IMAGES"}
    for pattern in framework + vendor:
        if pattern.endswith("/*"):
            known_dirs.add(pattern[:-2])
    for name in owners:
        known_dirs.update(str(parent) for parent in Path(name).parents)
    for name in directories:
        require(name not in owners and (name in known_dirs or any(
            fnmatchcase(name + "/", pattern) for pattern in framework + vendor
        )), f"unclassified directory: {name}")
    for partition in ("pvmfw", "system_dlkm"):
        protected = [f"IMAGES/{partition}.img"] + [
            name for name in owners if name.startswith(partition.upper() + "/")
        ]
        require(len(protected) > 1 and all(owners.get(name) == "vendor" for name in protected),
                f"{partition} must remain on the Vendor side")
    return owners, len(directories)


def read_small(archive, name, limit=1024 * 1024):
    require(archive.getinfo(name).file_size <= limit, f"oversized metadata: {name}")
    return archive.read(name)


def properties(archive, name):
    result = {}
    for line in read_small(archive, "META/" + name).decode().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        require(key and key not in result, f"invalid or repeated key in {name}: {key}")
        result[key] = value
    return result


def image_path(partition):
    return f"IMAGES/{partition}.img"


def expanded_size(archive, partition):
    name = image_path(partition)
    size = archive.getinfo(name).file_size
    with archive.open(name) as stream:
        header = stream.read(28)
    require(size > 0, f"empty image: {name}")
    if header[:4] != bytes.fromhex("3aff26ed"):
        return size
    require(len(header) == 28, f"truncated sparse header: {name}")
    _, major, _, file_header, chunk_header, block_size, blocks, chunks, _ = (
        struct.unpack("<I4H4I", header)
    )
    require(major == 1 and file_header >= 28 and chunk_header >= 12
            and block_size > 0 and block_size % 4 == 0 and blocks > 0
            and 0 < chunks <= (size - file_header) // chunk_header,
            f"invalid sparse header: {name}")
    return block_size * blocks


def dynamic_layout(archive, misc):
    layout = properties(archive, "dynamic_partitions_info.txt")
    require(layout.get("use_dynamic_partitions") == "true", "dynamic partitions disabled")
    require(set(layout["dynamic_partition_list"].split()) == DYNAMIC,
            "unexpected tokay dynamic partitions")
    sizes = {part: expanded_size(archive, part) for part in sorted(DYNAMIC)}
    groups = layout["super_partition_groups"].split()
    require(groups and len(set(groups)) == len(groups), "invalid dynamic groups")
    assigned = set()
    for group in groups:
        prefix = f"super_{group}"
        partitions = layout[f"{prefix}_partition_list"].split()
        members = set(partitions)
        require(members and len(members) == len(partitions)
                and members <= DYNAMIC and not members & assigned,
                f"invalid dynamic group membership: {group}")
        require(sum(sizes[part] for part in members) <= int(layout[f"{prefix}_group_size"]),
                f"expanded images exceed dynamic group size: {group}")
        assigned.update(members)
    require(assigned == DYNAMIC, "dynamic groups omit partitions")
    for key, value in layout.items():
        if key in misc:
            require(misc[key] == value or (
                key.endswith("partition_list") and sorted(misc[key].split()) == sorted(value.split())
            ), f"dynamic metadata disagrees on {key}")
    total = sum(sizes.values())
    limit = min(int(layout["super_partition_error_limit"]), LIMIT)
    require(total <= limit <= int(layout["super_partition_size"]),
            f"expanded dynamic size {total} exceeds error limit {limit} or super capacity")
    return {
        key: " ".join(sorted(value.split())) if key.endswith("partition_list") else value
        for key, value in layout.items()
    }, total, limit


def avb_descriptors(archive, partition):
    data = read_small(archive, image_path(partition))
    require(len(data) >= 256 and data[:4] == b"AVB0", f"invalid AVB header: {partition}")
    auth_size, aux_size = struct.unpack_from(">QQ", data, 12)
    algorithm = struct.unpack_from(">I", data, 28)[0]
    key_offset, key_size = struct.unpack_from(">QQ", data, 64)
    offset, length = struct.unpack_from(">QQ", data, 96)
    start = 256 + auth_size
    require(algorithm and auth_size and key_size and start + aux_size <= len(data)
            and key_offset + key_size <= aux_size and offset + length <= aux_size,
            f"invalid AVB metadata bounds: {partition}")
    public_key = data[start + key_offset:start + key_offset + key_size]
    descriptors = {}
    cursor, end = start + offset, start + offset + length
    while cursor < end:
        require(end - cursor >= 16, f"short AVB descriptor: {partition}")
        tag, size = struct.unpack_from(">QQ", data, cursor)
        cursor += 16
        require(size % 8 == 0 and cursor + size <= end, f"invalid AVB descriptor: {partition}")
        body = data[cursor:cursor + size]
        cursor += size
        if tag in (1, 2, 4):
            name_offset, length_offset = {1: (164, 88), 2: (116, 40), 4: (76, 4)}[tag]
            require(len(body) >= name_offset, f"short AVB partition descriptor: {partition}")
            name_size = struct.unpack_from(">I", body, length_offset)[0]
            require(0 < name_size <= len(body) - name_offset,
                    f"invalid AVB partition name: {partition}")
            name = body[name_offset:name_offset + name_size].decode("ascii")
            require((tag, name) not in descriptors, f"repeated AVB descriptor: {name}")
            descriptors[tag, name] = body
    return public_key, descriptors


def check_avb(archive, misc):
    require(misc["avb_enable"] == "true"
            and set(misc["avb_vbmeta_system"].split()) == SYSTEM_CHAIN
            and misc["avb_vbmeta_vendor"].split() == ["vendor"], "unexpected tokay AVB metadata")
    _, root = avb_descriptors(archive, "vbmeta")
    require(set(root) == ({(4, part) for part in ROOT_CHAIN}
                         | {(2, part) for part in ROOT_HASHES}
                         | {(1, "vendor_dlkm")}), "incorrect vbmeta root descriptors")
    locations = set()
    for part in ROOT_CHAIN:
        body = root[4, part]
        location, name_size, key_size = struct.unpack_from(">III", body)
        require(location == int(misc[f"avb_{part}_rollback_index_location"])
                and location > 0 and location not in locations
                and 0 < key_size <= len(body) - 76 - name_size,
                f"invalid AVB chain key or rollback location: {part}")
        locations.add(location)
        if part.startswith("vbmeta_"):
            key, descriptors = avb_descriptors(archive, part)
            expected = SYSTEM_CHAIN if part == "vbmeta_system" else {"vendor"}
            require(set(descriptors) == {(1, name) for name in expected},
                    f"incomplete AVB chain: {part}")
            require(key == body[76 + name_size:76 + name_size + key_size],
                    f"AVB chain key mismatch: {part}")


def audit(archive):
    owners, directories = classify(archive)
    misc = properties(archive, "misc_info.txt")
    require(misc["ab_update"] == "true" and misc["default_system_dev_certificate"],
            "missing A/B or system certificate metadata")
    ab = read_small(archive, "META/ab_partitions.txt").decode().split()
    radio = {Path(name).stem for name in owners if name.startswith("RADIO/")
             and name.endswith(".img")}
    expected = DYNAMIC | ROOT_CHAIN | ROOT_HASHES | {"vbmeta"}
    require(len(set(ab)) == len(ab) and set(ab) == expected | radio,
            "unexpected or repeated A/B partitions")
    for part in expected:
        require(image_path(part) in owners, f"missing A/B image: {part}")
    for name in manifest(FRAMEWORK) + manifest(VENDOR):
        if name.startswith("IMAGES/"):
            require(name in owners, f"missing required image: {name}")
    version = read_small(archive, "VENDOR/etc/selinux/plat_sepolicy_vers.txt").decode().strip()
    require(version and all(piece.isascii() and piece.isdecimal() for piece in version.split("."))
            and f"SYSTEM/etc/selinux/mapping/{version}.cil" in owners,
            f"missing framework SELinux mapping for vendor policy {version!r}")
    for part in ("SYSTEM_EXT", "PRODUCT"):
        prefix = f"{part}/etc/selinux/mapping/"
        require(not any(name.startswith(prefix) for name in owners)
                or f"{prefix}{version}.cil" in owners,
                f"missing {part} SELinux mapping for vendor policy {version}")
    layout, total, limit = dynamic_layout(archive, misc)
    check_avb(archive, misc)
    return {
        "members": len(owners), "directories": directories, "ownership": dict(Counter(owners.values())),
        "expanded_dynamic_bytes": total, "dynamic_error_limit_bytes": limit,
        "ab_partitions": sorted(ab), "selinux_version": version,
        "avb_images": ["vbmeta", "vbmeta_system", "vbmeta_vendor"],
    }, owners, layout, misc


def digest(archive, name):
    checksum = hashlib.sha256()
    with archive.open(name) as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.digest()


def merge_command(args, output):
    return [
        str(args.otatools.resolve() / "bin" / "merge_target_files"),
        "--framework-target-files", str(args.framework.resolve()),
        "--vendor-target-files", str(args.vendor.resolve()),
        "--framework-item-list", str(LISTS / FRAMEWORK),
        "--vendor-item-list", str(LISTS / VENDOR),
        "--framework-misc-info-keys", str(LISTS / KEYS),
        "--output-target-files", str(output),
    ]


def audit_merged(merged, framework, vendor, framework_audit, vendor_audit):
    _, _, _, framework_misc = framework_audit
    vendor_report, vendor_owners, vendor_layout, vendor_misc = vendor_audit
    frozen = [name for name, side in vendor_owners.items()
              if side == "vendor" and name.endswith(".img")]
    report, owners, layout, misc = audit(merged)
    require(report["ab_partitions"] == vendor_report["ab_partitions"],
            "merged A/B partitions differ from Vendor input")
    require(all(layout.get(key) == value for key, value in vendor_layout.items()),
            "merged dynamic layout differs from Vendor input")
    require(all(owners.get(name) == "vendor" for name in frozen),
            "merged package is missing frozen Vendor images")
    for name in frozen:
        require(digest(merged, name) == digest(vendor, name),
                f"frozen Vendor SHA-256 mismatch: {name}")
    keys = set(manifest(KEYS))
    for key in set(misc) | set(framework_misc) | set(vendor_misc):
        if key.startswith("avb_"):
            source = framework_misc if key in keys and key in framework_misc else vendor_misc
            require(misc.get(key) == source.get(key), f"merged AVB metadata changed: {key}")
    for partition in ("vbmeta", "vbmeta_system"):
        _, descriptors = avb_descriptors(merged, partition)
        _, framework_descriptors = avb_descriptors(framework, partition)
        _, vendor_descriptors = avb_descriptors(vendor, partition)
        for (tag, part), descriptor in descriptors.items():
            if tag == 4 and part == "vbmeta_system":
                continue  # The framework chain is regenerated and checked by audit.
            source = (vendor_descriptors if owners[image_path(part)] == "vendor"
                      else framework_descriptors)
            require(descriptor == source.get((tag, part)),
                    f"merged AVB descriptor changed: {partition}/{part}")
    report["verified_vendor_images"] = len(frozen)
    return report


def merge(args):
    output = args.output.resolve()
    if args.dry_run:
        print(shlex.join(merge_command(args, output)))
        return
    require(sys.platform.startswith("linux"), "merge requires Linux otatools")
    require(not output.exists(), f"output already exists: {output}")
    with (zipfile.ZipFile(args.framework) as framework, zipfile.ZipFile(args.vendor) as vendor,
          tempfile.TemporaryDirectory(prefix=".mixed-build-", dir=output.parent) as scratch):
        framework_audit = audit(framework)
        vendor_audit = audit(vendor)
        require(framework_audit[0]["ab_partitions"] == vendor_audit[0]["ab_partitions"],
                "inputs disagree on A/B partitions")
        staged = Path(scratch) / output.name
        subprocess.run(merge_command(args, staged), cwd=args.source_root or args.otatools, check=True)
        with zipfile.ZipFile(staged) as merged:
            report = audit_merged(merged, framework, vendor, framework_audit, vendor_audit)
        # Publish only after validation and never replace a concurrent writer's output.
        output.hardlink_to(staged)
    print(json.dumps(report, indent=2, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("audit")
    check.add_argument("target_files", type=Path)
    combine = commands.add_parser("merge")
    for name in ("framework", "vendor", "otatools", "output"):
        combine.add_argument("--" + name, type=Path, required=True)
    combine.add_argument("--source-root", type=Path,
                         help="Android source root for relative signing key paths")
    combine.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "audit":
            with zipfile.ZipFile(args.target_files) as archive:
                print(json.dumps(audit(archive)[0], indent=2, sort_keys=True))
        else:
            merge(args)
    except (OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile,
            subprocess.CalledProcessError) as error:
        parser.exit(1, f"mixed build: {error}\n")


if __name__ == "__main__":
    main()
