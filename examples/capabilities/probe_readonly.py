#!/usr/bin/env python3
"""White-listed emulator probe; never starts/stops apps or reads account data."""
import datetime, json, os, pathlib, re, subprocess
ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = os.environ.get('ANDROID_ADB', 'adb')
SERIAL = 'emulator-5582'
def call(*args):
    p = subprocess.run([ADB, '-s', SERIAL, *args], capture_output=True, text=True, timeout=20)
    return {'exitCode': p.returncode, 'output': p.stdout.strip(), 'stderr': p.stderr.strip()}
avd = call('emu', 'avd', 'name')
if avd['exitCode'] or avd['output'].splitlines()[0] != 'AgentOS_ImageCheck':
    raise SystemExit('Target guard failed: only AgentOS_ImageCheck / emulator-5582 is allowed')
commands = {
    'identity': ['id'], 'selinux': ['getenforce'],
    'androidRelease': ['getprop', 'ro.build.version.release'],
    'apiLevel': ['getprop', 'ro.build.version.sdk'],
    'abi': ['getprop', 'ro.product.cpu.abi'],
    'buildType': ['getprop', 'ro.build.type'],
    'brokerService': ['getprop', 'init.svc.agentos_codex'],
    'displayStatus': ['/system_ext/bin/phone-display', 'status'],
    'codexVersion': ['/system_ext/bin/codex', '--version'],
    'memorySummary': ['cat', '/proc/meminfo'],
    'storageSummary': ['df', '-h', '/data'],
}
report = {'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
          'serial': SERIAL, 'avd': avd, 'scope': 'read-only selected system properties and package metadata',
          'probes': {key: call('shell', *args) for key, args in commands.items()}, 'packages': {}}
for pkg in ('org.agentos.assistant', 'org.agentos.apiboard', 'org.agentos.reflexgame',
            'org.agentos.minilab', 'com.tencent.mm', 'com.ss.android.ugc.aweme', 'com.xingin.xhs'):
    path = call('shell', 'pm', 'path', pkg)
    dump = call('shell', 'dumpsys', 'package', pkg)
    fields = []
    for line in dump['output'].splitlines():
        if re.match(r'\s*(userId|versionCode|versionName|minSdk|targetSdk|codePath|pkgFlags)=', line):
            fields.append(line.strip())
    report['packages'][pkg] = {'path': path, 'exitCode': dump['exitCode'], 'metadata': fields}
out = pathlib.Path(os.environ.get('TAKU_PROBE_OUTPUT', str(ROOT / 'out/checks/current-readonly.json')))
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps({'saved': str(out), 'api': report['probes']['apiLevel'],
                  'broker': report['probes']['brokerService'], 'packages': report['packages']}, ensure_ascii=False, indent=2))
