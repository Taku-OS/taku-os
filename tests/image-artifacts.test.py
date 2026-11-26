#!/usr/bin/env python3
"""Package contract tests, not simulated phone acceptance."""
import hashlib,io,json,pathlib,struct,subprocess,sys,tempfile,unittest,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
BUILD_NUMBER='20261020010000'
BUILD_TIMESTAMP='1792458000'
# Pixel products retain a stock fingerprint independently of our build number.
BUILD_FINGERPRINT='google/tokay/tokay:17/CP2A.260805.005/stock-id:user/release-keys'
# Public AOSP test certificate, not a private key. Its DER fingerprint is fixed.
# platform/build android17-release/target/product/security/testkey.x509.pem
AOSP_TEST_CERTIFICATE='''-----BEGIN CERTIFICATE-----
MIIEqDCCA5CgAwIBAgIJAJNurL4H8gHfMA0GCSqGSIb3DQEBBQUAMIGUMQswCQYD
VQQGEwJVUzETMBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4g
VmlldzEQMA4GA1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UE
AxMHQW5kcm9pZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTAe
Fw0wODAyMjkwMTMzNDZaFw0zNTA3MTcwMTMzNDZaMIGUMQswCQYDVQQGEwJVUzET
MBEGA1UECBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4G
A1UEChMHQW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9p
ZDEiMCAGCSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbTCCASAwDQYJKoZI
hvcNAQEBBQADggENADCCAQgCggEBANaTGQTexgskse3HYuDZ2CU+Ps1s6x3i/waM
qOi8qM1r03hupwqnbOYOuw+ZNVn/2T53qUPn6D1LZLjk/qLT5lbx4meoG7+yMLV4
wgRDvkxyGLhG9SEVhvA4oU6Jwr44f46+z4/Kw9oe4zDJ6pPQp8PcSvNQIg1QCAcy
4ICXF+5qBTNZ5qaU7Cyz8oSgpGbIepTYOzEJOmc3Li9kEsBubULxWBjf/gOBzAzU
RNps3cO4JFgZSAGzJWQTT7/emMkod0jb9WdqVA2BVMi7yge54kdVMxHEa5r3b97s
zI5p58ii0I54JiCUP5lyfTwE/nKZHZnfm644oLIXf6MdW2r+6R8CAQOjgfwwgfkw
HQYDVR0OBBYEFEhZAFY9JyxGrhGGBaR0GawJyowRMIHJBgNVHSMEgcEwgb6AFEhZ
AFY9JyxGrhGGBaR0GawJyowRoYGapIGXMIGUMQswCQYDVQQGEwJVUzETMBEGA1UE
CBMKQ2FsaWZvcm5pYTEWMBQGA1UEBxMNTW91bnRhaW4gVmlldzEQMA4GA1UEChMH
QW5kcm9pZDEQMA4GA1UECxMHQW5kcm9pZDEQMA4GA1UEAxMHQW5kcm9pZDEiMCAG
CSqGSIb3DQEJARYTYW5kcm9pZEBhbmRyb2lkLmNvbYIJAJNurL4H8gHfMAwGA1Ud
EwQFMAMBAf8wDQYJKoZIhvcNAQEFBQADggEBAHqvlozrUMRBBVEY0NqrrwFbinZa
J6cVosK0TyIUFf/azgMJWr+kLfcHCHJsIGnlw27drgQAvilFLAhLwn62oX6snb4Y
LCBOsVMR9FXYJLZW2+TcIkCRLXWG/oiVHQGo/rWuWkJgU134NDEFJCJGjDbiLCpe
+ZTWHdcwauTJ9pUbo8EvHRkU3cYfGmLaLfgn9gP+pWA7LFQNvXwBnDa6sppCccEX
31I828XzgXpJ4O+mDL1/dBd+ek8ZPUP0IgdyZm5MTYPhvVqGCHzzTy3sIeJFymwr
sBbmg2OAUNLEMO6nwmocSdN2ClirfxqCzJOLSDE4QyS9BAH6EhY6UFcOaE0=
-----END CERTIFICATE-----
'''
class ArtifactBoundary(unittest.TestCase):
    def fixture(self,device='tokay',omit=None,google=False,system_device=None,vendor_device=None,variant='userdebug',stale_agent=False,release='17',sdk='37',ota_sdk='37',build_incremental=BUILD_NUMBER,omit_identity=None,ota_changes=None,run_input=True,product_incremental=None,certificate=None,vbmeta_flags=0):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        base=pathlib.Path(temp.name);product=base/'product';results=base/'results';results.mkdir()
        if run_input:
            (results/'run-input.json').write_text(json.dumps({'buildNumber':BUILD_NUMBER,'snapshotDigest':'a'*64,'sourceCommit':'b'*40}))
        folder=product/'obj/PACKAGING/target_files_intermediates';folder.mkdir(parents=True)
        with zipfile.ZipFile(folder/'target.zip','w') as z:
            system_props='ro.product.system.device='+(system_device or device)+'\nro.build.type='+variant+'\n'
            if release is not None:system_props+='ro.build.version.release='+release+'\n'
            if sdk is not None:system_props+='ro.build.version.sdk='+sdk+'\n'
            for key,value in {'ro.build.version.incremental':build_incremental,'ro.build.fingerprint':BUILD_FINGERPRINT,'ro.build.date.utc':BUILD_TIMESTAMP,'ro.build.version.security_patch':'2026-10-01'}.items():
                if key!=omit_identity:system_props+=key+'='+value+'\n'
            z.writestr('SYSTEM/build.prop',system_props)
            product_props='ro.product.product.device='+device+'\nro.product.build.type='+variant+'\n'
            if product_incremental is not None:product_props+='ro.build.version.incremental='+product_incremental+'\n'
            if omit!='gesture-default':product_props+='ro.boot.vendor.overlay.theme=com.android.internal.systemui.navbar.gestural\n'
            z.writestr('PRODUCT/etc/build.prop',product_props)
            for overlay in ['PRODUCT/overlay/NavigationBarModeGestural/NavigationBarModeGesturalOverlay.apk','SYSTEM_EXT/overlay/TakuGestureOverlay/TakuGestureOverlay.apk']:
                if omit!=overlay.rsplit('/',1)[1]:z.writestr(overlay,b'overlay fixture')
            z.writestr('VENDOR/build.prop','ro.product.vendor.device='+(vendor_device or device)+'\n')
            z.writestr('META/misc_info.txt','ab_update=true\ndefault_system_dev_certificate=build/make/target/product/security/testkey\navb_enable=true\navb_vbmeta_algorithm=SHA256_RSA4096\n')
            z.writestr('META/ab_partitions.txt','boot\nsystem\nsystem_ext\nproduct\nvendor\n')
            z.writestr('META/apkcerts.txt','name="TakuAssistant.apk" certificate="platform.x509.pem"\n')
            if certificate is not None:
                memory=io.BytesIO()
                with zipfile.ZipFile(memory,'w') as nested:nested.writestr('testkey.x509.pem',certificate)
                z.writestr('SYSTEM/etc/security/otacerts.zip',memory.getvalue())
            for name in ['boot','init_boot','vendor_boot','vendor_kernel_boot','dtbo','system','system_ext','product','vendor','vendor_dlkm','system_dlkm','vbmeta']:
                content=b'contract fixture only'
                if name=='vbmeta':
                    content=bytearray(256);content[:4]=b'AVB0';struct.pack_into('>I',content,120,vbmeta_flags)
                if name!=omit:z.writestr('IMAGES/'+name+'.img',content)
            for name in ['priv-app/TakuAssistant/TakuAssistant.apk','framework/agentos-task-broker.jar','framework/agentos-virtual-display.jar','framework/agentos-resolver-bridge.jar','bin/phone-display','bin/taku-device','bin/device-probe.sh','bin/taku-runtime-start','etc/codex/codex.tar.gz','bin/magpie','bin/magpie-runtime','etc/init/taku-magpie.rc','etc/magpie-LICENSE']:
                content=b'contract fixture only'
                if name.endswith('TakuAssistant.apk') and not stale_agent:
                    memory=io.BytesIO()
                    with zipfile.ZipFile(memory,'w') as nested:
                        for entry in ['assets/native/protocol.json','assets/skills/taku-app-development/SKILL.md','assets/skills/taku-app-development/references/native.md','res/raw/taku_harness.json']:
                            nested.writestr(entry,(ROOT/'system/assistant'/entry).read_bytes())
                    content=memory.getvalue()
                if name.endswith('agentos-task-broker.jar'):
                    memory=io.BytesIO()
                    with zipfile.ZipFile(memory,'w') as nested:
                        nested.writestr('classes.dex',b'fixture strings only\n'+b'\n'.join(('Lorg/agentos/lab/'+c+';').encode() for c in ['TurnProcesses','HarnessContext','SkillCommunity','NativeBridge','PhoneNativeTools','ModelConnection']))
                    content=memory.getvalue()
                z.writestr('SYSTEM_EXT/'+name,content)
            for name in ['SYSTEM_EXT/app/TakuNotebook/TakuNotebook.apk','SYSTEM_EXT/etc/default-permissions/taku-notebook.xml']:
                if omit!=name:z.writestr(name,b'contract fixture only')
            z.writestr('SYSTEM/etc/resolv.conf','nameserver 127.0.0.1\n')
            if google:z.writestr('PRODUCT/priv-app/NexusLauncher/NexusLauncher.apk',b'forbidden package fixture')
        with zipfile.ZipFile(product/'test-ota.zip','w') as z:
            metadata={'pre-device':device,'ota-type':'AB','post-build':BUILD_FINGERPRINT,
                      'post-build-incremental':BUILD_NUMBER,'post-timestamp':BUILD_TIMESTAMP,
                      'post-security-patch-level':'2026-10-01'}
            if ota_sdk is not None:metadata['post-sdk-level']=ota_sdk
            for key,value in (ota_changes or {}).items():
                if value is None:metadata.pop(key,None)
                else:metadata[key]=value
            z.writestr('META-INF/com/android/metadata',''.join(key+'='+value+'\n' for key,value in metadata.items()))
            z.writestr('payload.bin',b'contract fixture only')
        return product,results
    def invoke(self,**kw):
        product,results=self.fixture(**kw)
        p=subprocess.run([sys.executable,str(ROOT/'image/verify-artifacts.py'),str(product),str(results)],capture_output=True,text=True)
        return p,results
    def test_gesture_navigation_is_part_of_the_image(self):
        for omit,message in [('gesture-default','Gesture navigation is not the default'),
                             ('NavigationBarModeGesturalOverlay.apk','Missing navigation integration'),
                             ('TakuGestureOverlay.apk','Missing navigation integration')]:
            result,_=self.invoke(omit=omit)
            self.assertNotEqual(result.returncode,0,omit)
            self.assertIn(message,result.stderr+result.stdout,omit)

    def test_notebook_and_default_permissions_are_part_of_the_image(self):
        for entry in ['SYSTEM_EXT/app/TakuNotebook/TakuNotebook.apk','SYSTEM_EXT/etc/default-permissions/taku-notebook.xml']:
            with self.subTest(entry=entry):
                result,results=self.invoke(omit=entry)
                self.assertNotEqual(result.returncode,0)
                self.assertIn('Missing Taku integration: '+entry,result.stderr)
                self.assertFalse((results/'baseline-receipt.json').exists())

    def test_declared_new_home_cannot_be_omitted_from_the_rom(self):
        product,results=self.fixture()
        path=results/'run-input.json'
        inputs=json.loads(path.read_text());inputs['agentUi']={'versionCode':8}
        path.write_text(json.dumps(inputs))
        result=subprocess.run([sys.executable,str(ROOT/'image/verify-artifacts.py'),str(product),str(results)],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('ROM is missing the pinned TakuAgent UI',result.stderr)
        self.assertFalse((results/'baseline-receipt.json').exists())

    def test_wrong_device_is_rejected(self):
        p,r=self.invoke(device='marble');self.assertNotEqual(p.returncode,0);self.assertFalse((r/'status.json').exists())
    def test_canary_profile_requires_the_actual_archived_uri(self):
        product,results=self.fixture()
        inputs=json.loads((results/'run-input.json').read_text());inputs['integrationProfile']='updater-canary'
        (results/'run-input.json').write_text(json.dumps(inputs))
        target=next((product/'obj/PACKAGING/target_files_intermediates').glob('*.zip'))
        with zipfile.ZipFile(target,'a') as archive:
            archive.writestr('SYSTEM_EXT/etc/build.prop','lineage.updater.uri=https://unrelated.invalid/feed\n')
        result=subprocess.run([sys.executable,str(ROOT/'image/verify-artifacts.py'),str(product),str(results)],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('Updater canary URI differs',result.stderr)
        self.assertFalse((results/'baseline-receipt.json').exists())
    def test_branding_profile_cannot_relabel_an_existing_unbranded_target(self):
        product,results=self.fixture()
        path=results/'run-input.json'
        inputs=json.loads(path.read_text());inputs['integrationProfile']='taku-branding-v1'
        path.write_text(json.dumps(inputs))
        result=subprocess.run([sys.executable,str(ROOT/'image/verify-artifacts.py'),str(product),str(results)],capture_output=True,text=True)
        self.assertNotEqual(result.returncode,0)
        self.assertIn('product branding differs',result.stderr)
        self.assertFalse((results/'baseline-receipt.json').exists())
    def test_branding_properties_use_system_ext_and_last_loaded_product(self):
        version=json.loads((ROOT/'image/product-branding.json').read_text())['productVersion']
        brand=f'ro.taku.product.name=TakuOS\nro.taku.product.version={version}\nro.taku.display.version=TakuOS{version}\n'
        display=f'ro.build.display.id=TakuOS{version}-'+BUILD_NUMBER+'\n'
        uri='lineage.updater.uri=https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}\n'
        for correct in (False,True):
            with self.subTest(systemExt=correct):
                product,results=self.fixture()
                path=results/'run-input.json';inputs=json.loads(path.read_text());inputs['integrationProfile']='taku-branding-v1';path.write_text(json.dumps(inputs))
                target=next((product/'obj/PACKAGING/target_files_intermediates').glob('*.zip'))
                with zipfile.ZipFile(target) as archive:contents={name:archive.read(name) for name in archive.namelist()}
                contents['SYSTEM/build.prop']+=('ro.build.id=CP2A.260605.016\n'+('' if correct else brand)).encode()
                contents['SYSTEM_EXT/etc/build.prop']=(uri+(brand if correct else '')).encode()
                contents['PRODUCT/etc/build.prop']+=display.encode()
                with zipfile.ZipFile(target,'w') as archive:
                    for name,data in contents.items():archive.writestr(name,data)
                result=subprocess.run([sys.executable,str(ROOT/'image/verify-artifacts.py'),str(product),str(results)],capture_output=True,text=True)
                self.assertNotEqual(result.returncode,0)
                # The correct property partition reaches the independent APK
                # gate; this synthetic fixture is deliberately not an APK/ROM.
                self.assertIn('Missing platform Updater integration' if correct else 'product branding differs',result.stderr)
                self.assertFalse((results/'baseline-receipt.json').exists())
    def test_disabled_boot_verification_is_not_a_measurable_ota_baseline(self):
        for flags in (2,3,4):
            with self.subTest(flags=flags):
                p,r=self.invoke(vbmeta_flags=flags)
                self.assertNotEqual(p.returncode,0);self.assertIn('cannot measure OTA baseline',p.stderr)
                self.assertFalse((r/'baseline-receipt.json').exists())
    def test_verity_flag_is_separate_from_boot_verification(self):
        p,r=self.invoke(vbmeta_flags=1);self.assertEqual(p.returncode,0,p.stderr)
        self.assertEqual(json.loads((r/'baseline-receipt.json').read_text())['signingEvidence']['vbmetaFlags'],1)
    def test_generic_system_with_tokay_product_vendor_is_accepted(self):
        p,r=self.invoke(system_device='generic');self.assertEqual(p.returncode,0,p.stderr)
        self.assertEqual(json.loads((r/'status.json').read_text())['identity']['productDevice'],'tokay')
    def test_generic_system_does_not_hide_wrong_vendor(self):
        p,r=self.invoke(system_device='generic',vendor_device='marble');self.assertNotEqual(p.returncode,0)
    def test_device_name_prefix_is_not_a_match(self):
        p,r=self.invoke(device='tokay_pro',system_device='generic');self.assertNotEqual(p.returncode,0)
    def test_user_variant_is_rejected(self):
        p,r=self.invoke(variant='user');self.assertNotEqual(p.returncode,0)
    def test_android_16_and_inconsistent_platform_versions_are_rejected(self):
        for release,sdk in [('16','36'),('17','36'),('16','37'),(None,'37'),('17',None)]:
            with self.subTest(release=release,sdk=sdk):
                p,r=self.invoke(release=release,sdk=sdk)
                self.assertNotEqual(p.returncode,0);self.assertIn('Wrong Android platform',p.stderr)
                self.assertFalse((r/'status.json').exists())
    def test_android_17_target_files_do_not_hide_an_old_or_unidentified_ota(self):
        for sdk in ['36',None]:
            with self.subTest(ota_sdk=sdk):
                p,r=self.invoke(ota_sdk=sdk)
                self.assertNotEqual(p.returncode,0);self.assertIn('Wrong OTA Android platform',p.stderr)
                self.assertFalse((r/'status.json').exists())
    def test_missing_boot_stack_is_rejected(self):
        p,r=self.invoke(omit='vendor_kernel_boot');self.assertNotEqual(p.returncode,0);self.assertIn('Missing images',p.stderr)
    def test_google_launcher_is_rejected(self):
        p,r=self.invoke(google=True);self.assertNotEqual(p.returncode,0);self.assertIn('Unexpected Google',p.stderr)
    def test_old_agent_package_is_rejected(self):
        p,r=self.invoke(stale_agent=True);self.assertNotEqual(p.returncode,0);self.assertIn('Agent package contents missing',p.stderr)
    def test_build_input_and_target_incremental_must_match(self):
        p,r=self.invoke(build_incremental='20261019010000')
        self.assertNotEqual(p.returncode,0);self.assertIn('run-input buildNumber',p.stderr)
        self.assertFalse((r/'baseline-receipt.json').exists())
    def test_run_input_is_required(self):
        p,r=self.invoke(run_input=False)
        self.assertNotEqual(p.returncode,0);self.assertIn('run-input.json',p.stderr)
    def test_missing_build_identity_cannot_be_used_as_a_baseline(self):
        for key in ['ro.build.version.incremental','ro.build.fingerprint','ro.build.date.utc','ro.build.version.security_patch']:
            with self.subTest(key=key):
                p,r=self.invoke(omit_identity=key)
                self.assertNotEqual(p.returncode,0);self.assertIn('Missing target-files build identity',p.stderr)
    def test_product_build_identity_cannot_conflict_with_system(self):
        p,r=self.invoke(product_incremental='another-build')
        self.assertNotEqual(p.returncode,0);self.assertIn('Product/system build identity differs',p.stderr)
    def test_android_17_ota_must_belong_to_the_same_build(self):
        for key,value in [('post-build','different-fingerprint'),('post-build-incremental','older-build'),
                          ('post-timestamp','1'),('post-security-patch-level','2026-09-01')]:
            for changed in [value,None]:
                with self.subTest(key=key,value=changed):
                    p,r=self.invoke(ota_changes={key:changed})
                    self.assertNotEqual(p.returncode,0);self.assertIn('OTA build identity differs',p.stderr)
                    self.assertFalse((r/'baseline-receipt.json').exists())
    def test_incremental_and_non_ab_packages_cannot_be_full_baselines(self):
        for changes in [{'pre-build':'old-fingerprint'},{'pre-build-incremental':'old-build'},{'ota-type':'BLOCK'},{'ota-type':None}]:
            with self.subTest(changes=changes):
                p,r=self.invoke(ota_changes=changes)
                self.assertNotEqual(p.returncode,0);self.assertFalse((r/'baseline-receipt.json').exists())
    def test_malformed_certificate_evidence_is_rejected(self):
        p,r=self.invoke(certificate='not a PEM certificate')
        self.assertNotEqual(p.returncode,0);self.assertIn('certificate evidence',p.stderr)
    def test_receipt_fingerprints_only_the_certificate_actually_in_the_image(self):
        p,r=self.invoke(certificate=AOSP_TEST_CERTIFICATE);self.assertEqual(p.returncode,0,p.stderr)
        signing=json.loads((r/'baseline-receipt.json').read_text())['signingEvidence']
        self.assertEqual(signing['trustedOtaCertificates'],[{
            'archive':'SYSTEM/etc/security/otacerts.zip','certificate':'testkey.x509.pem',
            'derSha256':'a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc'}])
        self.assertFalse(signing['otaPackageSignatureVerified'])
    def test_receipt_preserves_identity_and_does_not_invent_signature_validation(self):
        p,r=self.invoke();self.assertEqual(p.returncode,0,p.stderr)
        receipt_bytes=(r/'baseline-receipt.json').read_bytes()
        receipt=json.loads(receipt_bytes)
        status=json.loads((r/'status.json').read_text())
        self.assertEqual(status['baselineReceipt'],{'path':str(r/'baseline-receipt.json'),'sha256':hashlib.sha256(receipt_bytes).hexdigest()})
        self.assertEqual(receipt['buildIdentity']['buildNumber'],BUILD_NUMBER)
        self.assertEqual(receipt['buildIdentity']['buildFingerprint'],BUILD_FINGERPRINT)
        self.assertEqual(receipt['fullOtas'][0]['metadata']['post-build-incremental'],BUILD_NUMBER)
        self.assertEqual(receipt['buildInput']['snapshotDigest'],'a'*64)
        self.assertEqual(receipt['signingEvidence']['trustedOtaCertificates'],[])
        for name in ['otaPackageSignatureVerified','payloadSignatureVerified','avbSignatureVerified']:
            self.assertFalse(receipt['signingEvidence'][name])
        self.assertFalse(receipt['incrementalOtaReady']);self.assertFalse(receipt['physicalDeviceVerified'])
        self.assertEqual(set(receipt['targetFilesMetadataSha256']),{'META/misc_info.txt','META/ab_partitions.txt','META/apkcerts.txt'})
    def test_package_pass_never_claims_phone_pass(self):
        p,r=self.invoke();self.assertEqual(p.returncode,0,p.stderr)
        d=json.loads((r/'status.json').read_text());self.assertFalse(d['physicalDeviceVerified']);self.assertFalse(d['coldBootVerified']);self.assertFalse(d['safeToFlashWithoutDeviceFirmwareAssessment'])
        self.assertEqual(d['identity']['androidRelease'],'17');self.assertEqual(d['identity']['androidSdk'],37)
class KernelDistributionBoundary(unittest.TestCase):
    def invoke(self,missing=False):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        base=pathlib.Path(temp.name);dist=base/'dist';dest=base/'kernel';results=base/'results'
        for p in [dist,dest,results]:p.mkdir()
        (dest/'system_dlkm.modules.load').write_text('kernel/arch/arm64/geniezone/gzvm.ko\n')
        (dist/'boot.img').write_bytes(b'fixture only')
        if not missing:(dist/'gzvm.ko').write_bytes(b'fixture only')
        p=subprocess.run([sys.executable,str(ROOT/'image/install-kernel.py'),str(dist),str(dest),str(results)],capture_output=True,text=True)
        return p,dest,results
    def test_missing_module_stops_installation(self):
        p,d,r=self.invoke(missing=True);self.assertNotEqual(p.returncode,0)
        self.assertIn('gzvm.ko',p.stderr);self.assertFalse((d/'boot.img').exists())
    def test_nested_module_name_and_provenance(self):
        p,d,r=self.invoke();self.assertEqual(p.returncode,0,p.stderr)
        self.assertTrue((d/'gzvm.ko').exists());self.assertTrue((d/'system_dlkm.modules.load').exists())
        record=json.loads((r/'kernel-artifacts.json').read_text())
        self.assertEqual(record['requiredModuleCount'],1);self.assertFalse(record['physicalDeviceVerified'])
if __name__=='__main__':unittest.main()
