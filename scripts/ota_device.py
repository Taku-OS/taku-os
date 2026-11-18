#!/usr/bin/env python3
"""Observe an Android 17 Pixel 9 A->B upgrade; never flash, reboot, root or wipe.

before installs only the independent test probe and initializes its own data.
The explicit reuse-probe mode instead reads and preserves an existing probe.
after is read-only and never reinstalls or launches the probe.
"""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import secrets
import shlex
import subprocess
import time

import ota_release


PACKAGE = "org.taku.otaprobe"
DATA_FILES = {"preferences": "shared_prefs/ota_probe.xml", "sqlite": "databases/ota_probe.db",
              "privateFile": "files/ota_probe.bin"}
PROPERTIES = {"device": "ro.product.device", "androidRelease": "ro.build.version.release",
              "androidSdk": "ro.build.version.sdk", "buildNumber": "ro.build.version.incremental",
              "buildFingerprint": "ro.build.fingerprint", "buildTimestamp": "ro.build.date.utc",
              "platformSecurityPatch": "ro.build.version.security_patch", "vbmetaDigest": "ro.boot.vbmeta.digest",
              "slotSuffix": "ro.boot.slot_suffix", "bootloader": "ro.bootloader", "baseband": "gsm.version.baseband"}
PROBE_ANCHOR_SCHEMA = "taku.I-direct-image-postboot-observation/2"
PROBE_ANCHOR_OBSERVER_SHA256 = "ba9f9b50de54b0b262e053a9a1d9f31abd52d34cedcec6dbe540a5c2af51077b"
PROBE_ANCHOR_FILES = {"report": "report.json", "commands": "commands.jsonl", "inputBindings": "input-bindings.json"}
PROBE_DATA_SCOPE = "Independent probe preferences, SQLite database and private file only"
BOOT_ID_PATH = "/proc/sys/kernel/random/boot_id"


class DeviceVerificationError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise DeviceVerificationError(message)


def digest(path):
    result = hashlib.sha256()
    with pathlib.Path(path).open("rb") as stream:
        for data in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            result.update(data)
    return result.hexdigest()


