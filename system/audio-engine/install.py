#!/usr/bin/env python3
"""Install development Audio Engine and verified recognition pack without clearing user data."""
import argparse,hashlib,json,re,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--adb',default=str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
a=p.parse_args()
if not re.fullmatch(r'emulator-[0-9]+',a.serial):p.error('Use an explicit development emulator; ROM/device installation is a separate step')
def adb(*args):return subprocess.check_output([a.adb,'-s',a.serial,*map(str,args)],text=True).strip()
if adb('shell','id','-u')!='0':raise SystemExit('Run adb root on the development emulator first')
module=root/'system/audio-engine'
pack=module/'out/model-pack'
manifest=json.loads((pack/'manifest.json').read_text())
for entry in manifest['files']:
 path=pack/entry['path']
 with path.open('rb') as data:digest=hashlib.file_digest(data,'sha256').hexdigest()
 if digest!=entry['sha256']:raise SystemExit('Model pack hash mismatch')
print(adb('install','--no-incremental','-r',module/'out/TakuAudioEngine.apk'))
print(adb('push',module/'out/audio-bridge.jar','/data/local/tmp/taku-audio-bridge.jar'))
adb('shell','rm -rf /data/local/tmp/taku-audio-model-pack')
print(adb('push',pack,'/data/local/tmp/taku-audio-model-pack'))
match=re.search(r'package:org\.agentos\.audio uid:([0-9]+)',adb('shell','cmd','package','list','packages','-U','org.agentos.audio'))
if not match:raise SystemExit('Audio package UID unavailable')
uid=match[1]
# Paths are static and uid is decimal, never shell text from credentials or client requests.
command=f'''set -e
base=/data/user/0/org.agentos.audio/files
mkdir -p "$base"
rm -rf "$base/models.incoming"
cp -R /data/local/tmp/taku-audio-model-pack "$base/models.incoming"
chown -R {uid}:{uid} "$base"
restorecon -R "$base"
'''
adb('shell',command)
checks=module/'out/model-pack.sha256'
checks.write_text(''.join(entry['sha256']+'  '+entry['path']+'\n' for entry in manifest['files']))
adb('push',checks,'/data/local/tmp/taku-audio-model-pack.sha256')
adb('shell','cd /data/user/0/org.agentos.audio/files/models.incoming && sha256sum -c /data/local/tmp/taku-audio-model-pack.sha256')
adb('shell','''set -e
base=/data/user/0/org.agentos.audio/files
am force-stop org.agentos.audio
rm -rf "$base/models.previous"
if [ -d "$base/models" ]; then mv "$base/models" "$base/models.previous"; fi
mv "$base/models.incoming" "$base/models"
pm grant org.agentos.audio android.permission.RECORD_AUDIO
pm grant org.agentos.audio android.permission.POST_NOTIFICATIONS
''')
print('Installed separate models with device hash verification. User credentials and preferences preserved.')
print('Build/deploy the updated assistant and root Broker, then open org.agentos.audio/.AudioHostActivity for CLI capture.')
