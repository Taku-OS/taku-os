#!/usr/bin/env python3
"""Check an open plugin list follows CLI changes on the dedicated lifecycle AVD.

The assistant, broker and CLI must be running and the plugin list must be open.
"""
import json, pathlib, subprocess, sys, time, xml.etree.ElementTree as ET
serial=sys.argv[1] if len(sys.argv)==2 else '';root=pathlib.Path(__file__).resolve().parents[2];out=root/'docs/reviews/skills-lifecycle-2026-10-30'; market='/data/local/agentos/work/.agents/plugins/marketplace.json'; aid='taku-local-example@taku-test-marketplace'
if not serial.startswith('emulator-'): raise SystemExit('usage: test-plugin-gui-refresh.py EMULATOR_SERIAL')
def adb(*args,**kwargs):return subprocess.run(['adb','-s',serial,*args],check=True,**kwargs)
def cli(*args):return json.loads(adb('shell','/system_ext/bin/taku-plugin',*args,capture_output=True,text=True).stdout)
def wait_state(label):
    for _ in range(12):
        adb('shell','uiautomator','dump','/data/local/tmp/taku-round11-panel.xml',stdout=subprocess.DEVNULL)
        raw=adb('exec-out','cat','/data/local/tmp/taku-round11-panel.xml',capture_output=True).stdout
        texts=[node.get('text','') for node in ET.fromstring(raw).iter('node')]
        if any(label in t and '1.0.0' in t for t in texts):return
        time.sleep(1)
    raise RuntimeError('GUI did not show '+label+': '+repr(texts))
if adb('emu','avd','name',capture_output=True,text=True).stdout.splitlines()[0].strip()!='Taku_SkillsLifecycle_20261030':raise RuntimeError('wrong AVD')
if subprocess.run(['adb','-s',serial,'shell','test','-e',market]).returncode==0:raise RuntimeError('market already exists')
fixture=root/'examples/codex-plugin-lifecycle/plugin'
for f in fixture.rglob('*'):
    if f.is_file():
        remote='/data/local/agentos/work/plugin/'+f.relative_to(fixture).as_posix()
        if adb('exec-out','cat',remote,capture_output=True).stdout!=f.read_bytes():raise RuntimeError('fixture content differs')
installed=False
try:
    adb('push',str(root/'examples/codex-plugin-lifecycle/.agents/plugins/marketplace.json'),market,stdout=subprocess.DEVNULL)
    entries=[p for m in cli('list')['marketplaces'] for p in m['plugins'] if p['id']==aid]
    if len(entries)!=1 or entries[0]['installed']:raise RuntimeError('fixture missing or already installed')
    cli('install',market,'taku-local-example');installed=True
    wait_state('Enabled')
    with open(out/'round11-gui-enabled.png','wb') as f:adb('exec-out','screencap','-p',stdout=f)
    print('PASS GUI automatically displayed CLI-installed enabled 1.0.0',flush=True)
    cli('disable',aid)
    wait_state('Disabled')
    with open(out/'round11-gui-disabled.png','wb') as f:adb('exec-out','screencap','-p',stdout=f)
    print('PASS open GUI automatically displayed CLI-disabled 1.0.0',flush=True)
    cli('enable',aid)
    wait_state('Enabled')
    print('PASS open GUI automatically displayed CLI-enabled 1.0.0',flush=True)
    adb('shell','input','tap','350','650')
    wait_state('Status: Enabled')
    cli('disable',aid)
    wait_state('Status: Disabled')
    with open(out/'round11-gui-detail-disabled.png','wb') as f:adb('exec-out','screencap','-p',stdout=f)
    print('PASS open plugin detail automatically displayed CLI-disabled 1.0.0',flush=True)
    cli('enable',aid)
    wait_state('Status: Enabled')
    print('PASS open plugin detail automatically displayed CLI-enabled 1.0.0',flush=True)
finally:
    if installed:
        try:cli('uninstall',aid)
        except Exception as e:print('cleanup failed:',e,file=sys.stderr)
    adb('shell','rm','-f',market)
