#!/usr/bin/env python3
"""Synthetic archive/input contracts only; no model, device or boot acceptance."""
import hashlib
import importlib.util
import io
import json
import shlex
from pathlib import Path
import subprocess
import struct
import stat
import sys
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


rom = module('ai_rom', 'system/ai/verify-rom.py')
stage = module('ime_stage', 'system/inputmethod/prepare-rom.py')
snapshot = module('pixel9_image', 'scripts/pixel9-image.py')
cold = module('ai_cold', 'system/ai/cold-check.py')
baseline = module('ai_baseline', 'system/ai/capture-baseline.py')
formats = module('rom_formats_test', 'system/ai/rom_formats.py')


def dex_fixture(descriptors, defined=True):
    count = len(descriptors)
    strings, types, classes = 112, 112 + 4 * count, 112 + 8 * count
    raw = bytearray(classes + (32 * count if defined else 0))
    raw[:8] = b'dex\n039\0'
    struct.pack_into('<II', raw, 36, 112, 0x12345678)
    for offset, size, at in [(56, count, strings), (64, count, types), (96, count if defined else 0, classes)]:
        struct.pack_into('<II', raw, offset, size, at)
    for n, desc in enumerate(descriptors):
        struct.pack_into('<I', raw, strings + 4 * n, len(raw))
        struct.pack_into('<I', raw, types + 4 * n, n)
        if defined:
            struct.pack_into('<I', raw, classes + 32 * n, n)
        raw.extend(bytes([len(desc)]) + desc.encode() + b'\0')
    struct.pack_into('<I', raw, 32, len(raw))
    return bytes(raw)


