#!/usr/bin/env python3
"""Bind a real, verified incremental installation as the next exact OTA source.

Private evidence stays portable and retains its original schemas. This never
turns an incremental receipt into a full-baseline installation or runs ADB.
"""
import datetime
import json
import pathlib
import shutil

import ota_device
from ota_release import digest, file_under, read_json, require

SCHEMA = "taku.incremental-source-installation/1"
METHOD = "verified-incremental-upgrade"
MAX_DEPTH = 32
MAX_FILES = 4096
MAX_BYTES = 8 * 1024**3
REFERENCES = {
    "plan": "previous-delivery/plan.json",
    "delivery": "previous-delivery/delivery.json",
    "installation": "previous-installation/installation.json",
    "after": "previous-device/post-upgrade.json",
    "acceptance": "previous-device/incremental-acceptance.json",
}
FIELDS = {"schema", "method", "reviewedAt", "baselineReceiptSha256", "adbSerialSha256",
          "beforeSha256", "chainDepth", "previous", "notFullBaselineInstallation", "allUserDataVerified"}


def previous_evidence(root, references):
    require(isinstance(references, dict) and set(references) == set(REFERENCES),
            "Incremental source needs the complete previous upgrade evidence")
    paths = {}
    for name, relative in REFERENCES.items():
        record = references[name]
        require(isinstance(record, dict) and set(record) == {"path", "sha256"}
                and record["path"] == relative and ota_device.is_sha(record["sha256"]),
                "Incremental source evidence reference differs: " + name)
        path = file_under(root, relative)
        require(digest(path) == record["sha256"], "Previous upgrade evidence changed: " + name)
        paths[name] = path
    return paths


def validate_after(after, old_before, prior_plan, before_hash):
    require(after.get("schema") == "taku.ota-device-after/1" and after.get("result") == "verified"
            and after.get("baselines") == ota_device.transition_bindings(prior_plan["source"], prior_plan["target"])
            and after.get("beforeEvidenceSha256") == before_hash
            and after.get("checks") == {"sameDeviceVerified": True, "targetBootVerified": True,
                                         "slotChanged": True, "probePrivateDataPreserved": True}
            and after.get("allUserDataVerified") is False
            and after.get("dataScope") == ota_device.PROBE_DATA_SCOPE,
            "Previous incremental upgrade has no exact target/probe observation")
    require(after.get("probe") == old_before["probe"], "Previous upgrade probe was not preserved")
    device = after.get("device", {})
    source_device = old_before["device"]
    target = ota_device.baseline_binding(prior_plan["target"])
    require(device.get("hardwareSerialSha256") == source_device["hardwareSerialSha256"]
            and device.get("adbSerialSha256") == source_device["adbSerialSha256"]
            and device.get("bootCompleted") is True and device.get("primaryUserUnlocked") is True
            and device.get("device") == "tokay" and device.get("vbmetaDigest") == target["vbmetaDigest"]
            and device.get("slotSuffix") in ("_a", "_b")
            and device["slotSuffix"] != source_device["slotSuffix"],
            "Previous target observation belongs to another phone/build/slot")
    for key in ("androidRelease", "androidSdk", "buildNumber", "buildFingerprint", "buildTimestamp", "platformSecurityPatch"):
        require(device.get(key) == str(target["identity"][key]), "Previous target identity differs: " + key)
    return device


def validate_source(path, expected_hash, source, before, before_hash, validate_prior):
    path = pathlib.Path(path).resolve()
    require(ota_device.is_sha(expected_hash) and digest(path) == expected_hash,
            "Incremental source installation SHA-256 differs")
    value = read_json(path)
    require(set(value) == FIELDS and value.get("schema") == SCHEMA and value.get("method") == METHOD
            and value.get("notFullBaselineInstallation") is True and value.get("allUserDataVerified") is False
            and value.get("baselineReceiptSha256") == source["receiptSha256"]
            and ota_device.is_sha(before_hash) and value.get("beforeSha256") == before_hash
            and value.get("adbSerialSha256") == before["device"]["adbSerialSha256"]
            and type(value.get("chainDepth")) is int and 1 <= value["chainDepth"] <= MAX_DEPTH,
            "Incremental source binding differs from the fresh exact source observation")
    refs = value["previous"]
    paths = previous_evidence(path.parent, refs)
    prior = validate_prior(paths, refs)
    prior_plan = prior["plan"]
    require(ota_device.baseline_binding(prior_plan["target"]) == ota_device.baseline_binding(source),
            "Previous incremental target is not this source archive")
    after = read_json(paths["after"])
    target_device = validate_after(after, prior["before"], prior_plan, prior["delivery"]["beforeSha256"])
    require(target_device == before["device"], "Current source no longer matches the previously installed target")
    # before/1 deliberately initializes only its independent test probe. Carry
    # the real previous probe observation unchanged and check stable APK/UID;
    # newly initialized sample hashes belong to the new before, not the old OTA.
    old_probe, new_probe = after["probe"], before["probe"]
    require(all(old_probe.get(key) == new_probe.get(key) for key in ("packageName", "apkSha256", "appUid")),
            "Current independent probe identity differs from previous target")
    accepted = read_json(paths["acceptance"])
    expected = {"planSha256": refs["plan"]["sha256"], "deliverySha256": refs["delivery"]["sha256"],
                "installationSha256": refs["installation"]["sha256"],
                "beforeSha256": prior["delivery"]["beforeSha256"], "afterSha256": refs["after"]["sha256"]}
    method = prior["installation"]["method"]
    completed = "incrementalSideloadCompleted" if method == "adb-sideload" else "incrementalUpdaterCompleted"
    require(accepted.get("schema") == "taku.ota-acceptance/1"
            and all(accepted.get(key) == val for key, val in expected.items())
            and accepted.get(completed) is True and accepted.get("targetBootVerified") is True
            and accepted.get("probePrivateDataPreserved") is True and accepted.get("allUserDataVerified") is False,
            "Previous upgrade acceptance is incomplete or belongs to another delivery")
    parent = read_json(paths["delivery"].parent / "source-installation/installation.json")
    depth = parent["chainDepth"] + 1 if parent.get("schema") == SCHEMA else 1
    require(value["chainDepth"] == depth, "Incremental source chain depth differs")
    return value


