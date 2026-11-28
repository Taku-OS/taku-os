#!/usr/bin/env python3
"""Dependency/crypto evidence boundaries; these tests never claim ROM or UI acceptance."""
import copy
import fcntl
import hashlib
import io
import json
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
G = runpy.run_path(str(ROOT / 'image/google_apps.py'))


class InternalGoogleApps(unittest.TestCase):
    def test_pixel_weather_install_paths_share_the_same_package(self):
        widget = json.loads((ROOT / 'system/desktop-widgets/pixel-weather.lock.json').read_text())
        image, _ = G['load_lock']()
        app = next(a for a in image['apps'] if a['packageName'] == widget['packageName'])
        self.assertEqual(widget['versionCode'], app['versionCode'])
        self.assertEqual(widget['apkSha256'], app['sha256'])
        self.assertEqual([widget['signerSha256']], app['signerSha256'])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.real, _ = G['load_lock']()
        self.lock = copy.deepcopy(self.real)
        self.apks = {}
        for app in self.lock['apps']:
            raw = io.BytesIO()
            with zipfile.ZipFile(raw, 'w') as z: z.writestr('classes.dex', ('fixture:' + app['id']).encode())
            self.apks[app['id']] = raw.getvalue()
            app.update(bytes=len(raw.getvalue()), sha256=hashlib.sha256(raw.getvalue()).hexdigest())
        generated = G['generated_files'](self.lock)
        self.lock['sourceFiles'] = sorted(
            [{'path': a['apk'], 'bytes': a['bytes'], 'sha256': a['sha256']} for a in self.lock['apps']]
            + [{'path': n, 'bytes': len(b), 'sha256': hashlib.sha256(b).hexdigest()} for n, b in generated.items()],
            key=lambda v: v['path'])
        self.lock_path = self.root / 'google-apps.lock.json'
        (self.root / 'google-services.lock.json').write_bytes((ROOT / 'image/google-services.lock.json').read_bytes())
        self.write_lock()

    def write_lock(self):
        self.lock_path.write_text(json.dumps(self.lock, indent=2) + '\n')
        return G['load_lock'](self.lock_path)

    def test_real_contract_is_internal_and_does_not_replace_core(self):
        self.assertEqual(self.real['coreLockSha256'], G['digest'](ROOT / 'image/google-services.lock.json'))
        self.assertEqual({a['packageName'] for a in self.real['apps']}, set(G['PACKAGES'].values()))
        self.assertEqual(self.real['distribution'], 'internal-test-only')
        bp = G['generated_files'](self.real)['Android.bp'].decode()
        self.assertIn('soong_namespace {}', bp)
        self.assertIn('legacy_proprietary', bp)
        self.assertEqual(bp.count('presigned: true'), len(G['PACKAGES']))
        self.assertEqual(bp.count('preprocessed: true'), len(G['PACKAGES']))
        self.assertNotIn('skip_preprocessed_apk_checks', bp)

    def test_default_product_and_audio_launcher_are_included(self):
        product = (ROOT / 'image/taku-core.mk').read_text()
        self.assertIn('$(call inherit-product, vendor/taku/image/google-apps.mk)', product)
        self.assertIn('TakuAudioEngine', product)
        apps = {a['id']: a for a in self.real['apps']}
        self.assertEqual(apps['pixel_weather']['packageName'], 'com.google.android.apps.weather')
        self.assertEqual(apps['youtube_music']['packageName'], 'com.google.android.apps.youtube.music')
        audio = json.loads((ROOT / 'system/audio-engine/rom-input-lock.json').read_text())
        self.assertIn('system/audio-engine/src/org/agentos/audio/AudioHomeActivity.kt', audio['sourceInputs'])
        for app_id in ('pixel_weather', 'youtube_music'):
            original = copy.deepcopy(self.lock)
            next(a for a in self.lock['apps'] if a['id'] == app_id)['signerSha256'] = ['b' * 64]
            with self.subTest(app=app_id), self.assertRaisesRegex(ValueError, 'publisher'):
                self.write_lock()
            self.lock = original

    def test_staging_extracts_only_pinned_apk_and_rejects_bad_transport(self):
        stage = runpy.run_path(str(ROOT / 'scripts/google-apps.py'))['stage_apk']
        app = copy.deepcopy(next(a for a in self.lock['apps'] if a['id'] == 'youtube_music'))
        payload = self.apks[app['id']]
        transport = io.BytesIO()
        with zipfile.ZipFile(transport, 'w') as z:
            z.writestr(app['source']['entry'], payload)
            z.writestr('install.sh', 'exit 99 # must never execute')
            z.writestr('overlay/Other.apk', b'unapproved')
        raw = transport.getvalue()
        app['source'].update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
        cache = self.root / 'out/dependencies/google-apps-downloads'; cache.mkdir(parents=True)
        archive = cache / (app['source']['sha256'] + '.zip'); archive.write_bytes(raw)
        with mock.patch.dict(stage.__globals__, {'ROOT': self.root}):
            target = stage(app, self.root / 'selected')
            self.assertEqual(target.read_bytes(), payload)
            self.assertEqual([p.relative_to(self.root / 'selected').as_posix()
                              for p in (self.root / 'selected').rglob('*') if p.is_file()], [app['apk']])
            archive.write_bytes(b'corrupt transport')
            with self.assertRaisesRegex(ValueError, 'transport changed'):
                stage(app, self.root / 'rejected')

    def test_scope_core_wrong_publisher_and_architecture_rejected(self):
        original = copy.deepcopy(self.lock)
        for change in ['scope', 'core', 'publisher', 'architecture', 'missing']:
            self.lock = copy.deepcopy(original)
            if change == 'scope': self.lock['distribution'] = 'public'
            if change == 'core': self.lock['coreLockSha256'] = 'a' * 64
            if change == 'publisher': self.lock['apps'][0]['signerSha256'] = ['b' * 64]
            if change == 'architecture': self.lock['apps'][0]['nativeAbis'] = ['x86_64']
            if change == 'missing': self.lock['apps'].pop()
            with self.subTest(change=change), self.assertRaises(ValueError): self.write_lock()

    def test_legacy_five_app_lock_is_only_accepted_for_archive_verification(self):
        self.lock['schema'] = 'taku.internal-google-apps/1'
        self.lock['apps'] = [a for a in self.lock['apps'] if a['id'] in G['LEGACY_PACKAGES']]
        self.lock['sourceFiles'] = sorted(
            [{'path': a['apk'], 'bytes': a['bytes'], 'sha256': a['sha256']} for a in self.lock['apps']]
            + [{'path': n, 'bytes': len(b), 'sha256': hashlib.sha256(b).hexdigest()}
               for n, b in G['generated_files'](self.lock).items()], key=lambda v: v['path'])
        self.lock_path.write_text(json.dumps(self.lock, indent=2) + '\n')
        with self.assertRaises(ValueError): G['load_lock'](self.lock_path)
        restored, _ = G['load_lock'](self.lock_path, allow_legacy=True)
        self.assertEqual({a['id'] for a in restored['apps']}, G['LEGACY_PACKAGES'])

    def test_exact_source_set_hashes_and_no_symlinks(self):
        cache = self.root / 'cache'; cache.mkdir()
        for name, raw in G['generated_files'](self.lock).items(): (cache / name).write_bytes(raw)
        for app in self.lock['apps']:
            p = cache / app['apk']; p.parent.mkdir(exist_ok=True); p.write_bytes(self.apks[app['id']])
        self.assertEqual(G['verify_source'](cache, self.lock)['files'], len(G['PACKAGES']) + 3)
        extra = cache / 'unreviewed.apk'; extra.write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'file set'): G['verify_source'](cache, self.lock)
        extra.unlink()
        p = cache / self.lock['apps'][0]['apk']; p.write_bytes(b'tamper')
        with self.assertRaisesRegex(ValueError, 'changed'): G['verify_source'](cache, self.lock)
        p.unlink(); p.symlink_to(cache / 'Android.bp')
        with self.assertRaisesRegex(ValueError, 'Symlink'): G['verify_source'](cache, self.lock)

    def badging(self, app, minimum='minSdkVersion'):
        return (f"package: name='{app['packageName']}' versionCode='{app['versionCode']}' versionName='{app['versionName']}'\n"
                f"{minimum}:'{app['minSdk']}'\ntargetSdkVersion:'{app['targetSdk']}'\n"
                + ''.join("uses-library:'" + s + "'\n" for s in app['usesLibraries'])
                + ''.join("uses-library-not-required:'" + s + "'\n" for s in app['optionalUsesLibraries'])
                + 'native-code: ' + ' '.join("'" + s + "'" for s in app['nativeAbis']) + '\n')

    def test_manifest_versions_dependency_order_and_complete_variants(self):
        app = next(a for a in self.lock['apps'] if a['id'] == 'gmail')
        xml = 'android.intent.action.MAIN android.intent.category.LAUNCHER'
        for version_tag in ['sdkVersion', 'minSdkVersion']:
            parsed = G['manifest_record'](self.badging(app, version_tag), xml, app)
            self.assertEqual(parsed['optionalUsesLibraries'], app['optionalUsesLibraries'])
            self.assertEqual(parsed['optionalUsesLibraries'][0], 'org.apache.http.legacy')
        for suffix in ['E: uses-split', 'E: uses-static-library', 'A: isSplitRequired=(type 0x12)0xffffffff']:
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, 'complete original variant'):
                G['manifest_record'](self.badging(app), xml + '\n' + suffix, app)
        with self.assertRaisesRegex(ValueError, 'manifest differs'):
            G['manifest_record'](self.badging(app).replace("'arm64-v8a'", "'x86_64'"), xml, app)

    def test_google_signers_exclude_source_stamp_and_handle_platform_tool(self):
        app = self.lock['apps'][0]; approved = app['signerSha256'][0]
        stamp = 'Source Stamp Signer certificate SHA-256 digest: ' + 'e' * 64 + '\n'
        for prefix in ['Signer #1', 'V3.0 Signer:', 'V3.1 Signer:']:
            self.assertEqual(G['signer_record'](prefix + ' certificate SHA-256 digest: ' + approved + '\n' + stamp, app), [approved])
        for text in [stamp, 'Signer #1 certificate SHA-256 digest: ' + 'e' * 64 + '\n']:
            with self.assertRaisesRegex(ValueError, 'approved Google publisher'): G['signer_record'](text, app)

    def archive(self, missing=False, tamper=False, resign=False, extra=False):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as z:
            z.writestr('PRODUCT/etc/build.prop', 'ro.taku.image.distribution=internal-test-only\n')
            rows = []
            for i, app in enumerate(self.lock['apps']):
                rows.append(f'name="{app["module"]}.apk" certificate="' + ('platform' if resign and i == 0 else 'PRESIGNED') + '" private_key="" partition="product"')
                if not missing or i:
                    z.writestr('PRODUCT/app/' + app['module'] + '/' + app['module'] + '.apk',
                               b'tamper' if tamper and i == 0 else self.apks[app['id']])
            z.writestr('META/apkcerts.txt', '\n'.join(rows))
            if extra: z.writestr('PRODUCT/app/TakuInternalOther/TakuInternalOther.apk', b'extra')
        raw.seek(0)
        return zipfile.ZipFile(raw)

    def test_archive_requires_all_original_presigned_bytes(self):
        with self.archive() as z: self.assertEqual(len(G['artifact_records'](z, self.lock)), len(G['PACKAGES']))
        for option in ['missing', 'tamper', 'resign', 'extra']:
            with self.subTest(option=option), self.archive(**{option: True}) as z, self.assertRaises(ValueError):
                G['artifact_records'](z, self.lock)

    def test_archived_report_cannot_borrow_other_inputs_or_signatures(self):
        lock, checksum = self.write_lock(); binding = G['binding'](lock, checksum)
        with self.archive() as z:
            records = G['artifact_records'](z, lock)
            for row, app in zip(records, lock['apps']):
                badging = self.badging(app); xml = 'android.intent.action.MAIN android.intent.category.LAUNCHER'
                row.update(manifest=G['manifest_record'](badging, xml, app), signers=app['signerSha256'],
                           signatureVerified=True, zipAligned4KiB=True, aaptBadging=badging, aaptManifest=xml,
                           signatureOutput='Signer #1 certificate SHA-256 digest: ' + app['signerSha256'][0] + '\n')
            report = {'schema': 'taku.internal-google-apps-artifacts/1', 'distribution': 'internal-test-only',
                      'lockSha256': checksum, 'coreLockSha256': lock['coreLockSha256'], 'files': records,
                      'artifactVerified': True, 'physicalVerified': False}
            proof = {'report': 'google-apps-artifacts.json', 'reportSha256': 'a' * 64, **binding,
                     'artifactVerified': True, 'physicalVerified': False}
            entries = {'image/google-apps.lock.json': checksum}
            G['validate_saved'](z, report, self.lock_path, {'googleApps': binding, 'distribution': 'internal-test-only'}, entries, proof, 'a' * 64)
            report['files'][0]['signatureOutput'] = 'Signer #1 certificate SHA-256 digest: ' + 'd' * 64 + '\n'
            with self.assertRaisesRegex(ValueError, 'approved Google publisher'):
                G['validate_saved'](z, report, self.lock_path, {'googleApps': binding, 'distribution': 'internal-test-only'}, entries, proof, 'a' * 64)

    def test_preview_controller_holds_source_operation_lock(self):
        controller = runpy.run_path(str(ROOT / 'scripts/pixel9-image.py'))
        main = controller['main']; observed = []
        def dispatch(args):
            self.assertEqual(args.action, 'google-apps-preview')
            with (self.root / 'source-operation.lock').open('a') as second:
                with self.assertRaises(BlockingIOError): fcntl.flock(second, fcntl.LOCK_EX | fcntl.LOCK_NB)
            observed.append(True)
        with mock.patch.dict(main.__globals__, {'OUT': self.root, 'dispatch': dispatch}), \
                mock.patch.object(sys, 'argv', ['pixel9-image.py', 'google-apps-preview', '--from-snapshot', 'a' * 64]):
            main()
        self.assertEqual(observed, [True])


