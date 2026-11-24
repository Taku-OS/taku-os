#!/usr/bin/env python3
"""Verify the dedicated display patch against the pinned framework inputs."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('display_audio', ROOT / 'image/apply-display-audio-patch.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
MANIFEST = json.loads((module.ROOT / 'manifest.json').read_text())


class DisplayAudioPatchTest(unittest.TestCase):
    def test_pinned_round_trip_and_policy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / module.REPOSITORY
            for entry in MANIFEST['files']:
                if entry['beforeSha256'] is None:
                    continue
                path = repo / entry['path']
                path.parent.mkdir(parents=True, exist_ok=True)
                url = ('https://raw.githubusercontent.com/LineageOS/android_frameworks_base/'
                       f'{module.UPSTREAM}/{entry["path"]}')
                with urlopen(url, timeout=20) as response:
                    path.write_bytes(response.read())
                self.assertEqual(module.digest(path), entry['beforeSha256'])

            # The input blobs are independently hash checked above. Substitute only checkout HEAD.
            check_output = subprocess.check_output
            def revision(command, **kwargs):
                if command[-2:] == ['rev-parse', 'HEAD']:
                    return module.UPSTREAM + '\n'
                return check_output(command, **kwargs)

            with patch.object(module.subprocess, 'check_output', side_effect=revision):
                with self.assertRaisesRegex(ValueError, 'not applied'):
                    module.apply(root, root / 'result', verify_only=True)
                module.apply(root, root / 'result')
                module.apply(root, root / 'result', verify_only=True)
                policy = next(repo / f['path'] for f in MANIFEST['files']
                              if f['path'].endswith('/DedicatedDisplayAudioPolicy.java'))
                subprocess.run(['javac', '-d', str(root), str(policy),
                                str(ROOT / 'tests/fixtures/display-audio/Process.java'),
                                str(ROOT / 'tests/fixtures/display-audio/PolicyTest.java')], check=True)
                subprocess.run(['java', '-cp', str(root),
                                'com.android.server.audio.PolicyTest'], check=True)
                subprocess.run(['git', '-C', str(repo), 'apply', '--reverse',
                                str(module.ROOT / 'dedicated-display-mute.patch')], check=True)
                self.assertFalse(policy.exists())
                for entry in MANIFEST['files']:
                    if entry['beforeSha256'] is not None:
                        self.assertEqual(module.digest(repo / entry['path']), entry['beforeSha256'])


if __name__ == '__main__':
    unittest.main()
