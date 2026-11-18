#!/usr/bin/env python3
"""Checkpoint failure gates with synthetic files; never a ROM or device acceptance."""
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('incremental', ROOT / 'image/check-incremental.py')
incremental = importlib.util.module_from_spec(spec)
spec.loader.exec_module(incremental)


class Checkpoint(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.old, self.new, self.source, self.results = [self.root / x for x in ('old', 'new', 'source', 'results')]
        for path in (self.old, self.new, self.source, self.results):
            path.mkdir()
        pinned = ['image/source-lock.json', 'image/kernel-source-lock.json', 'image/manifests/platform.xml',
                  'image/manifests/devices.xml', 'image/manifests/kernel.xml', 'image/build-kernel.sh',
                  'image/install-kernel.py']
        for base in (self.old, self.new):
            records = []
            for name in pinned + ['system/assistant/source.java']:
                p = base / name
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text('locked fixture' if name in pinned else str(base))
                records.append({'path': name, 'sha256': incremental.digest(p)})
            (base / 'input-manifest.json').write_text(json.dumps({'digest': base.name, 'files': records}))
        graph = self.source / 'out/combined-lineage_tokay.ninja'
        graph.parent.mkdir(); graph.write_text('fixture graph')
        kernel = self.source / 'device/google/caimito-kernels/6.1/boot.img'
        kernel.parent.mkdir(parents=True); kernel.write_bytes(b'fixture boot')
        (self.results / 'kernel-artifacts.json').write_text(json.dumps({'artifacts': [{'name': 'boot.img', 'sha256': incremental.digest(kernel)}]}))
        for name in ('resolved-manifest.xml', 'resolved-kernel-manifest.xml'):
            (self.results / name).write_text('fixture manifest')

    def verify(self):
        return incremental.verify(self.old, self.new, self.source, self.results)

    def test_taku_source_update_preserves_checkpoint(self):
        result = self.verify()
        self.assertTrue(result['compileCachePreserved'])
        self.assertFalse(result['kernelRebuilt'])
        self.assertEqual(result['changedInputs'], ['system/assistant/source.java'])

    def test_changed_platform_lock_rejected(self):
        manifest = self.new / 'input-manifest.json'
        data = json.loads(manifest.read_text())
        data['files'][0]['sha256'] = '0' * 64
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'same platform/kernel lock'):
            self.verify()

    def test_integrated_source_drift_rejected(self):
        (self.old / 'system/assistant/source.java').write_text('unexpected update')
        with self.assertRaisesRegex(ValueError, 'input drifted'):
            self.verify()

    def test_new_snapshot_drift_rejected(self):
        (self.new / 'system/assistant/source.java').write_text('unexpected update')
        with self.assertRaisesRegex(ValueError, 'immutable snapshot differs'):
            self.verify()

    def test_missing_cache_rejected(self):
        (self.source / 'out/combined-lineage_tokay.ninja').unlink()
        with self.assertRaisesRegex(ValueError, 'build graph'):
            self.verify()

    def test_changed_kernel_rejected(self):
        (self.source / 'device/google/caimito-kernels/6.1/boot.img').write_bytes(b'unexpected kernel')
        with self.assertRaisesRegex(ValueError, 'Kernel checkpoint changed'):
            self.verify()


if __name__ == '__main__':
    unittest.main()
