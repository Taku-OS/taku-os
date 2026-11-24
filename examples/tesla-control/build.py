#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile


EXAMPLE = Path(__file__).resolve().parent
REPO = EXAMPLE.parents[1]
OUTPUT = REPO / "out/tesla-control"
SKILL = Path("tesla-model-y")
RUNTIME_FILES = (
    "SKILL.md",
    "capabilities.json",
    "provenance.json",
    "scripts/control.sh",
    "scripts/request.cjs",
    "scripts/status.cjs",
)
BUNDLE_FILES = ("install.sh", "vehicle-binding.example.json", "README.md")


def copy_source(source, target):
    if source.resolve() != source or not source.is_file():
        raise ValueError(f"Expected a regular source file: {source.relative_to(EXAMPLE)}")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    target.chmod(0o755 if target.suffix == ".sh" else 0o644)


def file_info(root, filename):
    body = (root / filename).read_bytes()
    return {"path": filename.as_posix(), "sha256": hashlib.sha256(body).hexdigest(), "bytes": len(body)}


def compile_adapter(work, output):
    android_env = r'''
source "$1/scripts/android-env.sh"
printf '%s\0' "$JAVA_HOME" "$TOOLS" "$ANDROID_JAR"
'''
    variables = subprocess.check_output(["bash", "-ec", android_env, "android-env", str(REPO)])
    java_home, tools, android_jar = map(os.fsdecode, variables.rstrip(b"\0").split(b"\0"))
    adapter_source = EXAMPLE / "src/TeslaCommandAdapter.java"
    if adapter_source.resolve() != adapter_source or not adapter_source.is_file():
        raise ValueError("Expected a regular source file: src/TeslaCommandAdapter.java")
    classes = work / "classes"
    classes.mkdir()
    subprocess.run(
        [Path(java_home) / "bin/javac", "--release", "17", "-encoding", "UTF-8", "-classpath",
         android_jar, "-d", classes, adapter_source], check=True,
    )
    subprocess.run(
        [Path(tools) / "d8", "--min-api", "36", "--lib", android_jar, "--output", output,
         *sorted(classes.rglob("*.class"))],
        env={**os.environ, "JAVA_HOME": java_home}, check=True,
    )
    with zipfile.ZipFile(output) as jar:
        with jar.open("classes.dex") as dex:
            if dex.read(4) != b"dex\n":
                raise ValueError("D8 output is missing the DEX header")
    output.chmod(0o644)


def bundle_manifest(bundle, runtime):
    template = json.loads((bundle / "vehicle-binding.example.json").read_text(encoding="utf-8"))
    if (template.get("schema") != "tesla-case-vehicle-binding/1" or template.get("vin") != ""
            or "appWidgetId" not in template or template["appWidgetId"] is not None
            or template.get("widgetHostPackage", "") != "" or template.get("model", "Model Y") != "Model Y"
            or template.keys() - {"schema", "vin", "appWidgetId", "widgetHostPackage", "model"}):
        raise ValueError("vehicle-binding.example.json must be a blank binding template")

    catalog_path = runtime / "capabilities.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    application = {key: catalog["application"][key] for key in ("package", "versionName", "versionCode", "apkSha256")}
    if (application["package"] != "com.teslamotors.tesla"
            or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-[0-9]+", application["versionName"])
            or type(application["versionCode"]) is not int or application["versionCode"] < 1
            or not re.fullmatch(r"[0-9a-f]{64}", application["apkSha256"])):
        raise ValueError("Invalid Tesla App lock in capabilities.json")
    if catalog["schema"] != "taku.tesla-capabilities/1" or not isinstance(catalog["actions"], dict):
        raise ValueError("Invalid capability catalog")

    files = [file_info(bundle, path.relative_to(bundle)) for path in sorted(bundle.rglob("*")) if path.is_file()]
    return {
        "schema": "taku.tesla-control.bundle/1",
        "noDeviceCalls": True,
        "noVehicleCommands": True,
        "application": application,
        "catalog": {
            "schema": catalog["schema"],
            "sha256": file_info(bundle, catalog_path.relative_to(bundle))["sha256"],
            "actionCount": len(catalog["actions"]),
        },
        "files": files,
    }


def build():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    if OUTPUT.parent.resolve() != OUTPUT.parent or OUTPUT.is_symlink():
        raise ValueError("Build output must not be a symbolic link")
    with tempfile.TemporaryDirectory(prefix=".tesla-control-", dir=OUTPUT.parent) as temp:
        work = Path(temp)
        bundle = work / "bundle"
        runtime = bundle / "runtime" / SKILL
        for filename in RUNTIME_FILES:
            copy_source(EXAMPLE / "skill" / SKILL / filename, runtime / filename)
        for filename in BUNDLE_FILES:
            copy_source(EXAMPLE / filename, bundle / filename)
        compile_adapter(work, runtime / "scripts/command-adapter.jar")
        manifest = bundle / "manifest.json"
        manifest.write_text(json.dumps(bundle_manifest(bundle, runtime), indent=2) + "\n", encoding="utf-8")
        manifest_digest = hashlib.sha256(manifest.read_bytes()).hexdigest()

        previous = work / "previous"
        if OUTPUT.exists():
            if not OUTPUT.is_dir():
                raise ValueError("Build output must be a directory")
            OUTPUT.rename(previous)
        try:
            bundle.rename(OUTPUT)
        except OSError:
            if previous.exists() and not OUTPUT.exists():
                previous.rename(OUTPUT)
            raise
    print(f"Bundle: {OUTPUT.relative_to(REPO)}")
    print(f"Manifest SHA-256: {manifest_digest}")


def main():
    parser = argparse.ArgumentParser(description="Build the Tesla Control bundle with local Android SDK tools.")
    parser.parse_args()
    try:
        build()
    except (KeyError, OSError, TypeError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Build failed: {error}\n")


if __name__ == "__main__":
    main()
