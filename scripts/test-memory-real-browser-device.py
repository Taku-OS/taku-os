#!/usr/bin/env python3
"""Capture real public pages through the production observer on the designated AVD."""
import argparse,json,os,re,shlex,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(os.environ.get('TAKU_MEMORY_REAL_ROOT',str(Path(__file__).resolve().parents[1])))
OUT=ROOT/'out/memory-brand-device';OUT.mkdir(parents=True,exist_ok=True)
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--phase',required=True,choices=['capture','ui','restore']);args=p.parse_args()
ADB=os.environ.get('ADB',str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
def adb(*a):return subprocess.check_output([ADB,'-s',args.serial,*a],stdin=subprocess.DEVNULL,text=True,stderr=subprocess.PIPE,timeout=60).strip()
def sh(*a):return adb('shell',shlex.join(a))
def call(method,v=None):
 raw=sh('content','call','--user','0','--uri','content://org.agentos.memory','--method',method,'--arg',json.dumps(v or {},ensure_ascii=False));r=json.loads(raw[raw.index('result=')+7:raw.rindex('}]')]);assert r['ok'],r;return r['result']
def save(name,v):(OUT/name).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def load(name):return json.loads((OUT/name).read_text())
def wait_for(f,timeout=35):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  v=f()
  if v:return v
  time.sleep(.5)
 raise AssertionError('Expected device state was not reached')
def ui():
 sh('uiautomator','dump','/data/local/tmp/taku-real-memory.xml');return ET.fromstring(sh('cat','/data/local/tmp/taku-real-memory.xml'))
def tap(label):
 nodes=[n for n in ui().iter('node') if label in [n.get('text'),n.get('content-desc')]];assert nodes,label
 nodes.sort(key=lambda n:n.get('clickable')!='true');b=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def reveal(label):
 for _ in range(8):
  if any(label in [n.get('text'),n.get('content-desc')] for n in ui().iter('node')):return
  sh('input','swipe','600','1900','600','850','350')
 raise AssertionError('Could not reveal '+label)
def shot(name):
 time.sleep(.5);path='/data/local/tmp/taku-real-'+name+'.png';sh('screencap','-p',path);adb('pull',path,str(OUT/(name+'.png')));sh('rm',path)
def connect():
 b=load('baseline.json');old=b['accessibility'];services='' if old in ['null',''] else old+':'
 sh('settings','put','secure','enabled_accessibility_services','' if old in ['null',''] else old);time.sleep(.4)
 sh('settings','put','secure','enabled_accessibility_services',services+'org.agentos.memory/.ObservationService');sh('settings','put','secure','accessibility_enabled','1')
 wait_for(lambda:call('status')['observationConnected'])
assert args.serial=='emulator-5634' and sh('getprop','ro.boot.qemu.avd_name')=='Taku_ImageIntegration_20261010'
assert (OUT/'baseline.json').exists(),'Record original configuration and pause state before starting'
if args.phase=='capture':
 assert not call('status')['analysisEnabled'],'Real public-page UI acceptance does not require model requests'
 pages=[('work','https://docs.taku.ai/harness/overview/','理解 Taku OS 的 Harness'),('development','https://developer.android.com/guide/components/activities/tasks-and-back-stack?hl=zh-cn','任务和返回堆栈'),('visit','https://www.metmuseum.org/plan-your-visit','Plan Your Visit')]
 results=load('scenarios.json') if (OUT/'scenarios.json').exists() else [];attempts=call('status')['analysisBatchAttempts']
 for name,url,expected in pages:
  if any(r['scenario']==name and r['url']==url for r in results):continue
  call('configure',{'enabled':False,'analysisEnabled':False,'queryEnabled':True,'proactive':False,'sources':['org.mozilla.firefox']})
  sh('am','start','-a','android.intent.action.VIEW','-d',url,'-p','org.mozilla.firefox');time.sleep(3)
  def loaded():
   tree=ui()
   tip=next((n for n in tree.iter('node') if n.get('content-desc')=='Dismiss'),None)
   if tip is not None:
    b=list(map(int,re.findall(r'\d+',tip.get('bounds'))));sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));return None
   close=next((n for n in tree.iter('node') if n.get('content-desc')=='Close Translations sheet'),None)
   if close is not None:
    cancel=next((n for n in tree.iter('node') if n.get('text')=='Not now'),close)
    b=list(map(int,re.findall(r'\d+',cancel.get('bounds'))));sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));return None
   return tree if any(n.get('class')=='android.webkit.WebView' and expected in n.get('text','') for n in tree.iter('node')) else None
  tree=wait_for(loaded,70);document=next(n.get('text') for n in tree.iter('node') if n.get('class')=='android.webkit.WebView' and expected in n.get('text',''))
  shot('source-'+name)
  connect();before=call('status')['count'];call('configure',{'enabled':True});sh('input','swipe','600','1750','600','1500','300')
  def captured():
   rows=call('search',{'query':expected,'limit':20})['items'];return next((r for r in rows if r['source']=='org.mozilla.firefox' and r['title']==document),None)
  record=wait_for(captured);call('configure',{'enabled':False});assert record['analysis']=='pending' and call('status')['analysisBatchAttempts']==attempts
  results.append({'scenario':name,'url':url,'id':record['id'],'title':record['title'],'source':record['source'],'revision':record['revision'],'rawTextPreserved':True,'realObservation':True,'recordCountIncreased':call('status')['count']>before})
  save('scenarios.json',results);print('Captured actual Firefox page: '+document,flush=True)
 save('capture-result.json',{'passed':True,'scenarios':results,'directDatabaseSeeding':False,'fixtureAppUsed':False,'modelRequests':0,'analysisBatchAttempts':attempts})
 # Leave observation active only until the first native screenshot, so its status is genuine.
 connect();call('configure',{'enabled':True});sh('am','start','-n','org.agentos.memory/.MemoryActivity');time.sleep(1);shot('memory-real-home')
 print('Three public browsing scenarios captured; home screenshot shows actual running observation.',flush=True)
