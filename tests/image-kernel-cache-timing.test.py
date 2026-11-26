"""Host-only kernel cache and timed build lifecycle tests."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'image'))
import build_timing
import kernel_cache


class KernelCacheTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.dest = self.root / 'device'
        self.dest.mkdir()
        self.cache = self.root / 'cache'
        self.dist = self.root / 'out' / 'dist'
        self.results = self.root / 'results'
        self.results.mkdir()
        self.listing = self.dest / 'vendor_dlkm.modules.load'
        self.listing.write_text('drivers/example.ko\n')
        self.git('init', '-q')
        self.git('add', '.')
        self.commit()
        self.image = self.root / 'image'
        for name in kernel_cache.KEY_FILES:
            path = self.image / name
            path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / 'image' / name, path)
        self.manifest = self.root / 'resolved.xml'
        self.manifest.write_text('<manifest/>')
        self.build_count = 0

    def git(self, *args):
        subprocess.run(['git', *args], cwd=self.dest, check=True)

    def commit(self):
        self.git('-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                 'commit', '-qm', 'fixture')

    def key(self, **changes):
        inputs = dict(product='lineage_tokay', target='caimito', command=['build'],
                      image=self.image, manifest=self.manifest, dest=self.dest,
                      toolchain={'clang': 'v1', 'bazel': 'v1'})
        return kernel_cache.kernel_cache_key(**(inputs | changes))

    def build(self):
        self.build_count += 1
        self.dist.mkdir(parents=True, exist_ok=True)
        (self.dist / 'boot.img').write_text(f'boot {self.build_count}')
        (self.dist / 'example.ko').write_text(f'module {self.build_count}')

    def install(self, dist):
        subprocess.run([sys.executable, str(ROOT / 'image/install-kernel.py'),
                        str(dist), str(self.dest), str(self.results)],
                       check=True, capture_output=True)

    def run_cache(self, cache, key):
        return kernel_cache.install_or_build(cache, key, self.dist, self.dest, self.build, self.install)

    def test_all_key_inputs_invalidate(self):
        original = self.key()
        for override in [dict(product='different'), dict(target='different'),
                         dict(command=['build', '--other-config']),
                         dict(toolchain={'clang': 'v2', 'bazel': 'v1'})]:
            with self.subTest(override=override):
                self.assertNotEqual(original, self.key(**override))
        for name in kernel_cache.KEY_FILES:
            with self.subTest(file=name):
                path = self.image / name
                contents = path.read_bytes()
                path.write_bytes(contents + b'\n')
                self.assertNotEqual(original, self.key())
                path.write_bytes(contents)
        self.manifest.write_text('<manifest><project/></manifest>')
        self.assertNotEqual(original, self.key())

    def test_key_binds_live_list_and_pinned_boot_module_set(self):
        original = self.key()
        self.listing.write_text('another.ko\n')
        self.assertNotEqual(original, self.key())
        self.listing.write_text('drivers/example.ko\n')
        self.assertEqual(original, self.key())
        for name in ('boot.img', 'extra.ko'):
            before = self.key()
            (self.dest / name).write_text('changed')
            self.git('add', name)
            self.commit()
            self.assertNotEqual(before, self.key())

    def test_miss_then_verified_hit_skips_bazel(self):
        key = self.key()
        self.dist.mkdir(parents=True)
        (self.dist / 'stale.ko').write_text('stale build output')
        miss = self.run_cache(self.cache, key)
        self.assertEqual(miss['cache'], 'miss')
        self.assertGreaterEqual(miss['bazel_seconds'], 0)
        self.assertFalse((self.cache / key / 'dist' / 'stale.ko').exists())
        (self.dest / 'boot.img').unlink()
        hit = self.run_cache(self.cache, key)
        self.assertEqual(hit['cache'], 'hit')
        self.assertIsNone(hit['bazel_seconds'])
        self.assertGreaterEqual(hit['cache_verify_install_seconds'], 0)
        self.assertEqual(self.build_count, 1)
        self.assertEqual((self.dest / 'boot.img').read_text(), 'boot 1')
        self.assertEqual(json.loads((self.results / 'kernel-artifacts.json').read_text())['requiredModuleCount'], 1)

    def test_corrupt_or_incomplete_entries_rebuild_and_repair(self):
        key = self.key()
        entry = self.cache / key
        self.run_cache(self.cache, key)
        (entry / 'dist' / 'boot.img').write_text('bad hash')
        self.assertEqual(self.run_cache(self.cache, key)['cache'], 'miss')
        (entry / 'dist' / 'example.ko').unlink()
        manifest = json.loads((entry / 'manifest.json').read_text())
        del manifest['files']['example.ko']
        (entry / 'manifest.json').write_text(json.dumps(manifest))
        self.assertEqual(self.run_cache(self.cache, key)['cache'], 'miss')
        self.assertEqual(self.run_cache(self.cache, key)['cache'], 'hit')
        self.assertEqual(self.build_count, 3)

    def test_distribution_list_cannot_reference_missing_module(self):
        self.build()
        (self.dist / self.listing.name).write_text('missing.ko\n')
        with self.assertRaisesRegex(ValueError, 'missing.ko'):
            kernel_cache.distribution(self.dist, self.dest)

    def test_failed_build_leaves_no_cache_entry(self):
        def fail():
            self.build()
            raise RuntimeError('failed')
        key = self.key()
        with self.assertRaisesRegex(RuntimeError, 'failed'):
            kernel_cache.install_or_build(self.cache, key, self.dist, self.dest, fail, self.install)
        self.assertFalse((self.cache / key).exists())

    def test_disabled_rebuilds_every_time(self):
        for _ in range(2):
            self.assertEqual(self.run_cache(None, None)['cache'], 'disabled')
        self.assertEqual(self.build_count, 2)
        self.assertFalse(self.cache.exists())


class TimingTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.results = Path(temp.name)
        self.report = self.results / 'timing-baseline.json'

    def test_full_report_and_export(self):
        build_timing.record(self.results, 'start', 0, prepare_seconds=2)
        build_timing.record(self.results, 'sync', 3)
        build_timing.record(self.results, 'kernel', 10)
        kernel = {'cache': 'hit', 'bazel_seconds': None, 'cache_verify_install_seconds': 1}
        (self.results / 'kernel-timing.json').write_text(json.dumps(kernel))
        build_timing.record(self.results, 'prepare', 15)
        build_timing.record(self.results, 'aosp_build', 20)
        build_timing.record(self.results, 'finish', 60, 0)
        build_timing.add_host_stage(self.results, 'verify_signatures', 2)
        build_timing.add_host_stage(self.results, 'export', 3)
        data = json.loads(self.report.read_text())
        self.assertEqual(data['schema'], 'taku.build-timing/1')
        self.assertEqual(data['unit'], 'seconds')
        self.assertEqual(data['exit_code'], 0)
        self.assertEqual(data['kernel'], kernel)
        self.assertEqual(data['stages'], {
            'prepare': 10, 'sync': 7, 'kernel': 5, 'aosp_build': 40,
            'export': 3, 'verify_signatures': 2, 'total': 67,
        })

    def test_cli_skipped_and_failed_stages(self):
        cli = [sys.executable, str(ROOT / 'image/build_timing.py'), str(self.results)]
        env = os.environ | {'TAKU_HOST_PREPARE_SECONDS': '1'}
        subprocess.run([*cli, 'start'], env=env, check=True)
        subprocess.run([*cli, 'aosp_build'], check=True)
        subprocess.run([*cli, 'finish', '19'], check=True)
        data = json.loads(self.report.read_text())
        self.assertEqual(data['exit_code'], 19)
        self.assertGreaterEqual(data['stages']['prepare'], 1)
        self.assertGreaterEqual(data['stages']['aosp_build'], 0)
        for stage in ('sync', 'kernel', 'export', 'verify_signatures'):
            self.assertIsNone(data['stages'][stage])
        self.assertIsNone(data['kernel'])


if __name__ == '__main__':
    unittest.main()
