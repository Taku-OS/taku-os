#!/usr/bin/env python3
"""Resolve pinned Markdown jars into the ignored build cache; print their classpath."""
import hashlib
import json
import pathlib
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
assets = json.loads((ROOT / 'system/assistant/markdown-dependencies.json').read_text())['assets']
for item in assets:
    dest = ROOT / item['path']
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == item['sha256']:
        continue
    dest.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(item['url'], timeout=30) as response:
        data = response.read()
    if hashlib.sha256(data).hexdigest() != item['sha256']:
        raise SystemExit('Markdown dependency checksum mismatch: ' + item['name'])
    with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as temporary:
        temporary.write(data)
    pathlib.Path(temporary.name).replace(dest)
print(':'.join(str(ROOT / item['path']) for item in assets))
