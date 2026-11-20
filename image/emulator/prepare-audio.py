#!/usr/bin/env python3
"""Register the pinned Goldfish software AEC in an isolated emulator build."""
import argparse
import hashlib
import json
from pathlib import Path

UPSTREAM = '26b54895f82ece727f871568e6099a6e890e63125e3549b1eb61e91e6f4c2c9b'
REGISTERED = 'e9549bd4dbc26b1e2d86523dc1de2efce95c8ffba9c46f033d3781a55ca378a4'
CONFIG = 'frameworks/av/media/libeffects/data/audio_effects.xml'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def prepare(root):
    path = root / CONFIG
    raw = path.read_bytes()
    if digest(raw) == REGISTERED:
        return
    if digest(raw) != UPSTREAM:
        raise ValueError('Unexpected Goldfish audio effects configuration; refusing to overwrite it')
    raw = raw.replace(b'    <libraries>\n', b'    <libraries>\n'
                      b'        <library name="pre_processing" path="libaudiopreprocessing.so"/>\n', 1)
    raw = raw.replace(b'    <effects>\n', b'    <effects>\n'
                      b'        <effect name="aec" library="pre_processing" '
                      b'uuid="bb392ec0-8d4d-11e0-a896-0002a5d5c51b"/>\n', 1)
    if digest(raw) != REGISTERED:
        raise ValueError('AEC registration did not produce the verified configuration')
    path.write_bytes(raw)


def verify(product):
    config = product / 'vendor/etc/audio_effects.xml'
    library = product / 'vendor/lib64/soundfx/libaudiopreprocessing.so'
    if digest(config.read_bytes()) != REGISTERED:
        raise ValueError('Built vendor image is missing the verified AEC registration')
    if not library.is_file() or library.stat().st_size == 0:
        raise ValueError('Built vendor image is missing the software AEC library')
    return {'schema': 'taku.emulator-audio/1', 'configurationSha256': REGISTERED,
            'librarySha256': digest(library.read_bytes()), 'deviceVerified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--source-root', type=Path)
    mode.add_argument('--product', type=Path)
    args = parser.parse_args()
    if args.source_root:
        prepare(args.source_root)
    else:
        print(json.dumps(verify(args.product), indent=2))


if __name__ == '__main__':
    main()
