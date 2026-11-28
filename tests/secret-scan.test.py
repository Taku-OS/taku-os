#!/usr/bin/env python3
"""Ensure CI scans the initial tree as well as subsequent introduced commits."""
import os
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/ci-secret-scan.sh'
SCANNER = shutil.which('gitleaks')


@unittest.skipUnless(SCANNER, 'Install the CI-pinned gitleaks for detector tests')
class SecretScanAllowlist(unittest.TestCase):
    def test_allowlist_requires_both_exact_value_and_reviewed_path(self):
        self.assert_path_bound_allowlist(
            'docs/reviews/half-marathon-panels-2026-10-20/artifacts.json',
            '8d6eeb9f4d1056b583670e46e5dc21d27363f4ff5148ff9b9b0badb2c38c1566')

    def test_runtime_asset_digest_exception_is_exact_and_path_bound(self):
        for digest in (
                '56d2d3c9a73137ca1d84d335e355f83b5acebe2db26e72517b66ce216255aad7',
                '4a3cd2d45986a0b63081ad4c9eee63ca3e3cecfe9adcfa470cbfb9f83543ebb2',
                '78882e1d0704ea3b41acabd85821b2bdb42bcc0040f7132a95e932484a540c81'):
            with self.subTest(digest=digest):
                self.assert_path_bound_allowlist('image/runtime-assets.lock.json', digest)

    def test_audio_rom_source_digest_exception_is_exact_and_path_bound(self):
        self.assert_path_bound_allowlist(
            'system/audio-engine/rom-input-lock.json',
            'cde133cd826da00c41432e9fae3d082de21dbd58c0c4a2899a06854fad9061a5')

    def assert_path_bound_allowlist(self, reviewed_path, known):
        config = SCRIPT.parents[1] / '.gitleaks.toml'
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            root = base / 'source'
            path = root / reviewed_path
            path.parent.mkdir(parents=True)
            fresh = hashlib.sha256(b'synthetic secret scanner regression fixture').hexdigest()

            def scan(value, expected):
                path.write_text('    "api": "' + value + '",\n')
                report = base / 'report.json'
                result = subprocess.run([
                    SCANNER, 'dir', '.', '--config', str(config),
                    '--redact=100', '--no-banner', '--report-format', 'json',
                    '--report-path', str(report)], cwd=root, capture_output=True, text=True)
                findings = json.loads(report.read_text())
                self.assertEqual(result.returncode, int(expected > 0), result.stderr)
                self.assertEqual(len(findings), expected)

            scan(known, 0)
            scan(fresh, 1)
            path.unlink()
            path = path.with_name('unreviewed.json')
            scan(known, 1)
            path.unlink()
            path = root / 'nested' / reviewed_path
            path.parent.mkdir(parents=True)
            scan(known, 1)


class SecretScanScope(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'Test')
        self.git('config', 'user.email', 'test@example.invalid')
        self.first = self.commit('initial')
        self.git('update-ref', 'refs/remotes/origin/main', self.first)
        bin_dir = self.root / 'bin'
        bin_dir.mkdir()
        scanner = bin_dir / 'gitleaks'
        scanner.write_text('''#!/bin/bash
set -euo pipefail
for arg in "$@"; do
  case "$arg" in
    --log-opts=*) git rev-list "${arg#--log-opts=}" > "$SCAN_RESULT" ;;
  esac
done
''')
        scanner.chmod(0o755)
        self.env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'],
                        SCAN_RESULT=str(self.root / 'scanned'))
        self.env.pop('SCAN_BASE', None)
        self.env.pop('SCAN_HEAD', None)

    def git(self, *args):
        return subprocess.check_output(['git', *args], cwd=self.root, text=True).strip()

    def commit(self, text):
        (self.root / 'content').write_text(text)
        self.git('add', 'content')
        self.git('commit', '-qm', text)
        return self.git('rev-parse', 'HEAD')

    def scan(self, **env):
        result = subprocess.run(['bash', str(SCRIPT)], cwd=self.root,
                                env=dict(self.env, **env), capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return set((self.root / 'scanned').read_text().splitlines())

    def test_initial_commit_with_no_before_sha(self):
        self.assertEqual(self.scan(), {self.first})

    def test_initial_commit_with_zero_before_sha(self):
        self.assertEqual(self.scan(SCAN_BASE='0' * 40), {self.first})

    def test_new_branch_scans_reachable_history(self):
        second = self.commit('second')
        self.assertEqual(self.scan(SCAN_BASE='0' * 40), {self.first, second})

    def test_update_scans_only_introduced_commits(self):
        second = self.commit('second')
        self.assertEqual(self.scan(SCAN_BASE=self.first, SCAN_HEAD=second), {second})

    def test_unknown_nonzero_base_fails_instead_of_silently_skipping(self):
        result = subprocess.run(['bash', str(SCRIPT)], cwd=self.root,
                                env=dict(self.env, SCAN_BASE='f' * 40), capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / 'scanned').exists())


if __name__ == '__main__':
    unittest.main()
