#!/usr/bin/env python3
"""Plan, generate and observe one exact Pixel 9 A->B OTA; never unlock or wipe.

Run plan/generate on the builder. A sealed plan and the eventual delivery are
portable to the MacBook for USB tests. Cloud publication is a later phase.
"""
import argparse
import datetime
import fcntl
import json
import os
import pathlib
import shutil
import subprocess
import sys
from types import SimpleNamespace

from image_build_config import ROOT, OUT, NAME
from ota_device import DeviceVerificationError
from ota_release import (ReleaseError, create_baseline_binding, create_plan, digest, file_under,
                         load_baseline, load_baseline_binding, load_plan, read_json, require, require_measurable_boot, validate_before)


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    return {"path": str(path), "sha256": digest(path)}


def copy_exact(source, target):
    checksum = digest(source)
    with source.open("rb") as incoming, target.open("xb") as output:
        shutil.copyfileobj(incoming, output, 8 * 1024 * 1024)
    require(digest(target) == checksum, "Evidence changed during copy")


def freeze_helper(source, target):
    """Publish a reusable helper atomically; preserve interrupted cache copies."""
    expected = digest(source)
    target.parent.mkdir(parents=True, exist_ok=True)
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    if target.exists():
        if digest(target) == expected:
            return target
        target.rename(target.with_name(target.name + ".invalid-" + stamp))
    partial = target.with_name("." + target.name + ".partial")
    if partial.exists():
        partial.rename(partial.with_name(partial.name + ".interrupted-" + stamp))
    copy_exact(source, partial)
    require(digest(partial) == expected, "Generator changed during freezing")
    os.link(partial, target)
    partial.unlink()
    return target


def current_builder(target):
    state = json.loads(subprocess.check_output(["docker", "inspect", NAME]))[0]["State"]
    require(not state["Running"] and state["ExitCode"] == 0,
            "Wait for the full-image builder to finish successfully")
    status = read_json(OUT / "status.json")
    require(status.get("romBuilt") is True and status.get("artifactsExported") is True
            and status.get("artifactSignaturesVerified") is True,
            "Current full-image build has not completed verification/export")
    require(digest(OUT / "baseline-receipt.json") == target["receiptSha256"]
            and digest(OUT / "run-input.json") == target["runInputSha256"],
            "Current builder tools/source volume do not belong to target B")
    return read_json(OUT / "run-input.json")


def check_firmware_assessment(path, expected_hash, baseline, serial_hash, package_hash):
    """Consume an explicit, evidence-backed device review; never guess compatibility."""
    require(digest(path) == expected_hash, "Firmware assessment SHA-256 differs")
    assessment = read_json(path)
    require(assessment.get("schema") == "taku.ota-firmware-assessment/1"
            and assessment.get("result") == "compatible"
            and assessment.get("baselineReceiptSha256") == baseline["receiptSha256"]
            and assessment.get("adbSerialSha256") == serial_hash
            and assessment.get("fullOtaSha256") == package_hash,
            "A device/firmware assessment for this exact full baseline is required")
    require(assessment.get("checks") == {"deviceModelVerified": True, "firmwarePathCompatible": True,
                                         "bootloaderAcceptsPackage": True}, "Firmware assessment is incomplete")
    evidence = assessment.get("evidence", [])
    require(evidence, "Firmware assessment needs supporting device/release evidence")
    for item in evidence:
        require(digest(file_under(path.parent.resolve(), item["path"])) == item["sha256"],
                "Firmware assessment evidence changed")
    return assessment


