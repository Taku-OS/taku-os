#!/usr/bin/env python3
"""Packaging regressions for the observed privileged-permission boot failure."""
import copy
import io
from pathlib import Path
import runpy
import stat
import unittest
import warnings
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
G = runpy.run_path(str(ROOT / 'image/privapp_permissions.py'))


class PrivilegedPermissions(unittest.TestCase):
    def setUp(self):
        self.policy = (ROOT / G['SOURCE']).read_bytes()
        self.contents = {G['TARGET']: self.policy, G['APK']: b'memory-apk',
                         G['FRAMEWORK']: b'framework-apk',
                         'SYSTEM_EXT/priv-app/TakuAssistant/TakuAssistant.apk': b'assistant-apk',
                         'SYSTEM_EXT/priv-app/TakuSurfaces/TakuSurfaces.apk': b'surfaces-apk',
                         'META/system_ext_filesystem_config.txt': b'system_ext/etc/permissions/TakuMemory.xml 0 0 0644\n'}
        self.badging = {b'memory-apk': "package: name='org.agentos.memory'\nuses-permission: name='android.permission.DUMP'\n",
                        b'assistant-apk': "package: name='org.agentos.assistant'\nuses-permission: name='android.permission.INTERNET'\n",
                        b'surfaces-apk': "package: name='org.agentos.surfaces'\n"}
        self.tree = ('E: permission (line=1)\n A: android:name(0x01010003)="android.permission.DUMP"\n'
                     ' A: android:protectionLevel(0x01010009)=0x00000032\n'
                     'E: permission (line=2)\n A: android:name(0x01010003)="android.permission.INTERNET"\n'
                     ' A: android:protectionLevel(0x01010009)=0x00001000\n')

    def archive(self, extra=()):
        data = io.BytesIO()
        with warnings.catch_warnings(), zipfile.ZipFile(data, 'w') as z:
            warnings.simplefilter('ignore', UserWarning)
            for name, raw in [*self.contents.items(), *extra]:z.writestr(name, raw)
        return zipfile.ZipFile(data)

    def verify(self, extra=(), requested=None):
        with self.archive(extra) as z:
            return G['verify'](z, G['sha'](self.policy), requested if requested is not None else [G['PERMISSION']])

    def test_real_policy_grants_the_complete_current_privileged_request(self):
        proof = self.verify()
        self.assertEqual(proof['grantedPermissions'], ['android.permission.DUMP'])
        definitions = G['permission_definitions'](self.tree)
        with self.archive() as z:
            report = G['audit'](z, definitions, self.badging.__getitem__)
            self.assertTrue(report['applications']['org.agentos.memory']['allRequestedPrivilegedPermissionsAllowed'])
            G['validate_audit'](z, report)
            broken = copy.deepcopy(report);broken['frameworkApkSha256'] = '0'*64
            with self.assertRaisesRegex(ValueError, 'closure differs'):G['validate_audit'](z, broken)
            broken = copy.deepcopy(report);broken['applications']['org.agentos.memory']['requestedPermissions'] = []
            with self.assertRaisesRegex(ValueError, 'closure differs'):G['validate_audit'](z, broken)

    def test_missing_wrong_partition_duplicate_and_linked_policy_fail(self):
        original = dict(self.contents)
        for target in [None, G['TARGET'].replace('SYSTEM_EXT/', 'SYSTEM/')]:
            self.contents = dict(original);self.contents.pop(G['TARGET'])
            if target:self.contents[target] = self.policy
            with self.subTest(target=target), self.assertRaises(ValueError):self.verify()
        self.contents = original
        with self.assertRaises(ValueError):self.verify([(G['TARGET'], self.policy)])
        self.contents.pop(G['TARGET'])
        info = zipfile.ZipInfo(G['TARGET']);info.create_system = 3;info.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaises(ValueError):self.verify([(info, b'elsewhere')])

    def test_wrong_package_deny_missing_and_malformed_xml_fail_even_when_rehashed(self):
        original = self.policy
        for raw in [original.replace(b'org.agentos.memory', b'org.agentos.other'),
                    original.replace(b'<permission ', b'<deny-permission '),
                    original.replace(b'android.permission.DUMP', b'android.permission.INTERNET'), b'<permissions>']:
            self.policy = raw;self.contents[G['TARGET']] = raw
            with self.subTest(raw=raw), self.assertRaises((ValueError, ET.ParseError)):self.verify()

    def test_source_hash_fs_mode_request_and_conflicting_policy_drift_fail(self):
        self.contents[G['TARGET']] += b'\n'
        with self.assertRaisesRegex(ValueError, 'frozen input'):self.verify()
        self.contents[G['TARGET']] = self.policy
        with self.assertRaisesRegex(ValueError, 'request differs'):self.verify(requested=[])
        self.contents['META/system_ext_filesystem_config.txt'] = b'system_ext/etc/permissions/TakuMemory.xml 0 0 0666\n'
        with self.assertRaisesRegex(ValueError, 'ownership/mode'):self.verify()
        self.contents['META/system_ext_filesystem_config.txt'] = b'system_ext/etc/permissions/TakuMemory.xml 0 0 0644\n'
        self.contents['SYSTEM_EXT/etc/permissions/extra.xml'] = self.policy
        with self.assertRaisesRegex(ValueError, 'additional'):self.verify()

    def test_other_taku_app_new_privileged_request_cannot_silently_boot(self):
        self.badging[b'assistant-apk'] += "uses-permission: name='android.permission.DUMP'\n"
        with self.archive() as z, self.assertRaisesRegex(ValueError, 'not fully allowed: org.agentos.assistant'):
            G['audit'](z, G['permission_definitions'](self.tree), self.badging.__getitem__)

    def test_framework_privileged_permissions_with_non_android_prefix_are_audited(self):
        tree=self.tree+'E: permission (line=3)\n A: android:name(0x01010003)="com.android.permission.INSTALL_EXISTING_PACKAGES"\n A: android:protectionLevel(0x01010009)=0x04000012\n'
        self.badging[b'assistant-apk'] += "uses-permission: name='com.android.permission.INSTALL_EXISTING_PACKAGES'\n"
        with self.archive() as z, self.assertRaisesRegex(ValueError, 'not fully allowed: org.agentos.assistant'):
            G['audit'](z, G['permission_definitions'](tree), self.badging.__getitem__)

    def test_optional_surface_app_is_audited_only_when_actually_installed(self):
        self.contents.pop('SYSTEM_EXT/priv-app/TakuSurfaces/TakuSurfaces.apk')
        with self.archive() as z:
            report=G['audit'](z,G['permission_definitions'](self.tree),self.badging.__getitem__)
            self.assertEqual(set(report['applications']),{'org.agentos.assistant','org.agentos.memory'})


if __name__ == '__main__':unittest.main()
