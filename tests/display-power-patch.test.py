#!/usr/bin/env python3
"""Round-trip the Android 17 power-group patch against pinned framework bytes."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('display_power', ROOT / 'image/apply-display-power-patch.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
MANIFEST = json.loads((module.ROOT / 'manifest.json').read_text())

class DisplayPowerPatchTest(unittest.TestCase):
    def test_pinned_apply_reapply_verify_and_preserve_unknown_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo = root / module.REPOSITORY
            entry, = MANIFEST['files']
            target = repo / entry['path']
            target.parent.mkdir(parents=True)
            url = ('https://raw.githubusercontent.com/LineageOS/android_frameworks_base/'
                   f'{module.UPSTREAM}/{entry["path"]}')
            with urlopen(url, timeout=30) as response:
                original = response.read()
            target.write_bytes(original)
            self.assertEqual(module.digest(target), entry['beforeSha256'])
            check_output = subprocess.check_output
            def revision(command, **kwargs):
                return module.UPSTREAM + '\n' if command[-2:] == ['rev-parse', 'HEAD'] else check_output(command, **kwargs)
            with patch.object(module.subprocess, 'check_output', side_effect=revision):
                with self.assertRaisesRegex(ValueError, 'not applied'):
                    module.apply(root, root / 'result', verify_only=True)
                module.apply(root, root / 'result')
                patched = target.read_bytes()
                module.apply(root, root / 'result')
                module.apply(root, root / 'result', verify_only=True)
                self.assertEqual(target.read_bytes(), patched)
                target.write_bytes(patched + b'\n// unrelated local change\n')
                dirty = target.read_bytes()
                with self.assertRaisesRegex(ValueError, 'Unexpected source bytes'):
                    module.apply(root, root / 'result')
                self.assertEqual(target.read_bytes(), dirty)
                target.write_bytes(patched)
                subprocess.run(['git', '-C', str(repo), 'apply', '--reverse', str(module.ROOT / 'independent-display-group.patch')], check=True)
                self.assertEqual(target.read_bytes(), original)

    def test_both_image_build_paths_apply_patch(self):
        for script in ('image/build.sh', 'image/emulator/build.sh'):
            source = (ROOT / script).read_text()
            self.assertIn('python3 /taku/image/apply-display-power-patch.py --source-root /src --results-dir /results', source)
        self.assertIn('apply-display-power-patch.py --source-root /src --results-dir /results --verify-only', (ROOT / 'image/build.sh').read_text())

if __name__ == '__main__':
    unittest.main()
