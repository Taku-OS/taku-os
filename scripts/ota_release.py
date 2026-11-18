#!/usr/bin/env python3
"""Validate immutable full-image deliveries before planning a Pixel 9 delta.

This reads existing cryptographic evidence; it does not claim to re-sign images
or to prove that a phone has installed them. No private signing inputs are read.
"""
import hashlib
import importlib.util
import json
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


SIGNATURES = module("ota_signature_evidence", ROOT / "image/verify-signatures.py")
PAYLOAD = module("ota_payload_inspector", ROOT / "image/inspect-ota-payload.py")
BRANDING = module("ota_product_branding", ROOT / "image/product_branding.py")
digest = SIGNATURES.digest


class ReleaseError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ReleaseError(message)


def read_json(path):
    value = json.loads(pathlib.Path(path).read_text())
    require(isinstance(value, dict), "Expected a JSON object: " + str(path))
    return value


def sha(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def branding_version(brand):
    try:
        return BRANDING.validate_branding(brand)
    except ValueError as error:
        raise ReleaseError("Invalid product branding receipt: " + str(error)) from error


def check_product_upgrade(source, target):
    """Do not let a newer build number disguise a product-version downgrade."""
    if source.get('googleServicesIntegration'):
        require(bool(target.get('googleServicesIntegration')), 'Target cannot drop integrated Google services')
    if source.get('carrierDefaultsIntegration'):
        require(bool(target.get('carrierDefaultsIntegration')), 'Target cannot silently drop integrated carrier defaults')
    if source.get('googleAppsIntegration'):
        require(bool(target.get('googleAppsIntegration')), 'Target cannot silently drop integrated internal Google apps')
    for baseline in (source, target):
        if "productBranding" in baseline:
            branding_version(baseline["productBranding"])
    if "productBranding" in source:
        require("productBranding" in target, "Target cannot drop product branding")
        old = BRANDING.version_parts(source["productBranding"]["productVersion"], allow_legacy=True)
        new = BRANDING.version_parts(target["productBranding"]["productVersion"], allow_legacy=True)
        require(new >= old, "Product version downgrade is unsupported")


def file_under(root, relative):
    path = root / relative
    require(not path.is_symlink() and path.is_file() and path.resolve().is_relative_to(root),
            "Missing or escaped delivery file: " + str(relative))
    return path


def artifact_fields(record):
    return {key: record[key] for key in ("path", "bytes", "sha256")}


def load_source_repair(root, runtime, entries):
    repair=runtime.get('sourceRepair')
    require(bool(repair)==('image/source-repair.json' in entries), 'Archived source repair descriptor is missing')
    if not repair:return None
    capsule=file_under(root,'source-repair.json')
    helper=file_under(root,'source_repair.py')
    require(digest(capsule)==entries.get('image/source-repair.json')==repair.get('capsuleSha256')
            and digest(helper)==entries.get('image/source_repair.py'),
            'Archived source repair capsule/validator differs')
    return capsule.read_bytes()


def check_branding_boot_properties(root, entries, archive, brand, proof):
    """New content-bound policy cannot fall back to legacy SYSTEM_EXT checks."""
    if 'image/branding-properties.json' not in entries:
        require(proof is None, 'Branding boot-property proof requires frozen policy')
        # Legacy archives (including F) retain their original limited evidence.
        require(all(SIGNATURES.properties(archive.read('SYSTEM_EXT/etc/build.prop')).get(key)==value for key,value in {
                'ro.taku.product.name':brand['productName'],'ro.taku.product.version':brand['productVersion'],
                'ro.taku.display.version':brand['displayVersion'],'ro.build.display.id':brand['buildDisplayId']}.items()),
                'Archived product branding properties differ')
        return
    policy=file_under(root,'branding-properties.json')
    helper=file_under(root,'product_branding.py')
    require(digest(policy)==entries['image/branding-properties.json']
            and digest(helper)==entries.get('image/product_branding.py'),
            'Archived branding boot-property policy/helper differs')
    try:
        verifier=module('ota_branding_properties',ROOT/'image/product_branding.py')
        actual=verifier.verify_boot_properties(archive,brand,policy)
    except ValueError as error:
        raise ReleaseError('Archived branding boot properties differ: '+str(error)) from error
    require(proof==actual, 'Archived branding boot-property proof differs')


def load_baseline(directory):
    """Return a portable binding after checking every exported candidate byte."""
    root = pathlib.Path(directory).resolve()
    receipt_path = file_under(root, "baseline-receipt.json")
    receipt = read_json(receipt_path)
    require(receipt.get("schema") == "taku.image-baseline/1", "Unsupported baseline receipt")
    inputs_path = file_under(root, "run-input.json")
    inputs = read_json(inputs_path)
    require(receipt.get("runInputSha256") == digest(inputs_path), "Run-input hash differs from receipt")
    identity = receipt["buildIdentity"]
    require(identity.get("androidRelease") == "17" and identity.get("androidSdk") == 37
            and identity.get("productDevice") == "tokay" and identity.get("vendorDevice") == "tokay"
            and identity.get("buildType") == "userdebug", "Expected a Pixel 9 Android 17 development baseline")
    number = identity.get("buildNumber")
    require(isinstance(number, str) and number.isdigit() and int(number) > 0, "Invalid build number")
    require(type(identity.get("buildTimestamp")) is int and identity["buildTimestamp"] > 0,
            "Invalid build timestamp")
    require(inputs.get("buildNumber") == number and receipt["buildInput"] == {
        key: inputs.get(key) for key in ("buildNumber", "snapshotDigest", "sourceCommit")},
        "Receipt/build input identity differs")
    manifest = read_json(file_under(root, "input-manifest.json"))
    snapshot_hash = hashlib.sha256(json.dumps(manifest["files"], sort_keys=True).encode()).hexdigest()
    require(snapshot_hash == manifest.get("digest") == inputs.get("snapshotDigest"),
            "Input snapshot digest differs")
    require(manifest.get('runtimeIntegration') == inputs.get('runtimeIntegration'),
            'Runtime run-input differs from its frozen descriptor')
    entries = {item["path"]: item["sha256"] for item in manifest["files"]}
    require(len(entries) == len(manifest["files"]), "Duplicate input snapshot paths")
    locks = {}
    for name in ("source-lock.json", "kernel-source-lock.json"):
        locks[name] = digest(file_under(root, name))
        require(locks[name] == entries.get("image/" + name), "Source lock differs from snapshot: " + name)
    full = receipt.get("fullOtas")
    require(isinstance(full, list) and full, "Missing full OTA baseline")
    records = [artifact_fields(receipt["targetFiles"])] + [artifact_fields(r) for r in full]
    names = [pathlib.PurePosixPath(r["path"]).name for r in records]
    require(len(set(names)) == len(names) and all(n not in ("", ".", "..") for n in names),
            "Export artifact basename collision")
    mapped = []
    for record, name in zip(records, names):
        path = file_under(root, name)
        require(sha(record["sha256"]) and type(record["bytes"]) is int
                and path.stat().st_size == record["bytes"] and digest(path) == record["sha256"],
                "Export artifact hash/size mismatch: " + name)
        mapped.append({**record, "path": str(path)})
    partitions = receipt.get("abPartitions", [])
    require(partitions and len(set(partitions)) == len(partitions), "Invalid A/B partition list")
    with zipfile.ZipFile(mapped[0]["path"]) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate target-files entries")
        for path, checksum in receipt["targetFilesMetadataSha256"].items():
            require(hashlib.sha256(archive.read(path)).hexdigest() == checksum,
                    "Target-files metadata differs: " + path)
        require(archive.read("META/ab_partitions.txt").decode().split() == partitions,
                "Target-files A/B partitions differ")
        props = SIGNATURES.properties(archive.read("SYSTEM/build.prop"))
        expected = {"ro.build.version.release": "17", "ro.build.version.sdk": "37",
                    "ro.build.version.incremental": number, "ro.build.type": "userdebug",
                    "ro.build.fingerprint": identity["buildFingerprint"],
                    "ro.build.date.utc": str(identity["buildTimestamp"]),
                    "ro.build.version.security_patch": identity["platformSecurityPatch"]}
        require(all(props.get(k) == v for k, v in expected.items()), "Target-files build identity differs")
        branded=inputs.get('integrationProfile')=='taku-branding-v1'
        if branded:
            brand=receipt.get('productBranding',{})
            updater_version=branding_version(brand)
            require(brand.get('buildDisplayId')==brand['displayVersion']+'-'+number
                    and brand.get('fullOtaFileName')=='TakuOS-'+brand['productVersion']+'-tokay-'+number+'-full.zip'
                    and brand.get('configSha256')==entries.get('image/product-branding.json'), 'Invalid product branding receipt')
            require(len(full)==1 and names[1]==brand['fullOtaFileName'], 'Branded full OTA filename differs')
            check_branding_boot_properties(root,entries,archive,brand,receipt.get('productBrandingBootProperties'))
            branding_proof=read_json(file_under(root,'product-branding-integration.json'))
            require(branding_proof.get('schema')=='taku.product-branding-integration/1'
                    and branding_proof.get('sourcePatchVerified') is True
                    and branding_proof.get('manifestSha256')==entries.get('image/branding/manifest.json')
                    and branding_proof.get('productBranding')=={k:v for k,v in brand.items() if k not in ('buildDisplayId','fullOtaFileName')},
                    'Archived product branding source evidence differs')
        else:
            require('productBranding' not in receipt, 'Product branding requires its explicit integration profile')
            require('productBrandingBootProperties' not in receipt, 'Branding boot properties require product branding')
        updater=receipt.get('updaterIntegration')
        if updater is not None:
            expected_updater_version=updater_version if branded else 2
            require(inputs.get('integrationProfile') in ('updater-canary','taku-branding-v1')
                    and updater.get('apkEntry')=='SYSTEM_EXT/priv-app/Updater/Updater.apk'
                    and updater.get('versionCode')==expected_updater_version and updater.get('apkSignatureVerified') is True,
                    'Invalid Updater baseline evidence')
            updater_uri='https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}'
            require(updater.get('uri')==updater_uri and
                    SIGNATURES.properties(archive.read('SYSTEM_EXT/etc/build.prop')).get('lineage.updater.uri')==updater_uri,
                    'Archived Updater channel URI differs')
            raw=archive.read(updater['apkEntry'])
            require(len(raw)==updater['apkBytes'] and hashlib.sha256(raw).hexdigest()==updater['apkSha256'],
                    'Archived Updater APK differs')
            source_proof=file_under(root,'updater-integration.json')
            apk_proof=file_under(root,'updater-apk-verification.json')
            require(digest(source_proof)==updater['sourceEvidenceSha256']
                    and digest(apk_proof)==updater['apkEvidenceSha256'], 'Updater audit evidence differs')
            source_info,apk_info=read_json(source_proof),read_json(apk_proof)
            require(source_info.get('schema')=='taku.lineage-updater-integration/1'
                    and source_info.get('sourcePatchVerified') is True and source_info.get('uri')==updater_uri
                    and source_info.get('versionCode')==expected_updater_version
                    and source_info.get('manifestSha256')==updater['manifestSha256'],
                    'Updater source audit differs')
            require(apk_info.get('apkSignatureVerified') is True and apk_info.get('versionCode')==expected_updater_version
                    and apk_info.get('apkSha256')==updater['apkSha256']
                    and apk_info.get('platformCertificateSha256')==updater['platformCertificateSha256']
                    and apk_info.get('manifestSha256')==updater['manifestSha256']==entries.get('image/branding/manifest.json' if branded else 'image/updater/manifest.json'),
                    'Updater audit does not bind the immutable integration')
        else:
            require(inputs.get('integrationProfile') not in ('updater-canary','taku-branding-v1'),'Missing Updater integration acceptance')
        has_agent_ui='system/agent-app/out/TakuAgent.apk' in entries
        require(has_agent_ui == bool(inputs.get('agentUi')) == ('agentUiIntegration' in receipt),
                'New Home APK input/acceptance binding is missing')
        if has_agent_ui:
            ui_helper=file_under(root,'agent_ui.py')
            ui_lock=file_under(root,'runtime-assets.lock.json')
            require(digest(ui_helper)==entries.get('image/agent_ui.py')
                    and digest(ui_lock)==entries.get('image/runtime-assets.lock.json'),
                    'Archived new Home APK validator or lock differs')
            require(updater is not None, 'New Home APK baseline requires the verified platform updater')
            try:
                gate=module('agent_ui_archive_contract',ROOT/'image/agent_ui.py')
                gate.validate_saved(archive,read_json(ui_lock),receipt['agentUiIntegration'],inputs,entries,
                                    updater['platformCertificateSha256'])
            except (ValueError,KeyError) as error:
                raise ReleaseError(str(error)) from error
        google=inputs.get('googleServices')
        require(bool(google)==('googleServicesIntegration' in receipt)
                ==('image/google-services.lock.json' in entries), 'Google input/acceptance binding is missing')
        if google:
            google_helper=file_under(root,'google_services.py')
            google_lock=file_under(root,'google-services.lock.json')
            google_report=file_under(root,'google-services-artifacts.json')
            require(digest(google_helper)==entries.get('image/google_services.py'), 'Google archived helper differs')
            gate=module('google_services_archive_contract',ROOT/'image/google_services.py')
            try:
                gate.validate_saved(archive,read_json(google_report),google_lock,inputs,entries,
                                    receipt['googleServicesIntegration'],digest(google_report))
                require(read_json(google_report).get('runInputSha256')==digest(inputs_path), 'Google report run-input differs')
            except ValueError as error:
                raise ReleaseError(str(error)) from error
        carrier=inputs.get('carrierDefaults')
        require(bool(carrier)==('carrierDefaultsIntegration' in receipt)
                ==('image/carrier-defaults/manifest.json' in entries), 'Carrier input/acceptance binding is missing')
        if carrier:
            try:
                gate=module('carrier_defaults_archive_contract',ROOT/'image/carrier_defaults.py')
                gate.validate_saved(archive,root,inputs,entries,receipt['carrierDefaultsIntegration'])
                require(receipt['carrierDefaultsIntegration']['platformCertificateSha256']
                        ==updater['platformCertificateSha256'], 'Carrier platform signer differs from Updater')
            except ValueError as error:
                raise ReleaseError(str(error)) from error
        apps=inputs.get('googleApps')
        require(bool(apps)==('googleAppsIntegration' in receipt)
                ==('image/google-apps.lock.json' in entries), 'Internal Google app input/acceptance binding is missing')
        if not apps:
            require(not any('/TakuInternal' in name for name in archive.namelist()),
                    'Undeclared internal Google app content in an unscoped baseline')
        else:
            require(inputs.get('distribution')=='internal-test-only', 'Internal Google apps cannot lose their distribution scope')
            helper=file_under(root,'google_apps.py')
            app_lock=file_under(root,'google-apps.lock.json')
            app_report=file_under(root,'google-apps-artifacts.json')
            require(digest(helper)==entries.get('image/google_apps.py'), 'Archived internal app helper differs')
            gate=module('internal_google_apps_archive_contract',ROOT/'image/google_apps.py')
            try:
                gate.validate_saved(archive,read_json(app_report),app_lock,inputs,entries,
                                    receipt['googleAppsIntegration'],digest(app_report))
                require(read_json(app_report).get('runInputSha256')==digest(inputs_path), 'Internal app report run-input differs')
            except ValueError as error:
                raise ReleaseError(str(error)) from error
        runtime=inputs.get('runtimeIntegration')
        require(bool(runtime)==('systemAiIntegration' in receipt), 'Runtime input/acceptance binding is missing')
        if runtime:
            runtime_proof=receipt['systemAiIntegration']
            report_path=file_under(root,'system-ai-rom.json')
            handoff_path=file_under(root,'system-ai-handoff.json')
            lock_path=file_under(root,'rom-input-lock.json')
            helper_path=file_under(root,'verify-system-ai.py')
            require(digest(report_path)==runtime_proof.get('reportSha256')
                    and digest(handoff_path)==entries.get('image/system-ai-handoff.json')
                    and digest(lock_path)==entries.get('system/inputmethod/rom-input-lock.json')
                    and digest(helper_path)==entries.get('image/verify-system-ai.py')
                    and runtime_proof=={'report':'system-ai-rom.json','reportSha256':digest(report_path),
                        'runtimeIntegration':runtime,'imeInputLockSha256':digest(lock_path),
                        'artifactVerified':True,'physicalVerified':False}, 'Runtime archive evidence differs')
            gate=module('system_ai_archive_contract',ROOT/'image/verify-system-ai.py')
            repair_raw=load_source_repair(root,runtime,entries)
            permission_helper=None
            if 'system/memory/org.agentos.memory.xml' in entries:
                permission_helper=file_under(root,'privapp_permissions.py')
                permission_xml=file_under(root,'org.agentos.memory.xml')
                require(digest(permission_helper)==entries.get('image/privapp_permissions.py')
                        and digest(permission_xml)==entries['system/memory/org.agentos.memory.xml'],
                        'Archived Memory permission policy/validator differs')
            try:
                gate.validate_saved(archive,read_json(report_path),read_json(handoff_path),read_json(lock_path),
                                    runtime,digest(inputs_path),entries,repair_raw,permission_helper=permission_helper)
            except ValueError as error:
                raise ReleaseError(str(error)) from error
    expected_ota = {"ota-type": "AB", "pre-device": "tokay", "post-sdk-level": "37",
                    "post-build": identity["buildFingerprint"], "post-build-incremental": number,
                    "post-timestamp": str(identity["buildTimestamp"]),
                    "post-security-patch-level": identity["platformSecurityPatch"]}
    seen = set()
    for record in mapped[1:]:
        if record["sha256"] in seen:
            continue
        seen.add(record["sha256"])
        payload = PAYLOAD.inspect(record["path"])
        metadata = SIGNATURES.properties(payload["otaMetadata"].encode())
        actual = [p["name"] for p in payload["partitions"]]
        require(not payload["partialUpdate"] and len(actual) == len(set(actual))
                and set(actual) == set(partitions), "Partial or incomplete OTA cannot be a full baseline")
        require(payload["metadataSignatureBytes"] > 0, "Missing payload metadata signature")
        require(all(metadata.get(k) == v for k, v in expected_ota.items())
                and not any(k in metadata for k in ("pre-build", "pre-build-incremental")),
                "Full OTA identity differs from baseline")
        require(not any(metadata.get(k, "no").lower() not in ("no", "false", "0", "")
                        for k in ("ota-wipe", "ota-downgrade", "spl-downgrade")),
                "Wipe/downgrade OTA cannot be a baseline")
    proof_path = file_under(root, "signature-verification/signature-verification.json")
    proof = read_json(proof_path)
    verifier_hash = digest(file_under(root, "signature-verification/verify-signatures.py"))
    binding = {"baselineReceiptSha256": digest(receipt_path), "runInputSha256": digest(inputs_path),
               "buildIdentity": identity, "artifacts": records, "signingInputs": proof["signingInputs"]}
    try:
        SIGNATURES.reuse_report(proof_path, binding, proof["tools"], proof["toolSources"],
                                pathlib.Path("/src"), expected_verifier_hash=verifier_hash)
    except SIGNATURES.VerificationError as error:
        raise ReleaseError(str(error)) from error
    certificates = sorted({c["derSha256"] for c in receipt["signingEvidence"]["trustedOtaCertificates"]})
    require(certificates and all(sha(c) for c in certificates)
            and proof["certificate"]["derSha256"] in certificates
            and proof["signingInputs"]["certificate"]["derSha256"] == proof["certificate"]["derSha256"],
            "Verified OTA signing certificate is not trusted")
    baseline = {"directory": str(root), "identity": identity,
            "receiptSha256": binding["baselineReceiptSha256"], "runInputSha256": binding["runInputSha256"],
            "signatureReportSha256": digest(proof_path), "snapshotDigest": snapshot_hash,
            "sourceLockSha256": locks["source-lock.json"], "kernelSourceLockSha256": locks["kernel-source-lock.json"],
            "targetFiles": mapped[0], "fullOtas": mapped[1:], "abPartitions": sorted(partitions),
            "trustedOtaCertificates": certificates, "signingCertificate": proof["certificate"]["derSha256"],
            "vbmetaDigest": proof["avb"]["vbmetaDigestSha256"], "avbNodes": proof["avb"]["nodes"]}
    if receipt.get('productBranding') is not None:
        baseline['productBranding'] = receipt['productBranding']
        # New branded sources carry their authenticated installed APK contract.
        # Legacy baseline/plan shapes stay unchanged for archived D/E evidence.
        baseline['updaterIntegration'] = receipt['updaterIntegration']
    if receipt.get('systemAiIntegration') is not None:
        baseline['systemAiIntegration'] = receipt['systemAiIntegration']
    if receipt.get('googleServicesIntegration') is not None:
        baseline['googleServicesIntegration'] = receipt['googleServicesIntegration']
    if receipt.get('carrierDefaultsIntegration') is not None:
        baseline['carrierDefaultsIntegration'] = receipt['carrierDefaultsIntegration']
    if receipt.get('googleAppsIntegration') is not None:
        baseline['googleAppsIntegration'] = receipt['googleAppsIntegration']
        baseline['distribution'] = 'internal-test-only'
    return baseline


def require_measurable_boot(baseline, label="baseline"):
    roots=[node for node in baseline["avbNodes"] if node["partition"]=="vbmeta"]
    require(len(roots)==1 and type(roots[0].get("flags")) is int and not (roots[0]["flags"] & ~1),
            "OTA " + label + " disables boot verification; bootloader vbmeta measurement is required")


def create_baseline_binding(baseline):
    """Seal one already verified export for initial USB install, without a future build."""
    import ota_device
    require_measurable_boot(baseline)
    ota_device.baseline_binding(baseline)
    require(all(type(item.get("bytes")) is int and item["bytes"]>0
                and isinstance(item.get("path"),str) and item["path"]
                for item in baseline["fullOtas"]), "Invalid bound full OTA artifacts")
    return {"schema":"taku.ota-baseline-binding/1", "baseline":baseline, "physicalDeviceVerified":False}


def load_baseline_binding(path, expected_hash):
    require(sha(expected_hash) and digest(path)==expected_hash, "Baseline binding SHA-256 differs")
    value=read_json(path)
    require(value.get("schema")=="taku.ota-baseline-binding/1", "Unsupported baseline binding")
    require(value==create_baseline_binding(value["baseline"]), "Baseline binding fields differ")
    return value["baseline"]


def create_plan(source, target):
    check_product_upgrade(source, target)
    for label, baseline in (("source", source), ("target", target)):
        require_measurable_boot(baseline,label)
    old, new = source["identity"], target["identity"]
    require(int(new["buildNumber"]) > int(old["buildNumber"])
            and new["buildTimestamp"] > old["buildTimestamp"], "OTA target must be a newer build")
    require(new["platformSecurityPatch"] >= old["platformSecurityPatch"], "Security patch downgrade is unsupported")
    require(source["targetFiles"]["sha256"] != target["targetFiles"]["sha256"], "Identical target-files")
    require(source["vbmetaDigest"] != target["vbmetaDigest"], "A and B must have distinct AVB measurements")
    for key in ("sourceLockSha256", "kernelSourceLockSha256", "abPartitions",
                "trustedOtaCertificates", "signingCertificate"):
        require(source[key] == target[key], "First OTA demo requires unchanged " + key)
    before = {n["partition"]: n for n in source["avbNodes"]}
    after = {n["partition"]: n for n in target["avbNodes"]}
    require(before.keys() == after.keys(), "AVB partition graph changed")
    for name, node in before.items():
        newer = after[name]
        for key in ("algorithmId", "publicKeySha256", "chains", "flags", "rollbackIndexLocation"):
            require(node.get(key) == newer.get(key), "AVB signing/chain configuration changed: " + name)
        require(newer["rollbackIndex"] >= node["rollbackIndex"], "AVB rollback index decreased: " + name)
    pair = {"sourceTargetFilesSha256": source["targetFiles"]["sha256"],
            "targetTargetFilesSha256": target["targetFiles"]["sha256"]}
    identifier = old["buildNumber"] + "-to-" + new["buildNumber"] + "-" + hashlib.sha256(
        json.dumps(pair, sort_keys=True).encode()).hexdigest()[:16]
    return {"schema": "taku.ota-plan/1", "id": identifier, "source": source, "target": target,
            "physicalDeviceVerified": False, "dataPreservationVerified": False,
            "remainingValidation": ["Observe the exact source build and initialize the probe on Pixel 9",
                                    "Generate and verify the incremental OTA", "Install OTA without a data wipe",
                                    "Observe target boot, snapshot merge and probe data preservation",
                                    "Run Taku functionality and upgrade recovery acceptance"]}


def load_plan(path, expected_hash, *, revalidate=False):
    path = pathlib.Path(path)
    require(sha(expected_hash) and digest(path) == expected_hash, "OTA plan SHA-256 differs")
    plan = read_json(path)
    require(plan.get("schema") == "taku.ota-plan/1", "Unsupported OTA plan")
    expected = create_plan(plan["source"], plan["target"])
    require(plan == expected, "OTA plan fields differ")
    if revalidate:
        for name in ("source", "target"):
            require(load_baseline(plan[name]["directory"]) == plan[name],
                    "Archived baseline changed after planning: " + name)
    return plan


def validate_before(path, expected_hash, plan):
    import ota_device
    try:
        return ota_device.validate_before(path, expected_hash,
                                         ota_device.transition_bindings(plan["source"], plan["target"]))
    except ota_device.DeviceVerificationError as error:
        raise ReleaseError(str(error)) from error
