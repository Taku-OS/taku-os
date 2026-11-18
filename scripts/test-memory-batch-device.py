#!/usr/bin/env python3
"""Real-page, real-Jev batching acceptance on the designated development AVD only."""
import argparse,json,os,re,shlex,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'out/memory-batch-device';OUT.mkdir(parents=True,exist_ok=True)
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--phase',choices=['capture','live','ui','restore'],required=True);p.add_argument('--allow-live-model-calls',action='store_true');args=p.parse_args()
ADB=os.environ.get('ADB',str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
def adb(*a,check=True):
 r=subprocess.run([ADB,'-s',args.serial,*a],stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=60)
 if check:r.check_returncode()
 return r.stdout.strip()
def sh(*a,check=True):return adb('shell',shlex.join(a),check=check)
def call(method,values=None,authority='org.agentos.memory'):
 raw=sh('content','call','--user','0','--uri','content://'+authority,'--method',method,'--arg',json.dumps(values or {},ensure_ascii=False));result=json.loads(raw[raw.index('result=')+7:raw.rindex('}]')]);assert result['ok'],result;return result['result']
def status():return call('status')
def configure(**kw):return call('configure',kw)
def search(q):return call('search',{'query':q,'limit':20})['items']
def save(name,v):(OUT/name).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def load(name):return json.loads((OUT/name).read_text())
def wait_for(f,seconds=40):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  v=f()
  if v:return v
  time.sleep(.4)
 raise AssertionError('Timed out waiting for expected state')
def tasks():return set(sh('sh','-c','for d in /data/user/0/org.agentos.assistant/files/runtime/tasks/*; do [ -f "$d/ready" ] && echo "${d##*/}"; done; true').split())
def shot(name):
 f='/data/local/tmp/taku-batch-'+name+'.png';sh('screencap','-p',f);adb('pull',f,str(OUT/(name+'.png')));sh('rm',f)
def ui():
 sh('uiautomator','dump','/data/local/tmp/taku-batch-ui.xml');return ET.fromstring(sh('cat','/data/local/tmp/taku-batch-ui.xml'))
def tap(label):
 nodes=[n for n in ui().iter('node') if label in [n.get('text'),n.get('content-desc')]];assert nodes,label
 nodes.sort(key=lambda n:n.get('clickable')!='true')
 n=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));sh('input','tap',str((n[0]+n[2])//2),str((n[1]+n[3])//2))
assert args.serial=='emulator-5634' and sh('getprop','ro.boot.qemu.avd_name')=='Taku_ImageIntegration_20261010'
if args.phase=='capture':
 if not (OUT/'baseline.json').exists():save('baseline.json',{'accessibility':sh('settings','get','secure','enabled_accessibility_services'),'accessibilityEnabled':sh('settings','get','secure','accessibility_enabled'),'avdInitiallyPaused':False})
 original=status();assert not original['analysisEnabled'] and original['analysisQueued']==0,'Requires idle test-only memory setup; never clear existing records'
 save('original-config.json',original);save('original-tasks.json',sorted(tasks()))
 catalog=call('memory',{'action':'models'},'org.agentos.assistant.memory-gateway')['models'];model=next(r['id'] for r in catalog if re.search(r'(^|/)~?jev',r['id'],re.I))
 configure(enabled=True,analysisEnabled=False,queryEnabled=True,proactive=False,model=model,sources=['org.agentos.memorytests'])
 b=load('baseline.json');old=b['accessibility'];services='' if old in ['', 'null'] else old+':'
 sh('settings','put','secure','enabled_accessibility_services',services+'org.agentos.memory/.ObservationService');sh('settings','put','secure','accessibility_enabled','1');wait_for(lambda:status()['observationConnected'])
 marker='BATCH_RUN_'+str(time.time_ns());before=status()['analysisBatchAttempts'];ids=[]
 for index in range(6):
  body=f'合成会议资料，非真实工作信息。{marker}。第 {index+1} 项：产品评审后，小林负责明天 17:00 前提交设计稿，小陈负责明天 18:00 前完成接口联调，周五 10:00 检查验收结果；需要整理负责人、截止时间与依赖。本页用于自动记忆批处理验收。'
  sh('am','start','-n','org.agentos.memorytests/.PageActivity','--es','body',body,'--es','mode','normal')
  records=wait_for(lambda:search(marker));wait_for(lambda:len(search(marker))==index+1)
 records=search(marker);assert len(records)==6 and all('UNSENT_DRAFT' not in r['body'] for r in records);assert status()['analysisQueued']==6 and status()['analysisBatchAttempts']==before
 # Wait beyond the production coalescing window while analysis is off: no model dispatch.
 deadline=time.monotonic()+22
 while time.monotonic()<deadline:assert status()['analysisBatchAttempts']==before;time.sleep(1)
 # Kill only this test app process, leaving the other Android components and accounts intact.
 pid=sh('pidof','org.agentos.memory');assert pid;sh('kill','-9',*pid.split());time.sleep(1)
 after=status();assert not after['analysisEnabled'] and after['analysisQueued']==6 and after['analysisBatchAttempts']==before;assert len(search(marker))==6
 assert tasks()==set(load('original-tasks.json'))
 save('capture.json',{'passed':True,'marker':marker,'ids':[r['id'] for r in records],'model':model,'localRecords':6,'analysisOffBeyondMergeWindow':True,'attemptsBefore':before,'requestsWhileOff':0,'realProcessKillQueueSurvived':True,'unsentInputExcluded':True,'pageSynthetic':True});print('Six automatic local captures, zero analysis requests while off, queue survived process kill',flush=True)
elif args.phase=='live':
 assert args.allow_live_model_calls,'Explicit opt-in is required for real model usage';data=load('capture.json');assert status()['analysisQueued']==6
 configure(analysisEnabled=True);saw_remainder=False;wakes=0;last_wake=0;start=time.monotonic()
 while time.monotonic()-start<180:
  s=status();records=search(data['marker']);attempts=s['analysisBatchAttempts']-data['attemptsBefore']
  if attempts==1 and s['analysisQueued']==1:saw_remainder=True
  if len(records)==6 and all(r['analysis']=='ready' for r in records):break
  # Wake the real JobService for emulator reproducibility; store coalescing, leases and quotas still apply.
  if time.monotonic()-last_wake>=5:
   for job in ['4102','4103']:sh('cmd','jobscheduler','run','-f','org.agentos.memory',job,check=False)
   wakes+=1;last_wake=time.monotonic()
  time.sleep(.4)
 else:raise AssertionError('Queued real-model batches did not complete: '+json.dumps(status()))
 s=status();records=search(data['marker']);assert s['analysisQueued']==0 and s['analysisInFlight']==0;assert s['analysisBatchAttempts']-data['attemptsBefore']==2,s
 assert s['lastBatchSize']==1 and saw_remainder,'Expected five-record batch and a later one-record refill';assert all(r['model']==data['model'] and 'UNSENT_DRAFT' not in r['body'] for r in records)
 assert tasks()==set(load('original-tasks.json')),'Classification must not create Agent tasks or notifications when proactive is off'
 save('live.json',{'passed':True,'realJev':True,'mockedModelResponse':False,'model':data['model'],'localRecords':6,'batchRequests':2,'batchSizes':[5,1],'remainderObservedAfterFirstBatch':True,'allRecordsAnalyzed':True,'proactiveOffDidNotDisableAnalysis':True,'noAgentTasks':True,'elapsedSeconds':time.monotonic()-start,'schedulerWakeRounds':wakes,'productionRateGateBypassed':False,'records':[{k:r[k] for k in ['id','revision','source','analysis','category','model','decision','confidence']} for r in records]});print('Real Jev processed six records in two batches (5 + 1); backfill completed',flush=True)
elif args.phase=='ui':
 sh('am','start','-n','org.agentos.memory/.MemoryActivity');tap('设置');tree=ui();text='\n'.join(n.get('text','') for n in tree.iter('node'));assert '模型分析（Jev / Magpie）' in text and '待分析 0 条' in text;shot('analysis-enabled')
 before=status()['analysisBatchAttempts'];tap('模型分析（Jev / Magpie）');s=status();assert not s['analysisEnabled'] and s['enabled'] and not s['proactive'];shot('analysis-disabled-recording-enabled')
 tap('模型分析（Jev / Magpie）');tap('开启');assert status()['analysisEnabled'];assert status()['analysisBatchAttempts']==before
 sh('input','swipe','800','2000','800','700','500');shot('batch-status')
 save('ui.json',{'passed':True,'separateAnalysisSwitchVisible':True,'analysisOffLeavesRecordingOn':True,'independentConsentDialog':True,'emptyQueueNoNewRequests':True,'pendingCountVisible':True});print('Actual independent switches, consent and queue status UI passed',flush=True)
elif args.phase=='restore':
 original=load('original-config.json');keys=['enabled','analysisEnabled','queryEnabled','proactive','model','sources','retentionDays'];configure(**{k:original[k] for k in keys})
 b=load('baseline.json')
 for key,old in [('enabled_accessibility_services',b['accessibility']),('accessibility_enabled',b['accessibilityEnabled'])]:
  if old=='null':sh('settings','delete','secure',key)
  else:sh('settings','put','secure',key,old)
  assert sh('settings','get','secure',key)==old
 if b['avdInitiallyPaused']:assert 'OK' in adb('emu','avd','stop')
 save('cleanup.json',{'passed':True,'originalMemoryConfigurationRestored':True,'previousAccessibilityRestored':True,'originalAvdPauseRestored':True,'syntheticRecordsRetained':True});print('Original configuration and AVD pause restored',flush=True)
