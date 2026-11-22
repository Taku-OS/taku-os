#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Assemble declared preview APKs and public docs. No install, device or key access."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
DOCS = Path('docs/tryon-delivery')


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def relative(value):
    if not isinstance(value, str) or not value or '\\' in value:
        raise ValueError('Invalid relative path')
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ('', '.', '..') for part in value.split('/')):
        raise ValueError('Unsafe relative path')
    return path


def regular_file(root, value):
    rel = relative(value)
    current = root
    for part in rel.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError('Symlinks are not delivery inputs')
    if not current.is_file() or not current.resolve().is_relative_to(root.resolve()):
        raise ValueError('Missing or escaped delivery input: ' + value)
    return current


def validate_declaration(value):
    if value.get('schema') != 'taku.tryon.delivery.v1' or value.get('releaseReady') is not False:
        raise ValueError('Expected an explicitly non-release preview declaration')
    rows = value.get('artifacts')
    if not isinstance(rows, list) or not rows:
        raise ValueError('Missing artifacts')
    seen = set()
    for item in rows:
        rel = relative(item['file'])
        if len(rel.parts) != 3 or rel.parts[0] not in ('verified-device', 'source-build') or rel.parts[1] != 'apk' or rel.suffix != '.apk':
            raise ValueError('Only declared APK paths may be assembled')
        if str(rel) in seen:
            raise ValueError('Duplicate artifact path')
        seen.add(str(rel))
        if not re.fullmatch(r'[a-f0-9]{64}', item['sha256']) or not re.fullmatch(r'[a-f0-9]{64}', item['certificateSha256']):
            raise ValueError('Invalid artifact digest')
        if type(item['bytes']) is not int or item['bytes'] <= 0 or not item.get('signatureVerified'):
            raise ValueError('Missing verified artifact metadata')
        if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+', item['package']):
            raise ValueError('Invalid package name')
    runtime = value.get('runtimeArtifacts', [])
    if not isinstance(runtime, list):
        raise ValueError('Invalid runtime artifact declaration')
    allowed_runtime = {'taku-runtime-start', 'agentos-task-broker.jar', 'taku-audio-bridge.jar'}
    for item in runtime:
        rel = relative(item['file'])
        if len(rel.parts) != 3 or rel.parts[:2] != ('verified-device', 'runtime') or rel.name not in allowed_runtime:
            raise ValueError('Unapproved runtime file')
        if item['file'] in seen or not re.fullmatch(r'[a-f0-9]{64}', item['sha256']):
            raise ValueError('Duplicate or invalid runtime artifact')
        if type(item['bytes']) is not int or item['bytes'] <= 0:
            raise ValueError('Invalid runtime size')
        seen.add(item['file'])
    return rows + runtime


def verify(directory):
    directory = directory.resolve()
    manifest = json.loads(regular_file(directory, 'manifest.json').read_text())
    validate_declaration(manifest)
    files = manifest.get('files')
    if not isinstance(files, dict) or not files:
        raise ValueError('Missing exact file inventory')
    expected = set(files) | {'manifest.json', 'SHA256SUMS'}
    actual = set()
    for item in directory.rglob('*'):
        if item.is_symlink():
            raise ValueError('Unexpected symlink')
        if item.is_file():
            actual.add(item.relative_to(directory).as_posix())
    if actual != expected:
        raise ValueError('File inventory differs: ' + str(sorted(actual ^ expected)))
    for name, checksum in files.items():
        if digest(regular_file(directory, name)) != checksum:
            raise ValueError('File checksum differs: ' + name)
    for item in manifest['artifacts'] + manifest.get('runtimeArtifacts', []):
        path = regular_file(directory, item['file'])
        if path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
            raise ValueError('Artifact differs from declaration: ' + item['file'])
    checksums = dict(files)
    checksums['manifest.json'] = digest(directory / 'manifest.json')
    wanted = ''.join(checksums[name] + '  ' + name + '\n' for name in sorted(checksums))
    if (directory / 'SHA256SUMS').read_text() != wanted:
        raise ValueError('SHA256SUMS differs')
    return {'ok': True, 'artifacts': len(manifest['artifacts']), 'files': len(expected),
            'runtimeArtifacts': len(manifest.get('runtimeArtifacts', [])),
            'sourceCommit': manifest['source']['commit'], 'releaseReady': False}


def assemble(artifacts, output, declaration, repo=ROOT):
    value = json.loads(declaration.read_text())
    rows = validate_declaration(value)
    repo = repo.resolve()
    state = subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=all'], cwd=repo, text=True)
    if state.strip():
        raise ValueError('Commit source and declaration before assembling')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=repo, text=True).strip()
    if output.exists() or output.is_symlink():
        raise ValueError('Use a new output directory')
    artifacts = artifacts.resolve()
    inputs = []
    for item in rows:
        path = regular_file(artifacts, item['file'])
        if path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
            raise ValueError('Input checksum differs: ' + item['file'])
        inputs.append((item['file'], path))
    docs = ['README.md', 'INSTALL.zh-CN.md', 'BUILD.md', 'PR.md', 'CHECKS.json']
    for name in docs:
        inputs.append((name, regular_file(repo, (DOCS / name).as_posix())))
    inputs.append(('verify.py', Path(__file__).resolve()))
    source_files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=repo).decode().split('\0')
    lock = {name: digest(regular_file(repo, name)) for name in source_files if name}
    output.mkdir(parents=True)
    for name, source in inputs:
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    (output / 'source.lock.json').write_text(json.dumps({'commit': commit, 'files': lock}, indent=2) + '\n')
    value['source'] = {'commit': commit, 'dirty': False, 'base': value['sourceBase'], 'fileLock': 'source.lock.json'}
    value['files'] = {p.relative_to(output).as_posix(): digest(p) for p in output.rglob('*') if p.is_file()}
    (output / 'manifest.json').write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
    sums = dict(value['files']); sums['manifest.json'] = digest(output / 'manifest.json')
    (output / 'SHA256SUMS').write_text(''.join(sums[n] + '  ' + n + '\n' for n in sorted(sums)))
    return verify(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    check = sub.add_parser('verify'); check.add_argument('directory', type=Path)
    build = sub.add_parser('assemble')
    build.add_argument('--artifacts', required=True, type=Path)
    build.add_argument('--output', required=True, type=Path)
    build.add_argument('--declaration', type=Path, default=ROOT / DOCS / 'artifacts.json')
    args = parser.parse_args()
    try:
        result = verify(args.directory) if args.command == 'verify' else assemble(args.artifacts, args.output, args.declaration)
    except (ValueError, KeyError, TypeError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + '\n')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
