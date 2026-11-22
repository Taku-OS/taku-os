#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise corrupted inputs, path escapes, extra files and dirty-source refusal."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('delivery', ROOT / 'scripts/tryon-delivery.py')
delivery = importlib.util.module_from_spec(spec); spec.loader.exec_module(delivery)


class DeliveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / 'repo'; self.repo.mkdir()
        docs = self.repo / 'docs/tryon-delivery'; docs.mkdir(parents=True)
        for name in ['README.md', 'INSTALL.zh-CN.md', 'BUILD.md', 'PR.md', 'CHECKS.json']:
            (docs / name).write_text('fixture\n')
        self.inputs = self.base / 'inputs'
        self.artifact = self.inputs / 'verified-device/apk/org.example.app.apk'
        self.artifact.parent.mkdir(parents=True); self.artifact.write_bytes(b'synthetic-apk')
        self.value = {'schema': 'taku.tryon.delivery.v1', 'releaseReady': False,
                      'sourceBase': 'fixture', 'artifacts': [{'file': 'verified-device/apk/org.example.app.apk',
                      'sha256': delivery.digest(self.artifact), 'bytes': self.artifact.stat().st_size,
                      'certificateSha256': 'a' * 64, 'signatureVerified': True, 'package': 'org.example.app'}]}
        self.declaration = self.base / 'declaration.json'; self.declaration.write_text(json.dumps(self.value))
        for args in [['init', '-q'], ['add', '.'], ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture']]:
            subprocess.run(['git', *args], cwd=self.repo, check=True, capture_output=True)
        self.value['sourceBase'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=self.repo, text=True).strip()
        self.declaration.write_text(json.dumps(self.value))
        (docs / 'README.md').write_text('fixture feature\n')
        for args in [['add', '.'], ['-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'feature']]:
            subprocess.run(['git', *args], cwd=self.repo, check=True, capture_output=True)

    def assemble(self):
        return delivery.assemble(self.inputs, self.base / 'output', self.declaration, self.repo)

    def test_round_trip_and_no_extra_files(self):
        self.assertTrue(self.assemble()['ok'])
        subprocess.run(['git', 'bundle', 'verify', str(self.base / 'output/source.bundle')],
                       cwd=self.repo, check=True, capture_output=True)
        output = self.base / 'output'; (output / 'unexpected.key').write_text('extra')
        with self.assertRaises(ValueError): delivery.verify(output)

    def test_corrupted_input_rejected_before_output(self):
        self.artifact.write_bytes(b'corrupted')
        with self.assertRaises(ValueError): self.assemble()
        self.assertFalse((self.base / 'output').exists())

    def test_tampered_output_rejected(self):
        self.assemble(); (self.base / 'output/README.md').write_text('changed')
        with self.assertRaises(ValueError): delivery.verify(self.base / 'output')

    def test_dirty_source_rejected(self):
        (self.repo / 'dirty').write_text('untracked')
        with self.assertRaises(ValueError): self.assemble()

    def test_unsafe_path_and_unapproved_type(self):
        for value in ['../private.apk', '/tmp/private.apk', 'source-build/apk/../private.apk',
                      'verified-device/apk/key.pem', 'verified-device/apk/a\\b.apk']:
            self.value['artifacts'][0]['file'] = value
            with self.assertRaises(ValueError): delivery.validate_declaration(self.value)

    def test_symlink_input_rejected(self):
        copy = self.base / 'other.apk'; self.artifact.rename(copy); self.artifact.symlink_to(copy)
        with self.assertRaises(ValueError): self.assemble()

    def test_duplicates_and_missing_signature_rejected(self):
        self.value['artifacts'].append(dict(self.value['artifacts'][0]))
        with self.assertRaises(ValueError): delivery.validate_declaration(self.value)
        self.value['artifacts'].pop(); self.value['artifacts'][0]['signatureVerified'] = False
        with self.assertRaises(ValueError): delivery.validate_declaration(self.value)

    def test_unapproved_runtime_files_rejected(self):
        self.value['runtimeArtifacts'] = [{'file': 'verified-device/runtime/credentials.json',
                                          'bytes': 1, 'sha256': 'b' * 64}]
        with self.assertRaises(ValueError): delivery.validate_declaration(self.value)


if __name__ == '__main__': unittest.main()
