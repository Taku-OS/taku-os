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


def manifest_tree(text):
    stack = []
    root = None
    for line in text.splitlines():
        element = re.match(r'^( +)E: ([\w-]+)\b', line)
        if element:
            depth = len(element[1])
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = ET.Element(element[2])
            if stack:
                stack[-1][1].append(node)
            elif root is None:
                root = node
            else:
                raise ValueError('APK manifest has multiple roots')
            stack.append((depth, node))
        elif stack:
            attribute = re.match(r'^ +A: (?:[^ =]+:)?([\w-]+)(?:\(0x[\da-f]+\))?=(?:"([^"]*)"|(\S+))', line)
            if attribute:
                stack[-1][1].set(attribute[1], attribute[2] if attribute[2] is not None else attribute[3])
    if root is None or root.tag != 'manifest':
        raise ValueError('APK has no compiled manifest')
    return root


def declared(parent, tag, name, **attributes):
    nodes = [node for node in parent.findall(tag) if node.get('name') == name]
    if len(nodes) != 1 or any(nodes[0].get(key) != value for key, value in attributes.items()):
        raise ValueError('Invalid APK declaration: ' + name)
    return nodes[0]


def intent_filter(component, action, categories=(), priority=None):
    for node in component.findall('intent-filter'):
        if (action in {child.get('name') for child in node.findall('action')}
                and set(categories) <= {child.get('name') for child in node.findall('category')}
                and (priority is None or node.get('priority') == priority)):
            return
    raise ValueError('Missing ' + action + ' filter on ' + component.get('name'))


def pair_identity(raw, manifest, signature, package, code, shared_uid=None):
    signer = re.findall(r'^(?:Signer #\d+|V3\.0 Signer):? certificate SHA-256 digest: ([0-9a-f]{64})$',
                        signature(raw), re.M)
    if (manifest.get('package'), manifest.get('versionCode'), manifest.get('sharedUserId')) != (
            package, str(code), shared_uid) or len(signer) != 1:
        raise ValueError('Wrong APK package, version, UID or signing identity: ' + package)
    return {'package': package, 'versionCode': code, 'certificateSha256': signer[0]}


def verify_assistant_pair(backend, frontend, manifest, signature, root):
    if signature is None:
        raise ValueError('Assistant cutover verification requires apksigner')
    backend_code = int(ET.parse(root / 'system/assistant/AndroidManifest.xml').getroot().get(ANDROID + 'versionCode'))
    frontend_code = json.loads((root / 'system/agent-app/app.json').read_text())['expo']['android']['versionCode']
    retired = ('AgentDrawerView', 'AgentDrawerController', 'NativeAssistantSurface', 'NativeControlsActivity',
               'AssistantService', 'AssistantSession', 'SessionService', 'RecognitionUnavailableService',
               'ChatActivity', 'ComposerAttachmentActivity', 'ModelManagementActivity', 'ByokSettingsActivity',
               'NativeConversationView', 'NativeTurnView', 'NativeMarkdownView',
               'AssistantAuthorizationActivity', 'SkillRepositoryCredentialActivity')
    classes = definitions(backend)
    if any(f'Lorg/agentos/assistant/{cls};' in classes for cls in retired):
        raise ValueError('Backend APK still includes retired assistant classes')
    backend_xml = manifest(backend)
    backend_manifest = manifest_tree(backend_xml)
    backend_identity = pair_identity(backend, backend_manifest, signature, 'org.agentos.assistant',
                                     backend_code, 'android.uid.system')
    app = backend_manifest.find('application')
    if app is None or any(marker in backend_xml.partition('E: application')[2] for marker in (
            'android.service.voice.VoiceInteractionService', 'android.intent.category.HOME',
            'android.intent.category.LAUNCHER', 'com.android.settings.action.IA_SETTINGS')):
        raise ValueError('Backend APK still advertises an assistant UI entry or lacks an application')
    settings_action = 'org.agentos.action.AI_SETTINGS'
    default = 'android.intent.category.DEFAULT'
    forwarder = declared(app, 'activity', '.SystemAiActivity', exported='true')
    intent_filter(forwarder, settings_action, (default,), priority='-1')
    if sum(node.get('name') == settings_action for node in app.iter('action')) != 1:
        raise ValueError('Multiple backend handlers for ' + settings_action)
    ime_permission = 'org.agentos.assistant.permission.IME_REPLIES'
    agent_permission = 'org.agentos.permission.AGENT_CLIENT'
    declared(backend_manifest, 'permission', agent_permission, protectionLevel='0x00000002')
    ime_declaration = declared(backend_manifest, 'permission', ime_permission, protectionLevel='0x08000002')
    if not re.fullmatch(r'@0x[\da-f]+', ime_declaration.get('knownCerts', '')):
        raise ValueError('Backend IME permission has no known-signer certificate resource')
    for provider, authority, permission in (
            ('AgentGatewayProvider', 'org.agentos.assistant.agent-gateway', agent_permission),
            ('AgentOpenAccessProvider', 'org.agentos.assistant.open-access', None),
            ('ImeReplyProvider', 'org.agentos.assistant.ime-replies', ime_permission),
            ('MemoryGatewayProvider', 'org.agentos.assistant.memory-gateway', 'org.agentos.memory.ACCESS_MEMORY'),
            ('SystemAiProvider', 'org.agentos.ai', None)):
        declared(app, 'provider', '.' + provider, authorities=authority, exported='true', permission=permission)
        if f'Lorg/agentos/assistant/{provider};' not in classes:
            raise ValueError('Backend APK is missing ' + provider)
    frontend_manifest = manifest_tree(manifest(frontend))
    frontend_identity = pair_identity(frontend, frontend_manifest, signature, 'org.agentos.agent', frontend_code)
    if frontend_identity['certificateSha256'] != backend_identity['certificateSha256']:
        raise ValueError('Assistant frontend and backend signers differ')
    app = frontend_manifest.find('application')
    if app is None:
        raise ValueError('Frontend APK has no application')
    home = 'org.agentos.agent.home.HomeActivity'
    voice = 'org.agentos.agent.home.assistant.SystemAssistantService'
    session = 'org.agentos.agent.home.assistant.SystemAssistantSessionService'
    settings = 'org.agentos.agent.bridge.SystemAiEntryActivity'
    home_activity = declared(app, 'activity', home, exported='true')
    intent_filter(home_activity, 'android.intent.action.MAIN', ('android.intent.category.HOME', default))
    settings_activity = declared(app, 'activity', settings, exported='true')
    intent_filter(settings_activity, settings_action, (default,))
    intent_filter(settings_activity, 'com.android.settings.action.IA_SETTINGS')
    binding = 'android.permission.BIND_VOICE_INTERACTION'
    voice_service = declared(app, 'service', voice, exported='true', permission=binding)
    declared(app, 'service', session, exported='true', permission=binding)
    intent_filter(voice_service, 'android.service.voice.VoiceInteractionService')
    metadata = declared(voice_service, 'meta-data', 'android.voice_interaction')
    if not re.fullmatch(r'@0x[\da-f]+', metadata.get('resource', '')):
        raise ValueError('Frontend voice service has no Android metadata resource')
    classes = definitions(frontend)
    for cls in (home, voice, session, settings):
        if 'L' + cls.replace('.', '/') + ';' not in classes:
            raise ValueError('Frontend APK is missing ' + cls)
    return {'backendSha256': sha(backend), 'frontendSha256': sha(frontend),
            'backendIdentity': backend_identity, 'frontendIdentity': frontend_identity}


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