def baseline_sideload(args, baseline):
    import ota_device
    require_measurable_boot(baseline)
    ota_device.Adb(args.serial, args.adb)
    package = args.package.resolve()
    checksum = digest(package)
    require(any(x["sha256"] == checksum and x["bytes"] == package.stat().st_size
                for x in baseline["fullOtas"]), "Package is not the accepted source full OTA")
    serial_hash = ota_device.sha_text(args.serial)
    assessment = check_firmware_assessment(args.assessment, args.assessment_sha256, baseline, serial_hash, checksum)
    state = subprocess.check_output([args.adb, "-s", args.serial, "get-state"], text=True).strip()
    require(state == "sideload", "Put the assessed Pixel 9 in recovery ADB sideload mode first")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    evidence_root = output / "firmware-assessment"
    evidence_root.mkdir()
    copy_exact(args.assessment, evidence_root / "assessment.json")
    for item in assessment["evidence"]:
        destination = evidence_root / item["path"]
        require(destination.resolve().is_relative_to(evidence_root), "Escaped assessment destination")
        destination.parent.mkdir(parents=True, exist_ok=True)
        copy_exact(file_under(args.assessment.parent.resolve(), item["path"]), destination)
    with (output / "sideload.log").open("xb") as log:
        completed = subprocess.run([args.adb, "-s", args.serial, "sideload", str(package)],
                                   stdout=log, stderr=subprocess.STDOUT)
    unchanged = digest(package) == checksum
    result = write_json(output / "installation.json", {
        "schema": "taku.ota-baseline-installation/1", "method": "adb-sideload",
        "baselineReceiptSha256": baseline["receiptSha256"], "packageSha256": checksum,
        "adbSerialSha256": serial_hash, "assessmentSha256": args.assessment_sha256,
        "exitCode": completed.returncode, "commandCompleted": completed.returncode == 0 and unchanged,
        "log": "sideload.log", "logSha256": digest(output / "sideload.log"),
        "targetBootVerified": False, "dataPreservationVerified": False})
    require(completed.returncode == 0 and unchanged, "Full baseline sideload needs investigation; preserve recovery/logs")
    return result


def validate_source_installation(path, expected_hash, plan, before, before_hash=None, *, ancestry=()):
    require(digest(path) == expected_hash, "Source installation SHA-256 differs")
    value = read_json(path)
    if value.get("schema") == "taku.incremental-source-installation/1":
        import ota_chain
        identity = pathlib.Path(path).resolve()
        require(identity not in ancestry and len(ancestry) <= ota_chain.MAX_DEPTH,
                "Cyclic or excessive incremental source evidence")
        require(before_hash is not None, "Incremental source requires the explicit current before SHA-256")
        chain = (*ancestry, identity)
        return ota_chain.validate_source(path, expected_hash, plan["source"], before, before_hash,
            lambda paths, refs: validate_prior_upgrade(paths, refs, ancestry=chain))
    if value.get("schema") == "taku.direct-image-source-installation/1":
        import ota_source
        require(before_hash is not None, "Direct-image source requires the explicit before SHA-256")
        return ota_source.validate_direct_source(path, expected_hash, plan["source"], before, before_hash)[0]
    require(value.get("schema") == "taku.ota-baseline-installation/1" and value.get("method") == "adb-sideload"
            and value.get("commandCompleted") is True and value.get("exitCode") == 0
            and value.get("baselineReceiptSha256") == plan["source"]["receiptSha256"]
            and value.get("packageSha256") in {r["sha256"] for r in plan["source"]["fullOtas"]}
            and value.get("adbSerialSha256") == before["device"]["adbSerialSha256"] and value.get("log") == "sideload.log",
            "No successful full baseline installation on the observed Pixel 9")
    require(digest(file_under(path.parent.resolve(), value["log"])) == value["logSha256"],
            "Source installation log changed")
    check_firmware_assessment(path.parent / "firmware-assessment/assessment.json", value["assessmentSha256"],
                              plan["source"], value["adbSerialSha256"], value["packageSha256"])
    return value


def validate_prior_upgrade(paths, refs, *, ancestry=()):
    """Re-enter the existing crypto, delivery and installation gates; no ADB."""
    prior_plan = load_plan(paths["plan"], refs["plan"]["sha256"])
    delivery = read_json(paths["delivery"])
    old_before_path = paths["delivery"].parent / "pre-upgrade.json"
    old_before_hash = delivery["beforeSha256"]
    before = validate_before(old_before_path, old_before_hash, prior_plan)
    value, package = validate_delivery(paths["delivery"], refs["delivery"]["sha256"], prior_plan,
        refs["plan"]["sha256"], old_before_hash, ancestry=ancestry)
    args = SimpleNamespace(before=old_before_path, before_sha256=old_before_hash,
        delivery=paths["delivery"], delivery_sha256=refs["delivery"]["sha256"],
        plan_sha256=refs["plan"]["sha256"], installation=paths["installation"],
        installation_sha256=refs["installation"]["sha256"])
    installation = validate_installation_record(args, prior_plan, before, value, package)
    return {"plan": prior_plan, "before": before, "delivery": value, "installation": installation}


