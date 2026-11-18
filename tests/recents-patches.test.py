#!/usr/bin/env python3
"""Exercise the real pinned upstream patch, not a hand-written substitute."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('recents', ROOT / 'image/apply-recents-patches.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RecentsPatchTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(); self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.repo = self.root / module.REPOSITORY
        shutil.copytree(ROOT / 'tests/fixtures/recents', self.repo)
        original = subprocess.check_output
        def revision(command, **kwargs):
            if command[-2:] == ['rev-parse', 'HEAD']:
                return module.UPSTREAM + '\n'
            return original(command, **kwargs)
        mock = patch.object(module.subprocess, 'check_output', side_effect=revision)
        mock.start(); self.addCleanup(mock.stop)

    def test_verify_only_never_applies(self):
        before = (self.repo / 'quickstep/AndroidManifest.xml').read_bytes()
        with self.assertRaisesRegex(ValueError, 'not applied'):
            module.apply(self.root, self.root / 'result', verify_only=True)
        self.assertEqual(before, (self.repo / 'quickstep/AndroidManifest.xml').read_bytes())
        module.apply(self.root, self.root / 'result')
        module.apply(self.root, self.root / 'result', verify_only=True)

    def test_apply_and_repeat(self):
        first = module.apply(self.root, self.root / 'result')
        second = module.apply(self.root, self.root / 'result')
        self.assertEqual(first, second)
        self.assertFalse(first['apkBuiltVerified'])
        self.assertFalse(first['deviceVerified'])
        subprocess.run(['git', '-C', str(self.repo), 'apply', '--reverse', '--check',
                        str(module.ROOT / 'taku-recents.patch')], check=True)

    def test_unknown_edits_preserved(self):
        file = self.repo / 'quickstep/AndroidManifest.xml'
        file.write_text(file.read_text() + '\n<!-- local edit -->\n')
        before = file.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Unexpected source bytes'):
            module.apply(self.root, self.root / 'result')
        self.assertEqual(before, file.read_bytes())
        self.assertFalse((self.repo / 'quickstep/src/com/android/quickstep/TakuRecentsProvider.java').exists())

    def test_coexists_with_taku_navigation_patch(self):
        nav_spec = importlib.util.spec_from_file_location('nav', ROOT / 'image/apply-launcher-patches.py')
        nav = importlib.util.module_from_spec(nav_spec)
        nav_spec.loader.exec_module(nav)
        manifest, _ = nav.load_contract(ROOT / 'image/launcher/manifest.json')
        for entry in manifest['files']:
            if entry['beforeSha256']:
                path = self.repo / entry['path']; path.parent.mkdir(parents=True, exist_ok=True)
                folder = 'home-policy' if path.name == 'OverviewComponentObserver.java' else 'launcher-entry'
                shutil.copyfile(ROOT / 'tests/fixtures' / folder / path.name, path)
        nav.apply(self.root, self.root / 'result')
        module.apply(self.root, self.root / 'result')
        self.assertTrue(nav.inspect_sources(self.root, manifest)[1])

    def test_symlink_target_preserved(self):
        file = self.repo / 'quickstep/AndroidManifest.xml'
        outside = self.root / 'outside.xml'
        outside.write_bytes(file.read_bytes())
        file.unlink(); file.symlink_to(outside)
        before = outside.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Unexpected symlink'):
            module.apply(self.root, self.root / 'result')
        self.assertEqual(before, outside.read_bytes())

    def test_foreign_revision_rejected(self):
        with patch.object(module.subprocess, 'check_output', return_value='foreign-revision\n'):
            with self.assertRaisesRegex(ValueError, 'not pinned'):
                module.apply(self.root, self.root / 'result')

    def test_partial_patch_rejected(self):
        manifest = json.loads((module.ROOT / 'manifest.json').read_text())
        module.apply(self.root, self.root / 'result')
        entry = next(f for f in manifest['files'] if f['beforeSha256'])
        shutil.copyfile(ROOT / 'tests/fixtures/recents' / entry['path'], self.repo / entry['path'])
        with self.assertRaisesRegex(ValueError, 'Partially applied'):
            module.apply(self.root, self.root / 'result')


if __name__ == '__main__':
    unittest.main()
