#!/usr/bin/env python3
"""Stage only the locked independently signed IME APK as a ROM snapshot input."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('rom_formats', ROOT / 'system/ai/rom_formats.py')
formats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formats)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validate_inputs(root, lock):
    """No SDK needed when sealing a snapshot or checking immutable build inputs."""
    for name, digest in lock['sourceInputs'].items():
        path = root / name
        if path.is_symlink() or not path.is_file() or sha(path) != digest:
            raise ValueError('IME source input differs: ' + name)
    path = root / lock['path']
    if path.is_symlink() or not path.is_file() or sha(path) != lock['sha256']:
        raise ValueError('Locked ROM IME APK missing or changed: ' + lock['path'])
    if path.stat().st_size != lock['bytes']:
        raise ValueError('Locked ROM IME APK size differs')
    for item in lock.get('jni', []):
        native = root / item['path']
        if native.is_symlink() or not native.is_file() or sha(native) != item['sha256'] or native.stat().st_size != item['bytes']:
            raise ValueError('Locked ROM native sidecar missing or changed: ' + item['path'])
        check_native(native.read_bytes(), item)
    return path


def check_native(raw, item):
    actual = formats.elf_info(raw)
    if any(actual[key] != item[key] for key in actual):
        raise ValueError('Native ELF metadata differs: ' + item['apkEntry'])


def native_entries(apk, lock):
    with zipfile.ZipFile(apk) as archive:
        entries = [i for i in archive.infolist() if i.filename.startswith('lib/') and i.filename.endswith('.so')]
        expected = {i['apkEntry']: i for i in lock['jni']}
        if len(entries) != len(expected) or {i.filename for i in entries} != set(expected) or archive.testzip():
            raise ValueError('ROM APK native set or CRC differs')
        with apk.open('rb') as source:
            for info in entries:
                source.seek(info.header_offset)
                header = source.read(30)
                name_len, extra_len = struct.unpack_from('<HH', header, 26)
                offset = info.header_offset + 30 + name_len + extra_len
                if info.compress_type != zipfile.ZIP_STORED or offset % 16384:
                    raise ValueError('ROM JNI must be stored and 16KB ZIP aligned: ' + info.filename)
                item, raw = expected[info.filename], archive.read(info)
                if hashlib.sha256(raw).hexdigest() != item['sha256'] or len(raw) != item['bytes']:
                    raise ValueError('ROM JNI bytes differ: ' + info.filename)
                check_native(raw, item)
                yield item, raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, default=ROOT / 'out/inputmethod/TakuInputMethod-arm64-v8a-rom.apk')
    parser.add_argument('--verify-only', action='store_true', help='Verify staged inputs without modifying them or requiring the SDK')
    args = parser.parse_args()
    lock = json.loads((HERE / 'rom-input-lock.json').read_text())
    if args.verify_only:
        validate_inputs(ROOT, lock)
        print('Locked ROM IME snapshot inputs verified')
        return
    apk = args.apk.absolute()
    if apk.is_symlink() or not apk.is_file() or sha(apk) != lock['sha256']:
        raise SystemExit('APK is not the pinned ROM release; rebuild and review the release lock before staging')
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))))
    tools = sdk / 'build-tools' / os.environ.get('TAKU_BUILD_TOOLS', '36.1.0')
    signing = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$', signing, re.M)
    if certs != [lock['signerSha256']]:
        raise SystemExit('IME signer differs; do not platform-sign the keyboard')
    manifest = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", manifest, re.M)
    if not package or package.groups() != (lock['package'], str(lock['versionCode']), lock['versionName']):
        raise SystemExit('ROM IME package/version differs')
    if 'android.permission.INTERNET' in manifest or "native-code: 'arm64-v8a'" not in manifest:
        raise SystemExit('ROM IME must remain an offline, arm64 package')
    xml = subprocess.check_output([str(tools / 'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'], text=True)
    if not re.search(r':extractNativeLibs\([^\n]+\)=false', xml):
        raise SystemExit('ROM release must explicitly disable native extraction')
    natives = list(native_entries(apk, lock))
    subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(apk)], check=True, stdout=subprocess.DEVNULL)
    target = ROOT / lock['path']
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_symlink():
        raise SystemExit('Refuse symlink stage destination')
    temporary = target.with_suffix('.apk.tmp')
    shutil.copyfile(apk, temporary)
    temporary.chmod(0o644)
    temporary.replace(target)
    for item, raw in natives:
        native = ROOT / item['path']
        native.parent.mkdir(parents=True, exist_ok=True)
        if native.is_symlink():
            raise SystemExit('Refuse symlink native destination')
        pending = native.with_suffix('.tmp')
        pending.write_bytes(raw)
        pending.chmod(0o755 if item['executable'] else 0o644)
        pending.replace(native)
    validate_inputs(ROOT, lock)
    print(json.dumps({'path': lock['path'], 'sha256': lock['sha256'], 'signerSha256': lock['signerSha256'],
                      'sourceInputsVerified': True, 'credentialsIncluded': False}, indent=2))


if __name__ == '__main__':
    main()
