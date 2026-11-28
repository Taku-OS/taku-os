#!/usr/bin/env python3
"""Apply one exact TakuOS product layer after the separately pinned download fix."""
import argparse
import datetime
import hashlib
import json
import pathlib
import re
import runpy
import subprocess
import shutil
import tempfile
from types import SimpleNamespace


ROOT = pathlib.Path(__file__).resolve().parent
BRAND = SimpleNamespace(**runpy.run_path(str(ROOT / "product_branding.py"), run_name="taku_product_branding"))
BASE = runpy.run_path(str(ROOT / "apply-updater-patches.py"), run_name="taku_base_updater")
require, digest = BASE["require"], BASE["digest"]
LEGACY_MANIFEST_SHA256 = "47a7c2bebbc46c5ad002a0bdf8d2054fe86f12e6a0fba46fb4377d8064894d30"
PREVIOUS_MANIFEST_SHA256 = "dee9fc3149c4e57f1fd075d6f22db028e119b1713544deaba1d071b174fcf64d"
PREVIOUS_V5_MANIFEST_SHA256 = "3a8d952e9cc772a2e8103d3e48d4ec78d3440f68e51818ab59a641c087e1c598"
PREVIOUS_V6_MANIFEST_SHA256 = "ef99e40ea93a8447dea3be30f452b145b50710b558eb45ecd37d2765efb739de"
PREVIOUS_V7_MANIFEST_SHA256 = "3a7e54055e746b9aa73344c63bc51e70926e99a5a246546bde3326b3a84950a1"
PREVIOUS_V8_MANIFEST_SHA256 = "8683488928f554adca15c0f23b08928158948a543da11c9aa44844a29b11be77"
PREVIOUS_V9_MANIFEST_SHA256 = "1afa7939afb7bd819906b9933f946c79f7696cc08aa78c9ee44f50e62a218b40"
PREVIOUS_V10_MANIFEST_SHA256 = "89eb70b046b22c92f390f50e900260dd737e4dfc59fb5448f02848c7b26f0a36"
TARGETS = {
    "packages/apps/Updater": (BASE["UPSTREAM"], {
        "app/src/main/AndroidManifest.xml", "app/src/main/java/org/lineageos/updater/TakuBrand.kt",
        "app/src/main/java/org/lineageos/updater/deviceinfo/UpdaterCard.kt",
        "app/src/main/java/org/lineageos/updater/updates/action/UpdateActionHandler.kt",
        "app/src/main/java/org/lineageos/updater/updates/state/UpdateItemStateMapper.kt",
        "app/src/main/java/org/lineageos/updater/data/Update.kt",
        "app/src/main/java/org/lineageos/updater/data/source/network/NetworkUpdate.kt",
        "app/src/main/java/org/lineageos/updater/data/source/local/UpdateEntity.kt",
        "app/src/main/java/org/lineageos/updater/data/source/local/UpdatesDatabase.kt",
        "app/src/main/java/org/lineageos/updater/controller/UpdaterController.java",
        "app/src/main/res/drawable/ic_launcher_foreground.xml", "app/src/main/res/drawable/taku_brand_mark.xml",
        "app/src/main/res/values/taku_brand.xml", "app/src/main/res/values-zh-rCN/taku_brand.xml",
        "schemas/org.lineageos.updater.data.source.local.UpdatesDatabase/5.json",
    }),
    "packages/apps/Settings": ("b9b9d68135714d9a84741620e9386e1d43931139", {
        "res/values/taku_brand.xml",
        "src/com/android/settings/deviceinfo/firmwareversion/LineageVersionDetailPreference.kt",
        "src/com/android/settings/deviceinfo/firmwareversion/LineageVersionDetailPreferenceController.java",
    }),
}
ADDITIONS = {
    "app/src/main/java/org/lineageos/updater/TakuBrand.kt", "app/src/main/res/drawable/taku_brand_mark.xml",
    "app/src/main/res/values/taku_brand.xml", "app/src/main/res/values-zh-rCN/taku_brand.xml",
    "res/values/taku_brand.xml",
    "schemas/org.lineageos.updater.data.source.local.UpdatesDatabase/5.json",
}


