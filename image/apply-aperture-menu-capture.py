#!/usr/bin/env python3
"""Apply the Taku menu capture handoff to the pinned Aperture project."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def apply(source, verify_only=False):
    contract = Path(__file__).resolve().parent / "aperture"
    manifest = json.loads((contract / "manifest.json").read_text())
    patch = contract / "menu-capture.patch"
    checkout = source / "packages/apps/Aperture"
    git = ["git", "-C", str(checkout)]
    revision = subprocess.check_output(git + ["rev-parse", "HEAD"], text=True).strip()
    if revision != manifest["upstreamCommit"] or digest(patch) != manifest["patchSha256"]:
        raise SystemExit("Aperture menu capture inputs do not match the pinned source")
    files = manifest["files"]
    paths = [entry["path"] for entry in files]
    touched = {line[6:] for line in patch.read_text().splitlines()
               if line.startswith(("--- a/", "+++ b/"))}
    if len(paths) != len(touched) or set(paths) != touched or any(
            Path(path).is_absolute() or ".." in Path(path).parts for path in paths):
        raise SystemExit("Aperture menu capture patch has unexpected targets")
    before = [entry["beforeSha256"] for entry in files]
    after = [entry["afterSha256"] for entry in files]
    current = [digest(checkout / path) for path in paths]
    if current == after:
        return
    if verify_only or current != before:
        raise SystemExit("Aperture menu capture files differ from the expected state")
    command = git + ["apply", "--whitespace=error"]
    subprocess.run(command + ["--check", str(patch)], check=True)
    subprocess.run(command + [str(patch)], check=True)
    if [digest(checkout / path) for path in paths] != after:
        raise SystemExit("Aperture menu capture applied with unexpected output")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    apply(args.source_root, args.verify_only)