def validate_delivery(path, expected_hash, plan, plan_hash, before_hash, *, ancestry=()):
    require(digest(path) == expected_hash, "OTA delivery SHA-256 differs")
    value = read_json(path)
    require(value.get("schema") == "taku.ota-delivery/1" and value.get("planSha256") == plan_hash
            and value.get("beforeSha256") == before_hash and value.get("id") == plan["id"],
            "OTA delivery does not belong to this plan/device evidence")
    require(digest(file_under(path.parent.resolve(), "plan.json")) == plan_hash, "Delivered plan changed")
    before = validate_before(file_under(path.parent.resolve(), "pre-upgrade.json"), before_hash, plan)
    validate_source_installation(path.parent / "source-installation/installation.json",
                                 value["sourceInstallationSha256"], plan, before, before_hash, ancestry=ancestry)
    report_path = file_under(path.parent.resolve(), "incremental-ota.json")
    require(digest(report_path) == value["generatorReportSha256"], "Delta generation report changed")
    report = read_json(report_path)
    require(report.get("schema") == "taku.incremental-ota/1" and report.get("result") == "verified",
            "Delta generation/signature validation did not complete")
    require(all(report.get("checks", {}).get(key) is True for key in
                ("exactSourceTargetIdentityVerified", "noWipeOrDowngrade",
                 "otaPackageSignatureVerified", "payloadSignatureVerified")), "Incomplete delta acceptance")
    require(report.get("generatorSha256") == digest(file_under(path.parent.resolve(), "generate-incremental-ota.py")),
            "Delta generator evidence differs")
    for field, name in (("sourceTargetFiles", "source"), ("targetTargetFiles", "target")):
        candidate = plan[name]
        require(report[field]["sha256"] == candidate["targetFiles"]["sha256"]
                and report[field]["bytes"] == candidate["targetFiles"]["bytes"],
                "Delta report belongs to another " + name + " target-files")
    operations = set()
    for command in report["commands"]:
        operations.add(command["operation"])
        require(command["exitCode"] == 0 and digest(file_under(path.parent.resolve(), command["log"])) == command["logSha256"],
                "Delta command/log evidence differs")
    require(operations == {"generate", "verify-signatures"} and len(report["commands"]) == 2,
            "Missing delta command coverage")
    package = file_under(path.parent.resolve(), "incremental-ota.zip")
    expected = {"path": package.name, "sha256": report["package"]["sha256"], "bytes": report["package"]["bytes"]}
    require(value["package"] == expected and package.stat().st_size == expected["bytes"]
            and digest(package) == expected["sha256"], "Incremental OTA bytes differ")
    source_record = read_json(path.parent / "source-installation/installation.json")
    if source_record.get("method") == "direct-fastboot-images":
        import ota_source
        _, reference, qualification = ota_source.validate_direct_source(
            path.parent / "source-installation/installation.json", value["sourceInstallationSha256"],
            plan["source"], before, before_hash)
        require(value.get("sourcePartitionValidation") == ota_source.validate_delta_source(package, reference, qualification),
                "Delta source-partition validation differs")
    else:
        require("sourcePartitionValidation" not in value, "Legacy installation cannot claim direct partition qualification")
    return value, package


