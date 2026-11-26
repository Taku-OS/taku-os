#!/usr/bin/env python3
"""Offline lock screen input pin and non-destructive patch-application tests; no ROM/device claim."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('lockscreen_patches', ROOT / 'image/apply-lockscreen-patches.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LockscreenPatches(unittest.TestCase):
    def test_real_contract_pin_hash_and_source_coverage(self):
        contract = module.load_contract()
        self.assertEqual(contract['repository'], 'frameworks/base')
        self.assertGreaterEqual(len(contract['files']), 10)
        hooks = {entry['path'] for entry in contract['files'] if entry['beforeSha256'] is not None}
        self.assertEqual(hooks, module.HOOKS)

    def test_generator_and_apply_agree_on_hooks(self):
        spec = importlib.util.spec_from_file_location('lockscreen_update', ROOT / 'system/lockscreen/update-patch.py')
        update = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(update)
        self.assertEqual(set(update.HOOKS), module.HOOKS)
        for path, edits in update.HOOKS.items():
            self.assertTrue(edits, path)
            for anchor, replacement in edits:
                self.assertNotEqual(anchor, replacement)
                self.assertIn('Taku', replacement)

    def test_unknown_source_preserved_and_wrong_revision_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            subprocess.run(['git', 'init', '-q', str(repo)], check=True)
            target = repo / 'file'
            target.write_text('before\n')
            subprocess.run(['git', '-C', str(repo), 'add', 'file'], check=True)
            subprocess.run(['git', '-C', str(repo), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                            '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture'], check=True)
            revision = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip()
            contract = {'revision': revision, 'files': [{
                'path': 'file', 'beforeSha256': module.digest(target),
                'afterSha256': hashlib.sha256(b'after\n').hexdigest()}]}
            self.assertFalse(module.inspect(repo, contract))
            target.write_text('after\n')
            self.assertTrue(module.inspect(repo, contract))
            target.write_text('user change\n')
            with self.assertRaisesRegex(ValueError, 'preserving'):
                module.inspect(repo, contract)
            self.assertEqual(target.read_text(), 'user change\n')
            contract['revision'] = '0' * 40
            with self.assertRaisesRegex(ValueError, 'pinned revision'):
                module.inspect(repo, contract)

    def test_report_does_not_claim_compilation_or_acceptance(self):
        with tempfile.TemporaryDirectory() as temp:
            contract = module.load_contract()
            with patch.object(module, 'inspect', return_value=True):
                report = json.loads(module.apply(temp, temp, verify_only=True).read_text())
            self.assertEqual(report['revision'], contract['revision'])
            self.assertTrue(report['sourcePatchVerified'])
            for key in ('systemUiCompiled', 'romBuiltVerified', 'deviceVerified'):
                self.assertFalse(report[key])


if __name__ == '__main__':
    unittest.main()
