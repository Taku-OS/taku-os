#!/usr/bin/env python3
"""Build a deterministic, checksum-locked Taku runtime pack.

Usage:
  build-runtime-pack.py ROOT OUTPUT_ZIP LOCK_JSON [SHA256SUMS]

Regular files are sorted and compressed deterministically. Relative symlinks
whose resolved targets stay inside ROOT are recorded in the lock as `links`;
absolute and dangling links are omitted and listed under `skippedLinks`.
"""
from __future__ import annotations

import hashlib
import json
import os
import pathlib
import stat
import sys
import tempfile
import zipfile

REQUIRED = {
    "bin/node", "bin/java", "rea/package.json", "rea/scripts/rea.mjs",
    "lib/jadx-headless-mcp-0.7.1-all.jar",
}
FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def digest(path: pathlib.Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def atomic_write(path: pathlib.Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main(argv: list[str]) -> None:
    if len(argv) not in (4, 5):
        raise SystemExit("usage: build-runtime-pack.py ROOT OUTPUT_ZIP LOCK_JSON [SHA256SUMS]")
    root = pathlib.Path(argv[1]).resolve(strict=True)
    output = pathlib.Path(argv[2]).resolve()
    lock_path = pathlib.Path(argv[3]).resolve()
    sums_path = pathlib.Path(argv[4]).resolve() if len(argv) == 5 else None
    excluded = {output, lock_path}
    if sums_path is not None:
        excluded.add(sums_path)

    regular: list[pathlib.Path] = []
    executables: list[str] = []
    links: dict[str, str] = {}
    skipped_links: list[dict[str, str]] = []
    for current, directories, files in os.walk(root, topdown=True, followlinks=False):
        base = pathlib.Path(current)
        directories.sort()
        files.sort()
        for name in list(directories):
            path = base / name
            if path.is_symlink():
                directories.remove(name)
                files.append(name)
        for name in files:
            path = base / name
            if path in excluded:
                continue
            relative = path.relative_to(root).as_posix()
            mode = path.lstat().st_mode
            if stat.S_ISLNK(mode):
                target = os.readlink(path)
                target_path = pathlib.Path(target)
                if target_path.is_absolute():
                    skipped_links.append({"path": relative, "target": target, "reason": "absolute"})
                    continue
                resolved = (path.parent / target_path).resolve(strict=False)
                if not resolved.is_relative_to(root) or not resolved.exists():
                    skipped_links.append({"path": relative, "target": target, "reason": "outside-or-dangling"})
                    continue
                if relative in links:
                    raise ValueError(f"duplicate link path: {relative}")
                links[relative] = target
            elif stat.S_ISREG(mode):
                regular.append(path)
                if mode & 0o111:
                    executables.append(relative)
            else:
                raise ValueError(f"unsupported file type: {relative}")
    regular.sort(key=lambda item: item.relative_to(root).as_posix())
    names = {path.relative_to(root).as_posix() for path in regular}
    missing = REQUIRED - names
    if missing:
        raise ValueError("missing required files: " + ", ".join(sorted(missing)))
    overlap = names & set(links)
    if overlap:
        raise ValueError("paths are both files and links: " + ", ".join(sorted(overlap)))

    output.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_zip = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
    os.close(fd)
    try:
        file_hashes: dict[str, str] = {}
        with zipfile.ZipFile(temporary_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path in regular:
                relative = path.relative_to(root).as_posix()
                file_hashes[relative] = digest(path)
                info = zipfile.ZipInfo(relative, FIXED_TIME)
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                with path.open("rb") as source, archive.open(info, "w", force_zip64=True) as target:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        target.write(chunk)
        os.replace(temporary_zip, output)
    finally:
        if os.path.exists(temporary_zip):
            os.unlink(temporary_zip)

    lock = {
        "schemaVersion": 2,
        "platform": "android-arm64",
        "node": "24.22.2",
        "rea": "6.8.0",
        "java": "21",
        "jadx": "0.7.1",
        "frida": "17.25.2",
        "fridaBinding": "17.23.2",
        "fridaJavaBridge": "7.0.13",
        "rizin": "0.9.1",
        "apktool": "3.0.3",
        "aapt2": "2.20-android-16.0.0_r4",
        "archiveSha256": digest(output),
        "files": file_hashes,
        "executables": sorted(executables),
        "links": dict(sorted(links.items())),
        "skippedLinks": skipped_links,
    }
    encoded = (json.dumps(lock, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")
    atomic_write(lock_path, encoded)
    if sums_path is not None:
        lines = [f"{value}  {name}" for name, value in sorted(file_hashes.items())]
        atomic_write(sums_path, ("\n".join(lines) + "\n").encode("utf-8"))
    print(json.dumps({
        "ok": True,
        "archive": str(output),
        "lock": str(lock_path),
        "files": len(file_hashes),
        "links": len(links),
        "skippedLinks": len(skipped_links),
        "archiveSha256": lock["archiveSha256"],
    }, ensure_ascii=False))


if __name__ == "__main__":
    main(sys.argv)
