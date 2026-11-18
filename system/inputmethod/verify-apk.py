#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
"""Verify the product identity, offline boundary and engine payload of a built IME."""
import hashlib,json,os,pathlib,subprocess,sys,zipfile
apk=pathlib.Path(sys.argv[1]).resolve()
sdk=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT',os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk'))))
aapt=sdk/'build-tools'/os.environ.get('TAKU_BUILD_TOOLS','36.1.0')/'aapt2'
manifest=subprocess.check_output([str(aapt),'dump','badging',str(apk)],text=True)
assert "name='org.agentos.inputmethod'" in manifest, 'Wrong product package'
assert "versionName='0.4.0'" in manifest, 'Wrong product version'
assert "versionCode='10062'" in manifest or "versionCode='10064'" in manifest, 'Wrong product version code'
assert 'android.permission.INTERNET' not in manifest, 'IME must work offline'
assert 'application-debuggable' not in manifest, 'Product APK must be a release build'
xml=subprocess.check_output([str(aapt),'dump','xmltree',str(apk),'--file','AndroidManifest.xml'],text=True)
assert 'org.fcitx.fcitx5.android.input.FcitxInputMethodService' in xml, 'Missing Android IME service'
assert 'android.permission.BIND_INPUT_METHOD' in xml, 'IME service must require Android binding permission'
assert 'android:sharedUserId' not in xml, 'IME must use its own application UID'
assert "application-label:'Taku Input Method'" in manifest, 'Wrong product brand'
with zipfile.ZipFile(apk) as z:
 names=z.namelist()
 for suffix in ['libnative-lib.so','libpinyin.so','libIMEPinyin.so','libFcitx5Core.so']:
  assert any(n.endswith('/'+suffix) for n in names),f'Missing {suffix}'
 assert any('libime' in n and n.endswith('.dict') for n in names),'Missing offline dictionary'
 assert any('libime' in n and n.endswith('.lm') for n in names),'Missing offline language model'
here=pathlib.Path(__file__).resolve().parent
lock=json.loads((here/'source-lock.json').read_text())
patch=here/'patches/0001-taku-inputmethod.patch'
result={'upstreamCommit':lock['commit'],'patches':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((here/'patches').glob('*.patch'))},'apk':apk.name,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'bytes':apk.stat().st_size,'package':'org.agentos.inputmethod','version':'0.4.0','internetPermission':False}
apk.with_suffix('.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result,ensure_ascii=False))
