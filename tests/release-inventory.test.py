#!/usr/bin/env python3
"""Publication inventory must not omit newer media formats or tokenized links."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('inventory', Path(__file__).resolve().parents[1] / 'scripts/release-inventory.py')
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class InventoryCoverage(unittest.TestCase):
    def test_media_and_runtime_formats(self):
        for suffix in ['webp', 'svg', 'ttf', 'woff2', 'wav', 'mp4']:
            self.assertIn('media-content-and-rights', inventory.review_reasons('assets/example.' + suffix, b'test'))
        for suffix in ['gz', 'apk', 'onnx']:
            self.assertIn('archive-or-runtime-provenance', inventory.review_reasons('assets/example.' + suffix, b'test'))

    def test_real_internal_urls_only(self):
        for url in [b'https://applink.larksuite.com/client/message/link/open?token=synthetic', b'https://team.feishu.cn/wiki/synthetic']:
            self.assertIn('internal-link-review', inventory.review_reasons('docs/example.md', url))
        self.assertNotIn('internal-link-review', inventory.review_reasons('scripts/example.py', b"domains = ['larksuite.com', 'feishu.cn']"))

    def test_historical_media_keeps_both_reasons(self):
        self.assertEqual(inventory.review_reasons('docs/reviews/old/screen.webp', b'test'), ['historical-evidence', 'media-content-and-rights'])


if __name__ == '__main__':
    unittest.main()
