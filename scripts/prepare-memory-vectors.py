#!/usr/bin/env python3
"""Resolve the pinned on-device embedding dependencies into system/memory/downloads (ignored build cache).
Also lays out downloads/assets/embed/{model.onnx,tokenizer.bin} for Android.bp (the model hard-linked as model.onnx, the tokenizer
table converted by system/memory/tools/tokenizer_bin.py, exactly as system/memory/build.sh does)."""
import hashlib
import json
import os
import pathlib
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
MEMORY = ROOT / 'system/memory'
assets = json.loads((MEMORY / 'vector-dependencies.json').read_text())['assets']

def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for block in iter(lambda: f.read(1 << 20), b''):
            h.update(block)
    return h.hexdigest()

for item in assets:
    dest = ROOT / item['path']
    if dest.exists() and sha256(dest) == item['sha256']:
        continue
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=dest.parent, delete=False) as temporary:
        with urllib.request.urlopen(item['url'], timeout=60) as response:
            for block in iter(lambda: response.read(1 << 20), b''):
                temporary.write(block)
    if sha256(temporary.name) != item['sha256']:
        os.unlink(temporary.name)
        raise SystemExit('Memory vector dependency checksum mismatch: ' + item['name'])
    pathlib.Path(temporary.name).replace(dest)

import importlib.util
src = MEMORY / 'downloads/bekko-embedding-v1-a8m'
out = MEMORY / 'downloads/assets/embed'
out.mkdir(parents=True, exist_ok=True)
model = out / 'model.onnx'
if not model.exists() or not os.path.samefile(model, src / 'onnx/model.onnx'):
    model.unlink(missing_ok=True)
    os.link(src / 'onnx/model.onnx', model)
spec = importlib.util.spec_from_file_location('tokenizer_bin', MEMORY / 'tools/tokenizer_bin.py')
tokenizer_bin = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tokenizer_bin)
tokenizer_bin.convert(src / 'tokenizer.json', out / 'tokenizer.bin')
print(MEMORY / 'downloads')