def verify(archive, root, badging, manifest=None, signature=None):
    backend_code = int(ET.parse(root / 'system/assistant/AndroidManifest.xml').getroot().get(ANDROID + 'versionCode'))
    workspace_runtime = backend_code >= 73
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
            code = backend_code if package.endswith('.assistant') else int(ET.parse(
                root / 'system/memory/AndroidManifest.xml').getroot().get(ANDROID + 'versionCode'))
        text = badging(raw)
        match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", text, re.M)
        if not match or match.groups() != (package, str(code)):
            raise ValueError('Wrong ROM APK package/version: ' + package)
        report['components'][path].update(package=package, versionCode=code)
        if package.endswith('.assistant'):
            dex = definitions(raw)
            for cls in ['SystemAiProvider', 'ImeReplyProvider']:
                if 'Lorg/agentos/assistant/' + cls + ';' not in dex:
                    raise ValueError('Missing shared AI class definition: ' + cls)
            if backend_code >= 79:
                if manifest is None:
                    raise ValueError('Assistant cutover verification requires the APK manifest reader')
                frontend = member('app/TakuAgent/TakuAgent.apk')
                report['assistantPair'] = verify_assistant_pair(raw, frontend, manifest, signature, root)
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
    parser.add_argument('target_files', type=Path, nargs='?')
    parser.add_argument('--assistant-pair', type=Path, nargs=2, metavar=('BACKEND_APK', 'FRONTEND_APK'))
    parser.add_argument('--aapt2', type=Path)
    parser.add_argument('--apksigner', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if (args.target_files is None) == (args.assistant_pair is None):
        parser.error('Supply target-files or --assistant-pair')
    aapt = args.aapt2 or (Path(shutil.which('aapt2')) if shutil.which('aapt2') else None)
    if aapt is None:
        sdk = Path(os.environ.get('ANDROID_SDK_ROOT', os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))))
        aapt = sdk / 'build-tools' / os.environ.get('TAKU_BUILD_TOOLS', '36.1.0') / 'aapt2'
    signer = args.apksigner or aapt.with_name('apksigner')
    def inspect(raw, *args):
        with tempfile.TemporaryDirectory(prefix='taku-rom-apk-') as folder:
            apk = Path(folder) / 'component.apk'
            apk.write_bytes(raw)
            return subprocess.check_output([str(aapt), 'dump', args[0], str(apk), *args[1:]], text=True)
    def manifest(raw):
        return inspect(raw, 'xmltree', '--file', 'AndroidManifest.xml')
    def signature(raw):
        with tempfile.TemporaryDirectory(prefix='taku-rom-signature-') as folder:
            apk = Path(folder) / 'component.apk'
            apk.write_bytes(raw)
            return subprocess.check_output([str(signer), 'verify', '--print-certs', str(apk)], text=True)
    if args.assistant_pair is not None:
        report = verify_assistant_pair(*(apk.read_bytes() for apk in args.assistant_pair), manifest, signature, ROOT)
    else:
        target = args.target_files
        if target.is_dir():
            targets = list((target / 'obj/PACKAGING/target_files_intermediates').glob('*.zip'))
            if len(targets) != 1:
                raise SystemExit('Expected one target-files package for the built product')
            target = targets[0]
        with zipfile.ZipFile(target) as archive:
            report = verify(archive, ROOT, lambda raw: inspect(raw, 'badging'), manifest, signature)
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
