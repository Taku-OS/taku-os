#!/usr/bin/env python3
"""Verify baked IME/shared-AI components in target-files; never claim physical acceptance."""
import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ANDROID = '{http://schemas.android.com/apk/res/android}'
spec = importlib.util.spec_from_file_location('rom_formats', ROOT / 'system/ai/rom_formats.py')
formats = importlib.util.module_from_spec(spec)
spec.loader.exec_module(formats)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def definitions(raw):
    result = set()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        for name in archive.namelist():
            if re.fullmatch(r'classes\d*\.dex', name):
                result.update(formats.dex_classes(archive.read(name)))
    return result


def native_sidecars(archive, lock):
    expected = {item['installPath'] for item in lock['jni']} | {item['path'] for item in lock['jniSymlinks']}
    actual = {n for n in archive.namelist() if not n.endswith('/') and
              (n.startswith('SYSTEM_EXT/lib64/taku-inputmethod/') or n.startswith('SYSTEM_EXT/bin/taku-inputmethod/') or
               n == 'SYSTEM_EXT/app/TakuInputMethod/lib/arm64')}
    if expected != actual:
        raise ValueError('ROM native sidecar set differs: ' + str(sorted(expected ^ actual)))
    config = {}
    for line in archive.read('META/system_ext_filesystem_config.txt').decode().splitlines():
        fields = line.split()
        if len(fields) >= 4:
            config[fields[0]] = fields[1:4]
    report = {}
    for item in lock['jni']:
        path = item['installPath']
        if archive.namelist().count(path) != 1 or stat.S_ISLNK(archive.getinfo(path).external_attr >> 16):
            raise ValueError('Missing, duplicate or linked native sidecar: ' + path)
        raw = archive.read(path)
        if sha(raw) != item['sha256'] or len(raw) != item['bytes']:
            raise ValueError('ROM native sidecar bytes differ: ' + path)
        metadata = formats.elf_info(raw)
        if any(metadata[k] != item[k] for k in metadata):
            raise ValueError('ROM native ELF differs: ' + path)
        mode = '0755' if item['executable'] else '0644'
        fs = config.get('system_ext/' + path.removeprefix('SYSTEM_EXT/'))
        # AOSP's system_ext/bin/* default group is shell; shared objects use root.
        allowed_groups = ['0', '2000'] if item['executable'] else ['0']
        if fs is None or fs[0] != '0' or fs[1] not in allowed_groups or int(fs[2], 8) & 0o7777 != int(mode, 8):
            raise ValueError('ROM native fs_config ownership/mode differs: ' + path)
        report[path.removeprefix('SYSTEM_EXT/')] = {'sha256': sha(raw), 'bytes': len(raw), 'mode': mode, 'uid': int(fs[0]), 'gid': int(fs[1]), **metadata}
    links = {}
    for item in lock['jniSymlinks']:
        path = item['path']
        if archive.namelist().count(path) != 1 or not stat.S_ISLNK(archive.getinfo(path).external_attr >> 16) or archive.read(path).decode() != item['target']:
            raise ValueError('ROM native symlink differs: ' + path)
        links[path.removeprefix('SYSTEM_EXT/')] = item['target']
    return report, links


