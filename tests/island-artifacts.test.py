#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('island_artifacts', ROOT / 'image/verify-island-artifacts.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)


class IslandArtifactChecks(unittest.TestCase):
    def test_manifest_and_implementation_both_required(self):
        with tempfile.TemporaryDirectory() as folder:
            apk = Path(folder) / 'fixture.apk'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('classes.dex', b'Lorg/example/Service;')
            with patch.object(gate.subprocess, 'check_output', return_value='org.example.Service permission'):
                self.assertIn('sha256', gate.verify_apk(apk, 'unused', ('org.example.Service',), ('permission',)))
                with self.assertRaisesRegex(ValueError, 'manifest'):
                    gate.verify_apk(apk, 'unused', ('org.example.Missing',), ())
            with patch.object(gate.subprocess, 'check_output', return_value='org.example.Missing'):
                with self.assertRaisesRegex(ValueError, 'implementation'):
                    gate.verify_apk(apk, 'unused', ('org.example.Missing',), ())

    def test_complete_artifacts_do_not_claim_device_or_dagger_acceptance(self):
        with tempfile.TemporaryDirectory() as folder:
            product = Path(folder)
            for name in ('SystemUI', 'TakuAgent', 'TakuAssistant'):
                apk = product / 'system_ext' / 'app' / name / (name + '.apk')
                apk.parent.mkdir(parents=True)
                with zipfile.ZipFile(apk, 'w') as archive:
                    if name == 'TakuAssistant':
                        archive.write(ROOT / 'system/assistant/assets/skills/taku-island/SKILL.md', gate.DOC)
            with patch.object(gate, 'verify_apk', return_value={'fixture': True}):
                result = gate.verify(product, 'unused')
            self.assertTrue(result['artifactContentsVerified'])
            self.assertFalse(result['deviceVerified'])
            self.assertFalse(result['daggerRuntimeVerified'])


if __name__ == '__main__':
    unittest.main()
