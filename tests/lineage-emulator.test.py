#!/usr/bin/env python3
"""Image identity and AVD isolation checks; these never run adb or an emulator."""
import hashlib
import json
from pathlib import Path
import runpy
import tempfile
import unittest

CODE = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'image/emulator/start.py'))
FILES = ('system-qemu.img', 'vendor-qemu.img', 'ramdisk-qemu.img', 'kernel-ranchu',
         'userdata.img', 'encryptionkey.img', 'advancedFeatures.ini', 'kernel_cmdline.txt', 'build.prop')


class EmulatorIsolation(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'images').mkdir()
        records = []
        for name in FILES:
            raw = name.encode()
            (self.root / 'images' / name).write_bytes(raw)
            records.append({'path': name, 'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()})
        (self.root / 'images.json').write_text(json.dumps({
            'schema': 'taku.emulator-images/1', 'abi': 'arm64-v8a', 'files': records}))

    def test_tampered_image_rejected(self):
        CODE['verify_images'](self.root)
        (self.root / 'images/system-qemu.img').write_text('another platform')
        with self.assertRaises(ValueError):
            CODE['verify_images'](self.root)

    def test_missing_image_rejected(self):
        (self.root / 'images/kernel-ranchu').unlink()
        with self.assertRaises(ValueError):
            CODE['verify_images'](self.root)

    def test_sdk_aliases_resolve_verified_images(self):
        CODE['sdk_names'](self.root / 'images')
        self.assertEqual((self.root / 'images/system.img').resolve(), (self.root / 'images/system-qemu.img').resolve())
        CODE['verify_images'](self.root)

    def test_foreign_sdk_alias_untouched(self):
        alias = self.root / 'images/system.img'
        alias.write_text('another image')
        with self.assertRaises(ValueError):
            CODE['sdk_names'](self.root / 'images')
        self.assertEqual(alias.read_text(), 'another image')

    def test_existing_foreign_avd_untouched(self):
        home = self.root / 'avds'
        home.mkdir()
        old = home / 'Existing.ini'
        old.write_text('foreign configuration')
        with self.assertRaises(ValueError):
            CODE['prepare_avd'](home, 'Existing', self.root, 'hash')
        self.assertEqual(old.read_text(), 'foreign configuration')
        self.assertFalse((home / 'Existing.avd').exists())

    def test_restart_keeps_data_but_image_change_rejected(self):
        home = self.root / 'avds'
        avd = CODE['prepare_avd'](home, 'New', self.root, 'hash')
        data = avd / 'userdata-qemu.img'
        data.write_text('user data')
        CODE['prepare_avd'](home, 'New', self.root, 'hash')
        with self.assertRaises(ValueError):
            CODE['prepare_avd'](home, 'New', self.root, 'new hash')
        self.assertEqual(data.read_text(), 'user data')
        self.assertIn('hw.gpu.mode=host', (avd / 'config.ini').read_text())


if __name__ == '__main__':
    unittest.main()
