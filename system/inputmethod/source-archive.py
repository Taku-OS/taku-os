#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
"""Archive the patched, locked source and native inputs, without credentials or build output."""
import io
import json
from pathlib import Path
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE = ROOT / 'out/inputmethod/source'
LOCK = json.loads((HERE / 'source-lock.json').read_text())
OUTPUT = ROOT / f"out/inputmethod/TakuInputMethod-{LOCK['versionName']}-source.tar.gz"


def tracked(directory):
    return subprocess.check_output(
        ['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
        cwd=directory).decode().split('\0')


with tarfile.open(OUTPUT, 'w:gz') as archive:
    instructions = f"""Taku Input Method {LOCK['versionName']} source archive

This archive contains already-patched source, native dependencies, pinned support
libraries/data, and Taku build integration. It contains no Git administrative
files, signing keys, credentials, or build output.

For a normal Git checkout use taku-integration/README.md and prepare.py.
To build this standalone source tree, install the documented toolchain first,
then initialize a local-only Git snapshot for upstream's git-version plugin:

  cd fcitx5-android
  git init
  git add .
  git -c user.name='Source build' -c user.email='source@localhost' commit -m source
  ./gradlew :app:assembleRelease -PbuildABI=arm64-v8a -PbuildVersionName={LOCK['versionName']} -PbuildCommitHash={LOCK['commit']}

On Homebrew macOS also export ECM_DIR=$(brew --prefix extra-cmake-modules)/share/ECM/cmake.
The resulting APK is unsigned. Sign it with your development or release key.
The Git snapshot above is build metadata, not the original upstream commit.
Original revisions and product source hashes are in taku-integration/source-lock.json.
""".encode()
    info = tarfile.TarInfo('BUILDING.txt')
    info.size = len(instructions)
    archive.addfile(info, io.BytesIO(instructions))
    for base in [Path('.'), *(Path(p) for p in LOCK['submodules'])]:
        directory = SOURCE / base
        for name in dict.fromkeys(tracked(directory)):
            path = directory / name
            if not name or not (path.is_file() or path.is_symlink()):
                continue
            archive.add(path, arcname=str(Path('fcitx5-android') / base / name), recursive=False)
    for path in HERE.rglob('*'):
        if path.is_file() and '__pycache__' not in path.parts:
            archive.add(path, arcname=str(Path('taku-integration') / path.relative_to(HERE)))
print(OUTPUT)
