#!/usr/bin/env python3
"""Explicit live Jev -> phone Magpie -> hint -> existing Agent acceptance.

Requires the verified v57 components, fixture APK and live UI JAR already installed
on the configured development AVD. Never installs a key, reads an auth cache,
starts a mock upstream, or uses ADB reverse. Only a synthetic page is submitted.
"""
import argparse,datetime,json,os,pathlib,shlex,subprocess,time,uuid,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--serial',required=True)
p.add_argument('--allow-live-model-calls',action='store_true',required=True)
p.add_argument('--model',help='Actual decision catalog ID; defaults to the existing selection, then migrated Jev latest')
a=p.parse_args()
adb=[os.environ.get('ADB',str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb')),'-s',a.serial]
out=ROOT/'out/jev-magpie-live'/time.strftime('%Y%m%dT%H%M%SZ',time.gmtime());out.mkdir(parents=True)
def command(*args,timeout=50):
 r=subprocess.run(adb+list(args),capture_output=True,text=True,timeout=timeout)
 if r.returncode:raise RuntimeError('ADB operation failed: '+args[0]+' (exit '+str(r.returncode)+')')
 return r.stdout
def shell(*args,**kw):return command('shell',shlex.join(args),**kw)
def write(path,data):
 r=subprocess.run(adb+['shell','umask 077; cat > '+shlex.quote(path)],input=data,capture_output=True,timeout=30)
 if r.returncode:raise RuntimeError('Device write failed')
def exchange(data):
 raw=shell('/system/bin/env','CLASSPATH=/data/local/tmp/taku-jev-live.jar','app_process','/system/bin',
  'org.agentos.tests.NativeSocketProbe','exchange',json.dumps(data,ensure_ascii=False))
 return json.loads(raw)
def rpc(method,params):return exchange({'op':'rpc','method':method,'params':params})
def gateway_usage():
 raw=shell('sh','-c','test ! -f /data/local/agentos/magpie/config/magpie/usage.jsonl || cat /data/local/agentos/magpie/config/magpie/usage.jsonl')
 allowed={'route_id','t','provider','host','model','req','served','in','out','ms','status'}
 return [{k:v for k,v in item.items() if k in allowed} for line in raw.splitlines() if line.strip() for item in [json.loads(line)]]
def prefs():return ET.fromstring(shell('cat','/data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml'))
def values(xml):return {e.attrib['name']:ET.tostring(e,encoding='unicode') for e in xml}
def field(xml,name,default=''):
 for e in xml:
  if e.attrib['name']==name:return e.attrib.get('value',e.text or '')
 return default
def set_string(xml,k,v):
 for e in list(xml):
  if e.attrib['name']==k:xml.remove(e)
 ET.SubElement(xml,'string',name=k).text=v
def save_prefs(xml):
 path='/data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml'
 write(path,ET.tostring(xml,encoding='utf-8'));shell('chown','system:system',path);shell('restorecon',path)
assert command('emu','avd','name').splitlines()[0]=='Taku_ImageIntegration_20261010'
assert 'running' in command('emu','avd','status'),'Resume the AVD and reserve its UI before running'
assert shell('id','-u').strip()=='0'
assert 'versionCode=57 ' in shell('dumpsys','package','org.agentos.assistant')
assert rpc('account/read',{'refreshToken':False}).get('account') is not None,'Existing Agent account required'
initial_connection=exchange({'op':'modelConnection','action':'read'})
assert initial_connection['mode']=='openai','This acceptance preserves the existing ChatGPT Agent connection'
pending=shell('sh','-c','for d in /data/user/0/org.agentos.assistant/files/runtime/tasks/*; do [ ! -f "$d/ready" ] || [ -f "$d/done" ] || echo pending; done')
assert not pending.strip(),'Wait for existing tasks to finish'
initial=prefs();before=values(initial);thread='';device_out=''
usage_before={r['route_id'] for r in gateway_usage()}
capture_since=int(float(shell('cat','/proc/uptime').split()[0])*1000)
owned='/data/local/tmp/taku-jev-live-'+uuid.uuid4().hex
shell('mkdir','-p',owned);shell('chmod','700',owned)
write(owned+'/preferences-before.xml',ET.tostring(initial,encoding='utf-8'))
report={'passed':False,'modelResponsesSimulated':False,'pageSynthetic':True}
try:
 # Opening this settings page performs the app's normal, device-local Keystore migration.
 shell('am','start','-n','org.agentos.assistant/.PanelActivity','--es','page','jevConnection')
 deadline=time.monotonic()+45;catalog=[]
 while time.monotonic()<deadline:
  config=prefs()
  if field(config,'jevLegacyMigrated')=='true':
   catalog=exchange({'op':'decision','action':'models'})['models']
   if catalog:break
  time.sleep(.5)
 assert field(config,'jevLegacyMigrated')=='true','In-app credential migration did not complete'
 imported=field(config,'jevImportedProvider')
 candidates=[m['id'] for m in catalog if m['id'].startswith(imported+'/') and 'jev' in m['id'][len(imported)+1:].lower()]
 selected=a.model or (field(config,'jevModel') if field(config,'jevModel') in candidates else next((m for m in candidates if m.endswith('/~typesafe/jev-latest')),candidates[0] if candidates else ''))
 assert selected in candidates,'Choose an actual catalog Jev ID, not another model under the same provider'
 assert shell('sh','-c','test -e /data/user/0/org.agentos.assistant/files/jev-openrouter.enc && echo present || echo absent').strip()=='absent'
 print('Confirmed device-local credential migration and actual Jev decision catalog',flush=True)
 created=rpc('thread/start',{'developerInstructions':'Live acceptance using a synthetic dinner conversation. Read the screenshot and reply in simplified Chinese. Do not browse, execute tools, send messages, reserve, order, or modify data. Give a short plan and state any assumptions.'})
 thread=created['thread']['id'];rpc('thread/name/set',{'threadId':thread,'name':'验收 · Jev / Magpie（合成聊天）'})
 assert created['thread']['modelProvider']=='openai'
 shell('am','force-stop','org.agentos.assistant');config=prefs()
 set_string(config,'conversation',thread);set_string(config,'theme','light')
 set_string(config,'draft-'+thread,'真实 Jev 验收草稿：这段不要发送。')
 set_string(config,'composer.'+thread+'.permissionMode','readOnly')
 attachment='/data/user/0/org.agentos.assistant/files/composer-attachments/jev-live-'+uuid.uuid4().hex+'/keep-live-jev-fixture.txt'
 shell('mkdir','-p',str(pathlib.PurePosixPath(attachment).parent));write(attachment,b'Synthetic unsent attachment. Do not send.')
 shell('chown','-R','system:system',str(pathlib.PurePosixPath(attachment).parent));shell('restorecon','-R',str(pathlib.PurePosixPath(attachment).parent))
 set_string(config,'composer.'+thread+'.attachments',json.dumps([{'path':attachment,'name':'keep-live-jev-fixture.txt','mime':'text/plain'}]))
 for e in list(config):
  if e.attrib['name']=='includeScreenshot':config.remove(e)
 ET.SubElement(config,'boolean',name='includeScreenshot',value='true');save_prefs(config)
 device_out=owned+'/ui/';shell('mkdir','-p',device_out)
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
 report['decisionReceipt']=receipts[0];report['magpieUsage']=usage[0]
 report['agentModel']=created.get('model',created['thread'].get('model'))
 report['legacyCredentialMigrated']=True;report['legacyEncryptedCopyRemoved']=True
 report['decisionCatalogEntry']=next(m for m in catalog if m['id']==selected)
 report['physicalDeviceVerified']=False;report['fullRomVerified']=False
 report['checkedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
 report['passed']=True
finally:
 # Stop only our own unfinished turn before restoring the user's selection.
 if thread and not report.get('passed'):
  try:
   active=exchange({'op':'state','threadId':thread}).get('activeTurnId')
   if active:rpc('turn/interrupt',{'threadId':thread,'turnId':active});time.sleep(1)
  except Exception:pass
 # Restore only fixture-owned state; keep the successfully migrated shared Jev configuration.
 shell('am','force-stop','org.agentos.assistant');current=prefs()
 restore={'conversation','theme','includeScreenshot'}
 for e in list(current):
  k=e.attrib['name']
  if k in restore or (thread and (k.startswith('composer.'+thread+'.') or k in ['draft-'+thread,'task-'+thread])):current.remove(e)
 for e in initial:
  if e.attrib['name'] in restore:current.append(ET.fromstring(ET.tostring(e)))
 save_prefs(current)
 after=values(current)
 preserved=all(after.get(k)==v for k,v in before.items() if k.startswith('draft-') or k.startswith('composer.'))
 report['originalDraftPreferencesPreserved']=preserved
 report['originalConversationRestored']=field(current,'conversation')==field(initial,'conversation')
 restored=exchange({'op':'modelConnection','action':'read'})
 report['mainConnectionRestored']=all(restored.get(k)==initial_connection.get(k) for k in ['mode','model'])
 if thread:
  try:rpc('thread/archive',{'threadId':thread});report['testThreadArchived']=True
  except Exception:report['testThreadArchived']=False
 shell('am','force-stop','org.agentos.jevfixture')
 shell('rm','-rf',owned)
 if thread and 'attachment' in locals():shell('rm','-rf',str(pathlib.PurePosixPath(attachment).parent))
 report['passed']=report['passed'] and all(report.get(k) for k in ['originalDraftPreferencesPreserved','originalConversationRestored','mainConnectionRestored','testThreadArchived'])
 (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
assert report['passed'] and report['originalDraftPreferencesPreserved'] and report['originalConversationRestored'] and report['mainConnectionRestored'] and report['testThreadArchived']
print(json.dumps({'passed':True,'output':str(out),'model':selected,'taskId':report['taskId'],'modelResponsesSimulated':False},ensure_ascii=False))
