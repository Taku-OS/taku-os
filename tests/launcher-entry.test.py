#!/usr/bin/env python3
"""Apply real pinned Quickstep patches, including upgrades; no APK/device claims."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('launcher_policy', ROOT / 'image/apply-launcher-patches.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class LauncherEntry(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.source = Path(temp.name)
        self.repository = self.source / policy.REPOSITORY
        self.manifest, self.diff = policy.load_contract(ROOT / 'image/launcher/manifest.json')
        for entry in self.manifest['files']:
            if entry['beforeSha256']:
                target = self.repository / entry['path']
                target.parent.mkdir(parents=True, exist_ok=True)
                folder = 'home-policy' if target.name == 'OverviewComponentObserver.java' else 'launcher-entry'
                shutil.copyfile(ROOT / 'tests/fixtures' / folder / target.name, target)
        original = subprocess.check_output

        def revision(command, **kwargs):
            if command[-2:] == ['rev-parse', 'HEAD']:
                return policy.UPSTREAM + '\n'
            return original(command, **kwargs)
        mock = patch.object(policy.subprocess, 'check_output', side_effect=revision)
        mock.start()
        self.addCleanup(mock.stop)

    def test_verify_only_never_applies(self):
        target = self.repository / 'quickstep/src/com/android/quickstep/OverviewComponentObserver.java'
        before = target.read_bytes()
        with self.assertRaisesRegex(ValueError, 'not applied'):
            policy.apply(self.source, self.source / 'results', verify_only=True)
        self.assertEqual(before, target.read_bytes())
        self.apply()
        policy.apply(self.source, self.source / 'results', verify_only=True)

    def apply(self):
        return policy.apply(self.source, self.source / 'results')

    def test_clean_and_idempotent(self):
        report = self.apply()
        before = report.read_bytes()
        self.apply()
        self.assertEqual(before, report.read_bytes())
        self.assertTrue(policy.inspect_sources(self.source, self.manifest)[1])
        self.assertFalse(json.loads(before)['apkBuiltVerified'])

    def test_upgrade_both_reviewed_predecessors(self):
        for name in ['previous-home-manifest.json', 'legacy-manifest.json']:
            with self.subTest(name=name):
                old = json.loads((ROOT / 'image/launcher' / name).read_text())
                subprocess.run(['git', '-C', str(self.repository), 'apply',
                                str(ROOT / 'image/launcher' / old['patch'])], check=True)
                self.apply()
                self.assertTrue(policy.inspect_sources(self.source, self.manifest)[1])
                subprocess.run(['git', '-C', str(self.repository), 'apply', '--reverse',
                                str(self.diff)], check=True)

    def test_unknown_source_is_preserved(self):
        target = self.repository / next(e['path'] for e in self.manifest['files'] if e['beforeSha256'])
        target.write_text(target.read_text() + '\n// local modification\n')
        before = target.read_bytes()
        with self.assertRaisesRegex(ValueError, 'Unexpected bytes'):
            self.apply()
        self.assertEqual(before, target.read_bytes())
        self.assertFalse((self.repository / self.manifest['files'][0]['path']).exists())


if __name__ == '__main__':
    unittest.main()
