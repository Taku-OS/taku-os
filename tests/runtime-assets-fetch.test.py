#!/usr/bin/env python3
"""Exercise private release download and fail-closed size/hash checks."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/prepare-image-assets.py'

class RuntimeAssetsFetchTest(unittest.TestCase):
    def test_committed_runtime_assets_have_a_download_source(self):
        # A developer's populated cache must not hide a broken clean checkout.
        lock = json.loads((SCRIPT.parents[1] / 'image/runtime-assets.lock.json').read_text())
        for item in lock['assets']:
            with self.subTest(asset=item['path']):
                release = item.get('githubRelease')
                if release:
                    self.assertTrue(all(isinstance(release.get(key), str) and release[key]
                                        for key in ('repo', 'tag', 'asset')))
                else:
                    self.assertTrue(item.get('url', '').startswith('https://'),
                                    'Committed runtime asset cannot be fetched without a local cache')
                self.assertNotIn('unpublished', item.get('publication', '').lower())

    def test_release_download_verifies_size_hash_and_preserves_existing(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name in ('scripts', 'image', 'bin'):
                (root / name).mkdir()
            shutil.copy(SCRIPT, root / 'scripts/prepare-image-assets.py')
            payload = b'transaction runtime archive'
            asset = root / 'release-asset'
            asset.write_bytes(payload)
            gh = root / 'bin/gh'
            gh.write_text('#!/bin/sh\nwhile [ "$1" != "--output" ]; do shift; done\ncp "$TEST_ASSET" "$2"\n')
            gh.chmod(0o755)
            item = {'path': 'downloads/codex.tar.gz', 'url': 'https://example.invalid/archive',
                    'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest(),
                    'githubRelease': {'repo': 'example/private', 'tag': 'test', 'asset': 'codex.tar.gz'}}
            (root / 'image/runtime-assets.lock.json').write_text(json.dumps({'assets': [item]}))
            env = {**os.environ, 'PATH': str(root / 'bin') + os.pathsep + os.environ['PATH'], 'TEST_ASSET': str(asset)}
            command = [sys.executable, str(root / 'scripts/prepare-image-assets.py'), '--asset', item['path']]
            subprocess.run(command, env=env, check=True, capture_output=True)
            dest = root / item['path']
            self.assertEqual(dest.read_bytes(), payload)
            dest.write_bytes(b'existing')
            for bad in (b'wrong archive', b'x' * len(payload)):
                asset.write_bytes(bad)
                result = subprocess.run(command, env=env, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(dest.read_bytes(), b'existing')
                self.assertIn(b'Size or hash mismatch', result.stderr)

if __name__ == '__main__':
    unittest.main()
