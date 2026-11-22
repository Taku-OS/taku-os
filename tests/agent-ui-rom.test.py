#!/usr/bin/env python3
"""Reject stale UI inputs and missing/stale image payloads; not device acceptance."""
import io
import json
import pathlib
import runpy
import tempfile
import unittest
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
UI = runpy.run_path(str(ROOT / 'image/agent_ui.py'))


class AgentUiInputContract(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = pathlib.Path(self.tmp.name)
        self.app = self.root / 'system/agent-app'
        (self.app / 'out').mkdir(parents=True)
        (self.app / 'app.json').write_text(json.dumps({'expo': {'version': '0.3.0', 'android': {'versionCode': 8}}}))
        (self.app / 'package-lock.json').write_text('{"lockfileVersion":3}')
        self.raw = b'input-contract fixture, not an APK'
        (self.root / UI['APK_PATH']).write_bytes(self.raw)
        names = ['system/agent-app/app.json', 'system/agent-app/package-lock.json']
        self.manifest = {'files': [{'path': name} for name in names + [UI['APK_PATH']]]}
        self.save_manifest()
        self.lock = {'path': UI['APK_PATH'], 'package': UI['PACKAGE'], 'versionCode': 8,
                     'versionName': '0.3.0', 'homeActivity': UI['HOME'], 'sha256': UI['sha'](self.raw),
                     'certificateSha256': 'c' * 64, 'sourceCommit': 'd' * 40, 'bytes': len(self.raw),
                     'sourceInputs': {name: UI['sha']((self.root / name).read_bytes()) for name in names}}
        (self.root / 'image').mkdir()
        self.save_lock()

    def save_manifest(self):
        (self.root / 'input-manifest.json').write_text(json.dumps(self.manifest))

    def save_lock(self):
        (self.root / 'image/runtime-assets.lock.json').write_text(json.dumps({'assets': [self.lock]}))

    def test_current_pinned_input_is_accepted(self):
        self.assertEqual(UI['validate_inputs'](self.root)['sha256'], UI['sha'](self.raw))

    def test_new_automatically_compiled_file_requires_a_new_apk(self):
        name = 'system/agent-app/modules/taku-home/android/src/main/java/NewHome.kt'
        path = self.root / name
        path.parent.mkdir(parents=True)
        path.write_text('class NewHome')
        self.manifest['files'].append({'path': name})
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'file set differs'):
            UI['validate_inputs'](self.root)

    def test_rom_default_permissions_do_not_require_a_new_apk_pin(self):
        name = 'system/agent-app/default-permissions.xml'
        path = self.root / name
        original = (ROOT / name).read_text()
        path.write_text(original)
        self.manifest['files'].append({'path': name})
        self.save_manifest()
        for content in (original, original + '<!-- ROM policy changed -->\n'):
            with self.subTest(content=content):
                path.write_text(content)
                self.assertEqual(UI['validate_inputs'](self.root), self.lock)
                self.assertEqual(UI['source_inputs'](self.root), self.lock['sourceInputs'])

    def test_new_android_manifest_and_resource_xml_still_require_a_new_apk(self):
        names = (
            'system/agent-app/modules/taku-home/android/src/main/AndroidManifest.xml',
            'system/agent-app/modules/taku-home/android/src/main/res/values/strings.xml',
        )
        for name in names:
            with self.subTest(name=name):
                path = self.root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('<resources/>')
                self.manifest['files'].append({'path': name})
                self.save_manifest()
                try:
                    with self.assertRaisesRegex(ValueError, 'file set differs'):
                        UI['validate_inputs'](self.root)
                finally:
                    self.manifest['files'].pop()
                    self.save_manifest()

    def test_shared_island_wire_is_an_apk_input(self):
        name = 'system/island/src/org/agentos/island/IslandContentProtocol.java'
        self.assertEqual(UI['source_paths']([name, 'system/island/src/com/android/systemui/IslandEngine.kt']), [name])
        path = self.root / name
        path.parent.mkdir(parents=True)
        path.write_text('class IslandContentProtocol {}')
        self.manifest['files'].append({'path': name})
        self.save_manifest()
        with self.assertRaisesRegex(ValueError, 'file set differs'):
            UI['validate_inputs'](self.root)

    def test_changed_source_requires_a_new_apk(self):
        (self.app / 'package-lock.json').write_text('{"lockfileVersion":4}')
        with self.assertRaisesRegex(ValueError, 'source differs'):
            UI['validate_inputs'](self.root)

    def test_changed_apk_cannot_reuse_a_pin(self):
        (self.root / UI['APK_PATH']).write_bytes(self.raw + b'truncated-or-replaced')
        with self.assertRaisesRegex(ValueError, 'APK bytes differ'):
            UI['validate_inputs'](self.root)

    def test_code_seven_cannot_be_a_system_takeover_candidate(self):
        self.lock['versionCode'] = 7
        (self.app / 'app.json').write_text(json.dumps({'expo': {'version': '0.3.0', 'android': {'versionCode': 7}}}))
        self.lock['sourceInputs']['system/agent-app/app.json'] = UI['sha']((self.app / 'app.json').read_bytes())
        self.save_lock()
        with self.assertRaisesRegex(ValueError, 'cannot replace'):
            UI['validate_inputs'](self.root)

    def test_missing_or_stale_ui_in_target_files_is_rejected_before_signature_checks(self):
        for raw in [None, b'old-interface']:
            with self.subTest(raw=raw):
                memory = io.BytesIO()
                with zipfile.ZipFile(memory, 'w') as archive:
                    if raw is not None:
                        archive.writestr(UI['APK_ENTRY'], raw)
                with zipfile.ZipFile(memory) as archive, self.assertRaisesRegex(ValueError, 'missing|differs'):
                    UI['verify_archive'](self.root, archive, self.root / 'unused-tools', self.root)

    def test_portable_baseline_requires_the_same_ui_proof_and_source_set(self):
        entries = {**self.lock['sourceInputs'], UI['APK_PATH']: self.lock['sha256']}
        inputs = {'agentUi': UI['input_identity'](self.lock)}
        proof = {key: self.lock[key] for key in ('package', 'versionCode', 'versionName',
                                                'homeActivity', 'certificateSha256', 'sourceCommit')}
        proof.update(apkEntry=UI['APK_ENTRY'], apkSha256=self.lock['sha256'], apkBytes=len(self.raw),
                     sourceInputsSha256=UI['sha'](json.dumps(self.lock['sourceInputs'], sort_keys=True).encode()),
                     apkSignatureVerified=True, platformCertificateSha256='c' * 64)
        memory = io.BytesIO()
        with zipfile.ZipFile(memory, 'w') as archive:
            archive.writestr(UI['APK_ENTRY'], self.raw)
        with zipfile.ZipFile(memory) as archive:
            UI['validate_saved'](archive, {'assets': [self.lock]}, proof, inputs, entries, 'c' * 64)
            rom_policy = {**entries, 'system/agent-app/default-permissions.xml': 'e' * 64}
            UI['validate_saved'](archive, {'assets': [self.lock]}, proof, inputs, rom_policy, 'c' * 64)
            with self.assertRaisesRegex(ValueError, 'proof'):
                UI['validate_saved'](archive, {'assets': [self.lock]}, None, inputs, entries, 'c' * 64)
            changed = {**entries, 'system/agent-app/src/new-screen.tsx': 'e' * 64}
            with self.assertRaisesRegex(ValueError, 'source binding'):
                UI['validate_saved'](archive, {'assets': [self.lock]}, proof, inputs, changed, 'c' * 64)
            with self.assertRaisesRegex(ValueError, 'platform identity'):
                UI['validate_saved'](archive, {'assets': [self.lock]}, proof, inputs, entries, 'f' * 64)


if __name__ == '__main__':
    unittest.main()
