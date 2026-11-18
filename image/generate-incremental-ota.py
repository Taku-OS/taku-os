#!/usr/bin/env python3
"""Generate and verify one exact Android 17 tokay A -> B OTA, without flashing.

Uses the current build's upstream releasetools; never creates a payload itself.
CLI verified against android_build 90d006387398ae6899145e6531a304500cfa0f70:
tools/releasetools/{ota_from_target_files,check_ota_package_signature}.py.
Full-image/AVB baseline acceptance and installed-device identity belong to the
host controller. This report does not assert AVB or data-preservation acceptance.
"""
import argparse
import datetime
import hashlib
import io
import json
import os
import pathlib
import re
import shutil
import ssl
import struct
import subprocess
import zipfile


SCHEMA = "taku.incremental-ota/1"
HOST_TOOLS = ("ota_from_target_files", "check_ota_package_signature", "delta_generator", "brillo_update_payload")
TOOL_SOURCES = (
    "build/make/tools/releasetools/ota_from_target_files.py",
    "build/make/tools/releasetools/check_ota_package_signature.py",
    "build/make/tools/releasetools/ota_utils.py",
    "build/make/tools/releasetools/common.py",
    "build/make/tools/releasetools/payload_signer.py",
    "build/soong/ui/build/config.go",
    "build/soong/shared/env.go",
    "system/update_engine/update_metadata.proto",
    "system/update_engine/payload_generator/payload_signer.cc",
)
IDENTITY_PROPS = ("ro.build.version.incremental", "ro.build.fingerprint", "ro.build.date.utc",
                  "ro.build.version.security_patch")


class PackageError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise PackageError(message)


def digest(path):
    value = hashlib.sha256()
    with pathlib.Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def file_record(path):
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": digest(path)}


def absolute(value):
    require(value.is_absolute(), "Expected an absolute path: " + str(value))
    return value.resolve()


def source_path(root, value):
    path = pathlib.Path(value)
    path = (path if path.is_absolute() else root / path).resolve()
    require(path.is_relative_to(root), "Signing/tool path escapes source-root: " + str(value))
    return path


def properties(data):
    result = {}
    for line in data.decode("utf-8").splitlines():
        if not line or line.startswith("#") or line.startswith("import "):
            continue
        require("=" in line, "Invalid property line")
        key, value = line.split("=", 1)
        require(key and key not in result, "Duplicate or empty property: " + key)
        result[key] = value
    return result


