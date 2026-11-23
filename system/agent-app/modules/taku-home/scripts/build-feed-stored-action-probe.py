#!/usr/bin/env python3
"""Build only the synthetic Feed action instrumentation, using cached tools/debug classes."""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[5]
MODULE = ROOT / 'system/agent-app/modules/taku-home'
OUT = ROOT / 'out/legacy-feed-compat-20261121/action-probe'
SDK = Path(os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk')))
JAVA = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home'))
KOTLINC = Path(os.environ['TAKU_KOTLINC']).resolve()
LIB = KOTLINC.parent.parent / 'lib'
HOME = MODULE / 'android/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'
TOOLS = SDK / 'build-tools/36.0.0'
ANDROID = SDK / 'platforms/android-36/android.jar'
COROUTINES = LIB / 'kotlinx-coroutines-core-jvm.jar'
SIGNING = ROOT / 'system/agent-app/android/app/debug.keystore'

for path in [KOTLINC, HOME, ANDROID, COROUTINES, SIGNING]:
    assert path.is_file(), 'Missing cached test dependency: ' + str(path)
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'dex').mkdir(exist_ok=True)
env = {**os.environ, 'JAVA_HOME': str(JAVA)}
# Match the already-built debug target using its existing development signing config.
# Values stay in the child environment, never command arguments, receipts or logs.
config = (ROOT / 'system/agent-app/android/app/build.gradle').read_text()
debug = re.search(r'debug\s*\{([^{}]+)\}', config).group(1)
def literal(name):
    match = re.search(r'\b' + name + r'''\s+['"]([^'"]+)['"]''', debug)
    assert match, 'Expected the existing literal debug signing config: ' + name
    return match.group(1)
env['TAKU_FEED_TEST_STORE_PASS'] = literal('storePassword')
env['TAKU_FEED_TEST_KEY_PASS'] = literal('keyPassword')
alias = literal('keyAlias')


def run(parts):
    subprocess.run([str(part) for part in parts], env=env, check=True)


run([KOTLINC, '-jvm-target', '17', '-classpath', ':'.join(map(str, [ANDROID, HOME, COROUTINES])),
     '-d', OUT / 'classes.jar', MODULE / 'tests/FeedStoredActionProbe.kt'])
run([TOOLS / 'd8', '--min-api', '36', '--lib', ANDROID, '--classpath', HOME,
     '--classpath', COROUTINES, '--classpath', LIB / 'kotlin-stdlib.jar', '--output', OUT / 'dex', OUT / 'classes.jar'])
run([TOOLS / 'aapt2', 'link', '-o', OUT / 'resources.apk', '-I', ANDROID,
     '--manifest', MODULE / 'tests/FeedStoredActionManifest.xml'])
shutil.copyfile(OUT / 'resources.apk', OUT / 'unsigned.apk')
with zipfile.ZipFile(OUT / 'unsigned.apk', 'a') as archive:
    for path in (OUT / 'dex').glob('classes*.dex'):
        archive.write(path, path.name)
run([TOOLS / 'zipalign', '-f', '4', OUT / 'unsigned.apk', OUT / 'aligned.apk'])
run([TOOLS / 'apksigner', 'sign', '--ks', SIGNING, '--ks-key-alias', alias,
     '--ks-pass', 'env:TAKU_FEED_TEST_STORE_PASS', '--key-pass', 'env:TAKU_FEED_TEST_KEY_PASS',
     '--out', OUT / 'FeedStoredActionTests.apk', OUT / 'aligned.apk'])
run([TOOLS / 'apksigner', 'verify', OUT / 'FeedStoredActionTests.apk'])
receipt = {'apk': str(OUT / 'FeedStoredActionTests.apk'),
           'sha256': hashlib.sha256((OUT / 'FeedStoredActionTests.apk').read_bytes()).hexdigest(),
           'source': str(MODULE / 'tests/FeedStoredActionProbe.kt'), 'deviceCalls': 0}
(OUT / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
print(json.dumps(receipt))
