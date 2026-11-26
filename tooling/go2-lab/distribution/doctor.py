"""Read-only prerequisites report. No adb mutation, robot access, or secret contents."""
import json
import os
from pathlib import Path
import shutil
import socket
import sys

root=Path(__file__).resolve().parents[3]
sdk=Path(os.environ.get('ANDROID_SDK_ROOT',os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk'))))
cache=Path(os.environ.get('TAKU_GO2_CACHE',str(Path.home()/'Library/Caches/taku-go2')))
adb=os.environ.get('ADB') or shutil.which('adb') or str(sdk/'platform-tools/adb')
checks={'python3':bool(shutil.which('python3')),'uv':bool(shutil.which('uv')),
        'adb':Path(adb).is_file(),'runtimeInstalled':(cache/'venv/bin/python').is_file(),
        'apkPresent':any((root/p).is_file() for p in ['artifacts/TakuGo2.apk','out/go2-lab/TakuGo2.apk'])}
ports={}
for port in [18845,18846]:
    with socket.socket() as sock:
        sock.settimeout(.3)
        ports[str(port)]='in-use' if sock.connect_ex(('127.0.0.1',port))==0 else 'available'
print(json.dumps({'checks':checks,'localPorts':ports,'note':'Port occupancy is not robot readiness. No robot was contacted.'},indent=2))
sys.exit(0 if all(checks.values()) else 1)
