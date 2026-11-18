#!/usr/bin/env python3
"""Inventory tracked release candidates without exporting files or changing Git history."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MEDIA_SUFFIXES = {'.png', '.jpg', '.jpeg', '.webp', '.svg', '.gif', '.ico',
                  '.mp4', '.webm', '.mov', '.mp3', '.wav', '.ogg', '.flac',
                  '.ttf', '.otf', '.woff', '.woff2'}
ARCHIVE_SUFFIXES = {'.zip', '.gz', '.tgz', '.xz', '.aar', '.jar', '.apk', '.so', '.onnx'}


def review_reasons(name, data):
    path = Path(name)
    reasons = []
    if name.startswith(('evidence/', 'docs/reviews/', 'docs/migrations/')):
        reasons.append('historical-evidence')
    if path.suffix.lower() in MEDIA_SUFFIXES:
        reasons.append('media-content-and-rights')
    if path.suffix.lower() in ARCHIVE_SUFFIXES:
        reasons.append('archive-or-runtime-provenance')
    if re.search(rb'https?://[^\s/<>"\x27]*(?:larksuite\.com|feishu\.cn)(?:/|\b)', data):
        reasons.append('internal-link-review')
    if name.startswith(('system/inputmethod/', 'system/audio-engine/models/')) or path.name.lower().startswith(('license', 'notice', 'copying')):
        reasons.append('license-and-provenance')
    return reasons


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', default='out/open-source/inventory.json')
    args = parser.parse_args()
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    items = []
    for name in sorted(filter(None, names)):
        path = ROOT / name
        if path.is_symlink():
            raise SystemExit('Refusing a tracked symlink: ' + name)
        if not path.is_file():
            raise SystemExit('Missing tracked file: ' + name)
        data = path.read_bytes()
        reasons = review_reasons(name, data)
        items.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest(), 'bytes': len(data), 'reviewReasons': reasons})
    report = {'schema': 'taku.publication-inventory/1',
              'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT).decode().strip(),
              'treeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
              'files': items, 'publicationApproved': False,
              'scope': 'Tracked working-tree bytes only. This inventory is not a clearance, export or secret scan.'}
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    if output.is_symlink():
        raise SystemExit('Refusing a symlink output')
    output.resolve().relative_to((ROOT / 'out').resolve())
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'files': len(items), 'reviewRequired': sum(bool(i['reviewReasons']) for i in items), 'output': str(output), 'publicationApproved': False}))


if __name__ == '__main__':
    main()
