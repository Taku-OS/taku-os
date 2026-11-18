#!/usr/bin/env python3
"""Observe a user-started native Updater installation; never apply or reboot.

Only update_engine_client --follow is launched. A successful process exit alone
is insufficient: Android also exits 0 for kUpdatedButNotActive.
"""
import datetime
import json
import os
import pathlib
import re
import selectors
import struct
import subprocess
import time
import zipfile

import ota_device
import ota_release


PACKAGE = "org.lineageos.updater"
FOLLOW = ["shell", "/system/bin/update_engine_client", "--follow"]
STATUS = re.compile(r"onStatusUpdate\(UPDATE_STATUS_([A-Z_]+) \(([0-9]+)\), ([0-9.eE+\-]+)\)")
COMPLETE = re.compile(r"onPayloadApplicationComplete\(ErrorCode::(k[A-Za-z0-9]+) \(([0-9]+)\)\)")
STATUS_CODES = {"IDLE": 0, "CHECKING_FOR_UPDATE": 1, "UPDATE_AVAILABLE": 2,
                "DOWNLOADING": 3, "VERIFYING": 4, "FINALIZING": 5,
                "UPDATED_NEED_REBOOT": 6, "REPORTING_ERROR_EVENT": 7,
                "ATTEMPTING_ROLLBACK": 8, "DISABLED": 9,
                "NEED_PERMISSION_TO_UPDATE": 10, "CLEANUP_PREVIOUS_UPDATE": 11}
require = ota_device.require
digest = ota_device.digest
APPLY = re.compile(r"ApplyPayload\((file:///data/lineageos_updates/[A-Za-z0-9_.\-]+\.zip), ([0-9]+), ([0-9]+)\) (.*)$")


def payload_binding(package, downloaded_path):
    """Bind the actual engine arguments to bytes in the accepted delivery ZIP."""
    import base64
    import hashlib
    with zipfile.ZipFile(package) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate delivery ZIP entries")
        payload = archive.getinfo("payload.bin")
        require(payload.compress_type == zipfile.ZIP_STORED and payload.file_size > 0,
                "Native file installation requires a stored payload.bin")
        properties = archive.read("payload_properties.txt").decode("utf-8").splitlines()
        values = {}
        for item in properties:
            key, separator, value = item.partition("=")
            require(separator and key not in values and re.fullmatch(r"[A-Z0-9_]+", key) is not None
                    and ", " not in value, "Invalid or duplicate payload property")
            values[key] = value
        require(all(name in values for name in ("FILE_HASH", "FILE_SIZE", "METADATA_HASH", "METADATA_SIZE")),
                "Missing payload identity properties")
        with archive.open(payload) as stream:
            sha = hashlib.sha256()
            for data in iter(lambda: stream.read(1024 * 1024), b""):
                sha.update(data)
        require(values["FILE_SIZE"] == str(payload.file_size)
                and values["FILE_HASH"] == base64.b64encode(sha.digest()).decode(), "Payload properties do not bind actual payload bytes")
        require(values["METADATA_SIZE"].isdigit() and 0 < int(values["METADATA_SIZE"]) <= payload.file_size,
                "Invalid payload metadata size")
        with archive.open(payload) as stream:
            metadata = stream.read(int(values["METADATA_SIZE"]))
        require(values["METADATA_HASH"] == base64.b64encode(hashlib.sha256(metadata).digest()).decode(),
                "Payload metadata digest differs")
    with pathlib.Path(package).open("rb") as stream:
        stream.seek(payload.header_offset)
        header = stream.read(30)
        require(len(header) == 30 and header[:4] == b"PK\x03\x04", "Invalid ZIP local payload header")
        name_size, extra_size = struct.unpack_from("<HH", header, 26)
        offset = payload.header_offset + 30 + name_size + extra_size
    return {"url": "file://" + downloaded_path, "offset": offset, "size": 0,
            "payloadBytes": payload.file_size, "headers": properties,
            "propertiesSha256": ota_device.sha_text("\n".join(properties))}


