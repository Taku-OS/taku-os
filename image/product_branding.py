#!/usr/bin/env python3
"""One product-version source; Android/Lineage compatibility IDs stay upstream."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import uuid


ROOT = pathlib.Path(__file__).resolve().parent
VERSION_COMPONENT = r"(?:0|[1-9][0-9]*)"
VERSION_PATTERN = VERSION_COMPONENT + r"\." + VERSION_COMPONENT + r"\." + VERSION_COMPONENT
LEGACY_VERSION_PATTERN = r"[0-9]+\.[0-9]+"
CURRENT_SCHEMA = "taku.product-branding/2"
UPDATER_VERSION_CODE = 11
V2_MIN_UPDATER_VERSION_CODE = 4


def require(value, message):
    if not value:
        raise ValueError(message)


def version_parts(value, *, allow_legacy=False):
    """Numeric comparison only; never normalize bytes in an archived identity."""
    require(isinstance(value, str), "Product version must be a string")
    pattern = VERSION_PATTERN
    if allow_legacy:
        pattern += "|" + LEGACY_VERSION_PATTERN
    require(re.fullmatch(pattern, value) is not None, "Invalid product version")
    parts = tuple(int(part) for part in value.split("."))
    return parts if len(parts) == 3 else (*parts, 0)


def validate_branding(brand):
    """Read exact v1/v2 receipt contracts without weakening either generation."""
    require(isinstance(brand, dict) and brand.get("productName") == "TakuOS",
            "Invalid product branding name")
    schema, version = brand.get("schema"), brand.get("productVersion")
    if schema == "taku.product-branding/1":
        require(isinstance(version, str) and re.fullmatch(LEGACY_VERSION_PATTERN, version) is not None,
                "Invalid legacy product version")
        updater_version = 3
    else:
        require(schema == CURRENT_SCHEMA, "Unsupported product branding schema")
        version_parts(version)
        updater_version = brand.get("updaterVersionCode")
        require(type(updater_version) is int and V2_MIN_UPDATER_VERSION_CODE <= updater_version <= 2100000000,
                "Invalid product Updater version code")
    require(type(brand.get("updaterVersionCode")) is int and brand["updaterVersionCode"] == updater_version
            and brand.get("displayVersion") == brand["productName"] + version
            and isinstance(brand.get("configSha256"), str)
            and re.fullmatch(r"[0-9a-f]{64}", brand["configSha256"]) is not None,
            "Invalid product branding identity")
    return updater_version


def load(path=None):
    path = pathlib.Path(path or ROOT / "product-branding.json")
    require(not path.is_symlink(), "Product branding must not be a symlink")
    config = json.loads(path.read_text())
    require(isinstance(config, dict) and set(config) == {"schema", "productName", "productVersion", "updaterVersionCode"}
            and config["schema"] == CURRENT_SCHEMA and config["productName"] == "TakuOS"
            and isinstance(config["productVersion"], str)
            and config["updaterVersionCode"] == UPDATER_VERSION_CODE, "Unexpected product branding contract")
    identity = {**config, "displayVersion": config["productName"] + config["productVersion"],
                "configSha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    validate_branding(identity)
    return identity


def build_identity(config, number):
    require(re.fullmatch(r"[0-9]{14}", number) is not None, "Expected the exact immutable build number")
    return {**config, "buildDisplayId": config["displayVersion"] + "-" + number,
            "fullOtaFileName": f"{config['productName']}-{config['productVersion']}-tokay-{number}-full.zip"}


def makefile(config):
    # Values have no shell/make whitespace or escaping rules to drift across tools.
    properties = {"ro.taku.product.name": config["productName"],
                  "ro.taku.product.version": config["productVersion"],
                  "ro.taku.display.version": config["displayVersion"]}
    return ("# Generated from image/product-branding.json; validated before integration.\n"
            + "".join("PRODUCT_SYSTEM_EXT_PROPERTIES += " + key + "=" + value + "\n"
                      for key, value in properties.items())
            # init loads product after system_ext. Its generated optional
            # build-info default must be replaced in that same partition.
            + "PRODUCT_PRODUCT_PROPERTIES += ro.build.display.id="
            + config["displayVersion"] + "-$(BUILD_NUMBER)\n")


BOOT_PROPERTY_POLICY = {
    "schema": "taku.branding-boot-properties/1",
    "initRevision": "7c3f32fe89c52b4176fd288ba5bb4f907809c202",
    "partitionOrder": ["SYSTEM/build.prop", "SYSTEM_EXT/etc/build.prop",
                       "SYSTEM_DLKM/etc/build.prop", "VENDOR/default.prop",
                       "VENDOR/build.prop", "VENDOR_DLKM/etc/build.prop",
                       "ODM_DLKM/etc/build.prop", "ODM/etc/build.prop", "PRODUCT/etc/build.prop"],
    "requiredFiles": ["SYSTEM/build.prop", "SYSTEM_EXT/etc/build.prop",
                      "VENDOR/build.prop", "PRODUCT/etc/build.prop"],
    "placements": {"ro.taku.product.name": "SYSTEM_EXT/etc/build.prop",
                   "ro.taku.product.version": "SYSTEM_EXT/etc/build.prop",
                   "ro.taku.display.version": "SYSTEM_EXT/etc/build.prop",
                   "ro.build.display.id": "PRODUCT/etc/build.prop"},
}


def verify_boot_properties(archive, identity, policy_path=None):
    """Check fixed init's partition precedence, not a phone's runtime value.

    Second-stage defaults precede SYSTEM. These four required explicit
    properties therefore replace them. Debug-ramdisk overrides are inspected
    separately; imports and unmodelled legacy fallbacks fail closed.
    """
    policy_path = pathlib.Path(policy_path or ROOT / "branding-properties.json")
    require(not policy_path.is_symlink() and policy_path.is_file(), "Missing branding boot-property policy")
    raw_policy = policy_path.read_bytes()
    require(json.loads(raw_policy) == BOOT_PROPERTY_POLICY, "Unexpected branding boot-property policy")
    names = archive.namelist()
    require(len(names) == len(set(names)), "Duplicate target-files entries")
    expected = {"ro.taku.product.name": identity["productName"],
                "ro.taku.product.version": identity["productVersion"],
                "ro.taku.display.version": identity["displayVersion"],
                "ro.build.display.id": identity["buildDisplayId"]}
    require(all(name in names for name in BOOT_PROPERTY_POLICY["requiredFiles"]),
            "Missing branding boot-property partition")
    for partition in ("SYSTEM_EXT", "ODM", "PRODUCT"):
        if partition + "/etc/build.prop" not in names:
            require(not any(partition + "/" + name in names for name in ("default.prop", "build.prop")),
                    "Unmodelled legacy boot-property fallback")
    effective, provenance, records = {}, {}, []
    ordered = [name for name in BOOT_PROPERTY_POLICY["partitionOrder"] if name in names]
    ordered += sorted(name for name in names if name.endswith("/adb_debug.prop"))
    for name in ordered:
        raw = archive.read(name)
        values = {}
        for line in raw.decode("utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            require(not line.startswith("import "), "Unmodelled boot-property import: " + name)
            if "=" not in line:
                continue
            key, value = (part.strip() for part in line.split("=", 1))
            require(key.rstrip("?") not in expected or (not key.endswith("?") and key not in values),
                    "Unprocessed/duplicate branding boot property: " + name)
            if key in expected:
                values[key] = value
        records.append({"path": name, "sha256": hashlib.sha256(raw).hexdigest(),
                        "bytes": len(raw), "brandingProperties": values})
        for key, value in values.items():
            effective[key], provenance[key] = value, name
        for key, location in BOOT_PROPERTY_POLICY["placements"].items():
            if location == name:
                require(values.get(key) == expected[key], "Branding boot-property placement differs: " + key)
    require(effective == expected and provenance == BOOT_PROPERTY_POLICY["placements"],
            "Effective branding boot properties differ after init partition precedence")
    return {"schema": BOOT_PROPERTY_POLICY["schema"], "policySha256": hashlib.sha256(raw_policy).hexdigest(),
            "initRevision": BOOT_PROPERTY_POLICY["initRevision"], "files": records,
            "effectiveProperties": effective, "effectiveSources": provenance,
            "physicalDeviceVerified": False}


def updater_constants(config):
    kotlin_version = VERSION_PATTERN.replace("\\", "\\\\")
    kotlin_legacy = LEGACY_VERSION_PATTERN.replace("\\", "\\\\")
    return ("// Generated from image/product-branding.json.\n"
            "package org.lineageos.updater\n\nimport android.os.SystemProperties\n\nobject TakuBrand {\n"
            f"    const val NAME = \"{config['productName']}\"\n"
            f"    const val VERSION = \"{config['productVersion']}\"\n"
            f"    const val DISPLAY_VERSION = \"{config['displayVersion']}\"\n"
            f'    private val versionPattern = Regex("{kotlin_version}")\n'
            f'    private val legacyPattern = Regex("{kotlin_legacy}")\n'
            '    private fun validVersion(value: String?) = value?.takeIf {\n'
            '        versionPattern.matches(it) || legacyPattern.matches(it)\n'
            '    }\n'
            '    fun targetVersion(value: String?): String? =\n'
            '        value?.takeIf { it.startsWith(NAME) }?.removePrefix(NAME)?.let(::validVersion)\n'
            '    val currentVersion: String\n'
            '        get() = validVersion(SystemProperties.get("ro.taku.product.version", ""))\n'
            '            ?: targetVersion(SystemProperties.get("ro.taku.display.version", "")) ?: VERSION\n'
            '    fun targetDisplayName(value: String?) = targetVersion(value)?.let { "$NAME $it" } ?: NAME\n'
            '    fun buildLabel(value: String?, date: String) = "${targetDisplayName(value)} - $date"\n'
            "}\n")


def stage_full_ota(product, number):
    config = build_identity(load(), number)
    product = pathlib.Path(product)
    source = product / "lineage_tokay-ota.zip"
    require(source.is_file() and not source.is_symlink(), "Missing canonical completed full OTA")
    target = product / config["fullOtaFileName"]
    def checksum(path):
        value = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b""):
                value.update(chunk)
        return value.hexdigest()
    if target.exists():
        require(target.is_file() and not target.is_symlink() and checksum(target) == checksum(source),
                "Existing branded OTA differs; preserve it and investigate")
        return target
    # This is a build-output name, never a rewrite of an immutable old archive.
    partial = target.with_name(target.name + ".partial-" + uuid.uuid4().hex)
    with source.open("rb") as src, partial.open("xb") as dst:
        shutil.copyfileobj(src, dst, length=8 * 1024 * 1024)
    require(partial.stat().st_size == source.stat().st_size and checksum(partial) == checksum(source),
            "Branded OTA copy was truncated or changed")
    require(not target.exists(), "Branded OTA appeared during copy; preserve both files")
    partial.rename(target)
    return target


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage-full-ota", type=pathlib.Path)
    parser.add_argument("--build-number")
    args = parser.parse_args()
    if args.stage_full_ota:
        print(stage_full_ota(args.stage_full_ota, args.build_number))
    else:
        print(json.dumps(load(), indent=2))