elif args.phase=='ui':
 data=load('capture-result.json');assert data['passed'];call('configure',{'enabled':False})
 sh('am','start','-n','org.agentos.memory/.MemoryActivity');time.sleep(.5)
 tree=ui()
 if any(n.get('content-desc')=='返回' for n in tree.iter('node')):tap('返回')
 tap('记忆');tree=ui();assert any(n.get('content-desc')=='Taku Logo' for n in tree.iter('node'))
 visit=data['scenarios'][-1];tap(visit['title']);tree=ui();assert any(n.get('content-desc')=='Taku Logo' for n in tree.iter('node'));shot('memory-real-detail')
 reveal('展开 ＋');tap('展开 ＋');sh('input','swipe','600','1900','600','1200','350');words='\n'.join(n.get('text','') for n in ui().iter('node'));assert visit['id'] in words and 'org.mozilla.firefox' in words;shot('memory-real-provenance');tap('返回')
 tap('建议');assert any(n.get('content-desc')=='Taku Logo' for n in ui().iter('node'));shot('memory-branded-suggestions')
 tap('设置');assert any(n.get('content-desc')=='Taku Logo' for n in ui().iter('node'));shot('memory-branded-settings')
 # Real keyword search with an ordinary topic, without acceptance markers or sample records.
 tap('记忆');tap('搜索标题、内容或分类');sh('input','text','Android');sh('input','keyevent','66');time.sleep(.5);shot('memory-real-search')
 assert any('任务和返回堆栈' in n.get('text','') for n in ui().iter('node'))
 assert call('status')['analysisBatchAttempts']==data['analysisBatchAttempts']
 save('ui-result.json',{'passed':True,'formalLogoInHomeSuggestionsSettingsAndDetail':True,'realSourceAndRecordVisible':True,'realKeywordSearch':True,'modelRequests':0})
 print('Native brand, real provenance and ordinary keyword search verified.',flush=True)
else:
 b=load('baseline.json');old=b['config'];call('configure',{k:old[k] for k in ['enabled','analysisEnabled','queryEnabled','proactive','model','sources','retentionDays']})
 for key,value in [('enabled_accessibility_services',b['accessibility']),('accessibility_enabled',b['accessibilityEnabled'])]:
  if value=='null':sh('settings','delete','secure',key)
  else:sh('settings','put','secure',key,value)
  assert sh('settings','get','secure',key)==value
 status=call('status');assert status['analysisBatchAttempts']==old['analysisBatchAttempts'] and status['count']>=old['count'] and status['analysisInFlight']==0
 save('cleanup.json',{'passed':True,'originalConfigurationRestored':True,'originalAccessibilityRestored':True,'existingRecordsRetained':True,'realPublicPageRecordsRetained':True,'newModelRequests':0})
 if b['avdInitiallyPaused']:assert 'OK' in adb('emu','avd','stop')
 print('Original configuration and emulator pause restored; actual public-page records retained.',flush=True)
