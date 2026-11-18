#!/usr/bin/env python3
"""Install the built IME/assistant and owned development runtime mounts; no flash or reboot."""
import argparse,datetime,hashlib,json,pathlib,shlex,subprocess,time
p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');p.add_argument('--allow-physical',action='store_true');p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
if not a.serial.startswith('emulator-') and not a.allow_physical:p.error('Device-owner authorization required')
root=pathlib.Path(__file__).resolve().parents[3];out=a.output;out.mkdir(parents=True,exist_ok=True);adb=['adb','-s',a.serial]
def cmd(*v):
 r=subprocess.run(adb+list(v),capture_output=True,text=True,timeout=120)
 if r.returncode:raise RuntimeError('ADB '+v[0]+' failed')
 return r.stdout.strip()
def sh(*v):return cmd('shell',shlex.join(v))
assert sh('id','-u')=='0' and sh('getenforce')=='Enforcing'
assert sh('getprop','sys.boot_completed')=='1','Wait for Android to finish booting before deployment'
device={'incremental':sh('getprop','ro.build.version.incremental'),'androidSdk':sh('getprop','ro.build.version.sdk'),'bootId':sh('cat','/proc/sys/kernel/random/boot_id')}
focus=[line for line in sh('dumpsys','window','displays').splitlines() if 'mCurrentFocus=' in line]
assert not any('org.lineageos.updater/' in line for line in focus),'Do not take over an active OTA UI'
stage='/data/local/agentos/components/inputmethod/'+datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ');sh('mkdir','-p',stage);sh('chmod','700',stage)
runtime=[(root/'system/assistant/out/task-broker.jar','task-broker.jar','/system_ext/framework/agentos-task-broker.jar'),(root/'adapters/magpie-runtime/magpie','magpie','/system_ext/bin/magpie')]
mounts=sh('cat','/proc/self/mountinfo').splitlines()
for _,_,target in runtime:
 for line in mounts:
  f=line.split()
  if len(f)>4 and f[4]==target:assert 'agentos/components/' in f[3],'Another runtime owns this mount'
for local,name,_ in runtime:
 cmd('push',str(local),stage+'/'+name);sh('chmod','755' if name=='magpie' else '444',stage+'/'+name);sh('chcon','u:object_r:system_file:s0',stage+'/'+name)
sh('stop','taku_broker');sh('stop','taku_magpie')
for _,name,target in runtime:
 if any(len(l.split())>4 and l.split()[4]==target for l in mounts):sh('umount',target)
 sh('mount','--bind',stage+'/'+name,target)
sh('mount','-o','remount,bind,suid','/system_ext/bin/magpie');sh('start','taku_magpie');sh('start','taku_broker')
apps={'org.agentos.assistant':root/'system/assistant/out/AgentOSAssistant.apk','org.agentos.inputmethod':root/'out/inputmethod/TakuInputMethod-arm64-v8a.apk','org.agentos.inputmethod.probe':root/'out/inputmethod/probe/probe.apk','org.agentos.inputmethod.fixture':root/'out/inputmethod/fixture/fixture.apk','org.agentos.inputmethod.chatcase':root/'out/inputmethod/chatcase/chatcase.apk'}
report={'deployedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'device':device,'runtimeRebootPersistent':False,'selinux':sh('getenforce'),'serial':a.serial,'apps':{},'runtime':{}}
for pkg,path in apps.items():
 assert 'Success' in cmd('install','-r',str(path));installed=sh('pm','path',pkg).splitlines()[0].removeprefix('package:');digest=sh('sha256sum',installed).split()[0];assert digest==hashlib.sha256(path.read_bytes()).hexdigest();report['apps'][pkg]={'sha256':digest};print(pkg+': installed and hash verified',flush=True)
for local,name,target in runtime:
 digest=sh('sha256sum',target).split()[0];assert digest==hashlib.sha256(local.read_bytes()).hexdigest();report['runtime'][name]={'sha256':digest}
cmd('push',str(root/'out/inputmethod/native-probe/probe.jar'),'/data/local/tmp/taku-ime-native-probe.jar');sh('chmod','644','/data/local/tmp/taku-ime-native-probe.jar')
component='org.agentos.inputmethod/org.fcitx.fcitx5.android.input.FcitxInputMethodService';sh('ime','enable',component);sh('ime','set',component)
end=time.monotonic()+25
while time.monotonic()<end:
 try:
  state=json.loads(sh('env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','exchange','{"op":"systemAi","action":"read"}'))
  if state.get('connected') and state.get('models'):break
 except Exception:pass
 time.sleep(.4)
assert state.get('connected') and state.get('models'),'Live model catalog must work before tests'
report['systemModels']={'text':state['textModel'],'image':state['imageModel']};report['defaultInputMethod']=sh('settings','get','secure','default_input_method');report['liveCatalogReady']=True
assert sh('cat','/proc/sys/kernel/random/boot_id')==device['bootId'],'Device rebooted during deployment; repeat after OTA acceptance finishes'
(out/'deployment.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
