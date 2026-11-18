#!/usr/bin/env python3
"""Bind a completed component build to unchanged source and exact output bytes."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def sources():
    paths = [p for folder in ('src', 'client', 'contract', 'res', 'network', 'models')
             for p in (HERE / folder).rglob('*') if p.is_file()
             and p.name != 'rom-files.mk' and '__pycache__' not in p.parts]
    paths += [HERE / n for n in ('AndroidManifest.xml', 'build.sh', 'record-build.py')]
    paths += [ROOT / 'scripts' / n for n in ('android-env.sh', 'kotlin-env.sh', 'aosp-test-keys.py')]
    return {str(p.relative_to(ROOT)): sha(p) for p in sorted(paths)}


def verify():
    receipt = json.loads((HERE / 'out/build-receipt.json').read_text())
    if receipt['sourceInputs'] != sources():
        raise ValueError('Audio source changed since compilation; rebuild before refreshing the ROM lock')
    for name, expected in receipt['outputs'].items():
        if sha(HERE / 'out' / name) != expected:
            raise ValueError('Audio build output differs: ' + name)
    return receipt


if __name__ == '__main__':
    pending = HERE / 'out/build-inputs.json'
    if sys.argv[1:] == ['begin']:
        pending.parent.mkdir(exist_ok=True)
        pending.write_text(json.dumps(sources(), indent=2) + '\n')
    elif sys.argv[1:] == ['finish']:
        before = json.loads(pending.read_text())
        if before != sources():
            raise SystemExit('Audio sources changed during build; discard this output and rebuild')
        receipt = {'sourceInputs': before, 'outputs': {name: sha(HERE / 'out' / name)
                   for name in ('TakuAudioEngine.apk', 'audio-bridge.jar')}}
        (HERE / 'out/build-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
        verify()
    else:
        raise SystemExit('Usage: record-build.py begin|finish')
