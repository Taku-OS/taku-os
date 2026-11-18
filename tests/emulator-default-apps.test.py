#!/usr/bin/env python3
"""Exercise missing/corrupted weather/runtime export rejection without a device."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('emulator_apps', ROOT / 'image/emulator/verify-default-apps.py')
MODULE = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MODULE)


class DefaultApps(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.product = Path(self.temp.name)
        self.weather = self.product / 'product/app/Weather/Weather.apk'
        self.weather.parent.mkdir(parents=True)
        self.weather.write_bytes(b'original-weather')
        runtime = self.product / 'product/apex/gms.apex'
        runtime.parent.mkdir(parents=True)
        runtime.write_bytes(b'original-runtime')
        overlay = self.product / 'system_ext/overlay/Taku/Taku.apk'
        overlay.parent.mkdir(parents=True)
        overlay.write_bytes(b'overlay')
        props = self.product / 'product/etc/build.prop'
        props.parent.mkdir(parents=True)
        props.write_text('ro.taku.image.distribution=internal-test-only\n')
        self.app = {'id': 'pixel_weather', 'module': 'Weather', 'packageName': 'com.google.android.apps.weather',
                    'sha256': hashlib.sha256(self.weather.read_bytes()).hexdigest()}
        core = {'installedFiles': [{'path': 'PRODUCT/apex/gms.apex', 'exactBytes': True,
                  'bytes': runtime.stat().st_size, 'sha256': hashlib.sha256(runtime.read_bytes()).hexdigest()}],
                'overlays': [], 'takuAssistantOverlay': 'Taku'}
        def inspect(path, app, *tools):
            if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != app['sha256']:
                raise ValueError('Weather missing or corrupted')
            return {'signers': ['original'], 'manifest': {'packageName': app['packageName']}}
        a = patch.dict(MODULE.APPS, {'load_lock': lambda: ({'apps': [self.app], 'distribution': 'internal-test-only'}, 'apps-lock'),
                                    'inspect_apk': inspect})
        g = patch.dict(MODULE.GMS, {'load_lock': lambda: (core, 'core-lock')})
        a.start();g.start();self.addCleanup(a.stop);self.addCleanup(g.stop)

    def test_complete_export_has_weather_but_no_runtime_acceptance_claim(self):
        result = MODULE.verify(self.product, '/tools')
        self.assertEqual(result['apps'][0]['packageName'], 'com.google.android.apps.weather')
        self.assertFalse(result['runtimeVerified'])

    def test_missing_weather_rejected(self):
        self.weather.unlink()
        with self.assertRaises(ValueError): MODULE.verify(self.product, '/tools')

    def test_changed_google_runtime_rejected(self):
        (self.product / 'product/apex/gms.apex').write_bytes(b'changed-runtime!')
        with self.assertRaisesRegex(ValueError, 'runtime bytes differ'): MODULE.verify(self.product, '/tools')

    def test_missing_native_runtime_rejected(self):
        (self.product / 'product/apex/gms.apex').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing Google runtime'): MODULE.verify(self.product, '/tools')

    def test_missing_assistant_overlay_rejected(self):
        (self.product / 'system_ext/overlay/Taku/Taku.apk').unlink()
        with self.assertRaisesRegex(ValueError, 'runtime overlay'): MODULE.verify(self.product, '/tools')

    def test_missing_distribution_marker_rejected(self):
        (self.product / 'product/etc/build.prop').write_text('')
        with self.assertRaisesRegex(ValueError, 'distribution'): MODULE.verify(self.product, '/tools')


if __name__ == '__main__':
    unittest.main()