class DefaultAppInstall(unittest.TestCase):
    def test_missing_app_is_a_fresh_install_not_a_failed_update(self):
        check = runpy.run_path(str(ROOT / 'scripts/install-default-apps.py'))['existing_google_app']
        command = mock.Mock(return_value='')
        with mock.patch.dict(check.__globals__, {'run': command}):
            self.assertIsNone(check(['adb', '-s', 'emulator-5594'],
                                   {'package': 'com.google.android.apps.weather'}, Path('/sdk'), Path('/output')))
        self.assertEqual(command.call_count, 1)
        self.assertNotIn('path', command.call_args.args)

    def test_preflight_rejects_incompatible_devices_before_install(self):
        check = runpy.run_path(str(ROOT / 'scripts/install-default-apps.py'))['check_device']
        with mock.patch.dict(check.__globals__, {'run': mock.Mock()}) as _:
            with self.assertRaisesRegex(ValueError, 'explicit development emulator'):
                check('adb', 'physical-serial')
            check.__globals__['run'].assert_not_called()
        cases = [(['device', '35'], 'API 36'),
                 (['device', '37', 'x86_64'], 'ARM64'),
                 (['device', '37', 'arm64-v8a', ''], 'Missing Google runtime'),
                 (['device', '37', 'arm64-v8a', 'package:gms', 'package:gsf', ''], 'Missing Google runtime')]
        for responses, error in cases:
            command = mock.Mock(side_effect=responses)
            with self.subTest(error=error), mock.patch.dict(check.__globals__, {'run': command}):
                with self.assertRaisesRegex(ValueError, error): check('adb', 'emulator-5594')
                self.assertFalse(any('install' in c.args or 'root' in c.args for c in command.call_args_list))


if __name__ == '__main__': unittest.main()