def generate(args, plan):
    # Same lease used by the build controller before starting/replacing a build.
    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / "source-operation.lock").open("a") as guard:
        fcntl.flock(guard, fcntl.LOCK_EX)
        inputs = current_builder(plan["target"])
        load_plan(args.plan, args.plan_sha256, revalidate=True)
        before = validate_before(args.before, args.before_sha256, plan)
        validate_source_installation(args.source_installation, args.source_installation_sha256, plan, before, args.before_sha256)
        output = args.output.resolve()
        require(not output.exists(), "Each delta generation attempt requires a new output directory")
        source_evidence_root = args.source_installation.resolve().parent
        require(not output.is_relative_to(source_evidence_root) and not source_evidence_root.is_relative_to(output),
                "Delta output must not overlap the accepted source-installation evidence")
        for name in ("source", "target"):
            require(not output.is_relative_to(pathlib.Path(plan[name]["directory"])),
                    "Delta outputs must not alter a baseline archive")
        output.parent.mkdir(parents=True, exist_ok=True)
        helper = ROOT / "image/generate-incremental-ota.py"
        helper_hash = digest(helper)
        frozen = OUT / "ota/generator-inputs" / helper_hash / helper.name
        freeze_helper(helper, frozen)
        require(digest(frozen) == helper_hash, "Frozen delta generator differs")
        command = ["docker", "run", "--rm", "--platform", "linux/amd64", "--network=none",
                   "--cpus=4", "--memory=8g", "--memory-swap=10g"]
        mounts = [f"type=volume,source={inputs['volume']},target=/src,readonly",
                  f"type=volume,source={inputs['referenceVolume']},target=/reference,readonly",
                  f"type=volume,source={inputs['previousSourceVolume']},target=/previous,readonly",
                  f"type=bind,source={plan['source']['directory']},target=/baseline-a,readonly",
                  f"type=bind,source={plan['target']['directory']},target=/baseline-b,readonly",
                  f"type=bind,source={output.parent},target=/results",
                  f"type=bind,source={frozen.parent},target=/generator,readonly"]
        require(all("," not in str(p) for p in (output.parent, frozen.parent,
                plan["source"]["directory"], plan["target"]["directory"])), "Comma in Docker bind path")
        for mount in mounts:
            command += ["--mount", mount]
        command += ["--entrypoint", "python3", inputs["builderImage"], "/generator/" + helper.name,
                    "--source-root", "/src", "--source-target-files",
                    "/baseline-a/" + pathlib.Path(plan["source"]["targetFiles"]["path"]).name,
                    "--target-target-files", "/baseline-b/" + pathlib.Path(plan["target"]["targetFiles"]["path"]).name,
                    "--output-dir", "/results/" + output.name]
        # A failed worker retains its partial package/logs. Never overwrite it.
        subprocess.run(command, check=True)
        require(current_builder(plan["target"]) == inputs, "Builder changed during delta generation")
        load_plan(args.plan, args.plan_sha256, revalidate=True)
        validate_before(args.before, args.before_sha256, plan)
        validate_source_installation(args.source_installation, args.source_installation_sha256, plan, before, args.before_sha256)
        for source, name in ((args.plan, "plan.json"), (frozen, "generate-incremental-ota.py")):
            copy_exact(source, output / name)
        report = read_json(output / "incremental-ota.json")
        import ota_device
        ota_device.copy_before_evidence(args.before, args.before_sha256, output,
                                       ota_device.transition_bindings(plan["source"], plan["target"]))
        source_evidence = output / "source-installation"
        source_record = read_json(args.source_installation)
        source_partition_validation = None
        if source_record.get("method") == "direct-fastboot-images":
            import ota_source
            ota_source.copy_direct_source(args.source_installation, args.source_installation_sha256,
                                          source_evidence, plan["source"], before, args.before_sha256)
            _, reference, qualification = ota_source.validate_direct_source(
                source_evidence / "installation.json", args.source_installation_sha256,
                plan["source"], before, args.before_sha256)
            source_partition_validation = ota_source.validate_delta_source(output / "incremental-ota.zip", reference, qualification)
        elif source_record.get("schema") == "taku.incremental-source-installation/1":
            import ota_chain
            ota_chain.copy_tree(args.source_installation.parent, source_evidence)
            validate_source_installation(source_evidence / "installation.json", args.source_installation_sha256,
                                         plan, before, args.before_sha256)
        else:
            source_evidence.mkdir()
            copy_exact(args.source_installation, source_evidence / "installation.json")
            copy_exact(file_under(args.source_installation.parent.resolve(), source_record["log"]), source_evidence / source_record["log"])
            assessment_root = args.source_installation.parent / "firmware-assessment"
            accepted_assessment = source_evidence / "firmware-assessment"
            accepted_assessment.mkdir()
            copy_exact(assessment_root / "assessment.json", accepted_assessment / "assessment.json")
            for item in read_json(assessment_root / "assessment.json")["evidence"]:
                destination = accepted_assessment / item["path"]
                require(destination.resolve().is_relative_to(accepted_assessment), "Escaped assessment destination")
                destination.parent.mkdir(parents=True, exist_ok=True)
                copy_exact(file_under(assessment_root.resolve(), item["path"]), destination)
        delivery = {"schema": "taku.ota-delivery/1", "id": plan["id"],
                    "planSha256": args.plan_sha256, "beforeSha256": args.before_sha256,
                    "sourceInstallationSha256": args.source_installation_sha256,
                    "generatorReportSha256": digest(output / "incremental-ota.json"),
                    "package": {"path": "incremental-ota.zip", "sha256": report["package"]["sha256"],
                                "bytes": report["package"]["bytes"]},
                    "physicalDeviceVerified": False, "dataPreservationVerified": False}
        if source_partition_validation is not None:
            delivery["sourcePartitionValidation"] = source_partition_validation
        result = write_json(output / "delivery.json", delivery)
        validate_delivery(output / "delivery.json", result["sha256"], plan, args.plan_sha256, args.before_sha256)
        return result