def elf_fixture(executable=False):
    raw = bytearray(400)
    raw[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HH', raw, 16, 3, 183)
    struct.pack_into('<Q', raw, 32, 64)
    struct.pack_into('<HH', raw, 54, 56, 3 if executable else 2)
    struct.pack_into('<IIQQQQQQ', raw, 64, 1, 5, 0, 0, 0, 400, 400, 16384)
    struct.pack_into('<IIQQQQQQ', raw, 120, 2, 4, 256, 256, 0, 48, 48, 8)
    if executable:
        struct.pack_into('<IIQQQQQQ', raw, 176, 3, 4, 320, 320, 0, 1, 1, 1)
    struct.pack_into('<QQQQQQ', raw, 256, 5, 320, 10, 1, 0, 0)
    return bytes(raw)


class MergedBuildGate(unittest.TestCase):
    def test_runtime_report_is_not_overwritten_by_standalone_gate(self):
        source = (ROOT / 'image/build.sh').read_text()
        function = source[source.index('verify_artifacts() {'):source.index('if [[ "${1:-}" = "--verify-only" ]]')]
        for restricted in (False, True):
            with self.subTest(runtimeIntegration=restricted), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                (root / 'run-input.json').write_text(json.dumps({'runtimeIntegration': {'readyForBuild': True}} if restricted else {}))
                calls = root / 'calls'
                script = 'phase() { :; }; python3() { if [[ "$1" == "-c" ]]; then command python3 "$@"; else printf "%s\\n" "$*" >> ' + shlex.quote(str(calls)) + '; fi; };\n'
                script += function.replace('/results', str(root)) + '\nverify_artifacts\n'
                result = subprocess.run(['bash', '-c', script], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                commands = calls.read_text().splitlines()
                self.assertEqual(sum('/system/ai/verify-rom.py' in line for line in commands), 0 if restricted else 1)
                self.assertEqual(sum('/image/verify-artifacts.py' in line for line in commands), 1)


class BackendEntries(unittest.TestCase):
    def manifest(self, name, tag='activity', action='android.intent.action.MAIN',
                 category='android.intent.category.LAUNCHER'):
        return rom.manifest_tree(f"""
  E: manifest
    A: package="org.agentos.assistant"
    E: application
      E: {tag}
        A: android:name="{name}"
        E: intent-filter
          E: action
            A: android:name="{action}"
          E: category
            A: android:name="{category}"
""")

    def test_bscreen_launcher_allowed(self):
        for name in ('.BScreenActivity', 'BScreenActivity', 'org.agentos.assistant.BScreenActivity'):
            with self.subTest(name=name):
                manifest = self.manifest(name)
                self.assertIs(rom.verify_backend_entries(manifest), manifest.find('application'))

    def test_other_launcher_filters_rejected(self):
        for manifest in (self.manifest('.OtherActivity'),
                         self.manifest('.BScreenActivity', tag='activity-alias'),
                         self.manifest('.BScreenActivity', action='android.intent.action.VIEW')):
            with self.assertRaisesRegex(ValueError, 'assistant UI entry'):
                rom.verify_backend_entries(manifest)

    def test_existing_rejections_preserved(self):
        for manifest in (
            self.manifest('.BScreenActivity', category='android.intent.category.HOME'),
            self.manifest('.Voice', tag='service', action='android.service.voice.VoiceInteractionService',
                          category='android.intent.category.DEFAULT'),
            self.manifest('.Settings', action='com.android.settings.action.IA_SETTINGS',
                          category='android.intent.category.DEFAULT'),
            rom.manifest_tree('  E: manifest'),
        ):
            with self.assertRaisesRegex(ValueError, 'assistant UI entry'):
                rom.verify_backend_entries(manifest)


class RomContract(unittest.TestCase):
    def fixture(self, change=None, workspace=False):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        root = Path(folder.name)
        def apk(package, code, classes=()):
            raw = io.BytesIO()
            with zipfile.ZipFile(raw, 'w') as z:
                z.writestr('test-metadata', f"package: name='{package}' versionCode='{code}'")
                z.writestr('classes.dex', dex_fixture(['Lorg/agentos/assistant/' + c + ';' for c in classes]))
            return raw.getvalue()
        ime = apk('org.agentos.inputmethod', 10062)
        files = {
            'app/TakuInputMethod/TakuInputMethod.apk': ime,
            'priv-app/TakuAssistant/TakuAssistant.apk': apk('org.agentos.assistant', 73 if workspace else 64, ['SystemAiActivity', 'SystemAiProvider', 'ImeReplyProvider']),
            'priv-app/TakuMemory/TakuMemory.apk': apk('org.agentos.memory', 8),
            'bin/magpie-runtime': b'public runtime fixture',
        }
        for target, source in [('bin/magpie', 'adapters/magpie-runtime/magpie'), ('bin/taku-runtime-start', 'image/runtime/start'),
                               ('etc/init/taku-magpie.rc', 'adapters/magpie-runtime/taku-magpie.rc'),
                               ('etc/init/taku-runtime.rc', 'image/runtime/taku-runtime.rc')]:
            path = root / source
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture startup ' + target.encode())
            files[target] = path.read_bytes()
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as z:
            z.writestr('classes.dex', dex_fixture(['Lorg/agentos/lab/' + c + ';' for c in
                ['TaskBroker', 'NativeBridge', 'SystemAi', 'AiPolicy', 'ImeReplies', 'ImeReplyReuse', 'MemoryDecisionJson']]))
        files['framework/agentos-task-broker.jar'] = raw.getvalue()
        if workspace:
            for path, classes in [('framework/agentos-task-broker.jar', ['TaskBroker', 'NativeBridge', 'SystemAi', 'AiPolicy',
                    'ImeReplies', 'ImeReplyReuse', 'MemoryDecisionJson', 'PhoneExecutionScreens', 'WorkspaceControl', 'WorkspaceAdmissions']),
                    ('framework/agentos-virtual-display.jar', ['VirtualDisplayHost', 'DisplaySlot', 'DisplayControl', 'FrameCoalescer'])]:
                raw = io.BytesIO()
                with zipfile.ZipFile(raw, 'w') as z:
                    z.writestr('classes.dex', dex_fixture(['Lorg/agentos/lab/' + c + ';' for c in classes]))
                files[path] = raw.getvalue()
            files['priv-app/TakuSurfaces/TakuSurfaces.apk'] = apk('org.agentos.surfaces', 4)
            path = root / 'system/display/phone-display'
            path.parent.mkdir(parents=True); path.write_bytes(b'workspace display script')
            files['bin/phone-display'] = path.read_bytes()
        for name, code in [('assistant', 73 if workspace else 64), ('memory', 8), ('surfaces', 4)]:
            p = root / 'system' / name / 'AndroidManifest.xml'
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionCode="{code}"/>')
        p = root / 'system/inputmethod/rom-input-lock.json'
        p.parent.mkdir(parents=True)
        natives = []
        for name, executable in [('lib64/taku-inputmethod/libtest.so', False), ('bin/taku-inputmethod/libscel2org5.so', True)]:
            native = elf_fixture(executable)
            files[name] = native
            natives.append({'installPath': 'SYSTEM_EXT/' + name, 'sha256': hashlib.sha256(native).hexdigest(), 'bytes': len(native), **formats.elf_info(native)})
        links = [{'path': 'SYSTEM_EXT/app/TakuInputMethod/lib/arm64', 'target': '/system_ext/lib64/taku-inputmethod'},
                 {'path': 'SYSTEM_EXT/lib64/taku-inputmethod/libscel2org5.so', 'target': '/system_ext/bin/taku-inputmethod/libscel2org5.so'}]
        for link in links:
            files[link['path'].removeprefix('SYSTEM_EXT/')] = (link['target'],)
        files['META/system_ext_filesystem_config.txt'] = b'system_ext/lib64/taku-inputmethod/libtest.so 0 0 644 capabilities=0x0\nsystem_ext/bin/taku-inputmethod/libscel2org5.so 0 2000 755 capabilities=0x0\n'
        p.write_text(json.dumps({'jni': natives, 'jniSymlinks': links, 'sha256': hashlib.sha256(ime).hexdigest(), 'package': 'org.agentos.inputmethod', 'versionCode': 10062, 'signerSha256': 'fixture only'}))
        if change:
            change(files)
        data = io.BytesIO()
        with warnings.catch_warnings(), zipfile.ZipFile(data, 'w') as archive:
            warnings.simplefilter('ignore', UserWarning)
            for name, content in files.items():
                path = name if name.startswith('META/') else 'SYSTEM_EXT/' + name
                if isinstance(content, tuple):
                    info = zipfile.ZipInfo(path)
                    info.create_system = 3
                    info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    archive.writestr(info, content[0])
                else:
                    archive.writestr(path, content)
            if change and getattr(change, 'duplicate', False):
                archive.writestr('SYSTEM_EXT/bin/magpie', files['bin/magpie'])
        return root, data

    def check(self, change=None, workspace=False):
        root, data = self.fixture(change, workspace=workspace)
        def badging(raw):
            with zipfile.ZipFile(io.BytesIO(raw)) as z:
                return z.read('test-metadata').decode()
        with zipfile.ZipFile(data) as archive:
            return rom.verify(archive, root, badging)

    def test_valid_archive_does_not_claim_physical_boot(self):
        value = self.check()
        self.assertFalse(value['physicalVerified'])
        self.assertFalse(value['runtimeBindRequired'])

    def test_unpinned_ime_rejected(self):
        with self.assertRaisesRegex(ValueError, 'pinned'):
            self.check(lambda f: f.update({'app/TakuInputMethod/TakuInputMethod.apk': b'old IME'}))

    def test_workspace_runtime_requires_matching_display_and_script(self):
        value = self.check(workspace=True)
        self.assertEqual(value['components']['priv-app/TakuSurfaces/TakuSurfaces.apk']['versionCode'], 4)
        with self.assertRaisesRegex(ValueError, 'Missing'):
            self.check(lambda f: f.pop('framework/agentos-virtual-display.jar'), workspace=True)
        with self.assertRaisesRegex(ValueError, 'startup'):
            self.check(lambda f: f.update({'bin/phone-display': b'old display script'}), workspace=True)

    def test_missing_broker_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Missing'):
            self.check(lambda f: f.pop('framework/agentos-task-broker.jar'))

    def test_private_state_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Private'):
            self.check(lambda f: f.update({'etc/.codex/auth.json': b'synthetic private-state fixture'}))

    def test_old_startup_wrapper_rejected(self):
        with self.assertRaisesRegex(ValueError, 'startup'):
            self.check(lambda f: f.update({'bin/magpie': b'stale wrapper'}))

    def test_duplicate_startup_rejected(self):
        def duplicate(files):
            pass
        duplicate.duplicate = True
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.check(duplicate)

    def test_native_sidecar_drift_rejected(self):
        with self.assertRaisesRegex(ValueError, 'native sidecar bytes'):
            self.check(lambda f: f.update({'lib64/taku-inputmethod/libtest.so': b'changed'}))

    def test_missing_native_link_rejected(self):
        with self.assertRaisesRegex(ValueError, 'native sidecar set'):
            self.check(lambda f: f.pop('app/TakuInputMethod/lib/arm64'))

    def test_wrong_native_link_rejected(self):
        with self.assertRaisesRegex(ValueError, 'native symlink'):
            self.check(lambda f: f.update({'app/TakuInputMethod/lib/arm64': ('/data/local/tmp/override',)}))

    def test_nonexecutable_helper_rejected(self):
        def change(files):
            key = 'META/system_ext_filesystem_config.txt'
            files[key] = files[key].replace(b'0 2000 755', b'0 2000 644')
        with self.assertRaisesRegex(ValueError, 'fs_config'):
            self.check(change)

    def test_unexpected_native_group_rejected(self):
        def change(files):
            key = 'META/system_ext_filesystem_config.txt'
            files[key] = files[key].replace(b'0 2000 755', b'0 10000 755')
        with self.assertRaisesRegex(ValueError, 'fs_config'):
            self.check(change)

    def test_reference_only_dex_rejected(self):
        def change(files):
            key = 'priv-app/TakuAssistant/TakuAssistant.apk'
            source = io.BytesIO(files[key])
            dest = io.BytesIO()
            with zipfile.ZipFile(source) as before, zipfile.ZipFile(dest, 'w') as after:
                after.writestr('test-metadata', before.read('test-metadata'))
                after.writestr('classes.dex', dex_fixture(['Lorg/agentos/assistant/' + c + ';' for c in ['SystemAiActivity', 'SystemAiProvider', 'ImeReplyProvider']], defined=False))
            files[key] = dest.getvalue()
        with self.assertRaisesRegex(ValueError, 'class definition'):
            self.check(change)


class BinaryFormats(unittest.TestCase):
    def test_dex_definition_versus_reference(self):
        self.assertEqual(formats.dex_classes(dex_fixture(['Ltest/A;'])), {'Ltest/A;'})
        self.assertEqual(formats.dex_classes(dex_fixture(['Ltest/A;'], False)), set())

    def test_truncated_dex_rejected(self):
        with self.assertRaisesRegex(ValueError, 'DEX'):
            formats.dex_classes(dex_fixture(['Ltest/A;'])[:-1])

    def test_wrong_elf_machine_rejected(self):
        raw = bytearray(elf_fixture())
        struct.pack_into('<H', raw, 18, 62)
        with self.assertRaisesRegex(ValueError, 'AArch64'):
            formats.elf_info(raw)

    def test_small_elf_page_alignment_rejected(self):
        raw = bytearray(elf_fixture())
        struct.pack_into('<Q', raw, 112, 4096)
        with self.assertRaisesRegex(ValueError, '16KB'):
            formats.elf_info(raw)


class StagedInputContract(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        (self.root / 'source').write_bytes(b'fixture source')
        (self.root / 'ime.apk').write_bytes(b'fixture APK')
        self.lock = {'path': 'ime.apk', 'bytes': 11, 'sha256': stage.sha(self.root / 'ime.apk'),
                     'sourceInputs': {'source': stage.sha(self.root / 'source')}}
        native = self.root / 'jni/libtest.so'
        native.parent.mkdir()
        native.write_bytes(elf_fixture())
        self.lock['jni'] = [{'path': 'jni/libtest.so', 'apkEntry': 'lib/arm64-v8a/libtest.so', 'sha256': stage.sha(native), 'bytes': native.stat().st_size, **formats.elf_info(native.read_bytes())}]

    def test_exact_inputs_pass(self):
        self.assertEqual(stage.validate_inputs(self.root, self.lock), self.root / 'ime.apk')

    def test_source_drift_rejected(self):
        (self.root / 'source').write_bytes(b'new source')
        with self.assertRaisesRegex(ValueError, 'source input'):
            stage.validate_inputs(self.root, self.lock)

    def test_apk_drift_rejected(self):
        (self.root / 'ime.apk').write_bytes(b'other APK')
        with self.assertRaisesRegex(ValueError, 'APK missing or changed'):
            stage.validate_inputs(self.root, self.lock)

    def test_symlink_rejected(self):
        (self.root / 'ime.apk').rename(self.root / 'real.apk')
        (self.root / 'ime.apk').symlink_to(self.root / 'real.apk')
        with self.assertRaisesRegex(ValueError, 'APK missing or changed'):
            stage.validate_inputs(self.root, self.lock)

    def test_staged_native_drift_rejected(self):
        (self.root / 'jni/libtest.so').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'native sidecar'):
            stage.validate_inputs(self.root, self.lock)

    def test_staged_native_metadata_rejected(self):
        self.lock['jni'][0]['needed'] = ['libunexpected.so']
        with self.assertRaisesRegex(ValueError, 'ELF metadata'):
            stage.validate_inputs(self.root, self.lock)


class ColdMountContract(unittest.TestCase):
    def check(self, kind='erofs', options='ro,relatime', extra=''):
        text = f'1 0 0:1 / /system_ext {options} - {kind} /dev/block/dm-1 ro\n' + extra
        return cold.backing('/system_ext/bin/magpie', text)

    def test_readonly_system_mount_passes(self):
        self.assertEqual(self.check()['fs'], 'erofs')

    def test_file_bind_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'bind/overlay'):
            self.check(extra='2 1 0:2 /private/magpie /system_ext/bin/magpie ro - ext4 /dev/block/data ro\n')

    def test_overlay_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'bind/overlay'):
            self.check(kind='overlay')

    def test_writable_system_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'bind/overlay'):
            self.check(options='rw,relatime')