def inspect_target(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate target-files entries")
        system = properties(archive.read("SYSTEM/build.prop"))
        product = properties(archive.read("PRODUCT/etc/build.prop"))
        vendor = properties(archive.read("VENDOR/build.prop"))
        require(system.get("ro.product.system.device") in ("generic", "tokay") and
                product.get("ro.product.product.device") == vendor.get("ro.product.vendor.device") == "tokay",
                "Expected tokay target-files")
        require(system.get("ro.build.type") == product.get("ro.product.build.type") == "userdebug",
                "Expected userdebug target-files")
        require(system.get("ro.build.version.release") == "17" and system.get("ro.build.version.sdk") == "37",
                "Expected Android 17 / API 37 target-files")
        for key in IDENTITY_PROPS:
            require(system.get(key), "Missing build identity: " + key)
            require(key not in product or product[key] == system[key], "Product/system identity differs: " + key)
        for key in ("ro.build.version.incremental", "ro.build.date.utc"):
            require(re.fullmatch(r"[0-9]+", system[key]) is not None and int(system[key]) > 0,
                    "Expected positive numeric build identity: " + key)
        patch = system["ro.build.version.security_patch"]
        require(datetime.date.fromisoformat(patch).isoformat() == patch, "Invalid security patch date")
        identity = {"androidRelease": "17", "androidSdk": 37, "productDevice": "tokay", "buildType": "userdebug",
                    "buildNumber": system["ro.build.version.incremental"], "buildFingerprint": system["ro.build.fingerprint"],
                    "buildTimestamp": int(system["ro.build.date.utc"]), "platformSecurityPatch": patch}
        misc_bytes = archive.read("META/misc_info.txt")
        misc = properties(misc_bytes)
        require(misc.get("ab_update") == "true", "Expected A/B target-files")
        require(misc.get("avb_enable") == misc.get("avb_building_vbmeta_image") == "true", "Expected AVB baseline")
        require(misc.get("default_system_dev_certificate") and misc.get("avb_vbmeta_key_path") and
                misc.get("avb_vbmeta_algorithm") not in (None, "", "NONE"), "Missing signing declarations")
        partitions_bytes = archive.read("META/ab_partitions.txt")
        partitions = partitions_bytes.decode("ascii").split()
        require(partitions and len(partitions) == len(set(partitions)) and
                all(re.fullmatch(r"[A-Za-z0-9_]+", name) for name in partitions), "Invalid A/B partition list")
        require("userdata" not in partitions and "metadata" not in partitions, "Refusing user-data partition update")
        for name in partitions:
            require(any(prefix + name + ".img" in names for prefix in ("IMAGES/", "RADIO/")),
                    "Missing A/B partition image: " + name)
        trusted = set()
        for name in sorted(n for n in names if n.endswith("/etc/security/otacerts.zip")):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as certificates:
                require(len(certificates.namelist()) == len(set(certificates.namelist())), "Duplicate trust certificate entries")
                for cert_name in certificates.namelist():
                    if not cert_name.endswith("/"):
                        der = ssl.PEM_cert_to_DER_cert(certificates.read(cert_name).decode("ascii"))
                        trusted.add(hashlib.sha256(der).hexdigest())
        require(trusted, "Missing image OTA trust certificates")
        signing = {key: value for key, value in misc.items() if key == "default_system_dev_certificate" or
                   (key.startswith("avb_") and key.endswith(("_key_path", "_algorithm")))}
        layout = {key: misc.get(key) for key in ("use_dynamic_partitions", "dynamic_partition_retrofit", "virtual_ab")}
        return {**file_record(path), "identity": identity, "abPartitions": sorted(partitions),
                "trustedOtaCertificateDerSha256": sorted(trusted), "signingDeclarations": signing, "layout": layout,
                "metadataSha256": {"META/misc_info.txt": hashlib.sha256(misc_bytes).hexdigest(),
                                   "META/ab_partitions.txt": hashlib.sha256(partitions_bytes).hexdigest()}}


def check_pair(old, new):
    require(old["sha256"] != new["sha256"], "Source and target are the same target-files")
    a, b = old["identity"], new["identity"]
    require(int(b["buildNumber"]) > int(a["buildNumber"]) and b["buildTimestamp"] > a["buildTimestamp"],
            "Build number and timestamp must strictly increase; no downgrade or same-build update")
    require(b["platformSecurityPatch"] >= a["platformSecurityPatch"], "Security patch downgrade is prohibited")
    for key in ("abPartitions", "trustedOtaCertificateDerSha256", "signingDeclarations", "layout"):
        require(old[key] == new[key], "Source/target compatibility differs: " + key)


def tools_for(root):
    host = root / "out/host/linux-x86/bin"
    # Soong records ConfigJavaEnvironment's actual selection here. Reading it
    # avoids running envsetup/Soong (which writes out/) or guessing JDK 21 vs 25.
    build_environment = source_path(root, "out/soong/soong.environment.available")
    entries = json.loads(build_environment.read_text())
    require(isinstance(entries, list), "Invalid captured Soong environment")
    homes = [entry.get("Value") for entry in entries if isinstance(entry, dict) and entry.get("Key") == "ANDROID_JAVA_HOME"]
    require(len(homes) == 1 and isinstance(homes[0], str) and homes[0], "Missing/duplicate captured ANDROID_JAVA_HOME")
    java_home = source_path(root, homes[0])
    require(java_home.is_relative_to(root / "prebuilts/jdk") and java_home.name == "linux-x86",
            "Captured build JDK must be a source-tree Linux prebuilt")
    java = source_path(root, java_home / "bin/java")
    require(java.is_file() and os.access(java, os.X_OK), "Missing current-build Java executable")
    java_release = source_path(root, java_home / "release")
    env = dict(os.environ, PATH=os.pathsep.join((str(host), str(java_home / "bin"), os.defpath)),
               JAVA_HOME=str(java_home), ANDROID_JAVA_HOME=str(java_home), PYTHONDONTWRITEBYTECODE="1")
    for key in ("PYTHONOPTIMIZE", "PYTHONPATH", "PYTHONHOME", "LD_PRELOAD", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)
    tools = {}
    for name in HOST_TOOLS:
        path = source_path(root, host / name)
        require(path.is_file() and os.access(path, os.X_OK), "Missing current-build host tool: " + name)
        tools[name] = {"path": str(host / name), "sha256": digest(path)}
    openssl = shutil.which("openssl", path=env["PATH"])
    require(openssl is not None, "Missing openssl")
    tools["openssl"] = {"path": openssl, "sha256": digest(openssl)}
    tools["java"] = {"path": str(java), "sha256": digest(java), "release": file_record(java_release),
                     "buildEnvironment": file_record(build_environment)}
    tools["signapkJar"] = file_record(source_path(root, "out/host/linux-x86/framework/signapk.jar"))
    sources = [{"path": name, "sha256": digest(source_path(root, name))} for name in TOOL_SOURCES]
    return tools, sources, env


def varint(data, cursor):
    value = 0
    for shift in range(0, 70, 7):
        require(cursor < len(data), "Truncated payload protobuf varint")
        byte = data[cursor]
        cursor += 1
        value |= (byte & 127) << shift
        if not byte & 128:
            return value, cursor
    raise PackageError("Oversized payload protobuf varint")


def fields(data):
    """Read only fields needed for format checks; never generate payload data."""
    cursor = 0
    while cursor < len(data):
        tag, cursor = varint(data, cursor)
        number, wire = tag >> 3, tag & 7
        require(number > 0, "Invalid payload protobuf field")
        if wire == 0:
            value, cursor = varint(data, cursor)
        elif wire in (1, 2, 5):
            if wire == 2:
                length, cursor = varint(data, cursor)
            else:
                length = 8 if wire == 1 else 4
            require(cursor + length <= len(data), "Truncated payload protobuf field")
            value = data[cursor:cursor + length]
            cursor += length
        else:
            raise PackageError("Unsupported payload protobuf wire type")
        yield number, wire, value


def inspect_package(path, old, new):
    a, b = old["identity"], new["identity"]
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate incremental OTA entries")
        metadata = properties(archive.read("META-INF/com/android/metadata"))
        expected = {"ota-type": "AB", "pre-device": "tokay", "pre-build": a["buildFingerprint"],
                    "pre-build-incremental": a["buildNumber"], "post-build": b["buildFingerprint"],
                    "post-build-incremental": b["buildNumber"], "post-sdk-level": "37",
                    "post-timestamp": str(b["buildTimestamp"]), "post-security-patch-level": b["platformSecurityPatch"]}
        for key, value in expected.items():
            require(metadata.get(key) == value, "Incremental OTA identity differs: " + key)
        require(not {"ota-wipe", "ota-downgrade", "spl-downgrade", "ota-retrofit-dynamic-partitions"}.intersection(metadata),
                "Refusing wipe/downgrade/retrofit OTA metadata")
        payload_properties = properties(archive.read("payload_properties.txt"))
        require(all(payload_properties.get(key, "0") == "0" for key in ("POWERWASH", "SPL_DOWNGRADE")),
                "Refusing wipe/downgrade payload properties")
        with archive.open("payload.bin") as stream:
            header = stream.read(24)
            require(len(header) == 24, "Truncated payload header")
            magic, version, length, signature_bytes = struct.unpack(">4sQQI", header)
            require(magic == b"CrAU" and version == 2 and 0 < length <= 32 * 1024 * 1024 and signature_bytes > 0,
                    "Missing signed v2 payload metadata")
            require(24 + length + signature_bytes < archive.getinfo("payload.bin").file_size, "Payload metadata exceeds payload")
            manifest = stream.read(length)
            require(len(manifest) == length, "Truncated payload manifest")
        scalar, partitions = {}, []
        for number, wire, value in fields(manifest):
            if number in (4, 5, 12, 14, 16):
                require(wire == 0 and number not in scalar, "Invalid/duplicate payload scalar")
                scalar[number] = value
            elif number == 13:
                require(wire == 2, "Invalid payload partition field")
                names = [value.decode("ascii") for sub, subwire, value in fields(value) if sub == 1 and subwire == 2]
                require(len(names) == 1, "Invalid payload partition name")
                partitions += names
        require(scalar.get(12, 0) > 0, "Expected an incremental payload, not a full payload")
        require(scalar.get(16, 0) == 0, "Partial OTA is outside the initial A-to-B contract")
        require(scalar.get(14) == b["buildTimestamp"], "Payload timestamp differs from target")
        require(scalar.get(4, -1) >= 0 and scalar.get(5, 0) > 0, "Missing payload signature manifest fields")
        require(len(partitions) == len(set(partitions)) and sorted(partitions) == new["abPartitions"], "Payload A/B partitions differ")
        payload = {"version": version, "minorVersion": scalar[12], "manifestBytes": length,
                   "metadataSignatureBytes": signature_bytes, "partialUpdate": False,
                   "maxTimestamp": scalar[14], "partitions": sorted(partitions)}
    return metadata, payload, payload_properties


def generate(source_root, source_target, target_target, output_dir):
    root, source_target, target_target, output = map(absolute, (source_root, source_target, target_target, output_dir))
    require(root.is_dir() and source_target.is_file() and target_target.is_file(), "Missing source-root or target-files input")
    require(source_target != target_target, "Source and target target-files paths must differ")
    require(not output.is_relative_to(root), "Output directory must be outside the read-only source-root")
    # A directory is a single attempt. Never reuse evidence or overwrite a package.
    output.mkdir()
    commands = []
    binding = {"sourceTargetFilesPath": str(source_target), "targetTargetFilesPath": str(target_target)}
    try:
        old, new = inspect_target(source_target), inspect_target(target_target)
        check_pair(old, new)
        binding = {"sourceTargetFiles": old, "targetTargetFiles": new}
        tools, tool_sources, env = tools_for(root)
        prefix = source_path(root, old["signingDeclarations"]["default_system_dev_certificate"])
        certificate = source_path(root, str(prefix) + ".x509.pem")
        private_key = source_path(root, str(prefix) + ".pk8")
        certificate_hash = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()
        require(certificate_hash in old["trustedOtaCertificateDerSha256"], "Signing certificate is not trusted by both images")
        signing_inputs = {"certificate": file_record(certificate), "privateKey": file_record(private_key),
                          "certificateDerSha256": certificate_hash}
        binding.update({"tools": tools, "toolSources": tool_sources, "signingInputs": signing_inputs})
        work = output / "work"
        work.mkdir()
        env["TMPDIR"] = str(work)
        partial = output / "incremental-ota.partial.zip"

        def run(operation, tool, *args):
            argv = [tools[tool]["path"], *map(str, args)]
            log = output / (operation + ".log")
            with log.open("xb") as stream:
                process = subprocess.run(argv, cwd=root, env=env, stdin=subprocess.DEVNULL,
                                         stdout=stream, stderr=subprocess.STDOUT, check=False)
            commands.append({"operation": operation, "argv": argv, "exitCode": process.returncode,
                             "log": log.name, "logSha256": digest(log)})
            require(process.returncode == 0, "Upstream command failed: " + operation)
            return log

        run("generate", "ota_from_target_files", "-i", source_target, "-k", prefix,
            "--java_path", tools["java"]["path"], target_target, partial)
        metadata, payload, payload_properties = inspect_package(partial, old, new)
        package_before = file_record(partial)
        signature_log = run("verify-signatures", "check_ota_package_signature", certificate, partial)
        confirmation = signature_log.read_text(errors="replace")
        require("Whole package signature VERIFIED" in confirmation and "Payload signatures VERIFIED" in confirmation,
                "Upstream checker did not confirm both OTA and payload signatures")
        require(file_record(partial) == package_before, "Generated OTA changed during signature verification")
        require(file_record(source_target) == {k: old[k] for k in ("path", "bytes", "sha256")} and
                file_record(target_target) == {k: new[k] for k in ("path", "bytes", "sha256")},
                "Target-files input changed during generation")
        final_tools, final_sources, _ = tools_for(root)
        require(tools == final_tools and tool_sources == final_sources, "Upstream tools changed during generation")
        require(file_record(certificate) == signing_inputs["certificate"] and file_record(private_key) == signing_inputs["privateKey"],
                "Signing inputs changed during generation")
        shutil.rmtree(work)
        package = output / "incremental-ota.zip"
        # Exclusive link publishes without an overwrite race, on the same filesystem.
        os.link(partial, package)
        partial.unlink()
        report = {"schema": SCHEMA, "result": "verified", "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                  **binding, "generatorSha256": digest(__file__), "commands": commands, "package": file_record(package),
                  "metadata": metadata, "payload": payload, "payloadProperties": payload_properties,
                  "checks": {"exactSourceTargetIdentityVerified": True, "noWipeOrDowngrade": True,
                             "otaPackageSignatureVerified": True, "payloadSignatureVerified": True},
                  "physicalDeviceVerified": False, "dataPreservationVerified": False, "avbSignatureVerified": False}
        report_path = output / "incremental-ota.json"
        pending_report = output / "incremental-ota.json.partial"
        with pending_report.open("x") as stream:
            json.dump(report, stream, indent=2)
            stream.write("\n")
        os.link(pending_report, report_path)
        pending_report.unlink()
        return {"path": str(report_path), "sha256": digest(report_path), "package": report["package"]}
    except Exception as error:
        with (output / "failure.json").open("x") as stream:
            json.dump({"schema": SCHEMA, "result": "failed", "error": str(error), **binding,
                       "commands": commands, "physicalDeviceVerified": False}, stream, indent=2)
            stream.write("\n")
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("source-root", "source-target-files", "target-target-files", "output-dir"):
        parser.add_argument("--" + name, required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        print(json.dumps(generate(args.source_root, args.source_target_files, args.target_target_files, args.output_dir)))
    except (PackageError, OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile) as error:
        parser.exit(1, "Incremental OTA generation failed: " + str(error) + "\n")


if __name__ == "__main__":
    main()