def sideload(args, plan):
    import ota_device
    before = validate_before(args.before, args.before_sha256, plan)
    _, package = validate_delivery(args.delivery, args.delivery_sha256, plan, args.plan_sha256, args.before_sha256)
    ota_device.Adb(args.serial, args.adb)
    require(ota_device.sha_text(args.serial) == before["device"]["adbSerialSha256"],
            "Sideload serial differs from the observed source phone")
    # Recovery must already be in Apply update / ADB sideload mode. This command
    # never enters a bootloader, unlocks, formats userdata or flashes partitions.
    state = subprocess.check_output([args.adb, "-s", args.serial, "get-state"], text=True).strip()
    require(state == "sideload", "Put the same phone in recovery ADB sideload mode first")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    command = [args.adb, "-s", args.serial, "sideload", str(package)]
    with (output / "sideload.log").open("xb") as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    record = {"schema": "taku.ota-installation/1", "method": "adb-sideload", "planSha256": args.plan_sha256,
              "beforeSha256": args.before_sha256, "deliverySha256": args.delivery_sha256,
              "packageSha256": digest(package), "adbSerialSha256": ota_device.sha_text(args.serial),
              "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "exitCode": completed.returncode, "log": "sideload.log", "logSha256": digest(output / "sideload.log"),
              "commandCompleted": completed.returncode == 0,
              "targetBootVerified": False, "dataPreservationVerified": False}
    result = write_json(output / "installation.json", record)
    require(completed.returncode == 0, "Sideload did not return success; preserve the log and inspect recovery")
    return result


def check_installation(args, plan, *, ancestry=()):
    before = validate_before(args.before, args.before_sha256, plan)
    delivery, package = validate_delivery(args.delivery, args.delivery_sha256, plan, args.plan_sha256,
                                         args.before_sha256, ancestry=ancestry)
    return validate_installation_record(args, plan, before, delivery, package)