def sha_text(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def is_sha(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def baseline_binding(baseline):
    """Accept a baseline already authenticated by ota_release.load_baseline."""
    identity = baseline["identity"]
    require(identity.get("androidRelease") == "17" and identity.get("androidSdk") == 37 and
            identity.get("productDevice") == "tokay" and identity.get("vendorDevice") == "tokay",
            "Baseline must be Android 17/API 37 tokay")
    for key in ("buildNumber", "buildFingerprint", "platformSecurityPatch"):
        require(isinstance(identity.get(key), str) and identity[key].strip(), "Missing baseline identity: " + key)
    require(re.fullmatch(r"[0-9]+", identity["buildNumber"]) is not None and int(identity["buildNumber"]) > 0,
            "Invalid baseline build number")
    require(type(identity.get("buildTimestamp")) is int and identity["buildTimestamp"] > 0,
            "Invalid baseline build timestamp")
    require(is_sha(baseline.get("vbmetaDigest")), "Missing baseline AVB measurement")
    require(baseline.get("fullOtas"), "Missing accepted full OTA baseline")
    binding = {"receiptSha256": baseline["receiptSha256"],
               "signatureReportSha256": baseline["signatureReportSha256"],
               "targetFilesSha256": baseline["targetFiles"]["sha256"],
               "fullOtaSha256s": sorted({item["sha256"] for item in baseline["fullOtas"]}),
               "identity": identity, "vbmetaDigest": baseline["vbmetaDigest"]}
    for value in [binding["receiptSha256"], binding["signatureReportSha256"], binding["targetFilesSha256"], *binding["fullOtaSha256s"]]:
        require(is_sha(value), "Invalid baseline digest")
    if "productBranding" in baseline:
        try:
            version = ota_release.branding_version(baseline["productBranding"])
        except ValueError as error:
            raise DeviceVerificationError(str(error)) from error
        require(baseline.get("updaterIntegration", {}).get("versionCode") == version,
                "Branded baseline requires its authenticated Updater contract")
        binding["productBranding"] = baseline["productBranding"]
        binding["updaterIntegration"] = baseline["updaterIntegration"]
    return binding


def transition_bindings(source, target):
    try:
        ota_release.check_product_upgrade(source, target)
    except ValueError as error:
        raise DeviceVerificationError(str(error)) from error
    before, after = baseline_binding(source), baseline_binding(target)
    require(before["identity"]["buildNumber"] != after["identity"]["buildNumber"] and
            before["targetFilesSha256"] != after["targetFilesSha256"] and before["vbmetaDigest"] != after["vbmetaDigest"],
            "A and B must be distinct image candidates")
    require(after["identity"]["buildTimestamp"] > before["identity"]["buildTimestamp"] and
            int(after["identity"]["buildNumber"]) > int(before["identity"]["buildNumber"]),
            "Target baseline must be a newer build")
    require(after["identity"]["platformSecurityPatch"] >= before["identity"]["platformSecurityPatch"],
            "Security patch downgrade is unsupported")
    return {"from": before, "to": after}


class Adb:
    def __init__(self, serial, executable="adb", runner=None):
        require(isinstance(serial, str) and re.fullmatch(r"[A-Za-z0-9_.:\[\]-]+", serial) is not None,
                "An explicit valid ADB serial is required")
        require(not serial.startswith("emulator-"), "Emulators cannot prove Pixel 9 OTA acceptance")
        self.serial, self.executable, self.runner = serial, executable, runner or subprocess.run

    def call(self, *args, timeout=30):
        completed = self.runner([self.executable, "-s", self.serial, *map(str, args)],
                                capture_output=True, text=True, timeout=timeout, check=False)
        require(completed.returncode == 0, "ADB operation failed: " + str(args[0]))
        return completed.stdout.strip()

    def shell(self, *args):
        return self.call("shell", *args)


def inspect_device(adb, expected):
    require(adb.call("get-state") == "device", "Selected ADB device is not ready")
    for name in ("ro.kernel.qemu", "ro.boot.qemu"):
        require(adb.shell("getprop", name) in ("", "0"), "Emulated device rejected")
    values = {key: adb.shell("getprop", prop) for key, prop in PROPERTIES.items()}
    require(values["device"] == "tokay" and values["androidRelease"] == "17" and values["androidSdk"] == "37",
            "Live device must be physical tokay on Android 17/API 37")
    require(values["slotSuffix"] in ("_a", "_b"), "Missing A/B slot identity")
    require(adb.shell("getprop", "sys.boot_completed") == "1", "Android boot has not completed")
    require(adb.shell("am", "get-current-user") == "0", "Probe requires the primary user 0")
    require(adb.shell("am", "get-started-user-state", "0") == "RUNNING_UNLOCKED", "Primary user is not unlocked")
    serial = adb.shell("getprop", "ro.serialno")
    require(serial and serial.lower() not in ("unknown", "null"), "Missing stable physical-device serial")
    for key in ("androidRelease", "androidSdk", "buildNumber", "buildFingerprint", "buildTimestamp", "platformSecurityPatch"):
        require(values[key] == str(expected["identity"][key]), "Live build does not match baseline: " + key)
    require(values["vbmetaDigest"] == expected["vbmetaDigest"], "Live AVB digest does not match accepted baseline")
    return {**values, "hardwareSerialSha256": sha_text(serial), "adbSerialSha256": sha_text(adb.serial),
            "bootCompleted": True, "primaryUserUnlocked": True}


def parse_sha(output, expected_name):
    match = re.fullmatch(r"([0-9a-f]{64})\s+\*?(.+)", output)
    require(match is not None and match.group(2) == expected_name, "Invalid probe digest response")
    return match.group(1)


def probe_state(adb):
    package_path = adb.shell("pm", "path", "--user", "0", PACKAGE)
    require(package_path.startswith("package:") and "\n" not in package_path, "Missing or split probe APK")
    apk = package_path.removeprefix("package:")
    require(re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", apk) is not None, "Unexpected probe APK location")
    apk_digest = parse_sha(adb.shell("/system/bin/sha256sum", apk), apk)
    uid = adb.shell("run-as", PACKAGE, "--user", "0", "/system/bin/id", "-u")
    require(uid.isdigit() and int(uid) >= 10000, "Invalid probe application UID")
    values = {}
    for kind, path in DATA_FILES.items():
        values[kind] = parse_sha(adb.shell("run-as", PACKAGE, "--user", "0", "/system/bin/sha256sum", path), path)
    return {"packageName": PACKAGE, "apkSha256": apk_digest, "appUid": int(uid), "dataSha256": values}


def write_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Existing evidence must never be overwritten by a new initialization.
    with path.open("x") as output:
        output.write(json.dumps(report, indent=2) + "\n")
    return {"path": str(path), "sha256": digest(path)}


def _small_evidence(path):
    path = pathlib.Path(path)
    require(path.is_file() and not path.is_symlink(), "Evidence must be a regular non-symlink file: " + path.name)
    require(0 < path.stat().st_size <= 1024 * 1024, "Evidence file is empty or too large: " + path.name)
    data = path.read_bytes()
    require(0 < len(data) <= 1024 * 1024, "Evidence changed size while reading: " + path.name)
    return data


def _fsync_directory(path):
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _write_exclusive(path, data):
    # A failed write/fsync is retained for diagnosis, never overwritten on retry.
    with pathlib.Path(path).open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    _fsync_directory(pathlib.Path(path).parent)


def _copy_small_evidence(source, destination, expected_sha256):
    data = _small_evidence(source)
    require(hashlib.sha256(data).hexdigest() == expected_sha256, "Evidence changed before copying: " + pathlib.Path(source).name)
    _write_exclusive(destination, data)
    require(digest(destination) == expected_sha256, "Copied evidence differs: " + pathlib.Path(destination).name)


def _probe_shape(probe):
    require(isinstance(probe, dict) and probe.get("packageName") == PACKAGE and
            type(probe.get("appUid")) is int and probe["appUid"] >= 10000 and
            isinstance(probe.get("dataSha256"), dict) and set(probe["dataSha256"]) == set(DATA_FILES),
            "Incomplete private-data probe evidence")
    for value in [probe.get("apkSha256"), *probe["dataSha256"].values()]:
        require(is_sha(value), "Invalid before probe digest")


def load_probe_anchor(report_path, report_sha256, source_binding):
    """Recheck the reviewed I report and its original raw probe reads, offline.

    Absolute historical paths remain untouched inside the original documents;
    only the three fixed sibling files are opened. This is probe continuity
    evidence, never an OTA or direct-image installation receipt.
    """
    report_path = pathlib.Path(report_path)
    require(is_sha(report_sha256), "An explicit reviewed probe anchor SHA-256 is required")
    contents = {role: _small_evidence(report_path if role == "report" else report_path.parent / name)
                for role, name in PROBE_ANCHOR_FILES.items()}
    require(hashlib.sha256(contents["report"]).hexdigest() == report_sha256, "Probe anchor report hash differs")
    report = json.loads(contents["report"])
    require(isinstance(report, dict) and report.get("schema") == PROBE_ANCHOR_SCHEMA and
            report.get("candidate") == "I" and report.get("observerRevision") == 2 and
            report.get("scriptSha256") == PROBE_ANCHOR_OBSERVER_SHA256 and
            report.get("result") == "verified-scoped-I-postboot-observation" and
            type(report.get("actualExitCode")) is int and report["actualExitCode"] == 0 and
            report.get("observationMethod") == "read-only-adb-after-direct-fastboot-image-install" and
            report.get("phoneDataWritten") is False and report.get("probeSeeded") is False and
            report.get("allUserDataVerified") is False and report.get("notOtaReceipt") is True and
            report.get("isOtaInstallationReceipt") is False,
            "Probe anchor is not the successful reviewed read-only I observation")
    checks = report.get("checks", {})
    require(isinstance(checks, dict) and all(checks.get(key) is True for key in
                ("newISystemBootVerified", "samePhysicalDevice", "primaryUserUnlocked", "existingProbeUnchanged")),
            "Probe anchor did not verify the source phone and existing probe")
    inputs = json.loads(contents["inputBindings"])
    require(isinstance(inputs, dict) and inputs == report.get("inputs"), "Probe anchor input bindings differ")
    # The pinned observer wrote this exact serialization. Reconstruct it from the
    # hash-bound report rather than trusting an unbound third-file digest.
    require(contents["inputBindings"] == (json.dumps(inputs, ensure_ascii=False, indent=2) + "\n").encode(),
            "Probe anchor input-binding bytes differ from the reviewed observer format")
    archive, device = report.get("archiveBinding", {}), report.get("device", {})
    require(isinstance(archive, dict) and isinstance(device, dict) and
            isinstance(archive.get("targetFiles"), dict) and
            all(isinstance(inputs.get(key), dict) for key in ("baselineReceipt", "signatureReport")),
            "Incomplete probe anchor baseline references")
    require(inputs.get("baselineReceipt", {}).get("sha256") == source_binding["receiptSha256"] and
            inputs.get("signatureReport", {}).get("sha256") == source_binding["signatureReportSha256"] and
            archive.get("targetFiles", {}).get("sha256") == source_binding["targetFilesSha256"] and
            archive.get("metadataBindingsVerified") is True and
            archive.get("buildNumber") == source_binding["identity"]["buildNumber"] and
            archive.get("vbmetaDigest") == source_binding["vbmetaDigest"],
            "Probe anchor belongs to another source baseline")
    require(device.get("device") == "tokay" and device.get("androidRelease") == "17" and
            device.get("androidSdk") == "37" and device.get("buildNumber") == archive["buildNumber"] and
            device.get("vbmetaDigest") == archive["vbmetaDigest"] and device.get("slotSuffix") in ("_a", "_b"),
            "Probe anchor source-device identity differs")
    commands_ref = report.get("commands", {})
    require(isinstance(commands_ref, dict) and commands_ref.get("sha256") == hashlib.sha256(contents["commands"]).hexdigest() and
            type(commands_ref.get("bytes")) is int and commands_ref["bytes"] == len(contents["commands"]),
            "Probe anchor original commands changed")
    rows = [json.loads(line) for line in contents["commands"].splitlines()]
    require(0 < len(rows) <= 4096, "Missing or excessive probe anchor commands")
    commands = []
    for index, row in enumerate(rows, 1):
        require(isinstance(row, dict) and type(row.get("sequence")) is int and row["sequence"] == index and
                type(row.get("exitCode")) is int and row["exitCode"] == 0 and row.get("deviceReadOnly") is True,
                "Probe anchor command failed or is out of order")
        argv = row.get("argv")
        require(isinstance(argv, list) and all(isinstance(arg, str) for arg in argv) and len(argv) in (4, 5) and
                argv[1] == "-s" and argv[2] and (argv[3:] == ["get-state"] or (len(argv) == 5 and argv[3] == "shell")),
                "Unexpected probe anchor command shape")
        for stream in ("stdout", "stderr"):
            if stream in row:
                require(isinstance(row[stream], str) and type(row.get(stream + "Bytes")) is int and
                        row[stream + "Bytes"] == len(row[stream].encode()) and
                        row.get(stream + "Sha256") == sha_text(row[stream]), "Probe anchor raw command output changed")
        commands.append((shlex.split(argv[4]) if len(argv) == 5 else ["get-state"], row))

    def one_stdout(command):
        matches = [row for actual, row in commands if actual == command]
        require(len(matches) == 1 and isinstance(matches[0].get("stdout"), str),
                "Missing or ambiguous original probe read: " + command[0])
        return matches[0]["stdout"].strip()

    serials = [row.get("stdout", "").strip() for command, row in commands if command == ["getprop", "ro.serialno"]]
    require(serials and all(value and value.lower() not in ("unknown", "null") and value == serials[0] for value in serials),
            "Missing stable physical serial in probe anchor commands")
    # The historical I observer named this adbSerialSha256 but hashed ro.serialno.
    hardware_hash = sha_text(serials[0])
    require(device.get("adbSerialSha256") == hardware_hash, "Probe anchor hardware serial differs")
    require(all(row["argv"][2] == serials[0] for _, row in commands), "Probe anchor commands address different phones")
    active = one_stdout(["pm", "path", "--user", "0", PACKAGE])
    require(active.startswith("package:") and "\n" not in active, "Invalid anchor probe APK path")
    apk_path = active.removeprefix("package:")
    require(re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", apk_path) is not None, "Unexpected anchor probe APK location")
    uid = one_stdout(["run-as", PACKAGE, "--user", "0", "/system/bin/id", "-u"])
    require(uid.isdigit() and int(uid) >= 10000, "Invalid anchor probe UID")
    probe = {"packageName": PACKAGE,
             "apkSha256": parse_sha(one_stdout(["/system/bin/sha256sum", apk_path]), apk_path),
             "appUid": int(uid), "dataSha256": {kind: parse_sha(one_stdout(
                 ["run-as", PACKAGE, "--user", "0", "/system/bin/sha256sum", name]), name)
                 for kind, name in DATA_FILES.items()}}
    _probe_shape(report.get("probe"))
    require(probe == report["probe"], "Probe anchor summary differs from original raw reads")
    refs = {role: {"path": "probe-anchor/" + name, "bytes": len(contents[role]),
                   "sha256": hashlib.sha256(contents[role]).hexdigest()} for role, name in PROBE_ANCHOR_FILES.items()}
    return {"report": report, "device": device, "probe": probe, "hardwareSerialSha256": hardware_hash,
            "evidence": {"type": PROBE_ANCHOR_SCHEMA, **refs}}


class ReadOnlyProbeAdb(Adb):
    """Explicit reuse cannot reach install, launch, seed, root or reboot."""
    def call(self, *args, timeout=30):
        allowed = args == ("get-state",)
        if args and args[0] == "shell":
            command = args[1:]
            allowed = (len(command) == 2 and command[0] == "getprop" and command[1] in
                       (*PROPERTIES.values(), "ro.kernel.qemu", "ro.boot.qemu", "sys.boot_completed", "ro.serialno"))
            allowed = allowed or command in (("am", "get-current-user"), ("am", "get-started-user-state", "0"),
                         ("cat", BOOT_ID_PATH), ("pm", "path", "--user", "0", PACKAGE),
                         ("run-as", PACKAGE, "--user", "0", "/system/bin/id", "-u"))
            allowed = allowed or (len(command) == 2 and command[0] == "/system/bin/sha256sum" and
                                  re.fullmatch(r"/data/app/[A-Za-z0-9_./=+~-]+/base\.apk", command[1]) is not None)
            allowed = allowed or (len(command) == 6 and command[:5] ==
                                  ("run-as", PACKAGE, "--user", "0", "/system/bin/sha256sum") and command[5] in DATA_FILES.values())
        require(allowed, "Command is outside the read-only probe reuse allowlist")
        return super().call(*args, timeout=timeout)


def capture_before_reusing_probe(serial, source_baseline, target_baseline, evidence_dir,
                                anchor_report, anchor_sha256, *, adb="adb", runner=None):
    require(isinstance(source_baseline, dict) and isinstance(target_baseline, dict),
            "Both authenticated source and target baselines are required")
    baselines = transition_bindings(source_baseline, target_baseline)
    anchor = load_probe_anchor(anchor_report, anchor_sha256, baselines["from"])
    connection = ReadOnlyProbeAdb(serial, adb, runner)
    directory = pathlib.Path(evidence_dir).absolute()
    directory.mkdir(parents=True, exist_ok=False, mode=0o700)
    _fsync_directory(directory.parent)
    anchor_dir = directory / "probe-anchor"
    anchor_dir.mkdir(mode=0o700)
    _fsync_directory(directory)
    for role, name in PROBE_ANCHOR_FILES.items():
        source = pathlib.Path(anchor_report) if role == "report" else pathlib.Path(anchor_report).parent / name
        _copy_small_evidence(source, anchor_dir / name, anchor["evidence"][role]["sha256"])
    device = inspect_device(connection, baselines["from"])
    require(device["hardwareSerialSha256"] == anchor["hardwareSerialSha256"], "Probe anchor belongs to another physical device")
    boot_id = connection.shell("cat", BOOT_ID_PATH)
    require(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", boot_id) is not None, "Missing live source boot ID")
    probe = probe_state(connection)
    require(probe == anchor["probe"], "Existing probe APK, UID or private data differs from anchor; do not reseed")
    require(inspect_device(connection, baselines["from"]) == device and connection.shell("cat", BOOT_ID_PATH) == boot_id,
            "Device changed during read-only before capture")
    report = {"schema": "taku.ota-device-before/2", "result": "verified",
              "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "baselines": baselines,
              "captureMethod": "read-only-reuse-existing-probe", "captureBootId": boot_id,
              "device": device, "probe": probe, "probeAnchor": anchor["evidence"],
              "checks": {"sourceBootVerified": True, "existingProbeVerified": True, "probeAnchorVerified": True},
              "probeInitializedByThisCapture": False, "phoneDataWritten": False,
              "dataScope": PROBE_DATA_SCOPE, "allUserDataVerified": False}
    path = directory / "pre-upgrade.json"
    _write_exclusive(path, (json.dumps(report, indent=2) + "\n").encode())
    result = {"path": str(path), "sha256": digest(path)}
    validate_before(path, result["sha256"], baselines)
    return result


def capture_before(serial, source_baseline, target_baseline, evidence_dir, probe_apk, *, adb="adb", runner=None):
    path = pathlib.Path(evidence_dir).resolve() / "pre-upgrade.json"
    require(not path.exists(), "Before evidence already exists; preserve it or choose a new evidence directory")
    baselines = transition_bindings(source_baseline, target_baseline)
    connection = Adb(serial, adb, runner)
    device = inspect_device(connection, baselines["from"])
    apk = pathlib.Path(probe_apk).resolve()
    require(apk.is_file(), "Probe APK is missing")
    apk_hash = digest(apk)
    install = connection.call("install", "-r", "--user", "0", str(apk), timeout=120).splitlines()
    require(install and install[-1] == "Success", "Probe install did not succeed")
    nonce = secrets.token_hex(32)
    expected_private_hash = sha_text(nonce + "\n")
    launch = connection.shell("am", "start", "-W", "--user", "0", "-n", PACKAGE + "/.ProbeActivity", "--es", "nonce", nonce)
    require("Error:" not in launch and "Exception" not in launch, "Probe initialization activity failed")
    probe = None
    for attempt in range(20):
        try:
            candidate = probe_state(connection)
            if candidate["dataSha256"]["privateFile"] == expected_private_hash:
                probe = candidate
                break
        except DeviceVerificationError:
            pass
        if attempt < 19:
            time.sleep(0.1)
    require(probe is not None, "Probe did not finish initializing all private-data stores")
    require(probe["apkSha256"] == apk_hash and digest(apk) == apk_hash, "Installed probe APK differs from supplied APK")
    require(inspect_device(connection, baselines["from"]) == device, "Device changed during before capture")
    report = {"schema": "taku.ota-device-before/1", "result": "verified",
              "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "baselines": baselines,
              "device": device, "probe": probe, "checks": {"sourceBootVerified": True, "probeInitialized": True},
              "dataScope": "Independent probe preferences, SQLite database and private file only",
              "allUserDataVerified": False}
    return write_report(path, report)


def validate_before(path, expected_sha256, baselines):
    path = pathlib.Path(path)
    require(is_sha(expected_sha256), "Explicit before evidence SHA-256 is required")
    require(digest(path) == expected_sha256, "Before evidence hash differs")
    before = json.loads(path.read_text())
    require(isinstance(before, dict) and before.get("schema") in ("taku.ota-device-before/1", "taku.ota-device-before/2") and before.get("result") == "verified" and
            before.get("baselines") == baselines, "Before evidence belongs to another A/B transition")
    checks = before.get("checks")
    reused = before["schema"] == "taku.ota-device-before/2"
    expected_checks = {"sourceBootVerified", "existingProbeVerified", "probeAnchorVerified"} if reused else {"sourceBootVerified", "probeInitialized"}
    require(isinstance(checks, dict) and set(checks) == expected_checks and all(value is True for value in checks.values()) and
            before.get("allUserDataVerified") is False, "Before evidence did not verify source boot and probe")
    device = before.get("device", {})
    require(isinstance(device, dict) and device.get("device") == "tokay" and
            device.get("bootCompleted") is True and device.get("primaryUserUnlocked") is True and
            device.get("slotSuffix") in ("_a", "_b") and
            is_sha(device.get("hardwareSerialSha256")) and is_sha(device.get("adbSerialSha256")),
            "Incomplete source-device observation")
    source = baselines["from"]
    for key in ("androidRelease", "androidSdk", "buildNumber", "buildFingerprint", "buildTimestamp", "platformSecurityPatch"):
        require(device.get(key) == str(source["identity"][key]), "Before device differs from source baseline: " + key)
    require(device.get("vbmetaDigest") == source["vbmetaDigest"], "Before AVB measurement differs from source baseline")
    probe = before.get("probe", {})
    _probe_shape(probe)
    if reused:
        require(path.is_file() and not path.is_symlink() and
                before.get("captureMethod") == "read-only-reuse-existing-probe" and
                before.get("probeInitializedByThisCapture") is False and before.get("phoneDataWritten") is False and
                "probeInitialized" not in before and before.get("dataScope") == PROBE_DATA_SCOPE and
                re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", before.get("captureBootId", "")) is not None,
                "Reuse evidence must not claim that it installed or initialized the probe")
        evidence = before.get("probeAnchor", {})
        require(isinstance(evidence, dict) and set(evidence) == {"type", *PROBE_ANCHOR_FILES} and
                evidence.get("type") == PROBE_ANCHOR_SCHEMA, "Missing portable probe anchor")
        root = path.parent / "probe-anchor"
        require(root.is_dir() and not root.is_symlink(), "Probe anchor directory is missing or is a symlink")
        for role, name in PROBE_ANCHOR_FILES.items():
            ref = evidence.get(role)
            require(isinstance(ref, dict) and set(ref) == {"path", "bytes", "sha256"} and
                    ref.get("path") == "probe-anchor/" + name and is_sha(ref.get("sha256")) and
                    type(ref.get("bytes")) is int and ref["bytes"] > 0,
                    "Invalid portable probe anchor reference")
            raw = _small_evidence(root / name)
            require(len(raw) == ref["bytes"] and hashlib.sha256(raw).hexdigest() == ref["sha256"],
                    "Portable probe anchor bytes differ")
        anchor = load_probe_anchor(root / "report.json", evidence["report"]["sha256"], source)
        require(anchor["evidence"] == evidence and anchor["probe"] == probe and
                anchor["hardwareSerialSha256"] == device["hardwareSerialSha256"],
                "Before evidence does not preserve the anchored probe on the same physical phone")
    return before


def copy_before_evidence(before_path, before_hash, destination_dir, baselines):
    """Copy a /1 or /2 before bundle exclusively, then revalidate at its new root."""
    before_path = pathlib.Path(before_path)
    before = validate_before(before_path, before_hash, baselines)
    destination = pathlib.Path(destination_dir).absolute()
    require(not destination.is_symlink(), "Before destination must not be a symlink")
    destination.mkdir(parents=True, exist_ok=True)
    require(destination.is_dir(), "Before destination is not a directory")
    _fsync_directory(destination.parent)
    target = destination / "pre-upgrade.json"
    require(not target.exists() and not target.is_symlink(), "Copied before evidence already exists; preserve it")
    if before["schema"] == "taku.ota-device-before/2":
        anchor_dir = destination / "probe-anchor"
        anchor_dir.mkdir(mode=0o700)
        _fsync_directory(destination)
        for role, name in PROBE_ANCHOR_FILES.items():
            _copy_small_evidence(before_path.parent / "probe-anchor" / name, anchor_dir / name,
                                 before["probeAnchor"][role]["sha256"])
    # Publish the before JSON last. Partial sidecar copies are retained on error.
    _copy_small_evidence(before_path, target, before_hash)
    validate_before(target, before_hash, baselines)
    return {"path": str(target), "sha256": before_hash}


def capture_after(serial, source_baseline, target_baseline, evidence_dir, before_sha256, *, adb="adb", runner=None):
    directory = pathlib.Path(evidence_dir).resolve()
    path = directory / "post-upgrade.json"
    require(not path.exists(), "After evidence already exists; preserve the completed record")
    baselines = transition_bindings(source_baseline, target_baseline)
    before = validate_before(directory / "pre-upgrade.json", before_sha256, baselines)
    connection = Adb(serial, adb, runner)
    device = inspect_device(connection, baselines["to"])
    require(device["hardwareSerialSha256"] == before["device"]["hardwareSerialSha256"], "A different physical device was selected")
    require(device["slotSuffix"] != before["device"]["slotSuffix"], "A/B boot slot did not change")
    # Deliberately no install/start/repair here: missing data is a failed check.
    probe = probe_state(connection)
    require(probe == before["probe"], "Probe application identity or private data was not preserved")
    require(inspect_device(connection, baselines["to"]) == device, "Device changed during after capture")
    require(digest(directory / "pre-upgrade.json") == before_sha256, "Before evidence changed during after capture")
    report = {"schema": "taku.ota-device-after/1", "result": "verified",
              "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "baselines": baselines,
              "beforeEvidenceSha256": before_sha256, "device": device, "probe": probe,
              "checks": {"sameDeviceVerified": True, "targetBootVerified": True, "slotChanged": True,
                         "probePrivateDataPreserved": True},
              "dataScope": "Independent probe preferences, SQLite database and private file only",
              "allUserDataVerified": False}
    return write_report(path, report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("before", "after"))
    parser.add_argument("--serial", required=True)
    parser.add_argument("--from-baseline", required=True, type=pathlib.Path)
    parser.add_argument("--to-baseline", required=True, type=pathlib.Path)
    parser.add_argument("--evidence-dir", required=True, type=pathlib.Path)
    parser.add_argument("--probe-apk", type=pathlib.Path)
    parser.add_argument("--reuse-probe-anchor", type=pathlib.Path)
    parser.add_argument("--probe-anchor-sha256")
    parser.add_argument("--before-sha256")
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if args.action == "before" and (bool(args.probe_apk) == bool(args.reuse_probe_anchor) or args.before_sha256 or
                                    bool(args.reuse_probe_anchor) != bool(args.probe_anchor_sha256)):
        parser.error("before requires either --probe-apk or --reuse-probe-anchor with --probe-anchor-sha256")
    if args.action == "after" and (not args.before_sha256 or args.probe_apk or args.reuse_probe_anchor or args.probe_anchor_sha256):
        parser.error("after requires --before-sha256 and does not accept --probe-apk")
    from ota_release import load_baseline
    try:
        source, target = load_baseline(args.from_baseline), load_baseline(args.to_baseline)
        if args.action == "before":
            if args.reuse_probe_anchor:
                result = capture_before_reusing_probe(args.serial, source, target, args.evidence_dir,
                    args.reuse_probe_anchor, args.probe_anchor_sha256, adb=args.adb)
            else:
                result = capture_before(args.serial, source, target, args.evidence_dir, args.probe_apk, adb=args.adb)
        else:
            result = capture_after(args.serial, source, target, args.evidence_dir, args.before_sha256, adb=args.adb)
        print(json.dumps(result))
    except (DeviceVerificationError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, "OTA device verification failed: " + str(error) + "\n")


if __name__ == "__main__":
    main()