class BaselineContract(unittest.TestCase):
    def device(self, change=None):
        calls = []
        memory = {'followsSystem': True, 'modelOverride': '', 'model': '', 'enabled': False,
                  'analysisEnabled': False, 'queryEnabled': False, 'proactive': True,
                  'sources': [], 'retentionDays': 7, 'privateRecord': 'do not export'}
        def sh(*parts):
            calls.append(parts)
            if parts == ('id', '-u'): return '0'
            if parts == ('getprop', 'sys.boot_completed'): return '1'
            if parts == ('getprop', 'ro.build.version.incremental'): return 'fixture-build'
            if parts == ('getenforce',): return 'Enforcing'
            if parts == ('settings', 'get', 'secure', 'default_input_method'): return 'fixture-ime'
            if parts[:3] == ('stat', '-c', '%u:%g:%a'):
                return '0:0:700' if parts[3] == '/data/local/agentos/ai' else '0:0:600'
            if parts == ('cat', baseline.AI_FILE):
                config = {'enabled': True, 'textModel': 'fixture/text', 'imageModel': 'fixture/image',
                          'apps': {'ime': True, 'memory': True}, 'credential': 'do not export'}
                if change == 'model' and calls.count(parts) > 1: config['imageModel'] = 'other/model'
                return json.dumps(config)
            if parts == ('cat', baseline.IME_FILE):
                return '<map><boolean name="enabled" value="true"/><string name="model"></string><set name="apps"><string>fixture.chat</string></set><string name="privateInput">do not export</string></map>'
            if parts == ('cat', '/proc/sys/kernel/random/boot_id'):
                return 'different-boot' if change == 'boot' and calls.count(parts) > 1 else 'fixture-boot'
            if parts == ('cat', '/proc/self/mountinfo'):
                return '1 0 0:1 /private/broker /system_ext/framework/agentos-task-broker.jar rw - f2fs /dev/data rw'
            if parts[:2] == ('content', 'call'):
                return 'Bundle[{result=' + json.dumps({'ok': True, 'result': memory}) + '}]'
            if parts[:2] == ('pm', 'path'): return 'package:/data/app/' + parts[2] + '/base.apk'
            if parts[:2] == ('dumpsys', 'package'):
                return 'versionCode=66' if change == 'apk' and calls.count(parts) > 1 else 'versionCode=65'
            if parts[0] == 'sha256sum': return 'a' * 64 + '  ' + parts[1]
            self.fail('Unexpected or mutating command: ' + repr(parts))
        return sh, calls

    def test_only_configuration_and_account_metadata_exported(self):
        sh, calls = self.device()
        value = baseline.capture('fixture-serial', sh)
        self.assertNotIn('do not export', json.dumps(value))
        self.assertFalse(value['accountCacheContentsRead'])
        self.assertFalse(value['deviceModified'])
        self.assertEqual(value['imeAi'], cold.ime_settings(sh('cat', baseline.IME_FILE)))
        self.assertEqual(len(value['activePackages']), 3)
        self.assertEqual(value['memory']['enabled'], False)
        self.assertNotIn(('cat', baseline.ACCOUNT_FILE), calls)

    def test_concurrent_model_reboot_or_apk_change_rejected(self):
        for change in ['model', 'boot', 'apk']:
            with self.subTest(change=change):
                sh, _ = self.device(change)
                with self.assertRaisesRegex(ValueError, 'changed during capture'):
                    baseline.capture('fixture-serial', sh)

    def test_existing_evidence_never_overwritten_and_new_file_private(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'before.json'
            baseline.write_baseline(path, {'original': True})
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
            with self.assertRaises(FileExistsError):
                baseline.write_baseline(path, {'replacement': True})
            self.assertEqual(json.loads(path.read_text()), {'original': True})


class SnapshotContract(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name)
        def write(name, text):
            p = self.root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
            return p
        write('.gitignore', '*.apk\nout/\n')
        write('image/runtime-assets.lock.json', '{"assets":[]}')
        write('system/assistant/markdown-dependencies.json', '{"assets":[]}')
        write('system/audio-engine/rom-input-lock.json', '{"sourceInputs":{},"assets":[]}')
        write('system/audio-engine/prepare-rom.py', 'def validate_inputs(root, lock):\n    return lock["assets"]\n')
        # This fixture exercises the independent IME source/JNI gate; the UI
        # input/byte/source-set gate has its own agent-ui-rom contract tests.
        write('image/agent_ui.py', 'def validate_inputs(root):\n    return {}\n')
        write('system/agent-app/scripts/export-reply-contract.ts',
              'const fs=require("node:fs"),p=require("node:path");'
              'const target=p.resolve(__dirname,"../../assistant/out/reply-assets/native/reply-contract.json");'
              'fs.mkdirSync(p.dirname(target),{recursive:true});'
              'fs.writeFileSync(target,JSON.stringify({schema:"taku.reply-contract/1",fixture:true}));')
        source = write('system/inputmethod/source', 'fixture source')
        apk = write('system/inputmethod/prebuilt/TakuInputMethod.apk', 'fixture APK')
        native = write('system/inputmethod/jni/prebuilt/libtest.so', 'fixture native')
        write('system/inputmethod/rom-input-lock.json', json.dumps({'path': str(apk.relative_to(self.root)),
            'sha256': stage.sha(apk), 'jni': [{'path': str(native.relative_to(self.root)), 'sha256': stage.sha(native)}], 'sourceInputs': {str(source.relative_to(self.root)): stage.sha(source)}}))
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.old_root, self.old_out = snapshot.ROOT, snapshot.OUT
        self.addCleanup(self.restore)
        snapshot.ROOT, snapshot.OUT = self.root, self.root / 'out'

    def restore(self):
        snapshot.ROOT, snapshot.OUT = self.old_root, self.old_out

    def test_ignored_ime_is_explicitly_sealed(self):
        folder, digest = snapshot.snapshot()
        manifest = json.loads((folder / 'input-manifest.json').read_text())
        self.assertEqual(manifest['digest'], digest)
        self.assertIn('system/inputmethod/prebuilt/TakuInputMethod.apk', [r['path'] for r in manifest['files']])
        self.assertEqual((folder / 'system/inputmethod/prebuilt/TakuInputMethod.apk').read_bytes(), b'fixture APK')
        self.assertEqual((folder / 'system/inputmethod/jni/prebuilt/libtest.so').read_bytes(), b'fixture native')

    def test_snapshot_rejects_native_drift(self):
        (self.root / 'system/inputmethod/jni/prebuilt/libtest.so').write_bytes(b'changed native')
        with self.assertRaisesRegex(SystemExit, 'asset missing/hash mismatch'):
            snapshot.snapshot()

    def test_snapshot_rejects_source_drift_even_with_same_apk(self):
        (self.root / 'system/inputmethod/source').write_text('changed source')
        with self.assertRaisesRegex(SystemExit, 'source differs'):
            snapshot.snapshot()


if __name__ == '__main__':
    unittest.main()
