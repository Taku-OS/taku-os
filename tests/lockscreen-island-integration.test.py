#!/usr/bin/env python3
"""Island and lock screen patches applied in the image build order to the pinned upstream files.

Covers the shared SystemUI manifest: island first, then the lock screen layered on it, then every
re-run the builds perform (incremental re-integration, resume verify-only). Upstream bytes come from
TAKU_FRAMEWORKS_BASE (a frameworks/base checkout at the pin) or the pinned LineageOS commit on
GitHub; without either the test is skipped, not passed.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
FETCH_URL = 'https://raw.githubusercontent.com/LineageOS/android_frameworks_base/{revision}/{path}'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


island = load('island_patches', 'image/apply-island-patches.py')
lockscreen = load('lockscreen_patches', 'image/apply-lockscreen-patches.py')


def upstream(path, revision):
    local = os.environ.get('TAKU_FRAMEWORKS_BASE')
    if local:
        return (Path(local) / path).read_bytes()
    with urllib.request.urlopen(FETCH_URL.format(revision=revision, path=path), timeout=60) as response:
        return response.read()


class IslandThenLockscreen(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.island_contract = island.load_contract()
        cls.lockscreen_contract = lockscreen.load_contract()
        cls.revision = cls.island_contract['revision']
        paths = {entry['path'] for entry in cls.island_contract['files'] if entry['beforeSha256'] is not None}
        paths |= {entry['path'] for entry in cls.lockscreen_contract['files']
                  if entry['beforeSha256'] is not None and entry.get('layeredOn') is None}
        try:
            cls.files = {path: upstream(path, cls.revision) for path in sorted(paths)}
        except OSError as error:
            raise unittest.SkipTest(f'pinned upstream files unavailable: {error}')

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.source = Path(self.temp.name) / 'src'
        self.results = Path(self.temp.name) / 'results'
        repository = self.source / 'frameworks/base'
        for path, data in self.files.items():
            target = repository / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        subprocess.run(['git', 'init', '-q', str(repository)], check=True)
        real = subprocess.check_output

        def check_output(command, *args, **kwargs):
            # The fixture cannot have the pinned commit ID; everything else runs for real.
            if command[-2:] == ['rev-parse', 'HEAD']:
                return self.revision + '\n'
            return real(command, *args, **kwargs)

        self.patches = [mock.patch.object(module.subprocess, 'check_output', side_effect=check_output)
                        for module in (island, lockscreen)]
        for item in self.patches:
            item.start()
        self.manifest = repository / 'packages/SystemUI/AndroidManifest.xml'

    def tearDown(self):
        for item in self.patches:
            item.stop()
        self.temp.cleanup()

    def layered_entry(self):
        return next(entry for entry in self.lockscreen_contract['files'] if entry.get('layeredOn') == 'island')

    def test_build_order_and_every_rerun(self):
        island.apply(self.source, self.results)
        island.apply(self.source, self.results, verify_only=True)
        lockscreen.apply(self.source, self.results)
        lockscreen.apply(self.source, self.results, verify_only=True)
        self.assertEqual(island.digest(self.manifest), self.layered_entry()['afterSha256'])
        # Incremental builds integrate again; resume builds verify both, island first.
        island.apply(self.source, self.results)
        lockscreen.apply(self.source, self.results)
        island.apply(self.source, self.results, verify_only=True)
        lockscreen.apply(self.source, self.results, verify_only=True)
        text = self.manifest.read_text()
        self.assertIn('com.android.systemui.taku.island.IslandProvider', text)
        self.assertIn('com.android.systemui.taku.lockscreen.TakuShortcutAppActivity', text)
        report = json.loads((self.results / 'lockscreen-integration.json').read_text())
        self.assertTrue(report['sourcePatchVerified'])

    def test_lockscreen_before_island_is_refused_without_writing(self):
        before = self.manifest.read_bytes()
        with self.assertRaisesRegex(ValueError, 'apply the island patch before'):
            lockscreen.apply(self.source, self.results)
        self.assertEqual(self.manifest.read_bytes(), before)

    def test_unknown_manifest_bytes_still_preserved(self):
        island.apply(self.source, self.results)
        self.manifest.write_text(self.manifest.read_text() + '<!-- local edit -->\n')
        with self.assertRaisesRegex(ValueError, 'preserving|island-patched'):
            lockscreen.apply(self.source, self.results)
        with self.assertRaisesRegex(ValueError, 'preserving'):
            island.apply(self.source, self.results, verify_only=True)


if __name__ == '__main__':
    unittest.main()
