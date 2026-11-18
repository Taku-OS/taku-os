#!/usr/bin/env python3
"""Apply the reviewed Launcher3/Quickstep patch only to its exact pinned checkout.

Taku: double tap opens text; long press streams voice while Taku is selected. The patch is
applied only when packages/apps/Launcher3 is at the pinned revision and every target file has
the audited bytes. A partial or foreign state stops the build instead of being "fixed".
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess

SCHEMA = "taku.launcher3-patch/1"
UPSTREAM = "818f72b74e6f914cbca2b465776d6d20f960ee87"
REPOSITORY = "packages/apps/Launcher3"
PATCH = "nav-handle-double-tap.patch"
TARGETS = {
    "quickstep/src/com/android/quickstep/NavHandleDoubleTap.java",
    "quickstep/src/com/android/quickstep/TouchInteractionHandler.java",
    "quickstep/src/com/android/quickstep/TakuNavHandleBridge.java",
    "quickstep/src/com/android/quickstep/inputconsumers/NavHandleLongPressInputConsumer.java",
    "quickstep/src/com/android/quickstep/OverviewComponentObserver.java",
}


def digest(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_contract(manifest_path):
    manifest_path = pathlib.Path(manifest_path)
    value = json.loads(manifest_path.read_text())
    require(value.get("schema") == SCHEMA and value.get("upstreamCommit") == UPSTREAM
            and value.get("repository") == REPOSITORY and value.get("patch") == PATCH,
            "Launcher3 patch contract differs")
    files = value.get("files")
    require(isinstance(files, list) and {f.get("path") for f in files} == TARGETS and len(files) == len(TARGETS),
            "Launcher3 patch target set differs")
    patch = manifest_path.parent / PATCH
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == value.get("patchSha256"),
            "Launcher3 patch bytes differ")
    touched = set()
    for line in patch.read_text().splitlines():
        if line.startswith(("--- a/", "+++ b/")):
            touched.add(line[6:])
        require(not line.startswith(("GIT binary patch", "rename from ", "rename to ", "old mode ", "new mode ", "deleted file")),
                "Unsupported Launcher3 patch operation")
    require(touched == TARGETS, "Launcher3 patch coverage differs")
    return value, patch


def inspect_sources(source_root, manifest):
    repository = pathlib.Path(source_root) / REPOSITORY
    head = subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip()
    require(head == UPSTREAM, f"{REPOSITORY} is at {head}, not the pinned {UPSTREAM}")
    states = []
    for entry in manifest["files"]:
        path = repository / entry["path"]
        current = digest(path) if path.exists() else None
        require(current in (entry["beforeSha256"], entry["afterSha256"]),
                f"Unexpected bytes in {entry['path']}; preserve and inspect")
        states.append(current == entry["afterSha256"])
    require(all(states) or not any(states), "Partially applied Launcher3 patch; preserve and inspect")
    return repository, all(states)


def migrate_legacy_version(source_root, manifest_path, manifest, name, expected_digest):
    """Only the exact, fully applied v1 patch may be upgraded. Unknown/partial edits stay intact."""
    repository = pathlib.Path(source_root) / REPOSITORY
    legacy_path = pathlib.Path(manifest_path).parent / name
    require(digest(legacy_path) == expected_digest, "Legacy manifest bytes differ")
    legacy = json.loads(legacy_path.read_text())
    old_patch = legacy_path.parent / legacy["patch"]
    require(digest(old_patch) == legacy["patchSha256"], "Legacy patch bytes differ")
    old_files = {e["path"]: e["afterSha256"] for e in legacy["files"]}
    head = subprocess.check_output(["git", "-C", str(repository), "rev-parse", "HEAD"], text=True).strip()
    require(head == UPSTREAM, "Launcher3 checkout is not pinned")
    for entry in manifest["files"]:
        path = repository / entry["path"]
        actual = digest(path) if path.exists() else None
        if actual != old_files.get(entry["path"], entry["beforeSha256"]):
            return False
    command = ["git", "-C", str(repository), "apply", "--reverse"]
    subprocess.run([*command, "--check", str(old_patch.resolve())], check=True)
    subprocess.run([*command, str(old_patch.resolve())], check=True)
    return True


def migrate_legacy(source_root, manifest_path, manifest):
    for name, checksum in (
        ("previous-home-manifest.json", "1028e79e36fe20a353f66b0725c220e0bb8185495748a3366d31ade490d8d2e9"),
        ("legacy-manifest.json", "1b3bd205a78dddc0fac9001f96d30ad62bc5f9cce0e064cfd990e00561a9e76f"),
    ):
        if migrate_legacy_version(source_root, manifest_path, manifest, name, checksum):
            return True
    return False


def apply(source_root, results, manifest_path=None, verify_only=False):
    manifest_path = pathlib.Path(manifest_path or pathlib.Path(__file__).parent / "launcher/manifest.json")
    manifest, patch = load_contract(manifest_path)
    try:
        repository, applied = inspect_sources(source_root, manifest)
    except ValueError:
        if verify_only or not migrate_legacy(source_root, manifest_path, manifest):
            raise
        repository, applied = inspect_sources(source_root, manifest)
    if verify_only and not applied:
        raise ValueError("Launcher3 patch is not applied")
    if not applied:
        command = ["git", "-C", str(repository), "apply", "--whitespace=error"]
        subprocess.run([*command, "--check", str(patch.resolve())], check=True)
        subprocess.run([*command, str(patch.resolve())], check=True)
    _, applied = inspect_sources(source_root, manifest)
    require(applied, "Launcher3 patch did not produce the accepted sources")
    results = pathlib.Path(results)
    results.mkdir(parents=True, exist_ok=True)
    report = results / "launcher-integration.json"
    binding = {"schema": "taku.launcher3-integration/1", "upstreamCommit": UPSTREAM,
               "manifestSha256": digest(manifest_path), "patchSha256": manifest["patchSha256"],
               "files": manifest["files"], "sourcePatchVerified": True,
               "apkBuiltVerified": False, "physicalDeviceVerified": False}
    if report.exists():
        previous = json.loads(report.read_text())
        require({k: previous.get(k) for k in binding} == binding,
                "Existing Launcher3 integration audit belongs to different inputs")
        return report
    binding["verifiedAt"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    with report.open("x") as stream:
        stream.write(json.dumps(binding, indent=2) + "\n")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--results-dir", required=True)
    parser.add_argument("--manifest")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    print(apply(args.source_root, args.results_dir, args.manifest, args.verify_only))


if __name__ == "__main__":
    main()
