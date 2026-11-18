#!/usr/bin/env python3
"""ROM delivery failure boundaries; device/JNI acceptance is separate."""
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('audio_verify', ROOT / 'system/audio-engine/verify-rom.py')
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class AudioRom(unittest.TestCase):
    def setUp(self):
        self.files = {verify.ASSISTANT_POLICY: (verify.HERE / 'permissions/org.agentos.assistant.audio.xml').read_bytes(), verify.APK: b'fixture apk', verify.POLICY: (verify.HERE / 'permissions/org.agentos.audio.xml').read_bytes(),
                      verify.MODEL_PREFIX + 'tts/model.onnx': b'fixed model',
                      'SYSTEM_EXT/etc/taku-audio/taku-audio-bridge.jar': b'fixed bridge'}
        self.lock = {'apkEntries': {'classes.dex': 'fixed'}, 'assets': []}
        for source, target in [('TakuAudioEngine.apk', verify.APK), ('models/tts/model.onnx', verify.MODEL_PREFIX + 'tts/model.onnx'),
                               ('audio-bridge.jar', 'SYSTEM_EXT/etc/taku-audio/taku-audio-bridge.jar')]:
            raw = self.files[target]
            self.lock['assets'].append({'path': 'system/audio-engine/prebuilt/' + source,
                                       'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)})

    def check(self):
        with mock.patch.object(verify.rom, 'apk_entries', return_value=self.lock['apkEntries']):
            return verify.verify(self.files.__getitem__, self.files, self.lock)

    def test_repository_source_lock(self):
        # Runs on a clean checkout without downloading the 559 MB model pack. Staged asset
        # hashes are verified separately by prepare-rom and verify-rom during image builds.
        lock = json.loads((verify.HERE / 'rom-input-lock.json').read_text())
        self.assertEqual(set(lock['sourceInputs']), set(verify.rom.source_names(ROOT)))
        # prebuilt/Android.bp is generated, gitignored input. Reconstruct its exact bytes
        # from the sealed asset names; never rely on a developer's local staging directory.
        prefix = verify.rom.PREFIX + 'prebuilt/models/'
        models = sorted(item['path'].removeprefix(prefix).replace(verify.rom.SAFE_VOICE, verify.rom.SPACED_VOICE)
                        for item in lock['assets'] if item['path'].startswith(prefix))
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            for name in lock['sourceInputs']:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                if name == verify.rom.VOICES_BLUEPRINT:
                    target.write_text(verify.rom.voice_blueprint(models))
                else:
                    shutil.copyfile(verify.rom.ordinary(ROOT, name), target)
            verify.rom.validate_source_inputs(root, lock)

    def test_complete_delivery(self):
        result = self.check()
        self.assertTrue(result['passed'])
        self.assertFalse(result['deviceBootVerified'])

    def test_missing_and_corrupt_model_are_rejected(self):
        key = verify.MODEL_PREFIX + 'tts/model.onnx'
        self.files[key] = b'wrong model'
        with self.assertRaisesRegex(ValueError, 'asset differs'):
            self.check()
        del self.files[key]
        with self.assertRaises(KeyError):
            self.check()

    def test_unsealed_model_is_rejected(self):
        self.files[verify.MODEL_PREFIX + 'extra.onnx'] = b'unreviewed'
        with self.assertRaisesRegex(ValueError, 'extra ROM model'):
            self.check()

    def test_permission_addition_and_duplicate_policy_are_rejected(self):
        self.files[verify.POLICY] = self.files[verify.POLICY].replace(b'</privapp-permissions>', b'<permission name="android.permission.DUMP"/></privapp-permissions>')
        with self.assertRaisesRegex(ValueError, 'approved five'):
            self.check()
        self.files[verify.POLICY] = (verify.HERE / 'permissions/org.agentos.audio.xml').read_bytes()
        self.files['SYSTEM_EXT/etc/permissions/extra.xml'] = self.files[verify.POLICY]
        with self.assertRaisesRegex(ValueError, 'Additional audio permission'):
            self.check()

    def test_assistant_routing_policy_is_required(self):
        del self.files[verify.ASSISTANT_POLICY]
        with self.assertRaises(KeyError):
            self.check()

    def test_space_path_is_normalized_in_voice_blueprint(self):
        spaced = 'tts/espeak-ng-data/voices/!v/Mr serious'
        safe = 'tts/espeak-ng-data/voices/!v/Mr_serious'
        generated = verify.rom.model_makefile(['manifest.json', spaced])
        self.assertNotIn('Mr serious', generated)
        self.assertIn('models/manifest.json:', generated)
        blueprint = verify.rom.voice_blueprint(['manifest.json', spaced])
        self.assertTrue(blueprint.startswith('// Generated by prepare-rom.py'))
        self.assertFalse(blueprint.startswith('#'))
        self.assertIn('name: "taku-audio-v-voices"', blueprint)
        self.assertIn('"models/' + safe + '"', blueprint)
        self.assertNotIn('"models/' + spaced + '"', blueprint)
        self.assertEqual(safe, verify.rom.installed_model_name(spaced))
        self.assertIn('"taku-audio-v-voices"', (verify.HERE / 'Android.bp').read_text())
        with self.assertRaisesRegex(ValueError, 'whitespace'):
            verify.rom.model_makefile([spaced, 'unexpected name'])

    def test_stale_bridge_is_rejected(self):
        self.files['SYSTEM_EXT/etc/taku-audio/taku-audio-bridge.jar'] = b'old'
        with self.assertRaisesRegex(ValueError, 'asset differs'):
            self.check()

    def test_altered_apk_content_is_rejected(self):
        with mock.patch.object(verify.rom, 'apk_entries', return_value={'classes.dex': 'old'}):
            with self.assertRaisesRegex(ValueError, 'APK content differs'):
                verify.verify(self.files.__getitem__, self.files, self.lock)

    def test_duplicate_apk_entries_are_rejected(self):
        import tempfile
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'bad.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('classes.dex', b'one')
                with self.assertWarns(UserWarning):
                    archive.writestr('classes.dex', b'two')
            with self.assertRaisesRegex(ValueError, 'duplicate'):
                verify.rom.apk_entries(path)


if __name__ == '__main__':
    unittest.main()
