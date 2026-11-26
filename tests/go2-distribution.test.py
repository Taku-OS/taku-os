import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('devkit',ROOT/'tooling/go2-lab/package-devkit.py')
devkit=importlib.util.module_from_spec(spec);spec.loader.exec_module(devkit)


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name)
        script=self.root/'tooling/go2-lab/install.sh';script.parent.mkdir(parents=True)
        shutil.copy(ROOT/'tooling/go2-lab/install.sh',script)
        artifacts=self.root/'artifacts';artifacts.mkdir()
        (artifacts/'TakuGo2.apk').write_bytes(b'test');(artifacts/'client.jar').write_bytes(b'test')
        self.adb=self.root/'fake-adb'
        self.adb.write_text('''#!/usr/bin/env python3
import json,os,sys
from pathlib import Path
args=sys.argv[3:]
with open(os.environ['TEST_ADB_LOG'],'a') as f:f.write(json.dumps(args)+'\\n')
props={'ro.build.version.sdk':'36','ro.kernel.qemu':'1','ro.build.type':'userdebug'}
if args==['get-state']:print('device')
elif args==['emu','avd','name']:print('Test_AVD\\nOK')
elif args[:2]==['shell','getprop']:print(props.get(args[2],''))
elif args==['shell','getenforce']:print('Enforcing')
elif args==['shell','id','-u']:print('0')
else:print('OK')
''')
        self.adb.chmod(0o755)
        self.log=self.root/'adb.log'

    def tearDown(self):self.tmp.cleanup()

    def install(self,*args):
        env={**os.environ,'ADB':str(self.adb),'TEST_ADB_LOG':str(self.log),'TAKU_SERIAL':'','TAKU_AVD':''}
        result=subprocess.run(['bash',str(self.root/'tooling/go2-lab/install.sh'),*args],env=env,capture_output=True,text=True)
        calls=[json.loads(p) for p in self.log.read_text().splitlines()] if self.log.exists() else []
        return result,calls

    def test_no_implicit_target(self):
        result,calls=self.install()
        self.assertNotEqual(result.returncode,0);self.assertEqual(calls,[])

    def test_wrong_avd_cannot_install_or_root(self):
        result,calls=self.install('--serial','emulator-5554','--avd','Wrong','--with-ai-tools')
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(any(p[0] in {'install','root','push','reverse'} for p in calls))

    def test_prebuilt_install_does_not_require_root_or_jdk(self):
        result,calls=self.install('--serial','emulator-5554','--avd','Test_AVD')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn(['install','-r','artifacts/TakuGo2.apk'],calls)
        self.assertFalse(any(p[0]=='root' for p in calls))
        self.assertIn(['reverse','tcp:18846','tcp:18846'],calls)

    def test_ai_explicit_and_new_build_preferred(self):
        output=self.root/'out/go2-lab';output.mkdir(parents=True)
        (output/'TakuGo2.apk').write_bytes(b'new');(output/'client.jar').write_bytes(b'new')
        result,calls=self.install('--serial','emulator-5554','--avd','Test_AVD','--with-ai-tools')
        self.assertEqual(result.returncode,0,result.stderr)
        self.assertIn(['root'],calls);self.assertIn(['install','-r','out/go2-lab/TakuGo2.apk'],calls)

    def test_physical_target_cannot_install_ai_tools(self):
        result,calls=self.install('--serial','physical123','--with-ai-tools')
        self.assertNotEqual(result.returncode,0)
        self.assertFalse(any(p[0] in {'root','install','push'} for p in calls))

    def test_archive_detects_corruption_and_extra_entries(self):
        files={p:b'test' for p in ['README.md','go2','artifacts/TakuGo2.apk','artifacts/client.jar',
                                  'tooling/go2-lab/web/index.html','tooling/go2-lab/bridge.py','LICENSE']}
        files['manifest.json']=json.dumps({'format':'taku-go2-devkit/v1','realAutonomousNavigation':False,'version':'test','sourceCommit':'test'}).encode()
        files['SHA256SUMS']=''.join(f'{hashlib.sha256(v).hexdigest()}  {k}\n' for k,v in files.items()).encode()
        archive=self.root/'kit.zip'
        def write(extra=None):
            with zipfile.ZipFile(archive,'w') as z:
                for k,v in {**files,**(extra or {})}.items():z.writestr('kit/'+k,v)
        write();self.assertEqual(devkit.verify(archive)['version'],'test')
        write({'go2':b'changed'})
        with self.assertRaisesRegex(ValueError,'checksum'):devkit.verify(archive)
        write({'unexpected':b'extra'})
        with self.assertRaisesRegex(ValueError,'checksum'):devkit.verify(archive)
        write({'../escape':b'extra'})
        with self.assertRaisesRegex(ValueError,'path'):devkit.verify(archive)


if __name__=='__main__':unittest.main()
