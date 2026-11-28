#!/usr/bin/env python3
"""Apply the reviewed Updater patch only to its exact pinned generated checkout."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import re
import ssl
import subprocess


UPSTREAM = "6d50e0bf1b16fe7bca818375a3e734f4959e2e57"
REPOSITORY = "packages/apps/Updater"
TARGETS = {
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/org/lineageos/updater/controller/UpdaterController.java",
    "app/src/main/java/org/lineageos/updater/download/DownloadIntegrity.java",
    "app/src/main/java/org/lineageos/updater/download/HttpURLConnectionClient.java",
    "app/src/main/java/org/lineageos/updater/download/SingleRangeHttpFetcher.kt",
}
ADDITION = "app/src/main/java/org/lineageos/updater/download/DownloadIntegrity.java"
URI = "https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_sdk_badging(badging):
    # AAPT 2.20 renamed sdkVersion to minSdkVersion. Accept either spelling,
    # never duplicate/ambiguous fields or preview codenames in place of API 37.
    minima = re.findall(r"^(?:sdkVersion|minSdkVersion):(.*)$", badging, re.MULTILINE)
    targets = re.findall(r"^targetSdkVersion:(.*)$", badging, re.MULTILINE)
    require(len(minima) == 1 and len(targets) == 1,
            "Updater must have exactly one minimum and target SDK field")
    values = [re.fullmatch(r"'([0-9]+)'", field) for field in (minima[0], targets[0])]
    require(all(value and int(value.group(1)) == 37 for value in values),
            "Updater must use the pinned ROM's platform API 37")


def verify_platform_signer(signatures, platform_certificate):
    # Current apksigner identifies the APK v3 signer by scheme rather than #1.
    # Keep both known formats exact; never infer trust from an unknown/extra line.
    counts = re.findall(r"^Number of signers:(.*)$", signatures, re.MULTILINE)
    require(counts == [" 1"], "Updater must have exactly one APK signer")
    certificates = [line for line in signatures.splitlines()
                    if "certificate SHA-256 digest:" in line]
    require(len(certificates) == 1, "Updater must have exactly one certificate digest")
    signer = re.fullmatch(
        r"(?:Signer #1|V3\.0 Signer:) certificate SHA-256 digest: ([0-9a-f]{64})",
        certificates[0])
    require(signer and signer.group(1) == platform_certificate,
            "Updater APK is not signed by this image's platform certificate")


def git_revision(repository):
    return subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip()


def load_contract(manifest_path):
    manifest_path = pathlib.Path(manifest_path)
    require(not manifest_path.is_symlink(), "Updater manifest must not be a symlink")
    value = json.loads(manifest_path.read_text())
    require(value.get("schema") == "taku.lineage-updater-patch/1"
            and value.get("upstreamCommit") == UPSTREAM and value.get("repository") == REPOSITORY
            and value.get("versionCode") == 2 and value.get("packageName") == "org.lineageos.updater"
            and value.get("uri") == URI and value.get("patch") == "download-integrity.patch",
            "Unexpected Updater integration contract")
    records = value.get("files", [])
    require(len(records) == len(TARGETS) and {r.get("path") for r in records} == TARGETS,
            "Updater patch target set differs")
    for record in records:
        require(re.fullmatch(r"[0-9a-f]{64}", str(record.get("afterSha256"))) is not None,
                "Invalid patched Updater hash")
        original = record.get("beforeSha256")
        require((record["path"] == ADDITION and original is None)
                or (record["path"] != ADDITION and re.fullmatch(r"[0-9a-f]{64}", str(original)) is not None),
                "Invalid original Updater hash")
    patch = manifest_path.parent / value["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == value.get("patchSha256"),
            "Updater patch bytes differ")
    # Keep git apply's capability limited to the exact audited existing/addition paths.
    headers = []
    for line in patch.read_text().splitlines():
        if line.startswith(("--- ", "+++ ")):
            name = line[4:]
            if name != "/dev/null":
                require(name.startswith(("a/", "b/")) and name[2:] in TARGETS,
                        "Unexpected path in Updater patch")
                headers.append(name[2:])
        require(not line.startswith(("GIT binary patch", "rename from ", "rename to ", "old mode ", "new mode ")),
                "Unsupported Updater patch operation")
    require(set(headers) == TARGETS and len(headers) == 2 * len(TARGETS) - 1,
            "Updater patch coverage differs")
    return value, patch


def inspect_sources(source_root, manifest):
    repository = pathlib.Path(source_root).resolve() / REPOSITORY
    require(repository.is_dir() and not repository.is_symlink(), "Missing pinned Updater checkout")
    require(git_revision(repository) == UPSTREAM, "Updater HEAD differs from fixed source lock")
    states = []
    for record in manifest["files"]:
        target = repository / record["path"]
        require(target.resolve().is_relative_to(repository.resolve()), "Escaped Updater target")
        require(not any(p.is_symlink() for p in [target, *target.parents] if p != repository.parent),
                "Symlink in Updater target path")
        current = digest(target) if target.is_file() else None
        require(current in (record["beforeSha256"], record["afterSha256"]),
                "Unexpected Updater source drift: " + record["path"])
        states.append(current == record["afterSha256"])
    require(all(states) or not any(states), "Partially applied Updater patch; preserve and inspect")
    return repository, all(states)


def apply(source_root, results, manifest_path=None):
    manifest_path = pathlib.Path(manifest_path or pathlib.Path(__file__).parent / "updater/manifest.json")
    manifest, patch = load_contract(manifest_path)
    repository, already_applied = inspect_sources(source_root, manifest)
    if not already_applied:
        command = ["git", "-C", str(repository), "apply", "--no-index", "--whitespace=error"]
        subprocess.run([*command, "--check", str(patch.resolve())], check=True)
        subprocess.run([*command, str(patch.resolve())], check=True)
    _, applied = inspect_sources(source_root, manifest)
    require(applied, "Updater patch did not produce the accepted sources")
    results = pathlib.Path(results)
    results.mkdir(parents=True, exist_ok=True)
    report = results / "updater-integration.json"
    binding = {"schema": "taku.lineage-updater-integration/1", "upstreamCommit": UPSTREAM,
               "manifestSha256": digest(manifest_path), "patchSha256": manifest["patchSha256"],
               "versionCode": 2, "packageName": manifest["packageName"], "uri": URI,
               "files": manifest["files"], "sourcePatchVerified": True,
               "apkBuiltVerified": False, "physicalDeviceVerified": False}
    if report.exists():
        previous = json.loads(report.read_text())
        require({k: previous.get(k) for k in binding} == binding,
                "Existing Updater integration audit belongs to different inputs")
        return report
    binding["verifiedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with report.open("x") as stream:
        stream.write(json.dumps(binding, indent=2) + "\n")
    return report


def verify_apk(source_root, apk, results, manifest_path=None):
    """Inspect the actual platform-signed APK with this build's own host tools."""
    source_root, apk, results = map(pathlib.Path, (source_root, apk, results))
    manifest_path = pathlib.Path(manifest_path or pathlib.Path(__file__).parent / "updater/manifest.json")
    manifest, _ = load_contract(manifest_path)
    _, patched = inspect_sources(source_root, manifest)
    require(patched and apk.is_file() and not apk.is_symlink(), "Missing patched Updater APK")
    return verify_apk_bytes(source_root, apk, results, manifest["packageName"], 2, digest(manifest_path))


