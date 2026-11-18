#!/usr/bin/env python3
"""Host gates for pinned native inputs and archives; not phone/GMS acceptance."""
import hashlib
import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest
import zipfile
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
G = runpy.run_path(str(ROOT / 'image/google_services.py'))


class GoogleServices(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.lock, self.checksum = G['load_lock']()
        self.small = {'sourceFiles': [{'path': 'arm64/Android.bp', 'bytes': 5,
                                      'sha256': hashlib.sha256(b'pinned').hexdigest(), 'executable': False}]}
        self.small['sourceFiles'][0]['bytes'] = len(b'pinned')
        (self.root / 'arm64').mkdir(); (self.root / 'arm64/Android.bp').write_bytes(b'pinned')
        self.small['commit'] = 'a' * 40

    def test_real_lock_contains_google_core_and_no_binary_in_git(self):
        expected = {i['path']: i for i in self.lock['installedFiles']}
        self.assertEqual(len(self.lock['sourceFiles']), 97)
        for name in ('PRODUCT/priv-app/Phonesky/Phonesky.apk',
                     'SYSTEM_EXT/priv-app/GoogleServicesFramework/GoogleServicesFramework.apk',
                     'SYSTEM_EXT/priv-app/SetupWizard/SetupWizard.apk',
                     'PRODUCT/apex/com.google.android.gmssystem.prodvic.apex'):
            self.assertTrue(expected[name]['exactBytes'])
        self.assertEqual(len(expected['PRODUCT/apex/com.google.android.gmssystem.prodvic.apex']['inputs']), 2)
        self.assertNotIn('PRODUCT/priv-app/VelvetTitan/VelvetTitan.apk', expected)

    def test_input_drift_extra_file_and_symlinks_rejected(self):
        self.assertEqual(G['verify_source'](self.root, self.small)['files'], 1)
        (self.root / 'arm64/Android.bp').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'differs'):
            G['verify_source'](self.root, self.small)
        (self.root / 'arm64/Android.bp').write_bytes(b'pinned')
        (self.root / 'extra').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'file set'):
            G['verify_source'](self.root, self.small)
        (self.root / 'extra').unlink(); (self.root / 'linked').symlink_to(self.root / 'arm64')
        with self.assertRaisesRegex(ValueError, 'symlinks'):
            G['verify_source'](self.root, self.small)

    def test_historical_snapshot_has_no_implicit_new_input(self):
        self.assertIsNone(G['build_mount'](self.root, self.root))
        with self.assertRaisesRegex(ValueError, 'Missing Google dependency'):
            G['verify_source'](self.root / 'missing', self.small)

    def artifact(self, change=None, omit=None, extra=None, duplicate='PrebuiltGmsCoreVic.apk'):
        raw = io.BytesIO()
        tiny = dict(self.lock)
        tiny['installedFiles'] = []
        with zipfile.ZipFile(raw, 'w') as z:
            z.writestr('META/apkcerts.txt', '\n'.join('name="' + Path(r['path']).name +
                '" certificate="PRESIGNED" private_key="" partition="' + r['path'].split('/')[0].lower() + '"'
                for r in self.lock['installedFiles'] if r['path'].endswith('.apk')) + '\n' +
                'name="framework-res.apk" certificate="platform.x509.pem" private_key="platform.pk8" partition="system"\n' +
                'name="TakuGoogleAssistantOverlay.apk" certificate="platform.x509.pem" private_key="platform.pk8" partition="system_ext"' + '\n' +\
                '\n'.join('name="' + duplicate + '" certificate="platform.x509.pem" private_key="platform.pk8" partition="' + part + '"' for part in ('system','product')))
            z.writestr('META/apexkeys.txt', 'name="com.google.android.gmssystem.prodvic.apex" '
                'public_key="PRESIGNED" private_key="PRESIGNED" container_certificate="PRESIGNED" '
                'container_private_key="PRESIGNED" partition="product"')
            for record in self.lock['installedFiles']:
                name = record['path']; content = ('fixture:' + name).encode()
                if name.endswith('.so'):
                    content = b'\x7fELF\x02\x01' + bytes(12) + b'\xb7\x00' + bytes(44)
                if name.endswith('.jar'):
                    container = io.BytesIO()
                    with zipfile.ZipFile(container, 'w') as jar: jar.writestr('classes.dex', b'dex fixture')
                    content = container.getvalue()
                tiny['installedFiles'].append(record | {'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
                if name != omit:
                    z.writestr(name, b'changed' if name == change else content)
            for module in self.lock['overlays']:
                z.writestr('PRODUCT/overlay/' + module + '/' + module + '.apk', b'compiled fixture')
            module = self.lock['takuAssistantOverlay']
            z.writestr('SYSTEM_EXT/overlay/' + module + '/' + module + '.apk', b'compiled fixture')
            if extra: z.writestr(extra, b'not allowed')
        raw.seek(0)
        return zipfile.ZipFile(raw), tiny

    def test_archive_requires_all_google_core_bytes(self):
        archive, tiny = self.artifact()
        with archive:
            self.assertEqual(len(G['artifact_records'](archive, tiny)), len(tiny['installedFiles']) + 5)
        name = 'PRODUCT/apex/com.google.android.gmssystem.prodvic.apex'
        archive, tiny = self.artifact(change=name)
        with archive, self.assertRaisesRegex(ValueError, 'bytes changed'):
            G['artifact_records'](archive, tiny)
        archive, tiny = self.artifact(omit=name)
        with archive, self.assertRaisesRegex(ValueError, 'Missing native Google'):
            G['artifact_records'](archive, tiny)

    def test_only_consumed_real_google_certificates_require_unique_rows(self):
        archive,tiny=self.artifact()
        with archive: G['artifact_records'](archive,tiny) # duplicate APEX dummy is not a real APK trust source
        archive,tiny=self.artifact(duplicate='Phonesky.apk')
        with archive, self.assertRaisesRegex(ValueError,'duplicate Google signing metadata: Phonesky.apk'):
            G['artifact_records'](archive,tiny)

    def test_external_restore_and_overlay_config_require_review(self):
        for path in ('SYSTEM/addon.d/30-gapps.sh', 'PRODUCT/overlay/config/config.xml'):
            archive, tiny = self.artifact(extra=path)
            with archive, self.assertRaisesRegex(ValueError, 'explicit review'):
                G['artifact_records'](archive, tiny)

    def test_assistant_overlay_requires_one_unqualified_taku_value(self):
        verify=G['verify_artifacts']
        inputs={'googleServices':{'commit':self.lock['commit'],'lockSha256':self.checksum,
            'mountPath':'/src/vendor/gapps','readOnly':True}}
        (self.root/'run-input.json').write_text(json.dumps(inputs))
        manifest='package="org.agentos.gapps.overlay"\n A: android:targetPackage="android"\n A: android:isStatic=(type 0x12)0xffffffff\n'
        for values,valid in [('() "org.agentos.agent"',True),
                             ('() "com.google.android.googlequicksearchbox"\n  (en) "org.agentos.agent"',False),
                             ('(en) "org.agentos.agent"',False)]:
            archive,tiny=self.artifact()
            with archive, mock.patch.dict(verify.__globals__,{'load_lock':lambda p:(tiny,self.checksum),
                    'verify_source':lambda *args:None}), \
                    mock.patch.object(verify.__globals__['subprocess'],'check_output',side_effect=[manifest,
                        'resource 0x7f010000 string/config_defaultAssistant\n  '+values+'\n'
                        'resource 0x7f010001 string/config_forceVoiceInteractionServicePackage\n  () "org.agentos.agent"\n']):
                if valid:
                    proof=verify(archive,self.root,inputs,Path('/unused-aapt2'),root=self.root)
                    self.assertTrue(proof['artifactVerified'])
                else:
                    with self.assertRaisesRegex(ValueError,'default-assistant overlay differs'):
                        verify(archive,self.root,inputs,Path('/unused-aapt2'),root=self.root)

    def test_actual_android17_aapt2_boolean_format(self):
        raw=(ROOT/'tests/fixtures/google-services/assistant-overlay-aapt2.txt').read_text()
        resources,manifest=raw.split('N: android=',1);manifest='N: android='+manifest
        verify=G['verify_artifacts'];archive,tiny=self.artifact()
        inputs={'googleServices':{'commit':self.lock['commit'],'lockSha256':self.checksum,
            'mountPath':'/src/vendor/gapps','readOnly':True}}
        (self.root/'run-input.json').write_text(json.dumps(inputs))
        with archive, mock.patch.dict(verify.__globals__,{'load_lock':lambda p:(tiny,self.checksum),
                'verify_source':lambda *args:None}), \
                mock.patch.object(verify.__globals__['subprocess'],'check_output',side_effect=[manifest,resources]):
            self.assertTrue(verify(archive,self.root,inputs,Path('/unused'),root=self.root)['artifactVerified'])

    def test_google_cannot_disappear_from_next_ota(self):
        import sys
        sys.path.insert(0, str(ROOT / 'scripts'))
        import ota_release
        with self.assertRaisesRegex(ota_release.ReleaseError, 'cannot drop integrated Google'):
            ota_release.check_product_upgrade({'googleServicesIntegration': {'artifactVerified': True}}, {})
        ota_release.check_product_upgrade({}, {'googleServicesIntegration': {'artifactVerified': True}})


if __name__ == '__main__':
    unittest.main()