def engine_snapshot(adb, path):
    require(re.fullmatch(r"/data/misc/update_engine_log/update_engine[A-Za-z0-9_.\-]*", path) is not None,
            "Use the current regular native update-engine log file")
    pid = adb.shell("pidof", "update_engine")
    require(re.fullmatch(r"[1-9][0-9]*", pid) is not None, "Expected exactly one native update-engine process")
    process_stat = adb.shell("cat", "/proc/" + pid + "/stat").split(") ", 1)
    require(len(process_stat) == 2 and len(process_stat[1].split()) >= 20
            and process_stat[1].split()[19].isdigit(), "Missing engine process start time")
    start_ticks = int(process_stat[1].split()[19])
    inode = adb.shell("stat", "-c", "%F:%i", path)
    require(re.fullmatch(r"regular file:[1-9][0-9]*", inode) is not None,
            "Select the regular engine log, not a rotating symlink")
    descriptors=adb.shell('ls','-1','/proc/'+pid+'/fd').splitlines()
    require(descriptors and len(descriptors)<=1024 and all(re.fullmatch(r'[0-9]+',fd) for fd in descriptors),
            'Could not enumerate native engine file descriptors')
    matches=[]
    for fd in descriptors:
        descriptor='/proc/'+pid+'/fd/'+fd
        if adb.shell('readlink',descriptor)==path:
            require(adb.shell('stat','-L','-c','%i',descriptor)==inode.split(':')[1],
                    'Native engine descriptor inode differs from log file')
            matches.append(int(fd))
    require(len(matches)==1,'Engine log must be the one exact file currently opened by the native process')
    size = adb.shell("stat", "-c", "%s", path)
    require(size.isdigit() and 0 < int(size) <= 100 * 1024 * 1024, "Unexpected engine log size")
    # Adb.shell strips whitespace; use byte-preserving exec-out for a literal
    # file prefix, so its SHA/byte boundary can be checked without normalization.
    read = adb.runner([adb.executable, '-s', adb.serial, 'exec-out', '/system/bin/cat', path],
                      capture_output=True, timeout=30, check=False)
    require(read.returncode==0 and isinstance(read.stdout,bytes) and
            0<len(read.stdout)<=100*1024*1024, 'Could not read the exact native engine log bytes')
    text = read.stdout.decode('utf-8',errors='strict')
    return {"path": path, "pid": int(pid), "fd": matches[0], "startTicks": start_ticks,
            "bootId": boot_id(adb), "inode": inode, "text": text}


def engine_identity(value):
    return {key: value[key] for key in ("path", "pid", "fd", "startTicks", "bootId", "inode")}


def validate_engine_apply(text, expected):
    lines = [line for line in text.splitlines() if "ApplyPayload(" in line]
    require(len(lines) == 1, "This observation needs exactly one actual engine ApplyPayload call")
    match = APPLY.search(lines[0])
    require(match is not None, "Unsupported actual engine payload source")
    url, offset, size, headers = match.groups()
    require(url == expected["url"] and int(offset) == expected["offset"] and int(size) == expected["size"]
            and headers.split(", ") == expected["headers"],
            "Actual engine payload URL/offset/size/headers differ from the verified delta")
    return {"url": url, "offset": int(offset), "size": int(size), "headers": headers.split(", "),
            "propertiesSha256": ota_device.sha_text("\n".join(headers.split(", ")))}


def parse_events(log):
    events = []
    for line in log.splitlines():
        status, complete = STATUS.search(line), COMPLETE.search(line)
        if status:
            name, code, progress = status.groups()
            require(STATUS_CODES.get(name) == int(code), "Unknown native update-engine status")
            progress = float(progress)
            require(0 <= progress <= 1, "Invalid native update progress")
            events.append({"event": "status", "name": name, "code": int(code), "progress": progress})
        elif complete:
            name, code = complete.groups()
            events.append({"event": "completion", "name": name, "code": int(code)})
        else:
            require("onStatusUpdate(" not in line and "onPayloadApplicationComplete(" not in line,
                    "Malformed native update callback")
    return events


