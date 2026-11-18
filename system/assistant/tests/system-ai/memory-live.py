#!/usr/bin/env python3
"""Real Memory observation/queue/model acceptance on the synthetic chat page; restores collection policy."""
import argparse,json,pathlib,shlex,subprocess,time
p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');p.add_argument('--allow-physical',action='store_true');p.add_argument('--allow-live-model-calls',action='store_true');p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--fixture-page',action='store_true');p.add_argument('--forget-test-record',action='store_true');a=p.parse_args()
if not a.allow_live_model_calls or (not a.serial.startswith('emulator-') and not a.allow_physical):p.error('Explicit physical/live model authorization required')
if a.forget_test_record and not a.fixture_page:p.error('Cleanup requires the uniquely marked fixture page')
out=a.output;out.mkdir(parents=True,exist_ok=True);adb=['adb','-s',a.serial];pkg='org.agentos.memory';source='org.agentos.memorytests' if a.fixture_page else 'org.agentos.inputmethod.chatcase';marker='romqa'+str(time.time_ns());component=pkg+'/.ObservationService';old=None;original_services=None;original_enabled=None;record=None;report={'modelResponsesSimulated':False,'pageSynthetic':True,'observationInjected':False,'passed':False}
def sh(*v,check=True):
 r=subprocess.run(adb+['shell',shlex.join(v)],text=True,capture_output=True,timeout=60)
 if check and r.returncode:raise RuntimeError('ADB operation failed: '+v[0])
 return r.stdout.strip()
def call(method,params=None):
 raw=sh('content','call','--user','0','--uri','content://org.agentos.memory','--method',method,'--arg',json.dumps(params or {},separators=(',',':')));start=raw.index('result=')+7;end=raw.rindex('}]');r=json.loads(raw[start:end]);assert r['ok'],r;return r['result']
def exchange(**v):return json.loads(sh('env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','exchange',json.dumps(v,separators=(',',':'))))
def shot(name):(out/(name+'.png')).write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
def verify_accessibility(label):
 expected={k:v for k,v in [('enabled_accessibility_services',original_services),('accessibility_enabled',original_enabled)] if v is not None}
 actual={k:sh('settings','get','secure',k) for k in expected}
 report[label]={'expected':expected,'actual':actual,'passed':actual==expected}
 assert actual==expected,'Original accessibility settings must match their readback'
def restore_original_state():
 errors=[]
 def attempt(step,operation):
  try:operation()
  except Exception as error:errors.append({'step':step,'errorType':type(error).__name__})
 for key,value in [('enabled_accessibility_services',original_services),('accessibility_enabled',original_enabled)]:
  if value is not None:
   attempt(key,lambda key=key,value=value:sh('settings','delete','secure',key) if value=='null' else sh('settings','put','secure',key,value))
 if old is not None:
  restore={k:old[k] for k in ['enabled','analysisEnabled','queryEnabled','proactive','sources','retentionDays']};restore['followSystem']=old['followsSystem'];restore['model']=old['modelOverride']
  attempt('memoryPolicyWrite',lambda:call('configure',restore))
 # Both restoration writes are attempted before either readback can fail.
 attempt('accessibilityReadback',lambda:verify_accessibility('accessibilityAfterRestore'))
 if old is not None:
  def verify_memory():
   restored=call('status');report['collectionSettingsRestored']=all(restored[k]==v for k,v in restore.items() if k not in ['followSystem','model']) and restored['followsSystem']==old['followsSystem'] and restored['modelOverride']==old['modelOverride']
   assert report['collectionSettingsRestored'],'Memory policy must be restored before UI inspection'
  attempt('memoryPolicyReadback',verify_memory)
 if errors:
  report['restorationErrors']=errors;report['passed']=False
  raise RuntimeError('Original settings restoration failed: '+','.join(e['step'] for e in errors))
def forget_owned_record():
 if not a.forget_test_record or not record or report.get('testRecordRemoved'):return
 current=call('evidence',{'id':record['id']});assert current['source']==source and marker in current['body'],'Cleanup is limited to this unique synthetic record'
 assert call('forget',{'id':record['id']})['forgotten'];report['testRecordRemoved']=True
