#!/usr/bin/env python3
"""Pinned patch integrity and executable HOME/role policy boundaries; not ROM acceptance."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'tests/fixtures/home-policy'
spec = importlib.util.spec_from_file_location('home_policy', ROOT / 'image/apply-home-policy.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class HomePolicy(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = Path(self.temp.name)
        self.contract = policy.load_contract()
        for repo in self.contract['repositories']:
            for entry in repo['files']:
                if entry['beforeSha256']:
                    path = self.source / repo['path'] / entry['path']
                    path.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(FIXTURE / path.name, path)
        original = subprocess.check_output

        def revision(command, **kwargs):
            if command[-2:] == ['rev-parse', 'HEAD']:
                return next(r['revision'] for r in self.contract['repositories']
                            if Path(command[2]) == self.source / r['path']) + '\n'
            return original(command, **kwargs)
        # Only fake checkout HEAD metadata. git apply and Java execute real bytes.
        self.addCleanup(patch.stopall)
        patch.object(policy.subprocess, 'check_output', side_effect=revision).start()

    def apply(self):
        return policy.apply(self.source, self.source / 'results')

    def test_apply_idempotent_and_verify(self):
        report = self.apply()
        first = report.read_bytes()
        self.apply()
        policy.apply(self.source, self.source / 'results', verify_only=True)
        self.assertEqual(first, report.read_bytes())
        self.assertFalse(json.loads(first)['deviceVerified'])

    def test_unknown_second_repository_preserves_first(self):
        framework, permission = self.contract['repositories']
        file = self.source / permission['path'] / permission['files'][0]['path']
        file.write_text(file.read_text() + '// unknown change\n')
        with self.assertRaisesRegex(ValueError, 'Unknown HOME source'):
            self.apply()
        self.assertTrue(all(policy.digest(self.source / framework['path'] / f['path']) == f['beforeSha256']
                            for f in framework['files'] if f['beforeSha256']))
        self.assertFalse((self.source / framework['path'] / framework['files'][1]['path']).exists())

    def test_resume_requires_policy(self):
        with self.assertRaisesRegex(ValueError, 'not applied'):
            policy.apply(self.source, self.source / 'results', verify_only=True)

    def test_wrong_revision_rejected(self):
        with patch.object(policy.subprocess, 'check_output', return_value='wrong\n'):
            with self.assertRaisesRegex(ValueError, 'expected pinned'):
                self.apply()

    def test_partial_repository_rejected(self):
        self.apply()
        framework = self.contract['repositories'][0]
        (self.source / framework['path'] / framework['files'][1]['path']).unlink()
        with self.assertRaisesRegex(ValueError, 'Partially applied'):
            self.apply()

    def test_java_policy_behavior(self):
        self.apply()
        java_home = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
        javac = str(java_home / 'bin/javac') if (java_home / 'bin/javac').exists() else shutil.which('javac')
        java = str(java_home / 'bin/java') if (java_home / 'bin/java').exists() else shutil.which('java')
        self.assertTrue(javac and java, 'JDK required for executable HOME policy tests')
        classes = self.source / 'classes'
        sources = list((FIXTURE / 'stubs').rglob('*.java')) + [FIXTURE / 'HomePolicyTest.java']
        sources += list(self.source.rglob('TakuHomePolicy.java'))
        sources += list(self.source.rglob('HomeRoleBehavior.java'))
        sources += list(self.source.rglob('AssistantRoleBehavior.java'))
        subprocess.run([javac, '-d', str(classes), *map(str, sources)], check=True)
        subprocess.run([java, '-cp', str(classes), 'com.android.server.pm.HomePolicyTest'], check=True)


if __name__ == '__main__':
    unittest.main()
