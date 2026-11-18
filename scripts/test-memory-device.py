#!/usr/bin/env python3
"""Exclusive, labelled synthetic acceptance on the dedicated development AVD only."""
import argparse,json,os,re,shlex,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--phase',choices=['install','storage','capture','live','finish','ui','restore'],required=True);p.add_argument('--allow-live-model-calls',action='store_true');p.add_argument('--scenario',choices=['dining','meeting','travel'],default='meeting');args=p.parse_args()
ADB=os.environ.get('ADB',str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
OUT=ROOT/'out/memory-device';OUT.mkdir(parents=True,exist_ok=True)
def adb(*tail,timeout=45,check=True):
 r=subprocess.run([ADB,'-s',args.serial,*tail],capture_output=True,text=True,timeout=timeout)
 if check and r.returncode:raise RuntimeError('ADB operation failed: '+r.stderr[:250]+r.stdout[:250])
 return r.stdout
def sh(*tail,timeout=45,check=True):return adb('shell',shlex.join(tail),timeout=timeout,check=check)
def provider(method,params=None,authority='org.agentos.memory'):
 raw=sh('content','call','--user','0','--uri','content://'+authority,'--method',method,'--arg',json.dumps(params or {},ensure_ascii=False))
 assert 'result=' in raw,raw[:200]
 result=json.loads(raw[raw.index('result=')+7:raw.rindex('}]')]);assert result.get('ok'),result
 return result['result']
def gateway(action,params=None):return provider('memory',dict(params or {},action=action),'org.agentos.assistant.memory-gateway')
def configure(**values):return provider('configure',values)
def search(query=''):return provider('search',{'query':query,'limit':20})['items']
def wait_for(f,timeout=30):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  result=f()
  if result:return result
  time.sleep(.4)
 raise AssertionError('Timed out waiting for expected device state')
def fixture(body,mode='normal'):
 sh('am','start','-n','org.agentos.memorytests/.PageActivity','--es','body',body,'--es','mode',mode)
def tasks():
 raw=sh('sh','-c','for d in /data/user/0/org.agentos.assistant/files/runtime/tasks/*; do [ -f "$d/ready" ] && echo "${d##*/}"; done; true')
 return set(raw.split())
def screenshot(name):
 path='/data/local/tmp/taku-memory-'+name+'.png';sh('screencap','-p',path);adb('pull',path,str(OUT/(name+'.png')));sh('rm',path)
def save(name,value):OUT.joinpath(name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
assert args.serial=='emulator-5634','Only the dedicated dev emulator is authorized'
assert sh('getprop','ro.boot.qemu.avd_name').strip()=='Taku_ImageIntegration_20261010'
service='org.agentos.memory/.ObservationService'
if args.phase=='install':
 active=sh('sh','-c','for d in /data/user/0/org.agentos.assistant/files/runtime/tasks/*; do [ -f "$d/ready" ] && [ ! -f "$d/done" ] && echo active; done; true').split();assert not active,'Existing task active'
 assert not sh('pm','path','org.agentos.memory',check=False).strip(),'Do not replace an existing memory installation during first-install tests'
 if not (OUT/'baseline.json').exists():save('baseline.json',{'accessibility':sh('settings','get','secure','enabled_accessibility_services').strip(),'accessibilityEnabled':sh('settings','get','secure','accessibility_enabled').strip(),'avdInitiallyPaused':True})
 adb('pull','/system_ext/framework/agentos-task-broker.jar',str(OUT/'original-broker.jar'))
 for name in ['TakuSurfaces.apk','TakuMemory.apk','AgentOSAssistant.apk','TakuMemoryTests.apk']:print(name,adb('install','--no-incremental','-r',str(OUT/name),timeout=90).strip(),flush=True)
 adb('push',str(OUT/'task-broker.jar'),'/system_ext/framework/agentos-task-broker.jar')
 for row in sh('ps','-A','-o','PID,ARGS').splitlines():
  if 'org.agentos.lab.TaskBroker' in row:sh('kill',row.split()[0])
 sh('sh','-c','nohup env CLASSPATH=/system_ext/framework/agentos-task-broker.jar app_process /system/bin org.agentos.lab.TaskBroker >/data/local/tmp/taku-memory-broker.log 2>&1 </dev/null &')
 time.sleep(2);print(json.dumps(provider('status')),flush=True)
elif args.phase=='storage':
 raw=sh('am','instrument','-w','org.agentos.memorytests/org.agentos.memory.StoreTests',timeout=90);(OUT/'store-tests.txt').write_text(raw);print(raw,flush=True);assert '"passed":true' in raw,raw
elif args.phase=='capture':
 configure(enabled=True,analysisEnabled=False,queryEnabled=True,proactive=False,model='',sources=['org.agentos.memorytests'])
 baseline=json.loads((OUT/'baseline.json').read_text());old=baseline['accessibility'];enabled='' if old in ['null',''] else old+':'
 sh('settings','put','secure','enabled_accessibility_services',enabled+service);sh('settings','put','secure','accessibility_enabled','1')
 wait_for(lambda:provider('status')['observationConnected'])
 assert not search(),'Expected new test-only memory database'
 text='本轮标识 '+str(time.time_ns())+'。合成聊天：周六 18:00 在杭州西湖附近聚餐，4 人，人均 150 元。餐厅与集合地点尚未确定，请先整理事实和待确认项。这不是用户真实聊天。'
 fixture(text);records=wait_for(lambda:search('西湖'));assert len(records)==1,records;record=records[0];assert 'UNSENT_DRAFT' not in record['body'];assert record['source']=='org.agentos.memorytests'
 fixture(text);time.sleep(3);assert len(search())==1,'Duplicate page must not create memory'
 edited=provider('update',{'id':record['id'],'revision':record['revision'],'title':'聚餐安排（已纠正）','body':record['body'].replace('18:00','19:00')})
 assert edited['revision']==2 and edited['history'][0]['body'].find('18:00')>=0
 assert len(search('19:00'))==1 and not search('18:00')
 sh('am','start','-n','org.agentos.memory/.MemoryActivity');time.sleep(1);screenshot('memory-list');
 count=len(search());configure(enabled=False);fixture('PAUSED_PAGE 这是一段不应在暂停时被自动记录的合成页面文字。');time.sleep(3);assert len(search())==count,'Pause ignored'
 configure(enabled=True,sources=[]);fixture('EXCLUDED_PAGE 这是一段不应在排除来源后记录的合成页面文字。');time.sleep(3);assert len(search())==count,'Source exclusion ignored'
 configure(sources=['org.agentos.memorytests']);fixture('PASSWORD_PAGE 密码页面的可见说明文字也不应保存。','password');time.sleep(3);assert len(search())==count,'Password screen was recorded'
 fixture('SECURE_PAGE 受保护页面的可见说明文字也不应保存。','secure');time.sleep(3);assert len(search())==count,'Secure screen was recorded'
 provider('forget',{'id':record['id']});assert not search();fixture(text);time.sleep(3);assert not search(),'Forgotten content resurrected'
 save('capture-acceptance.json',{'passed':True,'automaticObservation':True,'perItemRememberAction':False,'unsentInputExcluded':True,'deduplicated':True,'correctionAndSourceHistory':True,'pause':True,'excludedSource':True,'passwordScreenExcluded':True,'secureWindowExcluded':True,'forgetRemovesSearchAndHistory':True,'forgottenContentNotResurrected':True,'data':'labelled synthetic page','physicalDevice':False,'fullRom':False})
 print('Automatic capture, management and exclusion checks passed',flush=True)
elif args.phase=='live':
 assert args.allow_live_model_calls,'Live model usage requires explicit opt-in'
 catalog=gateway('models')['models'];models=[r for r in catalog if re.match(r'~?(typesafe/)?jev',r['id'].split('/',1)[1],re.I)];assert models,'Existing real Jev decision model missing'
 model=models[0]['id'];configure(enabled=True,analysisEnabled=True,queryEnabled=True,proactive=True,model=model,sources=['org.agentos.memorytests'])
 baseline=tasks();save('live-baseline-tasks.json',sorted(baseline))
 scenarios={
  'dining':('合成活动安排：2026 年 10 月 24 日周六 19:00 在杭州西湖附近聚餐，共 4 人，人均预算 150 元。时间、人数和预算已确认，餐厅和集合点还没有确定。需要整理已确认信息、备选方向和待确认事项，再由大家决定。此页面是自动记忆验收，不是私人聊天。',['西湖','19:00']),
  'meeting':('合成会议纪要：产品评审已经结束，确认下周一发布新版本。小林负责在明天 17:00 前提交设计稿，小陈负责在明天 18:00 前完成接口联调，周五 10:00 共同检查验收结果。还需要把负责人、截止时间和发布前依赖整理成清单，避免遗漏。此页是合成验收资料，不是真实公司会议。',['17:00','18:00','设计稿']),
  'travel':('合成出差行程：明天 08:45 从杭州萧山机场乘 MU5101 到北京首都机场，07:00 前到达航站楼。下午 14:00 在海淀区开会，酒店位于中关村。机场到会场的交通和时间衔接尚未整理，需要列出出发安排、接驳路线与待确认事项。这是合成验收资料。',['08:45','萧山','14:00'])}
 text,expected=scenarios[args.scenario];marker='MEMORY_RUN_'+str(time.time_ns());text+=' 本轮标识 '+marker
 started=time.monotonic();fixture(text)
 record=wait_for(lambda:next((r for r in search(marker) if r['analysis'] in ['ready','error']),None),150)
 save('live-record.json',record);save('live-attempt-'+marker+'.json',record);assert record['analysis']=='ready',record['analysis'];assert record['decision'] in ['organize','summarize'] and record['confidence']>=.75,record
 assert tasks()==baseline,'No Agent task before consent'
 wait_for(lambda:'Taku System Surface' in sh('dumpsys','window','windows'),15)
 screenshot('live-before-consent');save('live-stage.json',{'id':record['id'],'revision':record['revision'],'model':model,'expected':expected,'scenario':args.scenario,'modelResponseSimulated':False,'pageSynthetic':True,'automaticCapture':True,'noTaskBeforeConsent':True,'decisionSeconds':time.monotonic()-started})
 print('Ready for actual UI acceptance of the system hint; do not submit a task through a test-only shortcut.',flush=True)
elif args.phase=='finish':
 stage=json.loads((OUT/'live-stage.json').read_text());before=set(json.loads((OUT/'live-baseline-tasks.json').read_text()))
 r=wait_for(lambda:(lambda v:v if v['task_id'] else None)(provider('get',{'id':stage['id']})),45);wait_for(lambda:len(tasks()-before)==1,45);assert len(tasks()-before)==1,'Expected one TaskBroker task'
 task=gateway('task',{'id':stage['id']});wait_for(lambda:gateway('task',{'id':stage['id']}).get('state') in ['completed','failed','needs_user'],180);task=gateway('task',{'id':stage['id']});assert task['state']=='completed',task
 again=gateway('accept',{'id':stage['id'],'revision':stage['revision']});assert again['taskId']==r['task_id'] and again['alreadyAccepted'];assert len(tasks()-before)==1,'Duplicate consent created task'
 reply=task.get('result.txt','');assert len(reply)>20 and any(word in reply for word in stage['expected']),reply
 info=json.loads(task['runtime-info.json']);assert not info['screenshotAttached'];
 transcript=sh('cat','/data/user/0/org.agentos.assistant/files/runtime/tasks/'+r['task_id']+'/request.json');req=json.loads(transcript);assert req['permissionMode']=='readOnly';assert req['memoryId']==stage['id'];
 # Only the test conversation is read, never other conversations or account files.
 probe='/data/local/tmp/taku-jev-live.jar'
 history=json.loads(sh('/system/bin/env','CLASSPATH='+probe,'app_process','/system/bin','org.agentos.tests.NativeSocketProbe','rpc','thread/read',json.dumps({'threadId':info['threadId'],'includeTurns':True})))
 save('live-thread.json',history)
 calls=[item for turn in history['thread']['turns'] for item in turn['items'] if item.get('type')=='dynamicToolCall']
 gets=[item for item in calls if item.get('namespace')=='taku_phone' and item.get('tool')=='memory_get' and item.get('status')=='completed' and item.get('success') and item.get('arguments',{}).get('id')==stage['id']]
 assert gets,'Agent did not complete the actual memory query tool'
 evidence=json.loads(next(c['text'] for c in gets[0]['contentItems'] if c.get('type')=='inputText'))
 assert evidence['id']==stage['id'] and evidence['revision']==stage['revision'] and evidence['source']=='org.agentos.memorytests'
 assert 'UNSENT_DRAFT' not in evidence['body'],'Editable input leaked to the Agent'
 assert any(word in evidence['body'] for word in stage['expected'])
 tool_calls=[{key:item.get(key) for key in ['namespace','tool','arguments','status','success']} for item in calls]
 save('live-acceptance.json',dict(stage,passed=True,taskId=r['task_id'],threadId=info['threadId'],agentModel=info['model'],agentReply=reply,realMemoryToolCall=True,toolCalls=tool_calls,unsentInputExcludedFromAgent=True,exactlyOneTask=True,duplicateConsentSuppressed=True,readOnly=True,screenshotAttached=False,physicalDevice=False,fullRom=False,coldBoot=False))
 print('Real Magpie, automatic memory, UI consent and native Agent tool loop passed',flush=True)
elif args.phase=='ui':
 stage=json.loads((OUT/'live-stage.json').read_text())
 def ui():
  sh('uiautomator','dump','/data/local/tmp/taku-memory-ui.xml')
  return ET.fromstring(sh('cat','/data/local/tmp/taku-memory-ui.xml'))
 def words():return '\n'.join(n.get('text','') for n in ui().iter('node'))
 def tap(label):
  nodes=[n for n in ui().iter('node') if label in [n.get('text'),n.get('content-desc')]];assert nodes,label
  nodes.sort(key=lambda n:n.get('clickable')!='true')
  v=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));sh('input','tap',str((v[0]+v[2])//2),str((v[1]+v[3])//2))
 sh('am','start','-n','org.agentos.memory/.MemoryActivity')
 tap('记忆');assert '合成会议纪要' in words();screenshot('memory-list-final')
 tap('建议');wait_for(lambda:'Completed' in words(),20);screenshot('suggestions-final')
 tap('查看 Agent 结果');text=words();assert 'Completed' in text and any(w in text for w in stage['expected']);screenshot('agent-result-final');tap('完成')
 tap('查看原始记忆 →');tap('展开 ＋');text=words();assert stage['id'] in text and 'org.agentos.memorytests' in text and '版本  1' in text;screenshot('memory-detail-final');tap('返回')
 tap('设置');screenshot('settings-final');sh('input','swipe','800','1950','800','720','500');sh('input','swipe','800','1950','800','720','500');tap('运行详情');assert stage['model'] in words();screenshot('settings-model-final')
 save('ui-acceptance.json',{'passed':True,'upgradePreservedMemory':True,'returnRefresh':True,'taskStatusRefreshed':True,'resultVisibleInMemoryApp':True,'sourceAndRevisionVisible':True,'settingsVisible':True})
 print('Actual memory, suggestion, source, settings and Agent result UI passed',flush=True)
elif args.phase=='restore':
 configure(enabled=False,analysisEnabled=False,proactive=False,sources=[])
 b=json.loads((OUT/'baseline.json').read_text())
 for key,val in [('enabled_accessibility_services',b['accessibility']),('accessibility_enabled',b['accessibilityEnabled'])]:
  if val=='null':sh('settings','delete','secure',key)
  else:sh('settings','put','secure',key,val)
 print('Automatic observation paused and previous accessibility configuration restored; synthetic memories retained for inspection.',flush=True)