try:
 assert sh('id','-u')=='0';old=call('status');assert old['followsSystem'],'Test starts with Memory following system models'
 original_services=sh('settings','get','secure','enabled_accessibility_services');original_enabled=sh('settings','get','secure','accessibility_enabled')
 report['initialCounts']={'count':old['count'],'queued':old['analysisQueued']};system=exchange(op='systemAi',action='read');report['systemModel']=system['textModel'];report['systemRevision']=system['revision'];baseline=system['usage']
 call('configure',{'enabled':True,'analysisEnabled':True,'queryEnabled':True,'proactive':False,'followSystem':True,'model':'','sources':[source]})
 services=[s for s in original_services.split(':') if s and s!='null'];
 if component not in services:services.append(component)
 sh('settings','put','secure','enabled_accessibility_services',':'.join(services));sh('settings','put','secure','accessibility_enabled','1')
 sh('cmd','voiceinteraction','hide',check=False);sh('am','force-stop',source)
 if a.fixture_page:
  body='合成群聊 · 周五约饭\n阿泽：周五晚上七点半，在地铁口火锅店集合。\n小林：我们一共四个人，餐位还没有预订，需要再确认。\n米粒：请大家先确认能否到场，再商量谁联系餐厅。\n这不是用户真实聊天。'+marker
  sh('am','start','-W','-n',source+'/.PageActivity','--es','title','系统 AI · 记忆整理验收','--es','body',body)
 else:sh('am','start','-W','-n',source+'/.ChatActivity','--ez','secure','false','--ez','password','false')
 # UiAutomation suppresses accessibility services by default. Do not run UI probes during capture.
 time.sleep(2);shot('07-memory-source-page');deadline=time.monotonic()+100;record=None;forced=False
 while time.monotonic()<deadline:
  items=call('search',{'query':marker if a.fixture_page else '周五','limit':20})['items'];records=[r for r in items if r['source']==source and '周五' in r['body'] and (not a.fixture_page or marker in r['body'])]
  if records:record=records[0]
  if record and record['analysis']=='ready':break
  if record and not forced and time.time()*1000-record['first_seen']>21000:
   for job in ['4102','4103']:sh('cmd','jobscheduler','run','-f',pkg,job,check=False)
   forced=True
  time.sleep(1)
 assert record and record['analysis']=='ready',{'record':record,'status':call('status')}
 record=call('get',{'id':record['id']});assert record['source']==source and record['analysis']=='ready'
 if a.fixture_page:assert marker in record['body']
 assert record['model']==system['textModel'],record
 assert 'UNSENT_DRAFT_MUST_NOT_BE_RECORDED' not in record['body'],record
 after=exchange(op='systemAi',action='read');receipts=[r for r in after['usage'] if r not in baseline and r['consumer']=='memory' and r['status']=='completed'];assert receipts,after['usage']
 report.update(passed=True,sourceRecord=record,receipts=receipts,queueAfter=call('status')['analysisQueued'],queryVerified=True,uniqueTestMarker=marker if a.fixture_page else '')
 print('PASS real observed group chat was queued and classified by the system text model',flush=True)
finally:
 try:
  restore_original_state()
 except Exception:
  report['passed']=False
  raise
 finally:
  try:
   if not report['passed']:forget_owned_record()
  finally:(out/'memory-live.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
try:
 if report['passed']:
  sh('am','start','-W','-f','0x14000000','-n',pkg+'/.MemoryActivity');time.sleep(.5)
  # Collection has already been restored before this read-only UI probe.
  def nodes():
   raw=sh('am','instrument','-w','org.agentos.inputmethod.probe/.UiProbe');line=next((s.split('=',1)[1] for s in raw.splitlines() if s.startswith('INSTRUMENTATION_RESULT: nodes=')),None)
   return [n for n in json.loads(line or '[]') if n.get('package')==pkg]
  ns=nodes();assert ns,'Memory must be foreground before its result screenshot';tab=next((n for n in ns if n.get('content-desc') in ['记忆','Memory']),None)
  import re
  def tap(n):
   x,y,z,w=map(int,re.findall(r'\d+',n['bounds']));sh('input','tap',str((x+z)//2),str((y+w)//2));time.sleep(.3)
  if tab:tap(tab)
  if a.fixture_page:
   field=next(n for n in nodes() if n.get('editable'));tap(field);sh('input','keycombination','113','29');sh('input','keyevent','DEL')
   # Numeric suffix stays literal through Pinyin key events, preserving the
   # owner's language mode while still filtering to the unique owned record.
   query=marker.removeprefix('romqa');sh('input','text',query);sh('input','keyevent','BACK')
   assert any(n.get('editable') and n.get('text')==query for n in nodes()),'Result screenshot must filter to this synthetic record'
   report['resultScreenshotQuery']=query
   report['resultScreenshotFilteredToSynthetic']=True
  shot('08-memory-real-result')
except Exception:
 report['passed']=False
 raise
finally:
 try:
  forget_owned_record()
  verify_accessibility('accessibilityAfterUi')
 except Exception:
  report['passed']=False
  raise
 finally:(out/'memory-live.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
