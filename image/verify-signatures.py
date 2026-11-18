#!/usr/bin/env python3
"""Verify an already accepted full-image candidate using its own Linux host tools.

This supplements, and never edits, status.json or baseline-receipt.json. AVB
parsing below establishes graph/key boundaries; upstream avbtool performs crypto.
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
import sys
import tempfile
import uuid
import zipfile


SCHEMA = "taku.image-signatures/1"
CHECKS = ("otaPackageSignatureVerified", "payloadSignatureVerified", "avbSignatureVerified")
HOST_TOOLS = ("check_ota_package_signature", "delta_generator", "avbtool", "avb_openssl", "fec")
TOOL_SOURCES = ("build/make/tools/releasetools/check_ota_package_signature.py",
                "system/update_engine/payload_generator/payload_signer.cc", "external/avb/avbtool.py")


class VerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise VerificationError(message)


def digest(path):
    result = hashlib.sha256()
    with pathlib.Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def read_json(path):
    value = json.loads(pathlib.Path(path).read_text())
    require(isinstance(value, dict), "Expected JSON object: " + str(path))
    return value


def contained(root, value):
    path = pathlib.Path(value)
    path = (path if path.is_absolute() else root / path).resolve()
    require(path.is_relative_to(root), "Path escapes expected root: " + str(value))
    return path


def properties(data):
    result = {}
    for line in data.decode("utf-8").splitlines():
        if line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        require(key not in result, "Duplicate property: " + key)
        result[key] = value
    return result


def record_fields(record):
    return {key: record[key] for key in ("path", "bytes", "sha256")}


def load_candidate(source, results):
    status = read_json(results / "status.json")
    receipt_path = results / "baseline-receipt.json"
    receipt_hash = digest(receipt_path)
    require(status.get("romBuilt") is True and status.get("phase") == "artifact-verified",
            "Full-image artifact acceptance has not completed")
    require(status.get("baselineReceipt", {}).get("sha256") == receipt_hash,
            "Baseline receipt hash differs from status")
    receipt = read_json(receipt_path)
    require(receipt.get("schema") == "taku.image-baseline/1", "Unsupported baseline receipt")
    run_hash = digest(results / "run-input.json")
    require(receipt.get("runInputSha256") == run_hash, "Build run-input hash differs from receipt")
    inputs = read_json(results / "run-input.json")
    identity = receipt["buildIdentity"]
    require(status.get("identity") == identity, "Status/receipt build identity differs")
    require(str(inputs["buildNumber"]) == identity["buildNumber"], "Build number differs from receipt")
    require(receipt["buildInput"] == {key: inputs.get(key) for key in
                                    ("buildNumber", "snapshotDigest", "sourceCommit")},
            "Receipt build inputs differ")
    require(isinstance(receipt.get("fullOtas"), list) and receipt["fullOtas"], "No full OTA candidates")
    artifacts = [record_fields(receipt["targetFiles"])] + [record_fields(r) for r in receipt["fullOtas"]]
    require(len({r["path"] for r in artifacts}) == len(artifacts), "Duplicate candidate artifact paths")
    require(sorted(artifacts, key=lambda r: r["path"]) ==
            sorted(status["artifacts"], key=lambda r: r["path"]), "Status/receipt artifacts differ")
    for record in artifacts:
        path = contained(source, record["path"])
        require(path.is_file() and path.stat().st_size == record["bytes"] and digest(path) == record["sha256"],
                "Candidate artifact hash/size mismatch: " + record["path"])
    return receipt, {"baselineReceiptSha256": receipt_hash, "runInputSha256": run_hash,
                     "buildIdentity": identity, "artifacts": artifacts}


def tools_for(source):
    host = source / "out/host/linux-x86/bin"
    env = dict(os.environ)
    env["PATH"] = str(host) + os.pathsep + os.defpath
    for name in ("PYTHONOPTIMIZE", "PYTHONPATH", "PYTHONHOME", "LD_PRELOAD"):
        env.pop(name, None)
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    tools = {}
    for name in HOST_TOOLS:
        path = contained(source, host / name)
        require(path.is_file() and os.access(path, os.X_OK), "Missing current-build host tool: " + name)
        # Keep the installed path for tools whose launcher locates adjacent libs.
        tools[name] = {"path": str(host / name), "sha256": digest(path)}
    openssl = shutil.which("openssl", path=env["PATH"])
    require(openssl is not None, "Missing openssl")
    tools["openssl"] = {"path": openssl, "sha256": digest(openssl)}
    sources = [{"path": name, "sha256": digest(contained(source, name))} for name in TOOL_SOURCES]
    return tools, sources, env


def signing_inputs_for(source, receipt):
    with zipfile.ZipFile(contained(source, receipt["targetFiles"]["path"])) as archive:
        misc = properties(archive.read("META/misc_info.txt"))
    certificate = contained(source, misc["default_system_dev_certificate"] + ".x509.pem")
    root_key = contained(source, misc["avb_vbmeta_key_path"])
    # Hash the existing input file without copying or emitting private material.
    return {"certificate": {"sourcePath": str(certificate), "sha256": digest(certificate),
                            "derSha256": hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()},
            "avbRootKey": {"sourcePath": str(root_key), "sha256": digest(root_key)}}


class Commands:
    def __init__(self, directory, attempt, tools, environment, temporary):
        self.directory, self.attempt = directory, attempt
        self.tools, self.environment, self.temporary = tools, environment, temporary
        self.records = []

    def run(self, operation, scope, tool, *args):
        command = [self.tools[tool]["path"], *map(str, args)]
        relative_log = self.attempt / (f"{len(self.records):03d}-{tool}.log")
        log = self.directory / relative_log
        # Upstream checker extracts payloads internally. Keep those temporary too.
        env = dict(self.environment, TMPDIR=str(self.temporary))
        with log.open("wb") as output:
            completed = subprocess.run(command, cwd=self.temporary, env=env,
                                       stdout=output, stderr=subprocess.STDOUT, check=False)
        self.records.append({"operation": operation, "scope": scope, "tool": tool,
                             "argv": command, "exitCode": completed.returncode,
                             "log": str(relative_log), "logSha256": digest(log)})
        require(completed.returncode == 0, "Verification command failed: " + tool + " (" + str(relative_log) + ")")
        return log.read_text(errors="replace")


def extract_target(target, temporary, receipt):
    with zipfile.ZipFile(target) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate target-files entries")
        misc_bytes = archive.read("META/misc_info.txt")
        require(hashlib.sha256(misc_bytes).hexdigest() == receipt["targetFilesMetadataSha256"]["META/misc_info.txt"],
                "Target-files signing metadata differs from receipt")
        misc = properties(misc_bytes)
        require(misc.get("avb_enable") == "true" and misc.get("avb_building_vbmeta_image") == "true",
                "Missing AVB full-image baseline")
        for entry in archive.infolist():
            parts = pathlib.PurePosixPath(entry.filename).parts
            if len(parts) != 2 or parts[0] not in ("IMAGES", "RADIO") or entry.is_dir():
                continue
            require(re.fullmatch(r"[A-Za-z0-9_.-]+", parts[1]) is not None and parts[1] not in (".", ".."),
                    "Invalid image archive path")
            path = temporary / parts[0] / parts[1]
            path.parent.mkdir(exist_ok=True)
            with archive.open(entry) as source, path.open("wb") as output:
                shutil.copyfileobj(source, output, 8 * 1024 * 1024)
        trusted = []
        for name in sorted(n for n in archive.namelist() if n.endswith("/etc/security/otacerts.zip")):
            with zipfile.ZipFile(io.BytesIO(archive.read(name))) as certificates:
                for cert_name in sorted(n for n in certificates.namelist() if not n.endswith("/")):
                    der = ssl.PEM_cert_to_DER_cert(certificates.read(cert_name).decode("ascii"))
                    trusted.append({"archive": name, "certificate": cert_name,
                                    "derSha256": hashlib.sha256(der).hexdigest()})
        require(trusted == receipt["signingEvidence"]["trustedOtaCertificates"],
                "Image trust certificates differ from receipt")
    images = temporary / "IMAGES"
    radio = temporary / "RADIO"
    if radio.exists():
        for path in radio.iterdir():
            if not (images / path.name).exists():
                (images / path.name).symlink_to(path)
    return misc, trusted, images


def check_otas(source, receipt, certificate, commands):
    groups = {}
    for record in receipt["fullOtas"]:
        groups.setdefault(record["sha256"], []).append(record)
    verified = []
    for checksum, records in groups.items():
        path = contained(source, records[0]["path"])
        with zipfile.ZipFile(path) as archive:
            require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate OTA entries")
            metadata = properties(archive.read("META-INF/com/android/metadata"))
            require(metadata.get("ota-type") == "AB" and "pre-build" not in metadata and
                    "pre-build-incremental" not in metadata, "Expected a full A/B OTA")
            for record in records:
                require(all(metadata.get(key) == value for key, value in record["metadata"].items()),
                        "OTA metadata differs from baseline receipt")
            with archive.open("payload.bin") as stream:
                header = stream.read(24)
            require(len(header) == 24, "Truncated payload header")
            magic, version, metadata_size, signature_size = struct.unpack(">4sQQI", header)
            require(magic == b"CrAU" and version == 2 and metadata_size > 0 and signature_size > 0,
                    "Missing signed v2 payload metadata")
            require(24 + metadata_size + signature_size < archive.getinfo("payload.bin").file_size,
                    "Payload metadata extends beyond payload")
        output = commands.run("ota", checksum, "check_ota_package_signature", certificate, path)
        require("Whole package signature VERIFIED" in output and "Payload signatures VERIFIED" in output,
                "OTA checker did not confirm both signature layers")
        verified.append({"sha256": checksum, "bytes": records[0]["bytes"],
                         "paths": [r["path"] for r in records], "metadataSignatureBytes": signature_size})
    return verified


def avb_node(path):
    """Read bounded AVB metadata, not image contents or cryptographic validity."""
    with path.open("rb") as stream:
        size = path.stat().st_size
        require(size >= 256, "Truncated AVB image: " + path.name)
        require(stream.read(4) != b"\x3a\xff\x26\xed", "Sparse chain VBMeta image is unsupported: " + path.name)
        offset, limit = 0, size
        # Match avbtool: a valid footer wins even when the image also begins
        # with AVB0. Otherwise we could bind one key and verify another blob.
        stream.seek(-64, os.SEEK_END)
        footer = stream.read(64)
        if footer[:4] == b"AVBf":
            offset, limit = struct.unpack_from(">QQ", footer, 20)
            require(offset + limit <= size - 64 and limit >= 256, "Invalid AVB footer extent")
        stream.seek(offset)
        header = stream.read(256)
        require(header[:4] == b"AVB0", "Missing VBMeta header")
        auth, aux, algorithm = struct.unpack_from(">QQI", header, 12)
        fields = struct.unpack_from(">11Q", header, 32)
        signature_offset, signature_size = fields[2:4]
        key_offset, key_size = fields[4:6]
        descriptors_offset, descriptors_size = fields[8:10]
        require(algorithm != 0 and key_size > 0 and signature_size > 0, "Unsigned AVB node: " + path.name)
        require(256 + auth + aux <= limit and aux <= 16 * 1024 * 1024 and
                signature_offset + signature_size <= auth and key_offset + key_size <= aux and
                descriptors_offset + descriptors_size <= aux, "Invalid VBMeta data bounds")
        stream.seek(offset + 256 + auth)
        auxiliary = stream.read(aux)
        require(len(auxiliary) == aux, "Truncated VBMeta auxiliary block")
    public_key = auxiliary[key_offset:key_offset + key_size]
    require(public_key and any(public_key), "Empty AVB public key: " + path.name)
    descriptors = auxiliary[descriptors_offset:descriptors_offset + descriptors_size]
    chains, cursor = [], 0
    while cursor < len(descriptors):
        require(cursor + 16 <= len(descriptors), "Truncated AVB descriptor")
        tag, following = struct.unpack_from(">QQ", descriptors, cursor)
        end = cursor + 16 + following
        require(end <= len(descriptors) and following % 8 == 0, "Invalid AVB descriptor bounds")
        if tag == 4:
            require(following >= 76, "Truncated chain descriptor")
            rollback_slot, name_size, chain_key_size, chain_flags = struct.unpack_from(">4I", descriptors, cursor + 16)
            data_offset = cursor + 92
            require(name_size > 0 and chain_key_size > 0 and data_offset + name_size + chain_key_size <= end,
                    "Empty or invalid AVB chain public key")
            name = descriptors[data_offset:data_offset + name_size].decode("ascii")
            require(re.fullmatch(r"[A-Za-z0-9_]+", name) is not None, "Invalid AVB chain partition name")
            key = descriptors[data_offset + name_size:data_offset + name_size + chain_key_size]
            require(any(key), "Empty AVB chain public key")
            require(name not in [c["partition"] for c in chains], "Duplicate AVB chain partition")
            chains.append({"partition": name, "rollbackIndexLocation": rollback_slot,
                           "flags": chain_flags, "publicKey": key})
        cursor = end
    flags, location = struct.unpack_from(">II", header, 120)
    return {"algorithmId": algorithm, "flags": flags, "rollbackIndex": fields[10],
            "rollbackIndexLocation": location, "publicKey": public_key, "chains": chains}


def check_avb(images, root_key, commands, temporary):
    expected = temporary / "expected-root.avbpubkey"
    commands.run("avb-root-key", "vbmeta", "avbtool", "extract_public_key", "--key", root_key, "--output", expected)
    root_public_key = expected.read_bytes()
    require(root_public_key and any(root_public_key), "Empty expected AVB root public key")
    nodes, active = {}, set()

    def visit(name, expected_key):
        require(name not in active, "Cycle in AVB chain")
        path = images / (name + ".img")
        node = avb_node(path)
        require(node["publicKey"] == expected_key, "AVB parent/root public key mismatch: " + name)
        if name in nodes:
            return
        active.add(name)
        args = ["verify_image", "--image", str(path)]
        chains = []
        for chain in node["chains"]:
            key_file = temporary / ("chain-" + name + "-" + chain["partition"] + ".avbpubkey")
            key_file.write_bytes(chain["publicKey"])
            args += ["--expected_chain_partition",
                     f"{chain['partition']}:{chain['rollbackIndexLocation']}:{key_file}"]
            visit(chain["partition"], chain["publicKey"])
            chains.append({k: v for k, v in chain.items() if k != "publicKey"} |
                          {"publicKeySha256": hashlib.sha256(chain["publicKey"]).hexdigest()})
        # Each signed node is verified explicitly, so all chain levels and hash
        # descriptors are covered without forwarding the root key to children.
        output = commands.run("avb-node", name, "avbtool", *args)
        require("Successfully verified" in output, "AVB tool did not confirm signature verification: " + name)
        nodes[name] = {k: v for k, v in node.items() if k not in ("publicKey", "chains")} | {
            "partition": name, "publicKeySha256": hashlib.sha256(node["publicKey"]).hexdigest(), "chains": chains}
        active.remove(name)

    visit("vbmeta", root_public_key)
    output = commands.run("avb-digest", "vbmeta", "avbtool", "calculate_vbmeta_digest", "--image", images / "vbmeta.img",
                          "--hash_algorithm", "sha256").strip()
    require(re.fullmatch(r"[0-9a-f]{64}", output) is not None, "Invalid calculated VBMeta digest")
    return {"expectedRootPublicKeySha256": hashlib.sha256(root_public_key).hexdigest(),
            "nodes": [nodes[name] for name in sorted(nodes)], "vbmetaDigestSha256": output}


def reuse_report(path, binding, tools, sources, source, *, expected_verifier_hash=None):
    report = read_json(path)
    require(report.get("schema") == SCHEMA and report.get("result") == "verified", "Invalid existing signature report")
    require(all(report.get(key) == value for key, value in binding.items()), "Existing signature report belongs to another candidate")
    require(report.get("tools") == tools and report.get("toolSources") == sources and
            report.get("verifierSha256") == (expected_verifier_hash or digest(__file__)),
            "Signature report tool identity differs")
    require(all(report.get("checks", {}).get(key) is True for key in CHECKS), "Incomplete signature report")
    require(report.get("commands") and report.get("avb", {}).get("nodes") and report.get("otas"),
            "Missing signature report evidence")
    covered = sorted((name, ota["sha256"], ota["bytes"]) for ota in report["otas"] for name in ota["paths"])
    expected = sorted((a["path"], a["sha256"], a["bytes"]) for a in binding["artifacts"][1:])
    require(covered == expected and all(o.get("metadataSignatureBytes", 0) > 0 for o in report["otas"]),
            "Signature report does not cover all full OTA bytes")
    require(len(report["otas"]) == len({a["sha256"] for a in binding["artifacts"][1:]}),
            "Invalid unique OTA signature evidence")
    nodes = {node["partition"]: node for node in report["avb"]["nodes"]}
    require(len(nodes) == len(report["avb"]["nodes"]) and "vbmeta" in nodes,
            "Invalid AVB signature report graph")
    for node in nodes.values():
        require(node.get("algorithmId", 0) != 0 and re.fullmatch(r"[0-9a-f]{64}", node.get("publicKeySha256", "")) is not None,
                "Unsigned AVB node in report")
        for chain in node["chains"]:
            child = nodes.get(chain["partition"], {})
            require(chain["publicKeySha256"] == child.get("publicKeySha256"), "Broken AVB report key chain")
    require(report["avb"].get("expectedRootPublicKeySha256") == nodes["vbmeta"]["publicKeySha256"] and
            re.fullmatch(r"[0-9a-f]{64}", report["avb"].get("vbmetaDigestSha256", "")) is not None,
            "Invalid AVB root/digest evidence")
    by_operation = {(c.get("operation"), c.get("scope")): c for c in report["commands"]}
    required_operations = {("ota", ota["sha256"]) for ota in report["otas"]} | {
        ("avb-node", name) for name in nodes} | {("avb-root-key", "vbmeta"), ("avb-digest", "vbmeta")}
    require(len(by_operation) == len(report["commands"]) and set(by_operation) == required_operations,
            "Incomplete signature verification command coverage")
    root_command = by_operation[("avb-root-key", "vbmeta")]
    require(isinstance(root_command.get("argv"), list) and len(root_command["argv"]) == 6,
            "Invalid root-key verification command")
    stage = pathlib.Path(root_command["argv"][-1]).parent
    require(stage.is_absolute() and stage.name.startswith("taku-signatures-"), "Invalid recorded verification workspace")
    for command in report["commands"]:
        require(command.get("exitCode") == 0, "Failed command in signature report")
        log = contained(path.parent, command["log"])
        require(not pathlib.Path(command["log"]).is_absolute() and digest(log) == command["logSha256"],
                "Existing signature verification log differs")
        operation, scope = command["operation"], command["scope"]
        expected_tool = "check_ota_package_signature" if operation == "ota" else "avbtool"
        require(command.get("tool") == expected_tool, "Incorrect verification tool evidence")
        expected_argv = [tools[expected_tool]["path"]]
        output = log.read_text(errors="replace")
        if operation == "ota":
            matching = [ota for ota in report["otas"] if ota["sha256"] == scope][0]
            expected_argv += [binding["signingInputs"]["certificate"]["sourcePath"],
                              str(contained(source, matching["paths"][0]))]
            require("Whole package signature VERIFIED" in output and "Payload signatures VERIFIED" in output,
                    "Missing OTA success evidence")
        elif operation == "avb-root-key":
            expected_argv += ["extract_public_key", "--key", binding["signingInputs"]["avbRootKey"]["sourcePath"],
                              "--output", str(stage / "expected-root.avbpubkey")]
        elif operation == "avb-node":
            expected_argv += ["verify_image", "--image", str(stage / "IMAGES" / (scope + ".img"))]
            for chain in nodes[scope]["chains"]:
                key_file = stage / ("chain-" + scope + "-" + chain["partition"] + ".avbpubkey")
                expected_argv += ["--expected_chain_partition",
                                  f"{chain['partition']}:{chain['rollbackIndexLocation']}:{key_file}"]
            require("Successfully verified" in output, "Missing AVB success evidence")
        else:
            expected_argv += ["calculate_vbmeta_digest", "--image", str(stage / "IMAGES/vbmeta.img"),
                              "--hash_algorithm", "sha256"]
            require(output.strip() == report["avb"]["vbmetaDigestSha256"], "VBMeta digest log differs")
        require(command["argv"] == expected_argv, "Signature verification command arguments differ")
    require(all(report.get(key) is False for key in ("physicalDeviceVerified", "coldBootVerified", "incrementalOtaReady")),
            "Unexpected device/delta claim in signature report")
    return report


def verify(source, results, directory):
    source, results, directory = source.resolve(), results.resolve(), directory.resolve()
    require(directory.is_relative_to(results) and directory != results, "Verification directory must be beneath results")
    require(not directory.is_relative_to(source), "Verification outputs cannot be written into the source tree")
    receipt, binding = load_candidate(source, results)
    binding["signingInputs"] = signing_inputs_for(source, receipt)
    tools, sources, environment = tools_for(source)
    report_path = directory / "signature-verification.json"
    if report_path.exists():
        reuse_report(report_path, binding, tools, sources, source)
        return {"path": str(report_path), "sha256": digest(report_path), "reused": True}
    directory.mkdir(parents=True, exist_ok=True)
    attempt = pathlib.Path("attempt-" + uuid.uuid4().hex[:12])
    (directory / attempt).mkdir()
    with tempfile.TemporaryDirectory(prefix="taku-signatures-") as staging:
        temporary = pathlib.Path(staging)
        commands = Commands(directory, attempt, tools, environment, temporary)
        try:
            misc, trusted, images = extract_target(contained(source, receipt["targetFiles"]["path"]), temporary, receipt)
            certificate = contained(source, misc["default_system_dev_certificate"] + ".x509.pem")
            cert_hash = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certificate.read_text())).hexdigest()
            require(cert_hash in {c["derSha256"] for c in trusted}, "Signing certificate is not trusted by candidate image")
            otas = check_otas(source, receipt, certificate, commands)
            avb = check_avb(images, contained(source, misc["avb_vbmeta_key_path"]), commands, temporary)
            # Re-read hashes before declaring success: another build cannot
            # silently substitute inputs while the cryptographic tools run.
            final_receipt, final_binding = load_candidate(source, results)
            final_binding["signingInputs"] = signing_inputs_for(source, final_receipt)
            require(binding == final_binding, "Candidate changed during signature verification")
            final_tools, final_sources, _ = tools_for(source)
            require(tools == final_tools and sources == final_sources, "Verification tools changed during verification")
            report = {"schema": SCHEMA, "result": "verified", "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                      **binding, "verifierSha256": digest(__file__), "tools": tools, "toolSources": sources,
                      "checks": {key: True for key in CHECKS}, "commands": commands.records,
                      "certificate": {"sourcePath": str(certificate), "derSha256": cert_hash},
                      "otas": otas, "avb": avb, "physicalDeviceVerified": False,
                      "coldBootVerified": False, "incrementalOtaReady": False}
            pending = directory / ".signature-verification.tmp"
            pending.write_text(json.dumps(report, indent=2) + "\n")
            pending.replace(report_path)
        except Exception as error:
            (directory / attempt / "failure.json").write_text(json.dumps({"result": "failed", "error": str(error),
                                                                        "commands": commands.records}, indent=2) + "\n")
            raise
    return {"path": str(report_path), "sha256": digest(report_path), "reused": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=pathlib.Path)
    parser.add_argument("--results-dir", required=True, type=pathlib.Path)
    parser.add_argument("--verification-dir", required=True, type=pathlib.Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify(args.source_root, args.results_dir, args.verification_dir)))
    except (VerificationError, OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile) as error:
        parser.exit(1, "Signature verification failed: " + str(error) + "\n")


if __name__ == "__main__":
    main()