def validate_events(events, exit_code):
    require(events and events[0].get("event") == "status" and events[0].get("name") == "IDLE",
            "Observer must attach while update engine is initially IDLE")
    completed = [event for event in events if event["event"] == "completion"]
    require(completed == [{"event": "completion", "name": "kSuccess", "code": 0}],
            "Native update did not explicitly report kSuccess (0)")
    completion_index = next(i for i, event in enumerate(events) if event["event"] == "completion")
    status = [event for event in events if event["event"] == "status"]
    require(any(event["event"] == "status" and event["name"] in ("DOWNLOADING", "VERIFYING", "FINALIZING")
                for event in events[:completion_index]),
            "No actual payload application was observed")
    require(status[-1]["name"] == "UPDATED_NEED_REBOOT" and status[-1]["code"] == 6,
            "Native engine did not reach UPDATED_NEED_REBOOT")
    # Cleanup's numeric status is 11, but it precedes payload application. Only
    # this successful lifecycle is accepted; resets, retries and error bounces
    # need their own observation instead of borrowing this attempt's success.
    phases = {name: index for index, name in enumerate(("IDLE", "CHECKING_FOR_UPDATE", "UPDATE_AVAILABLE",
              "CLEANUP_PREVIOUS_UPDATE", "DOWNLOADING", "VERIFYING", "FINALIZING", "UPDATED_NEED_REBOOT"))}
    require(all(event["name"] in phases for event in status),
            "Native engine reported an unsuccessful update state")
    require(all(phases[left["name"]] <= phases[right["name"]] for left, right in zip(status, status[1:])),
            "Native engine callbacks moved backwards between update phases")
    # Android may notify kSuccess just before or just after status 6. Both are
    # required, and after completion only the terminal status may follow.
    require(all(event["event"] == "status" and event["name"] == "UPDATED_NEED_REBOOT"
                for event in events[completion_index + 1:]), "Unexpected callbacks after completion")
    require(type(exit_code) is int and exit_code == 0, "Native follow process did not actually exit 0")
    return True


def boot_id(adb):
    value = adb.shell("cat", "/proc/sys/kernel/random/boot_id")
    require(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", value) is not None,
            "Missing device boot ID")
    return value


def proof_contract(proof, proof_sha256, source=None):
    """Legacy v2 or branded v3/v4 bound to the exact authenticated source."""
    version = 2
    if source is not None and ("productBranding" in source or "updaterIntegration" in source):
        brand, integration = source.get("productBranding", {}), source.get("updaterIntegration", {})
        try:
            version = ota_release.branding_version(brand)
        except ValueError as error:
            raise ota_device.DeviceVerificationError(str(error)) from error
        require(integration.get("versionCode") == version and integration.get("apkSignatureVerified") is True
                and integration.get("apkEntry") == "SYSTEM_EXT/priv-app/Updater/Updater.apk"
                and integration.get("uri") == "https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}"
                and ota_device.is_sha(integration.get("sourceEvidenceSha256"))
                and integration.get("apkEvidenceSha256") == proof_sha256,
                "Updater source integration differs from its signature evidence")
        for name in ("versionCode", "apkSha256", "apkBytes", "platformCertificateSha256", "manifestSha256"):
            require(integration.get(name) == proof.get(name), "Updater source APK contract differs: " + name)
    require(proof.get("schema") == "taku.lineage-updater-apk/1" and proof.get("packageName") == PACKAGE
            and proof.get("versionCode") == version and proof.get("apkSignatureVerified") is True
            and ota_device.is_sha(proof.get("platformCertificateSha256"))
            and ota_device.is_sha(proof.get("manifestSha256")), "No accepted platform-signed Updater APK evidence")
    return version


