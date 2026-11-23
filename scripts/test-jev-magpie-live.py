#!/usr/bin/env python3
"""Explicit live Jev -> phone Magpie -> hint -> existing Agent acceptance.

Requires matching Expo and Kotlin Assistant components, the fixture APK, and the
current UI JAR on the configured development AVD. Uses the existing provider and
account and submits only a synthetic page.
"""
import argparse,datetime,json,os,pathlib,shlex,subprocess,time,uuid
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--allow-live-model-calls',action='store_true',required=True)
p.add_argument('--model',help='Image-capable Jev group or its configured model ID; defaults to the selected group')
a=p.parse_args()
adb=[os.environ.get('ADB',str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb')),'-s',a.serial]
out=ROOT/'out/jev-magpie-live'/time.strftime('%Y%m%dT%H%M%SZ',time.gmtime());out.mkdir(parents=True)
def command(*args,timeout=50):
 r=subprocess.run(adb+list(args),capture_output=True,text=True,timeout=timeout)
 if r.returncode:raise RuntimeError('ADB operation failed: '+args[0]+' (exit '+str(r.returncode)+')')
 return r.stdout
def shell(*args,**kw):return command('shell',shlex.join(args),**kw)
def exchange(data):
 raw=shell('/system/bin/env','CLASSPATH=/data/local/tmp/taku-jev-live.jar','app_process','/system/bin',
  'org.agentos.tests.NativeSocketProbe','exchange',json.dumps(data,ensure_ascii=False))
 return json.loads(raw)
def rpc(method,params):return exchange({'op':'rpc','method':method,'params':params})
def access(method,params):
 raw=shell('content','call','--uri','content://org.agentos.assistant.open-access','--method',method,'--arg',json.dumps(params))
 result=json.JSONDecoder().raw_decode(raw.split('result=',1)[1])[0]
 if not result['ok']:raise RuntimeError(method+': '+result['code'])
 return result['result']
def manage(operation,**params):return access('manageSystemAi',{'operation':operation,**params})
def gateway_usage():
 raw=shell('sh','-c','test ! -f /data/local/agentos/magpie/config/magpie/usage.jsonl || cat /data/local/agentos/magpie/config/magpie/usage.jsonl')
 allowed={'route_id','t','provider','host','model','req','served','in','out','ms','status'}
 return [{k:v for k,v in item.items() if k in allowed} for line in raw.splitlines() if line.strip() for item in [json.loads(line)]]
assert command('emu','avd','name').splitlines()[0]=='Taku_ImageIntegration_20261010'
assert 'running' in command('emu','avd','status'),'Resume the AVD and reserve its UI before running'
assert shell('id','-u').strip()=='0'
assert 'org.agentos.agent/' in shell('cmd','package','resolve-activity','--brief','-n','org.agentos.agent/.MainActivity')
assert rpc('account/read',{'refreshToken':False}).get('account') is not None,'Existing Agent account required'
initial_connection=manage('readConnection')
assert initial_connection['mode']=='openai','This acceptance preserves the existing ChatGPT Agent connection'
pending=shell('sh','-c','for d in /data/user/0/org.agentos.assistant/files/runtime/tasks/*; do [ ! -f "$d/ready" ] || [ -f "$d/done" ] || echo pending; done')
assert not pending.strip(),'Wait for existing tasks to finish'
thread='';undo=None
initial_jev=manage('readJev')
screenshots=shell('settings','--user','0','get','secure','assist_screenshot_enabled').strip()
usage_before={r['route_id'] for r in gateway_usage()}
capture_since=int(float(shell('cat','/proc/uptime').split()[0])*1000)
owned='/data/local/tmp/taku-jev-live-'+uuid.uuid4().hex
shell('mkdir','-p',owned);shell('chmod','700',owned)
device_out=owned+'/ui/';shell('mkdir','-p',device_out)
report={'passed':False,'modelResponsesSimulated':False,'pageSynthetic':True}
try:
 catalog={m['id']:m for m in initial_jev['models']}
 def is_jev(model):
  return model.partition('/')[2].lower().startswith(('typesafe/jev','~typesafe/jev','jev'))
 groups={'group/'+g['id']:g for g in access('readSystemAi',{})['groups']}
 candidates={key:g for key,g in groups.items() if catalog.get(key,{}).get('imageInput') and g['members']
             and all(is_jev(member) for member in g['members'])}
 selected=a.model or initial_jev['selected']
 if selected not in candidates:
  matches=[key for key,g in candidates.items() if selected in g['members']]
  if a.model:assert len(matches)==1,'Choose a configured image-capable Jev group'
  selected=next(iter(matches or candidates),'')
 assert selected in candidates,'No image-capable Jev group is configured in Taku AI'
 if selected!=initial_jev['selected']:
  undo=access('bindSystemAi',{'scene':'jev','group':candidates[selected]['id']})['undo']
 if screenshots!='1':manage('setAssistScreenshots',enabled=True)
 created=rpc('thread/start',{'developerInstructions':'Live acceptance using a synthetic dinner conversation. Read the screenshot and reply in simplified Chinese. Do not browse, execute tools, send messages, reserve, order, or modify data. Give a short plan and state any assumptions.'})
 thread=created['thread']['id'];rpc('thread/name/set',{'threadId':thread,'name':'验收 · Jev / Magpie（合成聊天）'})
 assert created['thread']['modelProvider']=='openai'
 result=command('shell','timeout','270','uiautomator','runtest','/system/framework/android.test.base.jar',
  '/data/local/tmp/taku-jev-live.jar','-c','org.agentos.tests.JevMagpieLiveUiTest#testLiveJevMagpieChain',
  '-e','output',device_out,'-e','model',selected,'-e','thread',thread,'-e','liveAuthorized','true',timeout=295)
 (out/'ui-test.txt').write_text(result)
 command('pull',device_out,str(out/'device'))
 assert 'OK (1 test)' in result and 'FAILURES' not in result,'Live UI acceptance failed; inspect its synthetic-only UI log'
 report=json.loads((out/'device/live-acceptance.json').read_text())
 assert report['passed']
 report['passed']=False
 receipts=[]
 for line in shell('logcat','-d','-v','raw','-s','TakuJevDecision:I','*:S').splitlines():
  try:item=json.loads(line)
  except ValueError:continue
  if item.get('model')==selected and item.get('gateway')=='magpie' and item.get('capturedAt',0)>=capture_since:receipts.append(item)
 assert len(receipts)==1 and receipts[0]['decision']=='dining_plan' and receipts[0]['confidence']>=.65,'Expected one actionable production receipt'
 usage=[r for r in gateway_usage() if r.get('route_id') not in usage_before and r.get('req')==selected]
 assert len(usage)==1 and usage[0]['status']==200 and usage[0]['host']=='openrouter.ai','Expected one real OpenRouter route through phone Magpie'
 report['decisionLogReceipt']=receipts[0];report['magpieUsage']=usage[0]
 report['agentModel']=created.get('model',created['thread'].get('model'))
 report['decisionCatalogEntry']=catalog[selected]
 report['physicalDeviceVerified']=False;report['fullRomVerified']=False
 report['checkedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
 report['passed']=True
finally:
 if manage('readJev')['enabled']!=initial_jev['enabled']:
  manage('setJev',enabled=initial_jev['enabled'])
 if undo is not None:access('restoreSystemAi',{'undo':undo})
 if screenshots=='null':shell('settings','--user','0','delete','secure','assist_screenshot_enabled')
 elif screenshots!='1':shell('settings','--user','0','put','secure','assist_screenshot_enabled',screenshots)
 restored=manage('readConnection')
 report['mainConnectionRestored']=all(restored.get(k)==initial_connection.get(k) for k in ['mode','model'])
 jev=manage('readJev')
 report['jevSettingsRestored']=all(jev.get(k)==initial_jev.get(k) for k in ['selected','enabled'])
 if thread:
  try:
   rpc('thread/archive',{'threadId':thread});report['testThreadArchived']=True
  except Exception as absent:
   if 'no rollout found' not in str(absent):raise
   report['testThreadArchived']=True
 shell('am','force-stop','org.agentos.jevfixture')
 shell('rm','-rf',owned)
 report['passed']=report['passed'] and all(report.get(k) for k in ['jevSettingsRestored','mainConnectionRestored','testThreadArchived'])
 (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
assert report['passed']
print(json.dumps({'passed':True,'output':str(out),'model':selected,'taskId':report['taskId'],'modelResponsesSimulated':False},ensure_ascii=False))
