#!/usr/bin/env python3
"""Read an A/B payload's partition list without extracting or flashing it."""
import argparse
import json
import struct
import zipfile


def varint(data, offset):
    value = 0
    for shift in range(0, 70, 7):
        if offset >= len(data):
            raise ValueError("Truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, offset
    raise ValueError("Oversized protobuf varint")


def fields(data):
    offset = 0
    while offset < len(data):
        tag, offset = varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if not number:
            raise ValueError("Invalid protobuf field")
        if wire == 0:
            value, offset = varint(data, offset)
        elif wire in (1, 5):
            length = 8 if wire == 1 else 4
            value = data[offset:offset + length]
            offset += length
        elif wire == 2:
            length, offset = varint(data, offset)
            value = data[offset:offset + length]
            offset += length
        else:
            raise ValueError(f"Unsupported protobuf wire type {wire}")
        if offset > len(data):
            raise ValueError("Truncated protobuf field")
        yield number, wire, value


def inspect(path):
    with zipfile.ZipFile(path) as archive:
        with archive.open("payload.bin") as payload:
            header = payload.read(24)
            if len(header) != 24 or header[:4] != b"CrAU":
                raise ValueError("Not an A/B payload")
            version, length, signature_length = struct.unpack(">QQI", header[4:])
            if version != 2 or length > 32 * 1024 * 1024:
                raise ValueError("Unexpected payload format or manifest size")
            manifest = payload.read(length)
            if len(manifest) != length:
                raise ValueError("Truncated payload manifest")
        partitions = []
        partial = False
        for number, wire, value in fields(manifest):
            if number == 13 and wire == 2:
                name = None
                timestamp = None
                for subnumber, subwire, subvalue in fields(value):
                    if subnumber == 1 and subwire == 2:
                        name = subvalue.decode("utf-8")
                    if subnumber == 17 and subwire == 2:
                        timestamp = subvalue.decode("utf-8")
                if name is None:
                    raise ValueError("Partition has no name")
                partitions.append({"name": name, "version": timestamp})
            elif number == 16 and wire == 0:
                partial = bool(value)
        metadata = archive.read("META-INF/com/android/metadata").decode()
    return {"payloadVersion": version, "manifestBytes": length,
            "metadataSignatureBytes": signature_length, "partialUpdate": partial,
            "partitions": partitions, "otaMetadata": metadata}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ota")
    parser.add_argument("--require-preserved-firmware", action="store_true")
    args = parser.parse_args()
    result = inspect(args.ota)
    if args.require_preserved_firmware:
        expected = {"boot", "dtbo", "init_boot", "product", "system", "system_dlkm",
                    "system_ext", "vbmeta", "vbmeta_system", "vbmeta_vendor", "vendor",
                    "vendor_boot", "vendor_dlkm", "vendor_kernel_boot"}
        actual = {part["name"] for part in result["partitions"]}
        if (actual != expected or len(result["partitions"]) != len(expected)
                or not result["partialUpdate"]):
            raise SystemExit("Refusing package: partition set does not preserve firmware")
        if "pre-device=tokay" not in result["otaMetadata"].splitlines():
            raise SystemExit("Refusing package: target is not tokay")
        boot_version = next(part["version"] for part in result["partitions"]
                            if part["name"] == "boot")
        if not boot_version or not boot_version.isdigit():
            raise SystemExit("Refusing package: boot timestamp is missing")
    print(json.dumps(result, ensure_ascii=False, indent=2))