def apk_contract(apk, proof_path, proof_sha256, source=None):
    apk, proof_path = pathlib.Path(apk), pathlib.Path(proof_path)
    require(ota_device.is_sha(proof_sha256) and digest(proof_path) == proof_sha256,
            "Updater APK verification evidence changed")
    proof = json.loads(proof_path.read_text())
    version = proof_contract(proof, proof_sha256, source)
    require(apk.is_file() and not apk.is_symlink() and digest(apk) == proof.get("apkSha256")
            and apk.stat().st_size == proof.get("apkBytes"), "Updater APK differs from its signature evidence")
    return {"packageName": PACKAGE, "versionCode": version, "apkSha256": proof["apkSha256"],
            "apkBytes": proof["apkBytes"], "platformCertificateSha256": proof["platformCertificateSha256"],
            "manifestSha256": proof["manifestSha256"], "verificationSha256": proof_sha256}


def installed_apk(adb, expected):
    output = adb.shell("pm", "path", "--user", "0", PACKAGE)
    require(output.startswith("package:") and "\n" not in output, "Updater must have one installed APK")
    path = output.removeprefix("package:")
    require(re.fullmatch(r"/(?:system_ext/priv-app/Updater/Updater\.apk|data/app/[A-Za-z0-9_./=+~\-]+/base\.apk)", path)
            is not None and ".." not in pathlib.PurePosixPath(path).parts, "Unexpected installed Updater APK path")
    checksum = ota_device.parse_sha(adb.shell("/system/bin/sha256sum", path), path)
    size = adb.shell("stat", "-c", "%s", path)
    package_dump = adb.shell("dumpsys", "package", PACKAGE)
    versions = re.findall(r"\bversionCode=([0-9]+)\b", package_dump)
    # An updated system app also has a hidden system version 1 in dumpsys. Read
    # the active Package section before Hidden system packages.
    active_dump = package_dump.split("Hidden system packages:", 1)[0]
    active_versions = re.findall(r"\bversionCode=([0-9]+)\b", active_dump)
    require(active_versions and set(active_versions) == {str(expected["versionCode"])} and versions,
            "Installed Updater version differs from accepted APK")
    require(checksum == expected["apkSha256"] and size.isdigit() and int(size) == expected["apkBytes"],
            "Installed Updater bytes differ from platform-signed APK")
    return {**expected, "devicePath": path}


def downloaded_zip(adb, path, package):
    require(isinstance(path, str) and re.fullmatch(r"/data/lineageos_updates/[A-Za-z0-9_.\-]+\.zip", path)
            is not None, "Use a complete ZIP in the native Updater download directory")
    checksum = ota_device.parse_sha(adb.shell("/system/bin/sha256sum", path), path)
    size = adb.shell("stat", "-c", "%s", path)
    require(size.isdigit() and int(size) == package["bytes"] and checksum == package["sha256"],
            "Downloaded on-device ZIP differs from the verified delivery")
    return {"devicePath": path, "sha256": checksum, "bytes": int(size)}


def source_observation(adb, before, downloaded_path, package, updater):
    device = ota_device.inspect_device(adb, before["baselines"]["from"])
    require(device == before["device"], "Phone/source identity changed since before capture")
    probe = ota_device.probe_state(adb)
    require(probe == before["probe"], "Probe changed before reboot; preserve evidence")
    return {"bootId": boot_id(adb), "device": device, "probe": probe,
            "downloadedZip": downloaded_zip(adb, downloaded_path, package),
            "updater": installed_apk(adb, updater)}


