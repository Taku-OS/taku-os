"""Host Make evaluation of the Taku product declarations."""
import hashlib
from itertools import product
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
CORE = 'vendor/taku/image/taku-core.mk'
EMU = 'image/emulator/lineage_taku_emu_arm64.mk'
FIELDS = (
    'PRODUCT_PACKAGES', 'PRODUCT_PACKAGES_DEBUG', 'PRODUCT_COPY_FILES',
    'PRODUCT_SYSTEM_EXT_PROPERTIES', 'PRODUCT_PRODUCT_PROPERTIES',
    'PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST',
    'PRODUCT_PACKAGE_OVERLAYS', 'DEVICE_PACKAGE_OVERLAYS',
    'SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS', 'INHERITED_PRODUCTS',
)
PROFILES = tuple(product(('user', 'userdebug', 'eng'), ('', 'false', 'true'),
                         ('', 'false', 'true'), ('', 'true')))
# Ordered Make expansions of image/taku.mk at c861bc072346a0d9a6253f856701878d5c90ed1c.
PIXEL_BASELINE = '7d79739391f4b6e294325781ec0eec6a48c5d0063eaa4737dd529cadfda8e98a'


def expand(makefile, profiles, *, emulator=False):
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        vendor = root / 'vendor/taku'
        vendor.mkdir(parents=True)
        (vendor / 'image').symlink_to(ROOT / 'image', target_is_directory=True)
        audio = vendor / 'system/audio-engine/models'
        audio.mkdir(parents=True)
        (audio / 'rom-files.mk').write_text(
            'ifneq ($(filter userdebug eng,$(TARGET_BUILD_VARIANT)),)\n'
            'PRODUCT_COPY_FILES += audio-model:system_ext/etc/taku-audio/models/test\nendif\n')
        (root / 'product.mk').write_text(makefile)
        # Android records inheritance markers in each product field at the call site.
        markers = ''.join(f'$(eval {field} += @inherit:$(1))'
                          for field in FIELDS if field != 'INHERITED_PRODUCTS')
        inherited = '\t$(eval INHERITED_PRODUCTS += $(1))' + markers + '\n'
        if emulator:
            inherited = ('\t$(if $(filter ' + CORE + ',$(1)),$(eval include $(1)),'
                         '$(eval INHERITED_PRODUCTS += $(1))'
                         '$(if $(filter vendor/taku/%,$(1)),' + markers + '))\n')
        harness = (
            'define inherit-product\n' + inherited + 'endef\n'
            'find-copy-subdir-files = rea-fixture:$(3)/fixture\n'
            'TARGET_COPY_OUT_SYSTEM := system\nTARGET_COPY_OUT_SYSTEM_EXT := system_ext\n'
            'TARGET_COPY_OUT_PRODUCT := product\ninclude product.mk\n'
            'dump:\n' + ''.join(f"\t@printf '%s\\n' '{field}=$(strip $({field}))'\n"
                                 for field in FIELDS)
        )
        (root / 'Makefile').write_text(harness)
        results = []
        for values in profiles:
            settings = ('TARGET_BUILD_VARIANT=' + values[0],)
            settings += tuple(name + '=' + value for name, value in zip(
                ('TAKU_INCLUDE_GOOGLE_APPS', 'TAKU_ENABLE_PIXEL_OTA',
                 'TAKU_PREINSTALL_PERSONAL_PROXY'), values[1:]) if value)
            output = subprocess.check_output(['make', 'dump', *settings], cwd=root, text=True)
            results.append(dict(line.split('=', 1) for line in output.splitlines()))
        return results


class TakuCoreTest(unittest.TestCase):
    def test_device_boundary(self):
        core = (ROOT / 'image/taku-core.mk').read_text()
        self.assertNotRegex(core, re.compile(
            r'tokay|pixel[ -]?9|lineage\.updater\.uri|TAKU_ENABLE_PIXEL_OTA|'
            r'TAKU_PREINSTALL_PERSONAL_PROXY|^\s*(PRODUCT_(DEVICE|NAME|MODEL)|'
            r'TARGET_SCREEN_\w+|BOARD_\w+)\s*[:+?]?=', re.I | re.M))
        pixel = (ROOT / 'image/taku.mk').read_text()
        emulator = (ROOT / EMU).read_text()
        self.assertEqual(pixel.count('include ' + CORE), 1)
        self.assertEqual(emulator.count('$(call inherit-product, ' + CORE + ')'), 1)
        self.assertNotIn('vendor/taku/image/taku.mk', emulator)

    def test_pixel_matches_pre_extraction(self):
        pixel = expand((ROOT / 'image/taku.mk').read_text(), PROFILES)
        self.assertEqual(hashlib.sha256(json.dumps(pixel, sort_keys=True).encode()).hexdigest(),
                         PIXEL_BASELINE)

    def test_emulator_core_has_no_extra_entries(self):
        profiles = [('userdebug', google, 'false', '') for google in ('', 'false', 'true')]
        pixel = expand((ROOT / 'image/taku.mk').read_text(), profiles)
        emulator = expand((ROOT / EMU).read_text(), profiles, emulator=True)
        for expected, actual in zip(pixel, emulator):
            for field, suffix in (
                ('PRODUCT_PACKAGES', 'libaudiopreprocessing'),
                ('PRODUCT_SYSTEM_EXT_PROPERTIES',
                 'ro.taku.image.target=emulator lineage.updater.uri=http://127.0.0.1:9/emulator-no-ota'),
            ):
                self.assertEqual(actual[field], expected[field] + ' ' + suffix)
                actual[field] = expected[field]
            actual['INHERITED_PRODUCTS'] = actual['INHERITED_PRODUCTS'].removeprefix(
                'device/generic/goldfish/64bitonly/product/sdk_phone64_arm64.mk '
                'vendor/lineage/config/common_full_phone.mk ').strip()
            self.assertEqual(actual, expected)


if __name__ == '__main__':
    unittest.main()
