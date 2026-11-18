#!/usr/bin/env python3
"""Race CLI and GUI-equivalent broker calls on the dedicated lifecycle AVD.

Requires the assistant broker, CLI and a dexed NativeSocketProbe at
/data/local/tmp/taku-round11-native-test.jar. Uses the repository fixture.
"""
import json, pathlib, shlex, subprocess, sys
serial=sys.argv[1] if len(sys.argv)==2 else ''; root=pathlib.Path(__file__).resolve().parents[2]; aid='taku-local-example@taku-test-marketplace'; market='/data/local/agentos/work/.agents/plugins/marketplace.json'
if not serial.startswith('emulator-'): raise SystemExit('usage: test-plugin-race.py EMULATOR_SERIAL')
def adb(*args,**kwargs): return subprocess.run(['adb','-s',serial,*args],check=True,text=True,**kwargs)
def cmd(*args): return ['adb','-s',serial,'shell',shlex.join(args)]
def cli(*args): return json.loads(subprocess.check_output(cmd('/system_ext/bin/taku-plugin',*args),text=True))
def rpc(method,params): return json.loads(subprocess.check_output(cmd('/system/bin/env','CLASSPATH=/data/local/tmp/taku-round11-native-test.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','rpc',method,json.dumps(params,separators=(',',':'))),text=True))
def row(result):
    entries=[p for m in result['marketplaces'] for p in m['plugins'] if p['id']==aid]
    if len(entries)!=1: raise Exception('fixture catalog mismatch: '+str(entries))
    return entries[0]
if adb('emu','avd','name',capture_output=True).stdout.splitlines()[0].strip()!='Taku_SkillsLifecycle_20261030': raise RuntimeError('wrong AVD')
if subprocess.run(cmd('test','-e',market)).returncode==0: raise Exception('marketplace exists')
fixture=root/'examples/codex-plugin-lifecycle/plugin'
for f in fixture.rglob('*'):
    if f.is_file():
        remote='/data/local/agentos/work/plugin/'+f.relative_to(fixture).as_posix()
        if subprocess.run(['adb','-s',serial,'exec-out','cat',remote],capture_output=True,check=True).stdout != f.read_bytes(): raise Exception('fixture content differs')
adb('push',str(root/'examples/codex-plugin-lifecycle/.agents/plugins/marketplace.json'),market,stdout=subprocess.DEVNULL)
installed=False
try:
    if row(cli('list'))['installed']: raise Exception('fixture already installed')
    cli('install',market,'taku-local-example');installed=True
    for n in range(1,51):
        params={'keyPath':'plugins."'+aid+'".enabled','mergeStrategy':'upsert','value':True}
        a=subprocess.Popen(cmd('/system_ext/bin/taku-plugin','disable',aid),stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        b=subprocess.Popen(cmd('/system/bin/env','CLASSPATH=/data/local/tmp/taku-round11-native-test.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','rpc','config/value/write',json.dumps(params,separators=(',',':'))),stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        ao,ae=a.communicate(timeout=90);bo,be=b.communicate(timeout=90)
        if a.returncode or b.returncode: raise Exception(f'round {n} failed cli={a.returncode} {ae} broker={b.returncode} {be}')
        x=row(cli('list'));y=row(rpc('plugin/list',{'cwds':['/data/local/agentos/work']}))
        if not x['installed'] or not y['installed'] or x['enabled']!=y['enabled'] or x.get('localVersion')!=y.get('localVersion'): raise Exception(f'round {n} inconsistent cli={x} broker={y}')
        print(f"PASS round {n:02d}: enabled={x['enabled']} version={x.get('localVersion')}",flush=True)
    cli('disable',aid)
    print('PASS 50 CLI/broker overlapping enable/disable rounds, catalog states match',flush=True)
finally:
    if installed:
        try: cli('uninstall',aid)
        except Exception as e: print('cleanup error: '+str(e),file=sys.stderr)
    adb('shell','rm','-f',market)