def follow(adb, log_path, timeout, on_idle=None):
    """Stream a fixed read-only native command and return its actual exit status."""
    require(type(timeout) is int and 1 <= timeout <= 7200, "Observation timeout must be 1..7200 seconds")
    command = [adb.executable, "-s", adb.serial, *FOLLOW]
    started = time.monotonic()
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    events, text_buffer, announced = [], b"", False
    error = None
    with pathlib.Path(log_path).open("xb") as log, selectors.DefaultSelector() as selector:
        selector.register(process.stdout, selectors.EVENT_READ)
        try:
            while selector.get_map():
                require(time.monotonic() - started < timeout, "Native follow observation timed out")
                for key, _ in selector.select(timeout=min(0.5, timeout)):
                    data = key.fileobj.read1(64 * 1024)
                    if not data:
                        selector.unregister(key.fileobj)
                        continue
                    log.write(data); log.flush()
                    text_buffer += data
                    while b"\n" in text_buffer:
                        raw, text_buffer = text_buffer.split(b"\n", 1)
                        new = parse_events(raw.decode("utf-8", errors="strict"))
                        events.extend(new)
                        if new and not announced:
                            require(new[0]["event"] == "status" and new[0]["name"] == "IDLE",
                                    "Observer attached after update started; no initial IDLE")
                            announced = True
                            if on_idle:
                                os.fsync(log.fileno())
                                on_idle()
                        if any(event["event"] == "completion" and (event["name"] != "kSuccess" or event["code"] != 0)
                               for event in new):
                            raise ota_device.DeviceVerificationError("Native update application reported failure")
            if text_buffer:
                events.extend(parse_events(text_buffer.decode("utf-8", errors="strict")))
            remaining = max(0.1, timeout - (time.monotonic() - started))
            exit_code = process.wait(timeout=remaining)
        except Exception as caught:
            error = caught
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait()
            exit_code = process.returncode
        finally:
            process.stdout.close()
    return {"command": command, "exitCode": exit_code, "events": events,
            "elapsedSeconds": round(time.monotonic() - started, 3),
            "observerError": str(error) if error else None}