def load_contract():
    config = BRAND.load()
    path = ROOT / "branding/manifest.json"
    require(not path.is_symlink(), "Branding manifest must not be a symlink")
    manifest = json.loads(path.read_text())
    require(manifest.get("schema") == "taku.product-branding-patch/1"
            and manifest.get("brandingSha256") == config["configSha256"]
            and manifest.get("updaterVersionCode") == config["updaterVersionCode"]
            and manifest.get("baseUpdaterManifestSha256") == digest(ROOT / "updater/manifest.json"),
            "Product branding contract differs")
    repositories = manifest.get("repositories", [])
    require(len(repositories) == len(TARGETS)
            and {r.get("repository") for r in repositories} == set(TARGETS), "Unexpected branding repository set")
    for record in repositories:
        revision, targets = TARGETS[record["repository"]]
        require(record.get("upstreamCommit") == revision and len(record.get("files", [])) == len(targets)
                and {r.get("path") for r in record["files"]} == targets, "Unexpected branding target set")
        patch_name = f"updater-v{config['updaterVersionCode']}.patch" if record["repository"] == "packages/apps/Updater" else "settings.patch"
        patch = ROOT / "branding" / patch_name
        require(record.get("patch") == patch_name and patch.is_file() and not patch.is_symlink()
                and digest(patch) == record.get("patchSha256"), "Branding patch changed")
        headers = []
        for line in patch.read_text().splitlines():
            if line.startswith(("--- ", "+++ ")) and line[4:] != "/dev/null":
                name = line[4:]
                require(name.startswith(("a/", "b/")) and name[2:] in targets, "Escaped branding patch target")
                headers.append(name[2:])
            require(not line.startswith(("GIT binary patch", "rename from ", "rename to ", "old mode ", "new mode ")),
                    "Unsupported branding patch operation")
        require(set(headers) == targets and len(headers) == sum(1 if r["beforeSha256"] is None else 2
                                                               for r in record["files"]), "Branding coverage differs")
        for item in record["files"]:
            require(re.fullmatch(r"[0-9a-f]{64}", str(item.get("afterSha256"))) is not None
                    and (item["beforeSha256"] is None) == (item["path"] in ADDITIONS)
                    and (item.get("beforeSha256") is None or re.fullmatch(r"[0-9a-f]{64}", str(item["beforeSha256"]))),
                    "Invalid branding source hash")
    require((ROOT / "product-branding.mk").read_text() == BRAND.makefile(config), "Generated product properties drifted")
    # A changed version must regenerate the reviewed Kotlin artifact, not merely
    # relabel an old APK or mutate compatibility identifiers at runtime.
    updater = next(r for r in repositories if r["repository"] == "packages/apps/Updater")
    constants = next(r for r in updater["files"] if r["path"].endswith("/TakuBrand.kt"))
    require(constants["afterSha256"] == hashlib.sha256(BRAND.updater_constants(config).encode()).hexdigest(),
            "Updater display version does not derive from the product contract")
    legacy_contract()
    previous_contract()
    previous_v5_contract()
    previous_v6_contract()
    previous_v7_contract()
    previous_v8_contract()
    base, _ = BASE["load_contract"](ROOT / "updater/manifest.json")
    for name in ("app/src/main/AndroidManifest.xml", "app/src/main/java/org/lineageos/updater/controller/UpdaterController.java"):
        original = next(r for r in base['files'] if r['path'] == name)
        layered = next(r for r in updater['files'] if r['path'] == name)
        require(layered['beforeSha256'] == original['afterSha256'], "Branding must inherit the exact accepted download layer")
    return config, manifest, path


def legacy_contract():
    """The accepted v3 source layer is a pinned predecessor, not a reset rule."""
    path = ROOT / "branding/legacy-v3-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == LEGACY_MANIFEST_SHA256,
            "Legacy branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Legacy branding patch differs")
    return manifest, updater, patch


def inspect_repository(source, record):
    repository = pathlib.Path(source).resolve() / record["repository"]
    require(repository.is_dir() and not repository.is_symlink()
            and BASE["git_revision"](repository) == record["upstreamCommit"], "Branding source HEAD differs")
    states = []
    for item in record["files"]:
        target = repository / item["path"]
        require(target.resolve().is_relative_to(repository.resolve())
                and not any(p.is_symlink() for p in [target, *target.parents] if p != repository.parent),
                "Symlink/escape in branding path")
        current = digest(target) if target.is_file() else None
        require(current in (item["beforeSha256"], item["afterSha256"]), "Branding source drift: " + str(target))
        states.append(current == item["afterSha256"])
    require(all(states) or not any(states), "Partially applied branding layer; preserve evidence")
    return repository, all(states)


def previous_contract():
    """Keep the accepted 1.0.1/v4 source layer as an exact cache predecessor."""
    path = ROOT / "branding/previous-v4-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == PREVIOUS_MANIFEST_SHA256,
            "Previous branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Previous branding patch differs")
    return manifest, updater, patch


def previous_v5_contract():
    """The accepted 1.1.0 layer stays byte-bound when reusing its compile cache."""
    path = ROOT / "branding/previous-v5-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == PREVIOUS_V5_MANIFEST_SHA256,
            "Previous v5 branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Previous v5 branding patch differs")
    return manifest, updater, patch