def tree_files(source):
    source = pathlib.Path(source)
    require(source.is_dir() and not source.is_symlink(), "Evidence directory must be regular")
    files, size = [], 0
    for path in sorted(source.rglob("*")):
        require(not path.is_symlink(), "Symlink in private upgrade evidence")
        if path.is_dir():
            continue
        require(path.is_file(), "Non-regular private upgrade evidence")
        size += path.stat().st_size
        files.append(path)
        require(len(files) <= MAX_FILES and size <= MAX_BYTES, "Private upgrade evidence exceeds its bounded scope")
    return files


def copy_file(source, destination):
    source, destination = pathlib.Path(source), pathlib.Path(destination)
    require(source.is_file() and not source.is_symlink(), "Expected regular evidence file")
    checksum = digest(source)
    destination.parent.mkdir(parents=True, exist_ok=True)
    with source.open("rb") as incoming, destination.open("xb") as output:
        shutil.copyfileobj(incoming, output, 8 * 1024 * 1024)
    require(digest(source) == digest(destination) == checksum, "Evidence changed during private copy")


def copy_tree(source, destination):
    source, destination = pathlib.Path(source), pathlib.Path(destination)
    incoming, outgoing = source.resolve(), destination.resolve()
    require(not outgoing.is_relative_to(incoming) and not incoming.is_relative_to(outgoing),
            "Private evidence source and destination must not overlap")
    files = tree_files(source)
    destination.mkdir(parents=True, exist_ok=False)
    for path in files:
        copy_file(path, destination / path.relative_to(source))
    require([str(p.relative_to(source)) for p in tree_files(source)] ==
            [str(p.relative_to(destination)) for p in tree_files(destination)], "Evidence tree changed during copy")


def bind(args, plan, before, validate_prior):
    """Freeze the actual previous upgrade without touching a phone or archive."""
    output = args.output.resolve()
    require(not output.exists(), "Preserve existing source bindings; choose a new output directory")
    incoming = {
        "plan": (args.previous_delivery.resolve().parent / "plan.json", args.previous_plan_sha256),
        "delivery": (args.previous_delivery.resolve(), args.previous_delivery_sha256),
        "installation": (args.previous_installation.resolve(), args.previous_installation_sha256),
        "after": (args.previous_after.resolve(), args.previous_after_sha256),
        "acceptance": (args.previous_acceptance.resolve(), args.previous_acceptance_sha256),
    }
    require(incoming["delivery"][0].name == "delivery.json"
            and incoming["installation"][0].name == "installation.json", "Use canonical prior delivery/installation records")
    for path, checksum in incoming.values():
        require(ota_device.is_sha(checksum) and path.is_file() and not path.is_symlink() and digest(path) == checksum,
                "Previous source input SHA-256 differs")
        require(not output.is_relative_to(path.parent), "Source binding must not alter previous upgrade evidence")
    paths = {key: value[0] for key, value in incoming.items()}
    refs = {key: {"path": REFERENCES[key], "sha256": value[1]} for key, value in incoming.items()}
    # Validate the complete previous delivery/install before copying any tree.
    prior = validate_prior(paths, refs)
    require(ota_device.baseline_binding(prior["plan"]["target"]) == ota_device.baseline_binding(plan["source"]),
            "Previous target does not identify the new exact source")
    parent = read_json(paths["delivery"].parent / "source-installation/installation.json")
    depth = parent["chainDepth"] + 1 if parent.get("schema") == SCHEMA else 1
    require(depth <= MAX_DEPTH, "Incremental source chain needs a separately reviewed checkpoint")
    output.mkdir(parents=True, exist_ok=False)
    copy_tree(paths["delivery"].parent, output / "previous-delivery")
    copy_tree(paths["installation"].parent, output / "previous-installation")
    for key in ("after", "acceptance"):
        copy_file(paths[key], output / REFERENCES[key])
    record = {"schema": SCHEMA, "method": METHOD,
              "reviewedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "baselineReceiptSha256": plan["source"]["receiptSha256"],
              "adbSerialSha256": before["device"]["adbSerialSha256"], "beforeSha256": args.before_sha256,
              "chainDepth": depth, "previous": refs, "notFullBaselineInstallation": True, "allUserDataVerified": False}
    path = output / "installation.json"
    with path.open("x") as stream:
        json.dump(record, stream, indent=2); stream.write("\n")
    checksum = digest(path)
    validate_source(path, checksum, plan["source"], before, args.before_sha256, validate_prior)
    return {"path": str(path), "sha256": checksum, "chainDepth": depth}
