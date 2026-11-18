#!/usr/bin/env python3
"""Resolve the pinned on-device embedding dependencies into system/memory/downloads (ignored build cache).
Also lays out downloads/assets/bge/{model.onnx,vocab.txt} for Android.bp (the quantized model hard-linked as model.onnx, vocab derived
from tokenizer.json in id order, exactly as system/memory/build.sh does)."""
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

bge = MEMORY / 'downloads/bge-small-zh-v1.5'
out = MEMORY / 'downloads/assets/bge'
out.mkdir(parents=True, exist_ok=True)
model = out / 'model.onnx'
if not model.exists() or not os.path.samefile(model, bge / 'onnx/model_quantized.onnx'):
    model.unlink(missing_ok=True)
    os.link(bge / 'onnx/model_quantized.onnx', model)
vocab = json.loads((bge / 'tokenizer.json').read_text(encoding='utf-8'))['model']['vocab']
(out / 'vocab.txt').write_text(''.join(k + '\n' for k, _ in sorted(vocab.items(), key=lambda x: x[1])), encoding='utf-8')
print(MEMORY / 'downloads')
