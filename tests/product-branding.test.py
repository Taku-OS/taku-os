#!/usr/bin/env python3
"""Pinned source patch / product identity checks, not ROM or phone acceptance."""
import importlib.util
import hashlib
import io
import json
import pathlib
import os
import shutil
import sqlite3
import re
import subprocess
import tempfile
import unittest
import zipfile
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("product_patch", ROOT / "image/apply-product-branding.py")
PATCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCH)
BRAND = PATCH.BRAND
RELEASE_SPEC = importlib.util.spec_from_file_location("branding_release", ROOT / "scripts/ota_release.py")
RELEASE = importlib.util.module_from_spec(RELEASE_SPEC)
RELEASE_SPEC.loader.exec_module(RELEASE)
CURRENT = BRAND.load()


class ProductVersions(unittest.TestCase):
    @unittest.skipUnless(shutil.which("kotlinc") and shutil.which("java"), "Host Kotlin/JVM compiler unavailable")
    def test_generated_kotlin_displays_legacy_and_new_versions_with_separate_product_name(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            source = root / "TakuBrand.kt"
            source.write_text(BRAND.updater_constants(BRAND.load()))
            fixtures = ROOT / "tests/fixtures/taku-branding"
            jar = root / "contract.jar"
            # Supports an existing embeddable compiler as well as a Kotlin distribution.
            runtime = os.environ.get("TAKU_KOTLIN_STDLIB")
            compiled = subprocess.run(["kotlinc", str(source), str(fixtures / "SystemProperties.kt"),
                                       str(fixtures / "TakuBrandContractTest.kt"),
                                       *([] if runtime else ["-include-runtime"]), "-d", str(jar)],
                                      capture_output=True, text=True, timeout=120)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            command = ["java", "-cp", str(jar) + os.pathsep + runtime,
                       "org.lineageos.updater.TakuBrandContractTestKt"] if runtime else ["java", "-jar", str(jar)]
            result = subprocess.run([*command, CURRENT["productVersion"]], check=True,
                                    capture_output=True, text=True, timeout=30)
            self.assertIn("Generated TakuBrand runtime checks passed", result.stdout)

    def test_three_part_versions_are_compared_numerically_and_legacy_is_read_only(self):
        self.assertEqual(BRAND.version_parts("1.3.0"), (1, 3, 0))
        self.assertGreater(BRAND.version_parts("1.10.0"), BRAND.version_parts("1.9.0"))
        self.assertEqual(BRAND.version_parts("1.0", allow_legacy=True), (1, 0, 0))
        for value in ("1.0", "1", "1.3.0.2", "01.3.0", "1.00.1", "1.0.01", "-1.3.0",
                      "1.3.0-beta.1", "1.3.0+build", "1.3.0\n", " 1.3.0", 1.01, None, True):
            with self.subTest(version=value), self.assertRaises(ValueError):
                BRAND.version_parts(value)

    def test_current_writer_rejects_legacy_and_malformed_configurations(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "branding.json"
            config = json.loads((ROOT / "image/product-branding.json").read_text())
            path.write_text(json.dumps(config))
            self.assertEqual(BRAND.load(path)["productVersion"], CURRENT["productVersion"])
            for key, value in (("schema", "taku.product-branding/1"), ("productVersion", "1.0"),
                               ("productVersion", "01.3.0"), ("productVersion", None),
                               ("productName", "TakuOS1.3.0"), ("updaterVersionCode", 3),
                               ("updaterVersionCode", True)):
                path.write_text(json.dumps({**config, key: value}))
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    BRAND.load(path)

    def test_receipt_generations_cannot_be_mixed_or_relabelled(self):
        current = BRAND.load()
        legacy = {**current, "schema": "taku.product-branding/1", "productVersion": "1.0",
                  "displayVersion": "TakuOS1.0", "updaterVersionCode": 3}
        before = dict(legacy)
        self.assertEqual(BRAND.validate_branding(legacy), 3)
        self.assertEqual(BRAND.validate_branding(current), CURRENT["updaterVersionCode"])
        self.assertEqual(BRAND.validate_branding(dict(current, updaterVersionCode=5)), 5)
        self.assertEqual(legacy, before)
        for record in (dict(current, updaterVersionCode=3), dict(legacy, updaterVersionCode=4),
                       dict(current, schema="taku.product-branding/1"),
                       dict(legacy, schema="taku.product-branding/2"),
                       dict(current, schema="taku.product-branding/99"),
                       dict(current, displayVersion="TakuOS 1.3.0"),
                       dict(current, configSha256="changed")):
            with self.subTest(record=record), self.assertRaises(ValueError):
                BRAND.validate_branding(record)


class BootProperties(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name).resolve()
        self.identity = BRAND.build_identity(BRAND.load(), "20261024000000")
        self.contents = {
            "SYSTEM/build.prop": "ro.build.display.id=CP2A.260605.016 test-keys\n",
            "SYSTEM_EXT/etc/build.prop": "ro.taku.product.name=TakuOS\nro.taku.product.version=" + self.identity["productVersion"] + "\nro.taku.display.version=" + self.identity["displayVersion"] + "\n",
            "VENDOR/build.prop": "ro.vendor.build.version.sdk=37\n",
            "PRODUCT/etc/build.prop": "ro.build.display.id=" + self.identity["buildDisplayId"] + "\n",
        }
        self.entries = {}
        for name in ("product_branding.py", "branding-properties.json"):
            shutil.copy2(ROOT / "image" / name, self.root / name)
            self.entries["image/" + name] = hashlib.sha256((self.root / name).read_bytes()).hexdigest()

    def archive(self):
        value = io.BytesIO()
        with zipfile.ZipFile(value, "w") as archive:
            for name, data in self.contents.items():
                archive.writestr(name, data)
        return zipfile.ZipFile(value)

    def test_effective_value_uses_product_not_earlier_system_ext(self):
        with self.archive() as archive:
            proof = BRAND.verify_boot_properties(archive, self.identity)
            self.assertFalse(proof["physicalDeviceVerified"])
            self.assertEqual(proof["effectiveSources"]["ro.build.display.id"], "PRODUCT/etc/build.prop")
            RELEASE.check_branding_boot_properties(self.root, self.entries, archive, self.identity, proof)
        self.contents["SYSTEM_EXT/etc/build.prop"] += "ro.build.display.id=" + self.identity["buildDisplayId"] + "\n"
        self.contents["PRODUCT/etc/build.prop"] = "ro.build.display.id=CP2A.260605.016 test-keys\n"
        with self.archive() as archive, self.assertRaisesRegex(ValueError, "placement differs"):
            BRAND.verify_boot_properties(archive, self.identity)

    def test_later_product_and_debug_ramdisk_overrides_are_rejected(self):
        for path, text in (("PRODUCT/etc/build.prop", "ro.taku.display.version=Wrong\n"),
                           ("INIT_BOOT/RAMDISK/adb_debug.prop", "ro.build.display.id=Wrong\n")):
            with self.subTest(path=path):
                original = self.contents.copy()
                self.contents[path] = self.contents.get(path, "") + text
                with self.archive() as archive, self.assertRaisesRegex(ValueError, "after init partition precedence"):
                    BRAND.verify_boot_properties(archive, self.identity)
                self.contents = original

    def test_missing_import_optional_and_duplicate_properties_fail_closed(self):
        for text in ("", "ro.build.display.id?=" + self.identity["buildDisplayId"] + "\n",
                     self.contents["PRODUCT/etc/build.prop"] * 2,
                     self.contents["PRODUCT/etc/build.prop"] + "import /oem/oem.prop\n"):
            with self.subTest(text=text):
                original = self.contents["PRODUCT/etc/build.prop"]
                self.contents["PRODUCT/etc/build.prop"] = text
                with self.archive() as archive, self.assertRaises(ValueError):
                    BRAND.verify_boot_properties(archive, self.identity)
                self.contents["PRODUCT/etc/build.prop"] = original

    def test_receipt_cannot_drop_or_change_new_policy_evidence(self):
        with self.archive() as archive:
            proof = BRAND.verify_boot_properties(archive, self.identity)
            for invalid in (None, {**proof, "effectiveSources": {}}, {**proof, "physicalDeviceVerified": True}):
                with self.subTest(invalid=invalid), self.assertRaisesRegex(RELEASE.ReleaseError, "proof differs"):
                    RELEASE.check_branding_boot_properties(self.root, self.entries, archive, self.identity, invalid)
            with self.assertRaisesRegex(RELEASE.ReleaseError, "requires frozen policy"):
                RELEASE.check_branding_boot_properties(self.root, {}, archive, self.identity, proof)

    def test_policy_helper_bytes_and_target_property_bytes_are_bound(self):
        with self.archive() as archive:
            proof = BRAND.verify_boot_properties(archive, self.identity)
        self.contents["VENDOR/build.prop"] += "# changed after receipt\n"
        with self.archive() as archive, self.assertRaisesRegex(RELEASE.ReleaseError, "proof differs"):
            RELEASE.check_branding_boot_properties(self.root, self.entries, archive, self.identity, proof)
        for name in ("product_branding.py", "branding-properties.json"):
            path = self.root / name
            original = path.read_bytes()
            path.write_bytes(original + b"\n")
            with self.archive() as archive, self.assertRaisesRegex(RELEASE.ReleaseError, "policy/helper differs"):
                RELEASE.check_branding_boot_properties(self.root, self.entries, archive, self.identity, proof)
            path.write_bytes(original)
            path.unlink()
            with self.archive() as archive, self.assertRaisesRegex(RELEASE.ReleaseError, "delivery file"):
                RELEASE.check_branding_boot_properties(self.root, self.entries, archive, self.identity, proof)
            path.write_bytes(original)

    def test_legacy_archive_keeps_original_limited_validation(self):
        self.contents["SYSTEM_EXT/etc/build.prop"] += "ro.build.display.id=" + self.identity["buildDisplayId"] + "\n"
        self.contents["PRODUCT/etc/build.prop"] = "ro.build.display.id=CP2A.260605.016 test-keys\n"
        with self.archive() as archive:
            # Compatibility is explicit, not a claim that F had effective-value evidence.
            RELEASE.check_branding_boot_properties(self.root, {}, archive, self.identity, None)


class ProductBranding(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name).resolve()
        shutil.copytree(ROOT / "tests/fixtures/lineage-updater", self.root / "packages/apps/Updater")
        shutil.copytree(ROOT / "tests/fixtures/taku-settings-branding", self.root / "packages/apps/Settings")
        def head(command, **kwargs):
            self.assertEqual(command[:2], ["git", "-C"])
            return PATCH.TARGETS[str(pathlib.Path(command[2]).relative_to(self.root))][0] + "\n"
        self.head = mock.patch.object(PATCH.subprocess, "check_output", side_effect=head)
        self.head.start(); self.addCleanup(self.head.stop)

    def test_fixed_sources_apply_v2_then_branding_v4_idempotently(self):
        proof = PATCH.apply(self.root, self.root / "results")
        self.assertFalse(proof["physicalDeviceVerified"])
        config, manifest, _ = PATCH.load_contract()
        for record in manifest["repositories"]:
            for item in record["files"]:
                self.assertEqual(PATCH.digest(self.root / record["repository"] / item["path"]), item["afterSha256"])
        self.assertEqual(PATCH.apply(self.root, self.root / "results"), proof)
        updater = self.root / "packages/apps/Updater/app/src/main"
        self.assertIn('android:versionCode="' + str(CURRENT['updaterVersionCode']) + '"', (updater / "AndroidManifest.xml").read_text())
        self.assertIn('const val VERSION = "' + CURRENT['productVersion'] + '"', (updater / "java/org/lineageos/updater/TakuBrand.kt").read_text())
        card = (updater / "java/org/lineageos/updater/deviceinfo/UpdaterCard.kt").read_text()
        self.assertIn('text = TakuBrand.NAME,', card)
        self.assertIn('label = stringResource(R.string.taku_product_version, TakuBrand.currentVersion)', card)
        self.assertIn('value = stringResource(R.string.header_android_version, androidVersion)', card)
        mapper = (updater / "java/org/lineageos/updater/updates/state/UpdateItemStateMapper.kt").read_text()
        self.assertNotIn('R.string.list_full_install', mapper)
        self.assertIn('buildVersion = TakuBrand.targetDisplayName(update.displayVersion)', mapper)
        self.assertIn('TakuBrand.buildLabel(update.displayVersion, buildDate)', (updater / "java/org/lineageos/updater/updates/action/UpdateActionHandler.kt").read_text())
        self.assertIn('certificate: "platform"', (self.root / "packages/apps/Updater/app/Android.bp").read_text())

    def test_changed_source_partial_patch_and_symlink_refused(self):
        PATCH.apply(self.root, self.root / "results")
        target = self.root / "packages/apps/Settings/res/values/taku_brand.xml"
        original = target.read_bytes()
        target.write_text("drifted source")
        with self.assertRaisesRegex(ValueError, "source drift"):
            PATCH.apply(self.root, self.root / "another-results")
        target.write_bytes(original)
        target.unlink()
        with self.assertRaisesRegex(ValueError, "Partially applied"):
            PATCH.apply(self.root, self.root / "another-results")
        target.symlink_to(ROOT / "image/product-branding.json")
        with self.assertRaisesRegex(ValueError, "Symlink/escape"):
            PATCH.apply(self.root, self.root / "another-results")

    def legacy_source(self):
        base = ROOT / 'image/updater/download-integrity.patch'
        legacy, record, old_patch = PATCH.legacy_contract()
        for repository, patch in (('packages/apps/Updater', base),
                                  ('packages/apps/Updater', old_patch),
                                  ('packages/apps/Settings', ROOT / 'image/branding/settings.patch')):
            subprocess.run(['git', '-C', str(self.root / repository), 'apply', '--no-index',
                            '--whitespace=error', str(patch)], check=True)
        for entry in legacy['repositories']:
            self.assertTrue(PATCH.inspect_repository(self.root, entry)[1])

    def test_known_cached_v3_layer_migrates_to_v4_and_keeps_unrelated_inputs(self):
        self.legacy_source()
        sentinel = self.root / 'packages/apps/Updater/unrelated-user-change.txt'
        sentinel.write_text('preserve unrelated source')
        results = self.root / 'migrated-results'
        proof = PATCH.apply(self.root, results)
        migration = json.loads((results / 'product-branding-migration.json').read_text())
        self.assertEqual(migration['previousManifestSha256'], PATCH.LEGACY_MANIFEST_SHA256)
        self.assertTrue(migration['sourceOnly'])
        self.assertFalse(migration['physicalDeviceVerified'])
        self.assertEqual(sentinel.read_text(), 'preserve unrelated source')
        self.assertEqual(proof, PATCH.apply(self.root, results))
        self.assertEqual(migration, json.loads((results / 'product-branding-migration.json').read_text()))

    def test_exact_cached_v4_migrates_without_resetting_other_sources(self):
        PATCH.BASE['apply'](self.root, self.root / 'download')
        previous, _, _ = PATCH.previous_contract()
        for record in previous['repositories']:
            subprocess.run(['git', '-C', str(self.root / record['repository']), 'apply', '--no-index',
                            str(ROOT / 'image/branding' / record['patch'])], check=True)
        sentinel = self.root / 'packages/apps/Updater/keep-cache.txt'
        sentinel.write_text('retain compiler and unrelated sources')
        proof = PATCH.apply(self.root, self.root / 'from-v4')
        self.assertEqual(proof['productBranding']['productVersion'], CURRENT['productVersion'])
        migration = json.loads((self.root / 'from-v4/product-branding-migration.json').read_text())
        self.assertEqual(migration['previousManifestSha256'], PATCH.PREVIOUS_MANIFEST_SHA256)
        self.assertEqual(sentinel.read_text(), 'retain compiler and unrelated sources')
        self.assertEqual(PATCH.apply(self.root, self.root / 'from-v4'), proof)

    def test_exact_cached_v5_migrates_and_unknown_edits_are_preserved(self):
        PATCH.BASE['apply'](self.root, self.root / 'download')
        previous, _, _ = PATCH.previous_v5_contract()
        for record in previous['repositories']:
            subprocess.run(['git', '-C', str(self.root / record['repository']), 'apply', '--no-index',
                            str(ROOT / 'image/branding' / record['patch'])], check=True)
        target = self.root / 'packages/apps/Updater/app/src/main/java/org/lineageos/updater/TakuBrand.kt'
        original = target.read_bytes()
        target.write_bytes(original + b'\n// preserve unexpected source\n')
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        with self.assertRaisesRegex(ValueError, 'source drift'):
            PATCH.apply(self.root, self.root / 'refused-v5')
        self.assertEqual(before, {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})
        target.write_bytes(original)
        proof = PATCH.apply(self.root, self.root / 'from-v5')
        self.assertEqual(proof['productBranding']['productVersion'], CURRENT['productVersion'])
        migration = json.loads((self.root / 'from-v5/product-branding-migration.json').read_text())
        self.assertEqual(migration['previousManifestSha256'], PATCH.PREVIOUS_V5_MANIFEST_SHA256)
        self.assertEqual(PATCH.apply(self.root, self.root / 'from-v5'), proof)

    def test_cached_v6_migration_retains_predecessor_and_unrelated_files(self):
        PATCH.BASE['apply'](self.root, self.root / 'download')
        previous, _, _ = PATCH.previous_v6_contract()
        frozen = (ROOT / 'image/branding/previous-v6-manifest.json').read_bytes()
        for record in previous['repositories']:
            subprocess.run(['git', '-C', str(self.root / record['repository']), 'apply', '--no-index',
                            str(ROOT / 'image/branding' / record['patch'])], check=True)
        sentinel = self.root / 'packages/apps/Updater/retain-user-source.txt'
        sentinel.write_text('existing unrelated source')
        proof = PATCH.apply(self.root, self.root / 'from-v6')
        self.assertEqual(proof['productBranding']['productVersion'], CURRENT['productVersion'])
        self.assertEqual(proof['productBranding']['updaterVersionCode'], CURRENT['updaterVersionCode'])
        migration = json.loads((self.root / 'from-v6/product-branding-migration.json').read_text())
        self.assertEqual(migration['previousManifestSha256'], PATCH.PREVIOUS_V6_MANIFEST_SHA256)
        self.assertEqual(sentinel.read_text(), 'existing unrelated source')
        self.assertEqual((ROOT / 'image/branding/previous-v6-manifest.json').read_bytes(), frozen)
        self.assertEqual(PATCH.apply(self.root, self.root / 'from-v6'), proof)

    def test_cached_v7_migration_retains_predecessor_and_unrelated_files(self):
        PATCH.BASE['apply'](self.root, self.root / 'download')
        previous, _, _ = PATCH.previous_v7_contract()
        frozen = (ROOT / 'image/branding/previous-v7-manifest.json').read_bytes()
        for record in previous['repositories']:
            subprocess.run(['git', '-C', str(self.root / record['repository']), 'apply', '--no-index',
                            str(ROOT / 'image/branding' / record['patch'])], check=True)
        sentinel = self.root / 'packages/apps/Updater/retain-user-source.txt'
        sentinel.write_text('existing unrelated source')
        proof = PATCH.apply(self.root, self.root / 'from-v7')
        self.assertEqual(proof['productBranding']['productVersion'], CURRENT['productVersion'])
        self.assertEqual(proof['productBranding']['updaterVersionCode'], CURRENT['updaterVersionCode'])
        migration = json.loads((self.root / 'from-v7/product-branding-migration.json').read_text())
        self.assertEqual(migration['previousManifestSha256'], PATCH.PREVIOUS_V7_MANIFEST_SHA256)
        self.assertEqual(sentinel.read_text(), 'existing unrelated source')
        self.assertEqual((ROOT / 'image/branding/previous-v7-manifest.json').read_bytes(), frozen)
        self.assertEqual(PATCH.apply(self.root, self.root / 'from-v7'), proof)

    def test_cached_v8_migration_retains_predecessor_and_unrelated_files(self):
        PATCH.BASE['apply'](self.root, self.root / 'download')
        previous, _, _ = PATCH.previous_v8_contract()
        frozen = (ROOT / 'image/branding/previous-v8-manifest.json').read_bytes()
        for record in previous['repositories']:
            subprocess.run(['git', '-C', str(self.root / record['repository']), 'apply', '--no-index',
                            str(ROOT / 'image/branding' / record['patch'])], check=True)
        sentinel = self.root / 'packages/apps/Updater/retain-user-source.txt'
        sentinel.write_text('existing unrelated source')
        proof = PATCH.apply(self.root, self.root / 'from-v8')
        self.assertEqual(proof['productBranding']['productVersion'], CURRENT['productVersion'])
        self.assertEqual(proof['productBranding']['updaterVersionCode'], CURRENT['updaterVersionCode'])
        migration = json.loads((self.root / 'from-v8/product-branding-migration.json').read_text())
        self.assertEqual(migration['previousManifestSha256'], PATCH.PREVIOUS_V8_MANIFEST_SHA256)
        self.assertEqual(sentinel.read_text(), 'existing unrelated source')
        self.assertEqual((ROOT / 'image/branding/previous-v8-manifest.json').read_bytes(), frozen)
        self.assertEqual(PATCH.apply(self.root, self.root / 'from-v8'), proof)

    def test_cached_v10_migrates_to_current_branding(self):
        PATCH.BASE['apply'](self.root, self.root / 'download')
        previous, _, _ = PATCH.previous_v10_contract()
        for record in previous['repositories']:
            subprocess.run(['git', '-C', str(self.root / record['repository']), 'apply', '--no-index',
                            str(ROOT / 'image/branding' / record['patch'])], check=True)
        results = self.root / 'from-v10'
        proof = PATCH.apply(self.root, results)
        self.assertEqual(proof['productBranding'], CURRENT)
        migration = json.loads((results / 'product-branding-migration.json').read_text())
        self.assertEqual(migration['previousManifestSha256'], PATCH.PREVIOUS_V10_MANIFEST_SHA256)
        self.assertEqual(PATCH.apply(self.root, results), proof)

    def test_unknown_cached_v3_edit_is_rejected_before_any_source_is_changed(self):
        self.legacy_source()
        target = self.root / 'packages/apps/Updater/app/src/main/java/org/lineageos/updater/TakuBrand.kt'
        target.write_text(target.read_text() + '\n// unknown local change\n')
        before = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        with self.assertRaisesRegex(ValueError, 'source drift'):
            PATCH.apply(self.root, self.root / 'refused-results')
        after = {p.relative_to(self.root): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        self.assertEqual(before, after)

    def test_pinned_heads_and_download_integrity_cannot_drift(self):
        PATCH.apply(self.root, self.root / "results")
        with mock.patch.object(PATCH.subprocess, "check_output", return_value="0" * 40):
            with self.assertRaisesRegex(ValueError, "HEAD"):
                PATCH.apply(self.root, self.root / "another-results")
        path = self.root / "packages/apps/Updater/app/src/main/java/org/lineageos/updater/download/DownloadIntegrity.java"
        path.write_text(path.read_text() + "\n")
        with self.assertRaisesRegex(ValueError, "download-integrity"):
            PATCH.apply(self.root, self.root / "another-results")

    def test_product_version_derives_properties_names_and_constants(self):
        config = BRAND.load()
        identity = BRAND.build_identity(config, "20261023000000")
        self.assertEqual(identity["buildDisplayId"], "TakuOS" + CURRENT["productVersion"] + "-20261023000000")
        self.assertEqual(identity["fullOtaFileName"], "TakuOS-" + CURRENT["productVersion"] + "-tokay-20261023000000-full.zip")
        props = BRAND.makefile(config)
        self.assertEqual((ROOT / "image/product-branding.mk").read_text(), props)
        self.assertNotIn("ro.lineage", props)
        self.assertNotIn("ro.build.version", props)
        self.assertIn("ro.build.display.id=TakuOS" + CURRENT["productVersion"] + "-$(BUILD_NUMBER)", props)
        self.assertEqual(props.count("PRODUCT_SYSTEM_EXT_PROPERTIES += "), 3)
        self.assertEqual(props.count("PRODUCT_PRODUCT_PROPERTIES += "), 1)
        self.assertNotIn("PRODUCT_SYSTEM_PROPERTIES", props)
        self.assertNotIn("PRODUCT_BUILD_PROP_OVERRIDES", props)
        for number in ["", "../20261023000000", "20261023", "20261023000000;reboot"]:
            with self.assertRaises(ValueError):
                BRAND.build_identity(config, number)

    def test_full_ota_name_is_a_verified_copy_and_never_relabels_old_archives(self):
        product = self.root / "product"; product.mkdir()
        source = product / "lineage_tokay-ota.zip"; source.write_bytes(b"fixture full OTA; not a ROM")
        original = source.read_bytes()
        output = BRAND.stage_full_ota(product, "20261023000000")
        self.assertEqual(output.read_bytes(), original)
        self.assertEqual(source.read_bytes(), original)
        self.assertNotEqual(output.stat().st_ino, source.stat().st_ino)
        self.assertEqual(BRAND.stage_full_ota(product, "20261023000000"), output)
        output.write_bytes(b"changed output")
        with self.assertRaisesRegex(ValueError, "Existing branded OTA differs"):
            BRAND.stage_full_ota(product, "20261023000000")

    def test_contract_and_display_constants_are_bound_to_one_version(self):
        config = BRAND.load()
        with mock.patch.object(BRAND, "load", return_value={**config, "configSha256": "0" * 64}):
            with self.assertRaisesRegex(ValueError, "contract differs"):
                PATCH.load_contract()

    def test_actual_sqlite_v4_to_v5_preserves_downloads_and_nullable_target_label(self):
        PATCH.apply(self.root, self.root / 'results')
        app = self.root/'packages/apps/Updater'
        schemas = app/'schemas/org.lineageos.updater.data.source.local.UpdatesDatabase'
        old = json.loads((schemas/'4.json').read_text())['database']['entities'][0]
        new = json.loads((schemas/'5.json').read_text())['database']['entities'][0]
        source = (app/'app/src/main/java/org/lineageos/updater/data/source/local/UpdatesDatabase.kt').read_text()
        migration = source.split('private val MIGRATION_4_5 =',1)[1].split('@JvmStatic',1)[0]
        commands = re.findall(r'db\.execSQL\("([^"]+)"\)', migration)
        self.assertEqual(commands, ['ALTER TABLE `updates` ADD COLUMN `display_version` TEXT'])
        path = self.root/'updates.db'
        connection = sqlite3.connect(path)
        connection.execute(old['createSql'].replace('${TABLE_NAME}','updates'))
        columns = [f['columnName'] for f in old['fields']]
        for i in range(2):
            values = [(f['columnName']+str(i)) if f['affinity']=='TEXT' else 100+i for f in old['fields']]
            values[columns.index('version')]='24.0'
            values[columns.index('status')]=i+1
            connection.execute('INSERT INTO updates VALUES ('+','.join('?' for _ in values)+')',values)
        before = connection.execute('SELECT * FROM updates ORDER BY download_id').fetchall()
        for command in commands:connection.execute(command)
        self.assertEqual(connection.execute('SELECT '+','.join('`'+c+'`' for c in columns)+' FROM updates ORDER BY download_id').fetchall(),before)
        self.assertEqual(connection.execute('SELECT display_version FROM updates').fetchall(),[(None,),(None,)])
        fresh = sqlite3.connect(':memory:');fresh.execute(new['createSql'].replace('${TABLE_NAME}','updates'))
        self.assertEqual(connection.execute('PRAGMA table_info(updates)').fetchall(),fresh.execute('PRAGMA table_info(updates)').fetchall())
        connection.execute("UPDATE updates SET display_version='TakuOS1.1' WHERE status=1")
        connection.commit();connection.close()
        reopened = sqlite3.connect(path)
        self.assertEqual(reopened.execute('SELECT version,status,display_version FROM updates ORDER BY status').fetchall(),
                         [('24.0',1,'TakuOS1.1'),('24.0',2,None)])
        reopened.close();fresh.close()


if __name__ == "__main__":
    unittest.main()