def validate_installation_record(args, plan, before, delivery, package):
    """Check an installation after its exact before/delivery have been authenticated."""
    require(digest(args.installation) == args.installation_sha256, "Installation evidence SHA-256 differs")
    record = read_json(args.installation)
    require(record.get("schema") == "taku.ota-installation/1" and record.get("method") in ("adb-sideload", "lineage-updater")
            and record.get("commandCompleted") is True and record.get("exitCode") == 0,
            "No completed incremental sideload is recorded")
    require(record.get("planSha256") == args.plan_sha256 and record.get("beforeSha256") == args.before_sha256
            and record.get("deliverySha256") == args.delivery_sha256
            and record.get("packageSha256") == delivery["package"]["sha256"]
            and record.get("adbSerialSha256") == before["device"]["adbSerialSha256"],
            "Installation belongs to another phone/package")
    require(digest(file_under(args.installation.parent.resolve(), record["log"])) == record["logSha256"],
            "Installation log changed")
    if record["method"] == "lineage-updater":
        import ota_updater
        ota_updater.check_record(record, before, delivery, args.installation.parent.resolve(), package, plan["source"])
        if before.get("schema") == "taku.ota-device-before/2":
            require(record.get("initial", {}).get("bootId") == before["captureBootId"],
                    "Native installation did not use the qualified source boot")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    plan_command = commands.add_parser("plan", help="Validate two exported full-image baselines; no device operations")
    plan_command.add_argument("--from-baseline", type=pathlib.Path, required=True)
    plan_command.add_argument("--to-baseline", type=pathlib.Path, required=True)
    plan_command.add_argument("--output", type=pathlib.Path, required=True)
    baseline_command=commands.add_parser("bind-baseline",help="Seal one verified export for initial USB installation")
    baseline_command.add_argument("--baseline",type=pathlib.Path,required=True)
    baseline_command.add_argument("--output",type=pathlib.Path,required=True)
    reference_command = commands.add_parser("source-reference", help="Authenticate all source TF raw images against its signed full payload; no device")
    reference_command.add_argument("--baseline", type=pathlib.Path, required=True)
    reference_command.add_argument("--avbtool", type=pathlib.Path, required=True)
    reference_command.add_argument("--payload-proto", type=pathlib.Path, required=True)
    reference_command.add_argument("--output", type=pathlib.Path, required=True)
    for name in ("generate", "baseline-sideload", "device-before", "sideload", "observe-updater", "device-after", "source-partitions", "bind-direct-source", "bind-incremental-source"):
        command = commands.add_parser(name)
        if name=="baseline-sideload":
            source=command.add_mutually_exclusive_group(required=True)
            source.add_argument("--plan",type=pathlib.Path)
            source.add_argument("--baseline-binding",type=pathlib.Path)
            command.add_argument("--plan-sha256")
            command.add_argument("--baseline-sha256")
        else:
            command.add_argument("--plan", type=pathlib.Path, required=True)
            command.add_argument("--plan-sha256", required=True)
        if name not in ("device-before", "baseline-sideload"):
            command.add_argument("--before", type=pathlib.Path, required=True)
            command.add_argument("--before-sha256", required=True)
        if name in ("generate", "sideload", "baseline-sideload", "observe-updater", "source-partitions", "bind-direct-source", "bind-incremental-source"):
            command.add_argument("--output", type=pathlib.Path, required=True)
        if name in ("device-before", "device-after", "sideload", "baseline-sideload", "observe-updater", "source-partitions"):
            command.add_argument("--serial", required=True)
            command.add_argument("--adb", default="adb")
        if name in ("device-before", "device-after"):
            command.add_argument("--evidence-dir", type=pathlib.Path, required=True)
        if name == "device-before":
            probe = command.add_mutually_exclusive_group(required=True)
            probe.add_argument("--probe-apk", type=pathlib.Path)
            probe.add_argument("--reuse-probe-anchor", type=pathlib.Path)
            command.add_argument("--probe-anchor-sha256")
        if name in ("source-partitions", "bind-direct-source"):
            command.add_argument("--reference", type=pathlib.Path, required=True)
            command.add_argument("--reference-sha256", required=True)
        if name == "bind-direct-source":
            command.add_argument("--qualification", type=pathlib.Path, required=True)
            command.add_argument("--qualification-sha256", required=True)
            command.add_argument("--direct-evidence", type=pathlib.Path, required=True)
            command.add_argument("--direct-evidence-sha256", required=True)
        if name == "bind-incremental-source":
            command.add_argument("--previous-plan-sha256", required=True)
            for component in ("delivery", "installation", "after", "acceptance"):
                command.add_argument("--previous-" + component, type=pathlib.Path, required=True)
                command.add_argument("--previous-" + component + "-sha256", required=True)
        if name == "generate":
            command.add_argument("--source-installation", type=pathlib.Path, required=True)
            command.add_argument("--source-installation-sha256", required=True)
        if name == "baseline-sideload":
            command.add_argument("--package", type=pathlib.Path, required=True)
            command.add_argument("--assessment", type=pathlib.Path, required=True)
            command.add_argument("--assessment-sha256", required=True)
        if name in ("sideload", "device-after", "observe-updater"):
            command.add_argument("--delivery", type=pathlib.Path, required=True)
            command.add_argument("--delivery-sha256", required=True)
        if name == "observe-updater":
            command.add_argument("--engine-log", required=True,
                                 help="Regular current engine log bound to a unique open /proc/<pid>/fd descriptor")
            command.add_argument("--downloaded-package", required=True)
            command.add_argument("--updater-apk", type=pathlib.Path, required=True)
            command.add_argument("--updater-verification", type=pathlib.Path, required=True)
            command.add_argument("--updater-verification-sha256", required=True)
            command.add_argument("--timeout", type=int, default=3600)
        if name == "device-after":
            command.add_argument("--installation", type=pathlib.Path, required=True)
            command.add_argument("--installation-sha256", required=True)
    args = parser.parse_args()
    try:
        if args.action == "plan":
            plan = create_plan(load_baseline(args.from_baseline), load_baseline(args.to_baseline))
            result = write_json(args.output, plan)
        elif args.action=="bind-baseline":
            result=write_json(args.output,create_baseline_binding(load_baseline(args.baseline)))
        elif args.action == "source-reference":
            import ota_source
            result = ota_source.prepare_reference(args.baseline, args.avbtool, args.output, payload_proto=args.payload_proto)
        elif args.action=="baseline-sideload":
            if args.baseline_binding:
                require(args.baseline_sha256 and not args.plan_sha256,"Use only --baseline-sha256 with --baseline-binding")
                baseline=load_baseline_binding(args.baseline_binding,args.baseline_sha256)
            else:
                require(args.plan_sha256 and not args.baseline_sha256,"Use only --plan-sha256 with --plan")
                baseline=load_plan(args.plan,args.plan_sha256)["source"]
            result=baseline_sideload(args,baseline)
        else:
            plan = load_plan(args.plan, args.plan_sha256)
            if args.action == "generate":
                result = generate(args, plan)
            elif args.action == "source-partitions":
                import ota_source
                result = ota_source.observe_partitions(args.serial, plan["source"], plan["target"],
                    args.before, args.before_sha256, args.reference, args.reference_sha256, args.output, adb=args.adb)
            elif args.action == "bind-direct-source":
                import ota_source
                result = ota_source.bind_direct_source(plan, args.before, args.before_sha256,
                    args.reference, args.reference_sha256, args.qualification, args.qualification_sha256,
                    args.direct_evidence, args.direct_evidence_sha256, args.output)
            elif args.action == "bind-incremental-source":
                import ota_chain
                before = validate_before(args.before, args.before_sha256, plan)
                result = ota_chain.bind(args, plan, before, validate_prior_upgrade)
            elif args.action == "sideload":
                result = sideload(args, plan)
            elif args.action == "observe-updater":
                import ota_updater
                before = validate_before(args.before, args.before_sha256, plan)
                delivery, package = validate_delivery(args.delivery, args.delivery_sha256, plan, args.plan_sha256, args.before_sha256)
                if before.get("schema") == "taku.ota-device-before/2":
                    import ota_device, ota_source
                    current = ota_source.read_only_state(ota_device.Adb(args.serial, args.adb))
                    require(current["bootId"] == before["captureBootId"],
                            "Source rebooted after exact partition qualification; recapture before/source evidence")
                result = ota_updater.observe(args, plan, before, delivery, package)
            else:
                import ota_device
                if args.action == "device-before":
                    if args.reuse_probe_anchor:
                        require(args.probe_anchor_sha256, "--reuse-probe-anchor requires --probe-anchor-sha256")
                        result = ota_device.capture_before_reusing_probe(args.serial, plan["source"], plan["target"],
                            args.evidence_dir, args.reuse_probe_anchor, args.probe_anchor_sha256, adb=args.adb)
                    else:
                        require(not args.probe_anchor_sha256, "--probe-anchor-sha256 requires --reuse-probe-anchor")
                        result = ota_device.capture_before(args.serial, plan["source"], plan["target"],
                                                          args.evidence_dir, args.probe_apk, adb=args.adb)
                else:
                    installation = check_installation(args, plan)
                    require(args.before.resolve() == (args.evidence_dir / "pre-upgrade.json").resolve(),
                            "Use the same device evidence directory for before and after")
                    result = ota_device.capture_after(args.serial, plan["source"], plan["target"],
                                                      args.evidence_dir, args.before_sha256, adb=args.adb)
                    result = write_json(args.evidence_dir / "incremental-acceptance.json", {
                        "schema": "taku.ota-acceptance/1", "planSha256": args.plan_sha256,
                        "deliverySha256": args.delivery_sha256, "installationSha256": args.installation_sha256,
                        "beforeSha256": args.before_sha256, "afterSha256": result["sha256"],
                        **({"incrementalSideloadCompleted": True} if installation["method"] == "adb-sideload" else
                           {"installationMethod": "lineage-updater", "incrementalUpdaterCompleted": True}),
                        "targetBootVerified": True,
                        "probePrivateDataPreserved": True, "allUserDataVerified": False,
                        "snapshotMergeVerified": False, "functionalRegressionVerified": False,
                        "productionReleaseReady": False})
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ReleaseError, DeviceVerificationError, OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, "Pixel 9 OTA: " + str(error) + "\n")


if __name__ == "__main__":
    main()
