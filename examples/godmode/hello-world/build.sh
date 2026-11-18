#!/bin/bash
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
output=${1:-"$here/out"}
mkdir -p "$output"

python3 - "$here" "$output" <<'PY'
import hashlib
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
output = pathlib.Path(sys.argv[2])
archive = output / "org.taku.hello-world-1.0.0.zip"
files = (
    pathlib.Path("manifest.json"),
    pathlib.Path("server.sh"),
    pathlib.Path("README.md"),
    pathlib.Path("build.sh"),
    pathlib.Path("skills/hello-world/SKILL.md"),
)
with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as package:
    for relative in files:
        info = zipfile.ZipInfo(str(relative), (2026, 11, 9, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        mode = 0o100755 if relative.name.endswith(".sh") else 0o100644
        info.external_attr = mode << 16
        package.writestr(info, (source / relative).read_bytes())

digest = hashlib.sha256(archive.read_bytes()).hexdigest()
checksum = archive.with_suffix(".sha256")
checksum.write_text(f"{digest}  {archive.name}\n")
print(f"{archive}\nsha256={digest}")
PY