def previous_v6_contract():
    """The accepted 1.1.1 layer stays byte-bound when reusing its compile cache."""
    path = ROOT / "branding/previous-v6-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == PREVIOUS_V6_MANIFEST_SHA256,
            "Previous v6 branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Previous v6 branding patch differs")
    return manifest, updater, patch


def previous_v7_contract():
    """The accepted 1.2.0 layer stays byte-bound when reusing its compile cache."""
    path = ROOT / "branding/previous-v7-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == PREVIOUS_V7_MANIFEST_SHA256,
            "Previous v7 branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Previous v7 branding patch differs")
    return manifest, updater, patch


def previous_v8_contract():
    """The accepted 1.3.0 layer stays byte-bound when reusing its compile cache."""
    path = ROOT / "branding/previous-v8-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == PREVIOUS_V8_MANIFEST_SHA256,
            "Previous v8 branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Previous v8 branding patch differs")
    return manifest, updater, patch


def previous_v9_contract():
    path = ROOT / "branding/previous-v9-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == PREVIOUS_V9_MANIFEST_SHA256,
            "Previous v9 branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Previous v9 branding patch differs")
    return manifest, updater, patch


def previous_v10_contract():
    path = ROOT / "branding/previous-v10-manifest.json"
    require(path.is_file() and not path.is_symlink() and digest(path) == PREVIOUS_V10_MANIFEST_SHA256,
            "Previous v10 branding manifest differs")
    manifest = json.loads(path.read_text())
    updater = next(r for r in manifest["repositories"] if r["repository"] == BASE["REPOSITORY"])
    patch = ROOT / "branding" / updater["patch"]
    require(patch.is_file() and not patch.is_symlink() and digest(patch) == updater["patchSha256"],
            "Previous v10 branding patch differs")
    return manifest, updater, patch


def migrate_legacy_source(source, results, manifest):
    for prior in (previous_v10_contract(), previous_v9_contract(), previous_v8_contract(), previous_v7_contract(), previous_v6_contract(), previous_v5_contract(), previous_contract(), legacy_contract()):
        if migrate_source(source, results, manifest, prior):
            return


