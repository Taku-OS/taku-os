#!/usr/bin/env python3
"""Pack a fully built, stripped ARM64 Codex binary for the Android launcher."""
import gzip, hashlib, json, pathlib, sys, tarfile

if len(sys.argv) != 3:
    raise SystemExit('usage: package-runtime.py STRIPPED_CODEX OUTPUT_TAR_GZ')
binary, output = map(pathlib.Path, sys.argv[1:])
if not binary.is_file() or binary.stat().st_size == 0:
    raise SystemExit('missing binary')
licenses = pathlib.Path(__file__).resolve().parent / 'licenses'
provenance = json.loads((licenses / 'provenance.json').read_text())
names = [item['path'] for item in provenance['files']]
if len(names) != len(set(names)) or any(pathlib.PurePath(name).name != name for name in names):
    raise SystemExit('Unexpected runtime notice path')
expected = set(names) | {'provenance.json'}
if {path.name for path in licenses.iterdir()} != expected:
    raise SystemExit('Unexpected runtime notice entry')
for item in provenance['files']:
    path = licenses / item['path']
    if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
        raise SystemExit('Upstream license bytes do not match provenance')
output.parent.mkdir(parents=True, exist_ok=True)
if output.exists() or output.is_symlink():
    raise SystemExit('Refusing to replace an existing runtime archive')
with output.open('xb') as raw:
    with gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as archive:
            info = tarfile.TarInfo('codex-aarch64-unknown-linux-musl')
            info.size = binary.stat().st_size
            info.mode = 0o755
            info.mtime = info.uid = info.gid = 0
            with binary.open('rb') as source:
                archive.addfile(info, source)
            for path in (licenses / name for name in sorted(expected)):
                if path.is_symlink() or not path.is_file():
                    raise SystemExit('Unexpected runtime notice entry')
                info = tarfile.TarInfo('licenses/codex/' + path.name)
                info.size = path.stat().st_size
                info.mode = 0o644
                info.mtime = info.uid = info.gid = 0
                with path.open('rb') as source:
                    archive.addfile(info, source)
for path in (binary, output):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        while chunk := source.read(1024 * 1024): digest.update(chunk)
    print(f'{digest.hexdigest()}  {path.stat().st_size}  {path}')