def verify(archive, root, badging):
    workspace_runtime = int(ET.parse(root / 'system/assistant/AndroidManifest.xml').getroot().get(ANDROID + 'versionCode')) >= 73
    lock = json.loads((root / 'system/inputmethod/rom-input-lock.json').read_text())
    report = {'status': 'artifact_verified', 'physicalVerified': False, 'runtimeBindRequired': False, 'components': {}}
    names = set(archive.namelist())
    if any(n.endswith(('/auth.json', '/web.key')) or '/data/local/agentos/' in n for n in names):
        raise ValueError('Private runtime state must not be included in the ROM')
    def member(name):
        path = 'SYSTEM_EXT/' + name
        if archive.namelist().count(path) != 1:
            raise ValueError('Missing or duplicate system AI component: ' + path)
        raw = archive.read(path)
        report['components'][name] = {'sha256': sha(raw), 'bytes': len(raw)}
        return raw
    ime = member('app/TakuInputMethod/TakuInputMethod.apk')
    if sha(ime) != lock['sha256']:
        raise ValueError('ROM IME is not the pinned independently signed release')
    report['nativeComponents'], report['nativeSymlinks'] = native_sidecars(archive, lock)
    for path, package, code in [('app/TakuInputMethod/TakuInputMethod.apk', lock['package'], lock['versionCode']),
                                ('priv-app/TakuAssistant/TakuAssistant.apk', 'org.agentos.assistant', None),
                                ('priv-app/TakuMemory/TakuMemory.apk', 'org.agentos.memory', None)]:
        raw = ime if path.startswith('app/') else member(path)
        if code is None:
            source = 'assistant' if package.endswith('.assistant') else 'memory'
            code = int(ET.parse(root / 'system' / source / 'AndroidManifest.xml').getroot().get(ANDROID + 'versionCode'))
        text = badging(raw)
        match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", text, re.M)
        if not match or match.groups() != (package, str(code)):
            raise ValueError('Wrong ROM APK package/version: ' + package)
        report['components'][path].update(package=package, versionCode=code)
        if package.endswith('.assistant'):
            dex = definitions(raw)
            for cls in ['SystemAiActivity', 'SystemAiProvider', 'ImeReplyProvider']:
                if 'Lorg/agentos/assistant/' + cls + ';' not in dex:
                    raise ValueError('Missing shared AI class definition: ' + cls)
    broker = member('framework/agentos-task-broker.jar')
    dex = definitions(broker)
    broker_classes = ['TaskBroker', 'NativeBridge', 'SystemAi', 'AiPolicy', 'ImeReplies', 'ImeReplyReuse', 'MemoryDecisionJson']
    if workspace_runtime:
        broker_classes += ['PhoneExecutionScreens', 'WorkspaceControl', 'WorkspaceAdmissions']
    for cls in broker_classes:
        if 'Lorg/agentos/lab/' + cls + ';' not in dex:
            raise ValueError('ROM Broker class definition is missing: ' + cls)
    if workspace_runtime:
        display = definitions(member('framework/agentos-virtual-display.jar'))
        for cls in ['VirtualDisplayHost', 'DisplaySlot', 'DisplayControl', 'FrameCoalescer']:
            if 'Lorg/agentos/lab/' + cls + ';' not in display:
                raise ValueError('ROM display class definition is missing: ' + cls)
        surface = member('priv-app/TakuSurfaces/TakuSurfaces.apk')
        surface_code = int(ET.parse(root / 'system/surfaces/AndroidManifest.xml').getroot().get(ANDROID + 'versionCode'))
        if not re.search(r"^package: name='org.agentos.surfaces' versionCode='" + str(surface_code) + "'", badging(surface), re.M):
            raise ValueError('ROM Surfaces package/version differs')
        report['components']['priv-app/TakuSurfaces/TakuSurfaces.apk'].update(package='org.agentos.surfaces', versionCode=surface_code)
    startup = [('bin/magpie', 'adapters/magpie-runtime/magpie'),
                           ('bin/taku-runtime-start', 'image/runtime/start'),
                           ('etc/init/taku-magpie.rc', 'adapters/magpie-runtime/taku-magpie.rc'),
                           ('etc/init/taku-runtime.rc', 'image/runtime/taku-runtime.rc')]
    if workspace_runtime:
        startup.append(('bin/phone-display', 'system/display/phone-display'))
    for target, source in startup:
        if member(target) != (root / source).read_bytes():
            raise ValueError('ROM startup file differs from immutable source: ' + target)
    member('bin/magpie-runtime')
    report['imeSignerSha256'] = lock['signerSha256']
    report['credentialFilesIncluded'] = False
    report['security'] = 'Existing userdebug su runtime domain; global Enforcing still needs physical verification'
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target_files', type=Path)
    parser.add_argument('--aapt2', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    aapt = args.aapt2 or (Path(shutil.which('aapt2')) if shutil.which('aapt2') else None)
    if aapt is None:
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))))
        aapt = sdk / 'build-tools' / os.environ.get('TAKU_BUILD_TOOLS', '36.1.0') / 'aapt2'
    def badging(raw):
        with tempfile.TemporaryDirectory(prefix='taku-rom-apk-') as folder:
            apk = Path(folder) / 'component.apk'
            apk.write_bytes(raw)
            return subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True)
    target = args.target_files
    if target.is_dir():
        targets = list((target / 'obj/PACKAGING/target_files_intermediates').glob('*.zip'))
        if len(targets) != 1:
            raise SystemExit('Expected one target-files package for the built product')
        target = targets[0]
    with zipfile.ZipFile(target) as archive:
        report = verify(archive, ROOT, badging)
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
