#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
"""Fetch the locked upstream and apply the reviewable Taku patch, without touching other work."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
LOCK = json.loads((HERE / 'source-lock.json').read_text())

def run(*args, cwd=None, capture=False):
    return subprocess.run(args, cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout

def prepare(source, fetch=True):
    if not (source / '.git').exists():
        if not fetch:
            raise SystemExit('Source checkout missing; run prepare.py without --no-fetch first.')
        source.parent.mkdir(parents=True, exist_ok=True)
        run('git', 'clone', '--depth', '1', '--branch', LOCK['tag'], LOCK['url'], str(source))
    revision = run('git', 'rev-parse', 'HEAD', cwd=source, capture=True).strip()
    if revision != LOCK['commit']:
        raise SystemExit(f'Refusing unexpected upstream revision: {revision}')
    paths = LOCK['topLevelSubmodules']
    if fetch:
        run('git', 'submodule', 'update', '--init', '--recursive', '--depth', '1', '--jobs', '5', '--', *paths, cwd=source)
    for path, commit in LOCK['submodules'].items():
        revision = run('git', 'rev-parse', 'HEAD', cwd=source / path, capture=True).strip()
        if revision != commit:
            raise SystemExit(f'Wrong native dependency revision: {path}: {revision}')
    expected = LOCK['patchedFiles']
    # Later patches may change the same hunk as an earlier one, so an
    # individually reversible first patch is not a reliable completion test.
    complete = all((source / path).is_file() and
                   hashlib.sha256((source / path).read_bytes()).hexdigest() == digest
                   for path, digest in expected.items())
    for patch in ([] if complete else sorted((HERE / 'patches').glob('*.patch'))):
        already = subprocess.run(['git', 'apply', '--reverse', '--check', str(patch)], cwd=source,
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        if not already:
            run('git', 'apply', '--check', str(patch), cwd=source)
            run('git', 'apply', str(patch), cwd=source)
    for path, digest in expected.items():
        if hashlib.sha256((source / path).read_bytes()).hexdigest() != digest:
            raise SystemExit(f'Unexpected product source change: {path}')
    changed = run('git', 'diff', '--name-only', 'HEAD', cwd=source, capture=True).splitlines()
    untracked = run('git', 'ls-files', '--others', '--exclude-standard', cwd=source, capture=True).splitlines()
    extra = (set(changed) | set(untracked)) - set(expected)
    if extra:
        raise SystemExit(f'Unexpected source files: {sorted(extra)}')
    print(f'Taku source ready: {source}', flush=True)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT / 'out/inputmethod/source')
    parser.add_argument('--no-fetch', action='store_true')
    args = parser.parse_args()
    prepare(args.source.resolve(), not args.no_fetch)
