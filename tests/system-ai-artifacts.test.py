#!/usr/bin/env python3
"""Runtime receipt corruption tests with synthetic bytes; not ROM/device acceptance."""
import copy
import importlib.util
import io
import json
import pathlib
import stat
import tempfile
import runpy
import unittest
import zipfile
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('system_ai_gate', ROOT / 'image/verify-system-ai.py')
GATE = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(GATE)


class RuntimeReceipt(unittest.TestCase):
    def setUp(self):
        sha = GATE.checksum
        self.descriptor = {'schema':'taku.system-ai-runtime-input/1','readyForBuild':True,'sourceCommit':'a'*40,
                           'packetSha256':'b'*64,'baseSnapshotDigest':'c'*64,'handoffManifestSha256':'d'*64}
        self.handoff = {'sourceCommit':'a'*40,'baseSnapshotDigest':'c'*64,
                        'versions':{'ime':10062,'assistant':64,'memory':8}}
        self.lock = {'package':'org.agentos.inputmethod','sha256':sha(b'fixture IME'),'bytes':11,
                     'signerSha256':'e'*64,'jni':[],'jniSymlinks':[]}
        components = {}
        self.contents = {}
        for name, raw in {'app/TakuInputMethod/TakuInputMethod.apk':b'fixture IME',
                          'priv-app/TakuAssistant/TakuAssistant.apk':b'fixture assistant',
                          'priv-app/TakuMemory/TakuMemory.apk':b'fixture memory',
                          'framework/agentos-task-broker.jar':b'fixture broker',
                          'bin/magpie':b'fixture magpie wrapper','bin/taku-runtime-start':b'fixture init wrapper',
                          'etc/init/taku-magpie.rc':b'fixture magpie init','etc/init/taku-runtime.rc':b'fixture runtime init',
                          'bin/magpie-runtime':b'fixture runtime'}.items():
            self.contents['SYSTEM_EXT/'+name]=raw;components[name]={'sha256':sha(raw),'bytes':len(raw)}
        for name, package, code in [('app/TakuInputMethod/TakuInputMethod.apk','org.agentos.inputmethod',10062),
                                   ('priv-app/TakuAssistant/TakuAssistant.apk','org.agentos.assistant',64),
                                   ('priv-app/TakuMemory/TakuMemory.apk','org.agentos.memory',8)]:
            components[name].update(package=package,versionCode=code)
        self.contents['META/system_ext_filesystem_config.txt']=b''
        self.entries={'image/system-ai-handoff.json':'d'*64,'system/inputmethod/rom-input-lock.json':'f'*64}
        self.report={'schema':'taku.system-ai-rom-verification/1','status':'artifact_verified',
                     'physicalVerified':False,'runtimeBindRequired':False,'credentialFilesIncluded':False,
                     'runtimeIntegration':self.descriptor,'runInputSha256':'1'*64,
                     'handoffManifestSha256':'d'*64,'imeInputLockSha256':'f'*64,
                     'components':components,'imeSignerSha256':'e'*64,'imePackagingVerified':True,
                     'imeSignatureVerification':{'actualExit':0,'signerSha256':'e'*64,'toolSha256':'2'*64,
                         'stdout':'Number of signers: 1\nV3.0 Signer: certificate SHA-256 digest: '+'e'*64+'\n'},
                     'nativeComponents':{},'nativeSymlinks':{}}

    def check(self, source_repair=None, permission_helper=None):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as archive:
            for name,raw in self.contents.items():archive.writestr(name,raw)
        with zipfile.ZipFile(data) as archive:
            return GATE.validate_saved(archive,self.report,self.handoff,self.lock,self.descriptor,'1'*64,self.entries,source_repair,permission_helper=permission_helper)

    def test_permission_repair_revalidates_bound_policy_and_uses_archived_helper(self):
        helper=ROOT/'image/privapp_permissions.py';policy=runpy.run_path(str(helper))
        xml=(ROOT/policy['SOURCE']).read_bytes()
        self.entries.update({policy['SOURCE']:GATE.checksum(xml),'image/privapp_permissions.py':GATE.checksum(helper.read_bytes())})
        self.contents.update({policy['TARGET']:xml,policy['FRAMEWORK']:b'framework fixture',
            'SYSTEM_EXT/priv-app/TakuSurfaces/TakuSurfaces.apk':b'surfaces fixture',
            'META/system_ext_filesystem_config.txt':b'system_ext/etc/permissions/TakuMemory.xml 0 0 644\n'})
        tree='E: permission (line=1)\n A: android:name(0x01010003)="android.permission.DUMP"\n A: android:protectionLevel(0x01010009)=0x32\n'
        names={b'fixture memory':'org.agentos.memory',b'fixture assistant':'org.agentos.assistant',b'surfaces fixture':'org.agentos.surfaces'}
        def badging(raw):
            return "package: name='"+names[raw]+"'\n"+("uses-permission: name='android.permission.DUMP'\n" if raw==b'fixture memory' else '')
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as z:
            for n,r in self.contents.items():z.writestr(n,r)
        with zipfile.ZipFile(data) as z:
            self.report['memoryPrivappAllowlist']=policy['verify'](z,self.entries[policy['SOURCE']],['android.permission.DUMP'])
            self.report['privilegedPermissionAudit']=policy['audit'](z,policy['permission_definitions'](tree),badging)
        self.report['permissionExtractionToolSha256']='a'*64
        self.assertTrue(self.check())
        with tempfile.TemporaryDirectory() as folder:
            saved=pathlib.Path(folder)/'privapp_permissions.py';saved.write_bytes(helper.read_bytes())
            with mock.patch.object(GATE,'__file__',str(pathlib.Path(folder)/'future-checkout/verify-system-ai.py')):
                self.assertTrue(self.check(permission_helper=saved))
        self.report['memoryPrivappAllowlist']['bytes']+=1
        with self.assertRaisesRegex(ValueError,'policy proof differs'):self.check()

    def test_repaired_version_and_capsule_are_required_without_changing_legacy_handoff(self):
        self.assertTrue(self.check())
        self.descriptor['sourceRepair']={'versions':{'ime':10062,'assistant':66,'memory':8}}
        self.entries['image/source-repair.json']='a'*64
        with self.assertRaisesRegex(ValueError,'repair proof'): self.check()
        validator=mock.Mock()
        with mock.patch.object(GATE.runpy,'run_path',return_value={'validate':validator}):
            with self.assertRaisesRegex(ValueError,'version/package'): self.check(b'fixture capsule')
            self.report['components']['priv-app/TakuAssistant/TakuAssistant.apk']['versionCode']=66
            self.assertTrue(self.check(b'fixture capsule'))
            validator.assert_called_with(b'fixture capsule',self.descriptor['sourceRepair'],self.entries)
        del self.descriptor['sourceRepair']
        with self.assertRaisesRegex(ValueError,'repair proof'): self.check()

    def test_valid_receipt_preserves_physical_pending_and_rejects_other_run_or_inputs(self):
        self.assertTrue(self.check());original=copy.deepcopy(self.report)
        for key,value in [('runInputSha256','3'*64),('handoffManifestSha256','3'*64),('imeInputLockSha256','3'*64),
                          ('physicalVerified',True),('runtimeBindRequired',True),('imePackagingVerified',False)]:
            self.report=copy.deepcopy(original);self.report[key]=value
            with self.subTest(key=key),self.assertRaises(ValueError):self.check()

    def test_new_handoff_review_is_bound_to_parent_packet_source_versions_and_closure(self):
        self.descriptor['versions']=self.handoff['versions']
        self.handoff.update(baseManifestSha256='9'*64,ownedReplacementsAndAdds=[{}],unchangedOwnedInputs=[{}])
        policy={'schema':'taku.reviewed-runtime-handoff/1','baseSnapshotDigest':'c'*64,'baseManifestSha256':'9'*64,
                'handoffManifestSha256':'d'*64,'packetSha256':'b'*64,'sourceCommit':'a'*40,
                'versions':self.handoff['versions'],'replacementCount':1,'unchangedCount':1,
                'cachePolicy':'checksum-changed-inputs-fresh-mtime'}
        def bind(value):
            raw=json.dumps(value);self.descriptor['reviewedHandoff']={'json':raw,'sha256':GATE.checksum(raw.encode())}
        bind(policy);self.assertTrue(self.check())
        for key,value in [('baseManifestSha256','0'*64),('packetSha256','0'*64),('sourceCommit','0'*40),
                          ('replacementCount',2),('versions',{'ime':10062,'assistant':67,'memory':8})]:
            changed=dict(policy);changed[key]=value;bind(changed)
            with self.subTest(key=key),self.assertRaisesRegex(ValueError,'identity/closure'):self.check()
        bind(policy);self.descriptor['reviewedHandoff']['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'contract hash'):self.check()

    def test_target_component_and_apk_signer_mismatch_are_rejected(self):
        original=copy.deepcopy(self.report)
        self.contents['SYSTEM_EXT/framework/agentos-task-broker.jar']=b'different bytes'
        with self.assertRaisesRegex(ValueError,'hash/size'):self.check()
        self.contents['SYSTEM_EXT/framework/agentos-task-broker.jar']=b'fixture broker'
        for output in ('Number of signers: 2\n', 'Number of signers: 1\n',
                       self.report['imeSignatureVerification']['stdout'].replace('e'*64,'a'*64)):
            self.report=copy.deepcopy(original);self.report['imeSignatureVerification']['stdout']=output
            with self.subTest(output=output),self.assertRaises(ValueError):self.check()

    def test_preprocessed_native_missing_fs_config_or_wrong_mode_cannot_be_accepted(self):
        path='SYSTEM_EXT/lib64/taku-inputmethod/libfixture.so';raw=b'fixture native bytes'
        self.contents[path]=raw
        self.lock['jni']=[{'installPath':path,'sha256':GATE.checksum(raw),'bytes':len(raw),'executable':False}]
        self.report['nativeComponents']={path.removeprefix('SYSTEM_EXT/'):{'sha256':GATE.checksum(raw),'bytes':len(raw),'mode':'0644','uid':0,'gid':0}}
        with self.assertRaisesRegex(ValueError,'ownership/mode'):self.check()
        self.contents['META/system_ext_filesystem_config.txt']=b'system_ext/lib64/taku-inputmethod/libfixture.so 0 0 0755\n'
        with self.assertRaisesRegex(ValueError,'ownership/mode'):self.check()
        self.contents['META/system_ext_filesystem_config.txt']=b'system_ext/lib64/taku-inputmethod/libfixture.so 0 0 0644\n'
        self.assertTrue(self.check())
        self.lock['jni'][0]['executable']=True
        proof=self.report['nativeComponents'][path.removeprefix('SYSTEM_EXT/')]
        proof.update(mode='0755',uid=0,gid=2000)
        self.contents['META/system_ext_filesystem_config.txt']=b'system_ext/lib64/taku-inputmethod/libfixture.so 0 2000 0755\n'
        self.assertTrue(self.check())
        proof['gid']=0
        with self.assertRaisesRegex(ValueError,'ownership/mode'):self.check()


if __name__=='__main__':unittest.main()
