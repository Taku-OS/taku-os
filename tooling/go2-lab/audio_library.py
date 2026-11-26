"""Bundled, attributed clips. Clients select IDs, never filesystem paths or URLs."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent / 'audio-presets'


def catalog(directory=ROOT):
    root = Path(directory)
    data = json.loads((root / 'catalog.json').read_text())
    return {item['id']: item for item in data['clips']}


def read_preset(identity, directory=ROOT):
    if not isinstance(identity, str) or not re.fullmatch(r'[a-z][a-z0-9-]{0,39}', identity):
        raise ValueError('INVALID_PRESET')
    item = catalog(directory).get(identity)
    if item is None: raise ValueError('INVALID_PRESET')
    root = Path(directory).resolve()
    path = (root / item['file']).resolve()
    if path.parent != root or path.suffix != '.wav': raise ValueError('INVALID_PRESET')
    raw = path.read_bytes()
    if hashlib.sha256(raw).hexdigest() != item['sha256']: raise ValueError('PRESET_INTEGRITY')
    return item, raw


def public_catalog(directory=ROOT):
    return {'clips': [{k: v for k, v in item.items() if k not in {'file', 'sha256', 'sourceSha256'}}
                      for item in catalog(directory).values()]}