def seal_engine_before(output, engine):
    """Preserve the exact IDLE-time prefix before inviting the user to install."""
    prefix = pathlib.Path(output) / "update-engine-before.log"
    with prefix.open("xb") as stream:
        stream.write(engine["text"].encode("utf-8")); stream.flush(); os.fsync(stream.fileno())
    evidence = {"schema": "taku.native-updater-engine-before/1",
                "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "identity": engine_identity(engine), "observedAfterInitialIdle": True,
                "prefixLog": prefix.name, "prefixSha256": digest(prefix), "prefixBytes": prefix.stat().st_size}
    metadata = pathlib.Path(output) / "engine-before.json"
    with metadata.open("x") as stream:
        json.dump(evidence, stream, indent=2); stream.write("\n"); stream.flush(); os.fsync(stream.fileno())
    descriptor = os.open(output, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
    return {"path": metadata.name, "sha256": digest(metadata), **evidence}


def observe(args, plan, before, delivery, package):
    updater = apk_contract(args.updater_apk, args.updater_verification, args.updater_verification_sha256, plan.get("source"))
    adb = ota_device.Adb(args.serial, args.adb)
    initial = source_observation(adb, before, args.downloaded_package, delivery["package"], updater)
    expected_payload = payload_binding(package, args.downloaded_package)
    initial_engine = engine_snapshot(adb, args.engine_log)
    require(initial_engine['bootId'] == initial['bootId'], 'Device rebooted before engine attachment')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    # Make the APK signature statement portable alongside the installation log.
    proof = output / "updater-apk-verification.json"
    with proof.open("xb") as stream:
        stream.write(args.updater_verification.read_bytes())
    require(digest(proof) == args.updater_verification_sha256, "Updater proof changed while copying")
    with (output / "source-observation.json").open("x") as stream:
        json.dump(initial, stream, indent=2); stream.write("\n")
    engine_before, sealed_before = None, None
    def ready():
        nonlocal engine_before, sealed_before
        engine_before = engine_snapshot(adb, args.engine_log)
        require(engine_identity(engine_before) == engine_identity(initial_engine),
                'Native engine restarted before initial IDLE')
        sealed_before = seal_engine_before(output, engine_before)
        print(json.dumps({"event": "observer-ready", "state": "IDLE", "bootId": initial["bootId"],
                          "instruction": "Start Install in the native Updater UI; do not reboot until this observer exits."}), flush=True)
    result = follow(adb, output / "update-engine-follow.log", args.timeout, ready)
    final, engine_evidence, error = None, None, result["observerError"]
    try:
        validate_events(result["events"], result["exitCode"])
        require(error is None, "Observer did not complete normally")
        final = source_observation(adb, before, args.downloaded_package, delivery["package"], updater)
        require(final == initial, "Phone rebooted or source/APK/ZIP/probe changed during installation")
        engine_after = engine_snapshot(adb, args.engine_log)
        require(engine_before is not None and engine_identity(engine_before) == engine_identity(engine_after),
                'Native engine process/log/boot changed during observation')
        prefix = engine_before['text']
        require(engine_after['text'].startswith(prefix), 'Native engine log prefix was replaced or truncated')
        suffix = engine_after['text'][len(prefix):]
        engine_log = output / 'update-engine-apply.log'
        with engine_log.open('x') as stream:stream.write(suffix)
        engine_evidence = {'identity': engine_identity(engine_before),
                           'prefixSha256': ota_device.sha_text(prefix), 'prefixBytes': len(prefix.encode()),
                           'observedAfterInitialIdle': True, 'apply': None,
                           'expectedPayload': expected_payload, 'log': engine_log.name,
                           'logSha256': digest(engine_log), 'binderCallerVerified': False}
        engine_evidence['apply'] = validate_engine_apply(suffix, expected_payload)
    except (ota_device.DeviceVerificationError, OSError, ValueError, subprocess.SubprocessError) as caught:
        error = str(caught)
    record = {"schema": "taku.ota-installation/1", "method": "lineage-updater",
              "planSha256": args.plan_sha256, "beforeSha256": args.before_sha256,
              "deliverySha256": args.delivery_sha256, "packageSha256": delivery["package"]["sha256"],
              "adbSerialSha256": ota_device.sha_text(args.serial),
              "completedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              **result, "observerError": error, "commandCompleted": error is None,
              "log": "update-engine-follow.log", "logSha256": digest(output / "update-engine-follow.log"),
              "initial": initial, "final": final, "targetBootVerified": False,
              "dataPreservationVerified": False, 'engine': engine_evidence, 'engineBefore': sealed_before}
    report = ota_device.write_report(output / "installation.json", record)
    require(error is None, "Updater observation failed; preserve log/evidence: " + str(error))
    return report


def check_record(record, before, delivery, directory, package, source=None):
    """Revalidate immutable native log and bindings without consulting booleans alone."""
    require(record.get("method") == "lineage-updater" and record.get("observerError") is None
            and record.get("commandCompleted") is True, "Incomplete native Updater observation")
    command = record.get("command", [])
    require(len(command) == 6 and command[1] == "-s" and command[3:] == FOLLOW
            and ota_device.sha_text(command[2]) == before["device"]["adbSerialSha256"],
            "Observer must use only the native --follow command")
    require(record.get("log") == "update-engine-follow.log", "Unexpected native observer log")
    log = pathlib.Path(directory) / record["log"]
    require(not log.is_symlink() and digest(log) == record.get("logSha256"), "Native observer log changed")
    events = parse_events(log.read_text())
    require(events == record.get("events"), "Native callbacks differ from installation evidence")
    validate_events(events, record.get("exitCode"))
    initial, final = record.get("initial", {}), record.get("final")
    require(final == initial and initial.get("device") == before["device"]
            and initial.get("probe") == before["probe"], "Native observer source identity changed")
    require(re.fullmatch(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", initial.get("bootId", "")) is not None,
            "Missing same-boot observation")
    downloaded = initial.get("downloadedZip", {})
    require(downloaded.get("sha256") == delivery["package"]["sha256"]
            and downloaded.get("bytes") == delivery["package"]["bytes"]
            and re.fullmatch(r"/data/lineageos_updates/[A-Za-z0-9_.\-]+\.zip", downloaded.get("devicePath", "")) is not None,
            "Native observer downloaded ZIP binding differs")
    updater = initial.get("updater", {})
    proof_path = pathlib.Path(directory) / "updater-apk-verification.json"
    require(not proof_path.is_symlink() and digest(proof_path) == updater.get("verificationSha256"),
            "Native Updater APK signature evidence changed")
    proof = json.loads(proof_path.read_text())
    proof_contract(proof, updater.get("verificationSha256"), source)
    for key in ("packageName", "versionCode", "apkSha256", "apkBytes", "platformCertificateSha256", "manifestSha256"):
        require(updater.get(key) == proof.get(key), "Installed Updater identity differs: " + key)
    for key in ("apkSha256", "platformCertificateSha256", "manifestSha256"):
        require(ota_device.is_sha(updater.get(key)), "Invalid Updater identity hash")
    engine=record.get('engine', {})
    identity=engine.get('identity', {})
    require(identity.get('bootId')==initial['bootId'] and type(identity.get('pid')) is int and identity['pid']>0
            and type(identity.get('fd')) is int and identity['fd']>=0
            and type(identity.get('startTicks')) is int and identity['startTicks']>0
            and re.fullmatch(r'/data/misc/update_engine_log/update_engine[A-Za-z0-9_.\-]*',identity.get('path','')) is not None
            and re.fullmatch(r'regular file:[1-9][0-9]*',identity.get('inode','')) is not None,
            'Missing actual native engine process/log identity')
    require(ota_device.is_sha(engine.get('prefixSha256')) and type(engine.get('prefixBytes')) is int
            and engine['prefixBytes']>0 and engine.get('observedAfterInitialIdle') is True
            and engine.get('binderCallerVerified') is False and engine.get('log')=='update-engine-apply.log',
            'Missing bounded native engine observation')
    sealed = record.get('engineBefore', {})
    require(isinstance(sealed, dict) and sealed.get('path') == 'engine-before.json'
            and ota_device.is_sha(sealed.get('sha256')), 'Missing sealed initial engine evidence')
    seal_path = pathlib.Path(directory) / 'engine-before.json'
    prefix_path = pathlib.Path(directory) / 'update-engine-before.log'
    require(seal_path.is_file() and not seal_path.is_symlink() and digest(seal_path) == sealed['sha256']
            and prefix_path.is_file() and not prefix_path.is_symlink(), 'Initial engine seal files changed or missing')
    metadata = json.loads(seal_path.read_text())
    require(metadata.get('schema') == 'taku.native-updater-engine-before/1'
            and metadata.get('observedAfterInitialIdle') is True
            and metadata.get('prefixLog') == prefix_path.name and metadata.get('identity') == identity
            and metadata.get('prefixSha256') == engine['prefixSha256'] == digest(prefix_path)
            and type(metadata.get('prefixBytes')) is int
            and metadata['prefixBytes'] == engine['prefixBytes'] == prefix_path.stat().st_size
            and sealed == {'path': seal_path.name, 'sha256': digest(seal_path), **metadata},
            'Initial engine seal differs from bounded application evidence')
    source_log=pathlib.Path(directory)/engine['log']
    require(not source_log.is_symlink() and digest(source_log)==engine.get('logSha256'), 'Engine application log changed')
    expected=payload_binding(package,downloaded['devicePath'])
    require(engine.get('expectedPayload')==expected and validate_engine_apply(source_log.read_text(),expected)==engine.get('apply'),
            'Actual engine payload does not bind this delta delivery')
    return record
