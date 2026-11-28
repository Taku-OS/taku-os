#!/usr/bin/env python3
"""Verify audio inputs in a product directory or target-files, including OTA bytes."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('audio_rom', HERE / 'prepare-rom.py')
rom = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rom)
APK = 'SYSTEM_EXT/priv-app/TakuAudioEngine/TakuAudioEngine.apk'
POLICY = 'SYSTEM_EXT/etc/permissions/privapp-permissions-org.agentos.audio.xml'
ASSISTANT_POLICY = 'SYSTEM_EXT/etc/permissions/privapp-permissions-org.agentos.assistant.audio.xml'
MODEL_PREFIX = 'SYSTEM_EXT/etc/taku-audio/models/'


def installed_path(item):
    relative = item['path'].split('/prebuilt/', 1)[1]
    if relative == 'TakuAudioEngine.apk':
        return APK
    if relative == 'audio-bridge.jar':
        return 'SYSTEM_EXT/etc/taku-audio/taku-audio-bridge.jar'
    return 'SYSTEM_EXT/etc/taku-audio/' + relative


def verify(read, names, lock, aapt2=None):
    for item in lock['assets']:
        name = installed_path(item)
        raw = read(name)
        if name != APK and (len(raw) != item['bytes'] or hashlib.sha256(raw).hexdigest() != item['sha256']):
            raise ValueError('Installed audio asset differs: ' + name)
    expected_models = {installed_path(i) for i in lock['assets'] if '/prebuilt/models/' in i['path']}
    if {n for n in names if n.startswith(MODEL_PREFIX) and not n.endswith('/')} != expected_models:
        raise ValueError('Missing or extra ROM model files')
    if read(ASSISTANT_POLICY) != (HERE / 'permissions/org.agentos.assistant.audio.xml').read_bytes():
        raise ValueError('Assistant audio routing permission policy differs')
    policy = read(POLICY)
    if policy != (HERE / 'permissions/org.agentos.audio.xml').read_bytes():
        raise ValueError('Audio privileges differ from the approved six permissions')
    for name in names:
        if name != POLICY and name.startswith('SYSTEM_EXT/etc/permissions/') and name.endswith('.xml'):
            if any(n.get('package') == 'org.agentos.audio' for n in ET.fromstring(read(name)).iter('privapp-permissions')):
                raise ValueError('Additional audio permission policy')
    with tempfile.TemporaryDirectory() as temp:
        apk = Path(temp) / 'audio.apk'
        apk.write_bytes(read(APK))
        # Soong/OTA may change only the signing block and META-INF signatures.
        if rom.apk_entries(apk) != lock['apkEntries']:
            raise ValueError('Installed audio APK content differs from the sealed component')
        if aapt2:
            text = subprocess.check_output([str(aapt2), 'dump', 'badging', str(apk)], text=True)
            if f"package: name='{lock['package']}' versionCode='{lock['versionCode']}'" not in text:
                raise ValueError('Installed audio package/version mismatch')
            if "launchable-activity: name='org.agentos.audio.AudioHomeActivity'" not in text:
                raise ValueError('Installed Taku Audio launcher entry is missing')
    return {'schema': 'taku.audio-rom-verification/1', 'passed': True, 'assets': len(lock['assets']),
            'modelBytes': sum(i['bytes'] for i in lock['assets'] if '/models/' in i['path']),
            'apkSha256': hashlib.sha256(read(APK)).hexdigest(), 'deviceBootVerified': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--aapt2', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    lock = json.loads((HERE / 'rom-input-lock.json').read_text())
    rom.validate_inputs(rom.ROOT, lock)
    if args.input.is_dir():
        def read(name):
            partition, relative = name.split('/', 1)
            return rom.ordinary(args.input, partition.lower() + '/' + relative).read_bytes()
        names = ['SYSTEM_EXT/' + str(p.relative_to(args.input / 'system_ext'))
                 for p in (args.input / 'system_ext').rglob('*') if p.is_file()]
        result = verify(read, names, lock, args.aapt2)
    else:
        with zipfile.ZipFile(args.input) as archive:
            if len(archive.namelist()) != len(set(archive.namelist())):
                raise ValueError('Duplicate target-files entries')
            result = verify(archive.read, archive.namelist(), lock, args.aapt2)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
