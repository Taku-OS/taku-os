#!/usr/bin/env python3
"""Install already-built AI components for development testing; no image flash, writable partition remount or reboot."""
import argparse,datetime,hashlib,json,pathlib,shlex,subprocess,time
p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');p.add_argument('--allow-physical',action='store_true');p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
if not a.serial.startswith('emulator-') and not a.allow_physical:p.error('Device-owner authorization and --allow-physical required')
root=pathlib.Path(__file__).resolve().parents[4];out=a.output;out.mkdir(parents=True,exist_ok=True);adb=['adb','-s',a.serial]
def command(*v):
 r=subprocess.run(adb+list(v),text=True,capture_output=True,timeout=120)
 if r.returncode:raise RuntimeError('ADB '+v[0]+' failed: '+r.stderr[:250])
 return r.stdout.strip()
def sh(*v):return command('shell',shlex.join(v))
assert sh('id','-u')=='0' and sh('getprop','sys.boot_completed')=='1'
assert sh('getenforce')=='Enforcing','This acceptance keeps SELinux enforcing'
stage='/data/local/agentos/components/system-ai/'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
sh('mkdir','-p',stage);sh('chmod','700',stage)
for local,name in [(root/'system/assistant/out/task-broker.jar','task-broker.jar'),(root/'adapters/magpie-runtime/magpie','magpie')]:
 command('push',str(local),stage+'/'+name);sh('chmod','755' if name=='magpie' else '444',stage+'/'+name);sh('chcon','u:object_r:system_file:s0',stage+'/'+name)
mounts=sh('cat','/proc/self/mountinfo').splitlines()
targets={'task-broker.jar':'/system_ext/framework/agentos-task-broker.jar','magpie':'/system_ext/bin/magpie'}
for target in targets.values():
 for line in mounts:
  fields=line.split()
  if len(fields)>4 and fields[4]==target:assert 'agentos/components/' in fields[3],'Refusing to replace another worktree runtime mount'
sh('stop','taku_broker');sh('stop','taku_magpie')
for name,target in targets.items():
 if any(len(l.split())>4 and l.split()[4]==target for l in mounts):sh('umount',target)
 sh('mount','--bind',stage+'/'+name,target)
sh('mount','-o','remount,bind,suid','/system_ext/bin/magpie');sh('start','taku_magpie');sh('start','taku_broker')
apps={'org.agentos.assistant':root/'system/assistant/out/AgentOSAssistant.apk','org.agentos.inputmethod':root/'out/inputmethod/TakuInputMethod-arm64-v8a.apk','org.agentos.memory':root/'system/memory/out/TakuMemory.apk','org.agentos.memorytests':root/'system/memory/out/tests/TakuMemoryTests.apk','org.agentos.inputmethod.probe':root/'out/inputmethod/probe/probe.apk'}
report={'serial':a.serial,'deployment':'temporary development bind mounts plus APK updates','rebootPersistentRuntime':False,'selinux':sh('getenforce'),'apps':{}}
for pkg,path in apps.items():
 assert 'Success' in command('install','-r',str(path));installed=sh('pm','path',pkg).splitlines()[0].removeprefix('package:');digest=sh('sha256sum',installed).split()[0];assert digest==hashlib.sha256(path.read_bytes()).hexdigest();report['apps'][pkg]={'sha256':digest};print(pkg+': installed and hash verified',flush=True)
deadline=time.monotonic()+25;state={}
while time.monotonic()<deadline:
 try:
  raw=sh('env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','exchange','{"op":"systemAi","action":"read"}');state=json.loads(raw)
  if state.get('connected') and state.get('models'):break
 except Exception:pass
 time.sleep(.4)
assert state.get('connected') and state.get('models'),'Root positive-control model directory must work before any denial tests'
report.update(rootGatewayPositiveControl=True,modelCount=len(state['models']),brokerSha256=sh('sha256sum',targets['task-broker.jar']).split()[0],wrapperSha256=sh('sha256sum',targets['magpie']).split()[0],gatewayRules=sh('iptables','-S','taku_ai').splitlines())
(out/'deployment.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n');print('PASS root gateway positive control; system AI runtime ready',flush=True)
