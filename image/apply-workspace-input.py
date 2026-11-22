#!/usr/bin/env python3
"""Integrate temporary workspace input routing into the pinned Android framework."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
INPUT = ROOT / "image/workspace-input"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None


def require(condition, message):
    if not condition:
        raise ValueError(message)


def apply(source_root, verify_only):
    manifest = json.loads((INPUT / "manifest.json").read_text())
    base = Path(source_root) / "frameworks/base"
    revision = next(p.attrib["revision"] for p in ET.parse(
        ROOT / "image/manifests/platform.xml").iter("project")
                    if p.attrib.get("path") == "frameworks/base")
    head = subprocess.check_output(["git", "-C", base, "rev-parse", "HEAD"], text=True).strip()
    require(head == revision == manifest["revision"], "Workspace input framework pin differs")
    patch = INPUT / "framework.patch"
    require(sha(patch) == manifest["patchSha256"], "Workspace input patch bytes differ")

    states = []
    for item in manifest["files"]:
        target = base / item["path"]
        require(not target.is_symlink(), f"Symlink workspace input target: {target}")
        current = sha(target)
        require(current in (item["beforeSha256"], item["afterSha256"]),
                f"Unknown workspace input target: {target}")
        states.append(current == item["afterSha256"])
    require(all(states) or not any(states), "Workspace input framework patch is partially applied")
    additions = []
    for item in manifest["sources"]:
        source = ROOT / item["source"]
        target = base / item["path"]
        require(sha(source) == item["sha256"], f"Workspace input source differs: {source}")
        require(not target.is_symlink() and (not target.exists() or sha(target) == item["sha256"]),
                f"Unknown workspace input source target: {target}")
        additions.append((source, target))

    if not all(states):
        require(not verify_only, "Workspace input framework patch is missing")
        command = ["git", "-C", base, "apply", "--whitespace=error"]
        subprocess.run([*command, "--check", patch], check=True)
        subprocess.run([*command, patch], check=True)
    for source, target in additions:
        if not target.exists():
            require(not verify_only, f"Workspace input source is missing: {target}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read_bytes())
    for item in manifest["files"]:
        require(sha(base / item["path"]) == item["afterSha256"],
                f"Workspace input target verification failed: {item['path']}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    apply(args.source_root, args.verify_only)
