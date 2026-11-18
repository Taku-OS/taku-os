#!/usr/bin/env python3
"""Replay BUG-0007 against an installed release APK using its matching R8 mapping.

Only a task-owned emulator is accepted. This is controlled lifecycle fault injection,
not a claim that the same callback order was produced by ordinary UI navigation.
"""
import argparse
import os
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True)
parser.add_argument('--mapping', type=Path, required=True)
parser.add_argument('--baseline', action='store_true')
parser.add_argument('--output', type=Path, required=True)
options = parser.parse_args()
if not re.fullmatch(r'emulator-\d+', options.serial):
    parser.error('Only a dedicated emulator serial is accepted; physical devices are excluded.')

root = Path(__file__).resolve().parents[3]
sdk = Path(os.environ.get('ANDROID_SDK_ROOT', Path.home() / 'Library/Android/sdk'))
jdk = Path(os.environ['JAVA_HOME'])
build_tools = sdk / 'build-tools' / os.environ.get('TAKU_BUILD_TOOLS', '37.0.0')
android = sdk / 'platforms' / 'android-37' / 'android.jar'
if not android.exists():
    android = sdk / 'platforms' / 'android-37.0' / 'android.jar'
out = options.output.resolve()
out.mkdir(parents=True, exist_ok=True)

def run(*args):
    return subprocess.run([str(arg) for arg in args], check=True, text=True, capture_output=True).stdout

def mapped_names():
    classes = {}
    current = None
    for line in options.mapping.read_text().splitlines():
        if line and not line.startswith((' ', '#')) and ' -> ' in line:
            current = line.split(' -> ')[0]
            classes[current] = []
        elif current and line.startswith('    ') and ' -> ' in line and not line.lstrip().startswith('#'):
            classes[current].append(line.strip())
    host_type = 'org.agentos.agent.home.TakuReactHost'
    if options.baseline:
        host_type = 'com.facebook.react.runtime.ReactHostImpl'
    def member(cls, signature):
        matches = {line.rsplit(' -> ', 1)[1] for line in classes[cls] if re.search(r'(?<![\w.])' + re.escape(signature) + r'(?=[: ]|$)', line.split(' -> ')[0])}
        if len(matches) != 1:
            raise ValueError(f'Mapping does not uniquely identify {cls}.{signature}: {matches}')
        return matches.pop()
    return {
        'pool': member('org.agentos.agent.home.HomeActivity', 'org.agentos.agent.home.ui.ReactCardPool pool'),
        'host': member('org.agentos.agent.home.ui.ReactCardPool', 'com.facebook.react.ReactHost host'),
        'resume': member(host_type, 'void onHostResume(android.app.Activity,com.facebook.react.modules.core.DefaultHardwareBackBtnHandler)'),
        'pause': member(host_type, 'void onHostPause(android.app.Activity)'),
        'destroy': member(host_type, 'void onHostDestroy(android.app.Activity)'),
        'context': member(host_type, 'com.facebook.react.bridge.ReactContext getCurrentReactContext()'),
    }

names = mapped_names()
source = root / 'system/agent-app/tests/lifecycle-device/HostLifecycleProbe.java'
classes = out / 'classes'
classes.mkdir(exist_ok=True)
run(jdk / 'bin/javac', '-source', '8', '-target', '8', '-classpath', android, '-d', classes, source)
run(jdk / 'bin/jar', 'cf', out / 'probe.jar', '-C', classes, '.')
run(build_tools / 'd8', '--lib', android, '--output', out, out / 'probe.jar')
manifest = out / 'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="org.agentos.agent.lifecycleprobe">
  <uses-sdk android:minSdkVersion="31" android:targetSdkVersion="37"/>
  <application android:label="Taku lifecycle test"/>
  <instrumentation android:name="org.agentos.agent.lifecycleprobe.HostLifecycleProbe" android:targetPackage="org.agentos.agent"/>
</manifest>''')
run(build_tools / 'aapt2', 'link', '-I', android, '--manifest', manifest, '-o', out / 'probe-unsigned.apk')
run(jdk / 'bin/jar', 'uf', out / 'probe-unsigned.apk', '-C', out, 'classes.dex')
signing = root / 'out/development-signing'
run('python3', root / 'scripts/aosp-test-keys.py', signing)
run(build_tools / 'zipalign', '-P', '16', '-f', '4', out / 'probe-unsigned.apk', out / 'probe-aligned.apk')
run(build_tools / 'apksigner', 'sign', '--key', signing / 'platform.pk8', '--cert', signing / 'platform.x509.pem', '--out', out / 'probe.apk', out / 'probe-aligned.apk')
adb = [sdk / 'platform-tools/adb', '-s', options.serial]
print(run(*adb, 'install', '-r', out / 'probe.apk'))
command = [*adb, 'shell', 'am', 'instrument', '-w']
for name, value in names.items():
    command += ['-e', name, value]
if options.baseline:
    command += ['-e', 'baseline', 'true']
command += ['org.agentos.agent.lifecycleprobe/.HostLifecycleProbe']
result = run(*command)
(out / 'result.txt').write_text(result)
print(result)
expected = 'EXPECTED_BASELINE_ASSERTION' if options.baseline else 'PASS'
if f'result={expected}' not in result:
    raise SystemExit('Lifecycle probe did not produce the expected result.')