def migrate_source(source, results, manifest, prior):
    legacy, old, old_patch = prior
    old_apk = next(r for r in old["files"] if r["path"] == "app/src/main/AndroidManifest.xml")
    repository = pathlib.Path(source) / old["repository"]
    if digest(repository / old_apk["path"]) != old_apk["afterSha256"]:
        return False
    # Validate every old branded file before changing any source. Unknown edits
    # and partial layers are preserved and rejected, never reset or force-patched.
    for record in legacy["repositories"]:
        require(inspect_repository(source, record)[1], "Incomplete legacy branding layer")
    current = next(r for r in manifest["repositories"] if r["repository"] == old["repository"])
    new_patch = ROOT / "branding" / current["patch"]
    command = ["git", "-C", str(repository), "apply", "--no-index", "--whitespace=error"]
    # The pinned upstream SVG contains trailing whitespace. Reversing its exact
    # deletion must preserve those preimage bytes; the new forward patch still
    # uses whitespace=error and the complete output is hash-checked.
    reverse = ["git", "-C", str(repository), "apply", "--no-index", "--whitespace=nowarn", "--reverse"]
    subprocess.run([*reverse, "--check", str(old_patch)], check=True)
    # Preflight the entire transition on a source-only temporary copy. No
    # compiler outputs, source volumes, frozen inputs or phone data are copied.
    with tempfile.TemporaryDirectory(prefix="taku-branding-migration-") as directory:
        temporary = pathlib.Path(directory)
        for item in old["files"]:
            target = temporary / item["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(repository / item["path"], target)
        trial = ["git", "-C", str(temporary), "apply", "--no-index", "--whitespace=error"]
        subprocess.run(["git", "-C", str(temporary), "apply", "--no-index",
                        "--whitespace=nowarn", "--reverse", str(old_patch)], check=True)
        subprocess.run([*trial, str(new_patch)], check=True)
        require(all(digest(temporary / item["path"]) == item["afterSha256"] for item in current["files"]),
                "Legacy source migration preflight differs")
    subprocess.run([*reverse, str(old_patch)], check=True)
    subprocess.run([*command, str(new_patch)], check=True)
    require(inspect_repository(source, current)[1], "Legacy source migration did not verify")
    evidence = {"schema": "taku.product-branding-source-migration/1",
                "previousManifestSha256": {3: LEGACY_MANIFEST_SHA256, 4: PREVIOUS_MANIFEST_SHA256,
                                           5: PREVIOUS_V5_MANIFEST_SHA256, 6: PREVIOUS_V6_MANIFEST_SHA256, 7: PREVIOUS_V7_MANIFEST_SHA256, 8: PREVIOUS_V8_MANIFEST_SHA256,
                                           9: PREVIOUS_V9_MANIFEST_SHA256, 10: PREVIOUS_V10_MANIFEST_SHA256}[legacy["updaterVersionCode"]],
                "previousPatchSha256": old["patchSha256"], "targetPatchSha256": current["patchSha256"],
                "sourceOnly": True, "physicalDeviceVerified": False}
    results.mkdir(parents=True, exist_ok=True)
    output = results / "product-branding-migration.json"
    if output.exists():
        require(json.loads(output.read_text()) == evidence, "Existing source migration evidence differs")
    else:
        with output.open("x") as stream:
            json.dump(evidence, stream, indent=2); stream.write("\n")
    return True


def verify_download_layer(source):
    contract, _ = BASE["load_contract"](ROOT / "updater/manifest.json")
    repository = pathlib.Path(source) / BASE["REPOSITORY"]
    require(BASE["git_revision"](repository) == BASE["UPSTREAM"], "Updater source HEAD changed")
    for item in contract["files"]:
        if item["path"] not in ("app/src/main/AndroidManifest.xml", "app/src/main/java/org/lineageos/updater/controller/UpdaterController.java"):
            target = repository / item["path"]
            require(target.is_file() and target.resolve().is_relative_to(repository.resolve())
                    and not any(p.is_symlink() for p in [target, *target.parents] if p != repository.parent)
                    and digest(target) == item["afterSha256"],
                    "The accepted download-integrity layer changed")


def apply(source, results):
    config, manifest, path = load_contract()
    source, results = pathlib.Path(source), pathlib.Path(results)
    updater = source / BASE["REPOSITORY"] / "app/src/main/AndroidManifest.xml"
    base_contract, _ = BASE["load_contract"](ROOT / "updater/manifest.json")
    original_manifest = next(r for r in base_contract["files"] if r["path"] == "app/src/main/AndroidManifest.xml")
    if updater.is_file() and digest(updater) == original_manifest["beforeSha256"]:
        BASE["apply"](source, results / "updater-download-layer")
    verify_download_layer(source)
    migrate_legacy_source(source, results, manifest)
    for record in manifest["repositories"]:
        repository, applied = inspect_repository(source, record)
        if not applied:
            command = ["git", "-C", str(repository), "apply", "--no-index", "--whitespace=error"]
            patch = ROOT / "branding" / record["patch"]
            subprocess.run([*command, "--check", str(patch)], check=True)
            subprocess.run([*command, str(patch)], check=True)
        require(inspect_repository(source, record)[1], "Branding patch did not produce accepted sources")
    evidence = {"schema": "taku.product-branding-integration/1", "productBranding": config,
                "manifestSha256": digest(path), "repositories": manifest["repositories"],
                "sourcePatchVerified": True, "physicalDeviceVerified": False}
    updater_evidence = {"schema": "taku.lineage-updater-integration/1", "upstreamCommit": BASE["UPSTREAM"],
                        "manifestSha256": digest(path), "baseManifestSha256": digest(ROOT / "updater/manifest.json"),
                        "versionCode": config["updaterVersionCode"], "packageName": "org.lineageos.updater", "uri": BASE["URI"],
                        "sourcePatchVerified": True, "apkBuiltVerified": False, "physicalDeviceVerified": False}
    results.mkdir(parents=True, exist_ok=True)
    for name, value in [("product-branding-integration.json", evidence), ("updater-integration.json", updater_evidence)]:
        output = results / name
        if output.exists():
            require(json.loads(output.read_text()) == value, "Existing branding evidence belongs to different inputs")
        else:
            with output.open("x") as stream:
                json.dump(value, stream, indent=2); stream.write("\n")
    return evidence


def verify_apk(source, apk, results):
    config, manifest, path = load_contract()
    verify_download_layer(source)
    require(all(inspect_repository(source, r)[1] for r in manifest["repositories"]), "Unverified branding sources")
    return BASE["verify_apk_bytes"](source, apk, results, "org.lineageos.updater", config["updaterVersionCode"], digest(path))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=pathlib.Path, required=True)
    parser.add_argument("--results-dir", type=pathlib.Path, required=True)
    parser.add_argument("--apk", type=pathlib.Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify_apk(args.source_root, args.apk, args.results_dir) if args.apk
                         else apply(args.source_root, args.results_dir)))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, "Product branding failed: " + str(error) + "\n")
