#!/usr/bin/env python3
"""Check actual notice archive bytes and Android asset inclusion paths."""
import hashlib
import json
import shutil
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LicensePackaging(unittest.TestCase):
    def test_font_notices_are_in_apk_asset_roots(self):
        manifest = json.loads((ROOT / 'system/font-license-packaging.json').read_text())
        for entry in manifest['files']:
            source, packaged = (ROOT / entry[k] for k in ('source', 'packagedSource'))
            self.assertEqual(source.read_bytes(), packaged.read_bytes())
            self.assertEqual(hashlib.sha256(packaged.read_bytes()).hexdigest(), entry['sha256'])
            self.assertIn(b'SIL OPEN FONT LICENSE', packaged.read_bytes())
            self.assertTrue('/assets/licenses/fonts/' in entry['packagedSource'])
        bp = (ROOT / 'Android.bp').read_text()
        self.assertIn('"system/assistant/assets"', bp)
        self.assertEqual(len(manifest['files']), 8)

    def test_runtime_archive_contains_exact_notices_and_is_immutable(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            binary = base / 'fixture-binary'
            binary.write_bytes(b'packaging fixture only; not an executable runtime')
            archive = base / 'runtime.tar.gz'
            command = [sys.executable, str(ROOT / 'adapters/codex-runtime/package-runtime.py'), str(binary), str(archive)]
            subprocess.run(command, check=True, capture_output=True)
            original = archive.read_bytes()
            with tarfile.open(archive) as package:
                self.assertEqual(package.extractfile('codex-aarch64-unknown-linux-musl').read(), binary.read_bytes())
                for name in ['OpenAI-LICENSE.txt', 'OpenAI-NOTICE.txt', 'provenance.json']:
                    self.assertEqual(package.extractfile('licenses/codex/' + name).read(), (ROOT / 'adapters/codex-runtime/licenses' / name).read_bytes())
            self.assertNotEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(archive.read_bytes(), original)

    def test_runtime_archive_rejects_unlisted_notice_files(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            shutil.copyfile(ROOT / 'adapters/codex-runtime/package-runtime.py', base / 'package-runtime.py')
            shutil.copytree(ROOT / 'adapters/codex-runtime/licenses', base / 'licenses')
            (base / 'licenses/unlisted.txt').write_text('must not enter the package')
            (base / 'binary').write_bytes(b'fixture')
            result = subprocess.run([sys.executable, str(base / 'package-runtime.py'), str(base / 'binary'), str(base / 'output.tar.gz')], capture_output=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((base / 'output.tar.gz').exists())


if __name__ == '__main__':
    unittest.main()