def verify_apk_bytes(source_root, apk, results, package_name, version_code, manifest_sha256):
    """Byte/signature inspection only; callers must first verify their strict source contract."""
    source_root, apk, results = map(pathlib.Path, (source_root, apk, results))
    require(package_name == "org.lineageos.updater" and type(version_code) is int and version_code in (2, 3, 4, 5, 6, 7, 8, 9, 10, 11)
            and re.fullmatch(r"[0-9a-f]{64}", manifest_sha256) is not None
            and apk.is_file() and not apk.is_symlink(), "Invalid APK verification contract")
    host = source_root / "out/host/linux-x86/bin"
    env = dict(os.environ)
    env["JAVA_HOME"] = str(source_root / "prebuilts/jdk/jdk21/linux-x86")
    env["PATH"] = str(pathlib.Path(env["JAVA_HOME"]) / "bin") + os.pathsep + str(host) + os.pathsep + os.defpath
    tools = {}
    for name in ("aapt2", "apksigner"):
        path = host / name
        require(path.is_file() and os.access(path, os.X_OK), "Missing APK verification host tool: " + name)
        tools[name] = {"path": str(path), "sha256": digest(path)}
    badging = subprocess.check_output([tools["aapt2"]["path"], "dump", "badging", str(apk)], env=env, text=True)
    signatures = subprocess.check_output([tools["apksigner"]["path"], "verify", "--verbose", "--print-certs", str(apk)], env=env, text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'", badging, re.MULTILINE)
    require(package and package.group(1) == package_name and int(package.group(2)) == version_code,
            "Built Updater package/version differs")
    verify_sdk_badging(badging)
    public_key = source_root / "build/make/target/product/security/platform.x509.pem"
    platform_certificate = hashlib.sha256(ssl.PEM_cert_to_DER_cert(public_key.read_text())).hexdigest()
    verify_platform_signer(signatures, platform_certificate)
    record = {"schema": "taku.lineage-updater-apk/1", "packageName": package.group(1), "versionCode": version_code,
              "apkSha256": digest(apk), "apkBytes": apk.stat().st_size,
              "manifestSha256": manifest_sha256, "platformCertificateSha256": platform_certificate,
              "tools": tools, "badging": badging, "signatureVerification": signatures,
              "apkSignatureVerified": True, "physicalDeviceVerified": False}
    results.mkdir(parents=True, exist_ok=True)
    path = results / "updater-apk-verification.json"
    if path.exists():
        if json.loads(path.read_text()) == record:
            return record
        previous = path.with_name(path.name + ".previous-" + digest(path))
        if previous.exists():
            require(previous.read_bytes() == path.read_bytes(), "Conflicting previous APK evidence")
            path.unlink()
        else:
            path.rename(previous)
    with path.open("x") as stream:
        stream.write(json.dumps(record, indent=2) + "\n")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=pathlib.Path, required=True)
    parser.add_argument("--results-dir", type=pathlib.Path, required=True)
    parser.add_argument("--apk", type=pathlib.Path, help="Verify an already built APK; never compile it")
    args = parser.parse_args()
    try:
        if args.apk:
            print(json.dumps(verify_apk(args.source_root, args.apk, args.results_dir)))
        else:
            report = apply(args.source_root, args.results_dir)
            print(json.dumps({"path": str(report), "sha256": digest(report)}))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, "Updater integration failed: " + str(error) + "\n")


if __name__ == "__main__":
    main()
