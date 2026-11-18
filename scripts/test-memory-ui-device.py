#!/usr/bin/env python3
"""Native memory UI acceptance on the designated test AVD, without model requests."""
import argparse, json, os, re, shlex, subprocess, time
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(os.environ.get('TAKU_MEMORY_UI_ROOT',str(Path(__file__).resolve().parents[1])))
OUT=ROOT/'out/memory-ui-device';OUT.mkdir(parents=True,exist_ok=True)
p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--phase',choices=['prepare','ui','restore'],required=True);p.add_argument('--avd-initially-paused',action='store_true');args=p.parse_args()
ADB=os.environ.get('ADB',str(Path.home()/'Library/Android/sdk/platform-tools/adb'))
def adb(*a,check=True):
 r=subprocess.run([ADB,'-s',args.serial,*a],stdin=subprocess.DEVNULL,capture_output=True,text=True,timeout=60)
 if check:r.check_returncode()
 return r.stdout.strip()
def sh(*a,check=True):return adb('shell',shlex.join(a),check=check)
def call(method,values=None):
 raw=sh('content','call','--user','0','--uri','content://org.agentos.memory','--method',method,'--arg',json.dumps(values or {},ensure_ascii=False));r=json.loads(raw[raw.index('result=')+7:raw.rindex('}]')]);assert r['ok'],r;return r['result']
def save(name,v):(OUT/name).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n')
def load(name):return json.loads((OUT/name).read_text())
def wait_for(f,seconds=30):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  r=f()
  if r:return r
  time.sleep(.4)
 raise AssertionError('Expected state did not appear')
def ui():
 sh('uiautomator','dump','/data/local/tmp/taku-memory-ui.xml');return ET.fromstring(sh('cat','/data/local/tmp/taku-memory-ui.xml'))
def texts():return '\n'.join(n.get('text','') for n in ui().iter('node'))
def tap(label):
 nodes=[n for n in ui().iter('node') if label in [n.get('text'),n.get('content-desc')]];assert nodes,label
 nodes.sort(key=lambda n:n.get('clickable')!='true')
 b=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))));sh('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))
def reveal(label):
 for attempt in range(5):
  if any(label in [n.get('text'),n.get('content-desc')] for n in ui().iter('node')):return
  sh('input','swipe','650','1950','650','900','300')
 raise AssertionError('Cannot reveal '+label)
def shot(name):
 time.sleep(.4);f='/data/local/tmp/taku-memory-'+name+'.png';sh('screencap','-p',f);adb('pull',f,str(OUT/(name+'.png')));sh('rm',f)
assert args.serial=='emulator-5634' and sh('getprop','ro.boot.qemu.avd_name')=='Taku_ImageIntegration_20261010'
if args.phase=='prepare':
 assert not (OUT/'baseline.json').exists(),'Preserve the existing baseline; use restore before a new run'
 old=call('status');assert not old['analysisEnabled'] and old['analysisInFlight']==0,'Requires model analysis paused'
 save('baseline.json',{'config':old,'accessibility':sh('settings','get','secure','enabled_accessibility_services'),'accessibilityEnabled':sh('settings','get','secure','accessibility_enabled'),'fontScale':sh('settings','get','system','font_scale'),'avdInitiallyPaused':args.avd_initially_paused})
 shot('before')
 print(adb('install','-r',str(OUT/'TakuMemory.apk')),flush=True)
 print(adb('install','-r',str(OUT/'TakuMemoryTests.apk')),flush=True)
 for test in ['StoreTests','QueueTests']:
  result=sh('am','instrument','-w','org.agentos.memorytests/org.agentos.memory.'+test);(OUT/(test+'.txt')).write_text(result+'\n');assert '"passed":true' in result,result;print(result,flush=True)
 upgraded=call('status');assert upgraded['count']==old['count'] and upgraded['analysisBatchAttempts']==old['analysisBatchAttempts']
 call('configure',{'enabled':True,'analysisEnabled':False,'proactive':False,'queryEnabled':True,'sources':['org.agentos.memorytests']})
 b=load('baseline.json');services='' if b['accessibility'] in ['null',''] else b['accessibility']+':'
 sh('settings','put','secure','enabled_accessibility_services',services+'org.agentos.memory/.ObservationService');sh('settings','put','secure','accessibility_enabled','1');wait_for(lambda:call('status')['observationConnected'])
 marker='UI_REVIEW_'+str(time.time_ns());save('capture-marker.json',{'marker':marker})
 pages=[('灵感备忘：让信息更容易找到','合成演示资料。阅读产品设计文章时记录：把常用搜索放在显眼位置，让内容先出现，来源与时间随时可查。以后整理设计方案时可以参考这些要点。'),('周末去杭州看展','合成演示资料。周六上午去看建筑展，开放时间 10:00—18:00。出发前确认预约和地址，带好雨伞；下午可以沿湖散步。门票与行程尚待确认。'),('下周产品评审准备','合成演示资料。周一 14:00 进行产品评审。准备用户流程、交互稿和待确认问题；小林负责设计说明，小陈负责接口清单。评审后再明确负责人和截止时间。')]
 for i,(title,body) in enumerate(pages):
  sh('am','start','-n','org.agentos.memorytests/.PageActivity','--es','title',title,'--es','body',body+'\n验收标识：'+marker+'_'+str(i),'--es','mode','normal')
  wait_for(lambda:len(call('search',{'query':marker,'limit':20})['items'])==i+1)
 records=call('search',{'query':marker,'limit':20})['items'];assert all('UNSENT_DRAFT' not in r['body'] for r in records)
 assert call('status')['analysisBatchAttempts']==old['analysisBatchAttempts']
 save('capture.json',{'marker':marker,'records':[{k:r[k] for k in ['id','title','source','analysis']} for r in records],'upgradePreservedRecords':True,'modelRequests':0})
 # Restore capture permissions before using uiautomator, which can suppress accessibility.
 call('configure',{k:old[k] for k in ['enabled','analysisEnabled','queryEnabled','proactive','model','sources','retentionDays']})
 for key,value in [('enabled_accessibility_services',b['accessibility']),('accessibility_enabled',b['accessibilityEnabled'])]:
  if value=='null':sh('settings','delete','secure',key)
  else:sh('settings','put','secure',key,value)
 sh('am','start','-n','org.agentos.memory/.MemoryActivity');print('Upgrade, store tests and three real automatic captures passed; analysis stayed off.',flush=True)
elif args.phase=='ui':
 # Keep this phase free of model dispatch or acceptance of Agent suggestions.
 expected=call('status')['count'];before=call('status')['analysisBatchAttempts'];sh('am','start','-n','org.agentos.memory/.MemoryActivity');time.sleep(1)
 if '让看过的信息，随时找得到' not in texts():tap('记忆')
 assert '让看过的信息，随时找得到' in texts();shot('memory-home')
 tap('下周产品评审准备');assert '纠正内容' in texts();shot('memory-detail')
 tap('展开 ＋');assert '应用包名' in texts();tap('更多操作');tap('删除记忆');tap('Cancel');assert '纠正内容' in texts()
 tap('返回');tap('分类：学习');assert '搜索结果' not in texts();shot('memory-filter')
 tap('分类：全部');tap('筛选来源');tap('Taku 记忆合成验收');assert '筛选结果 · ' in texts();tap('筛选来源');tap('所有来源')
 # The last three items were unreachable in the old UI. Reach and open them via pagination.
 for attempt in range(14):
  value=texts();match=re.search(r'加载更多 · 还有 \d+ 条',value)
  if match:break
  sh('input','swipe','650','1950','650','850','260')
 else:raise AssertionError('No pagination control')
 tap(match.group());sh('input','swipe','650','1950','650','850','260');sh('input','swipe','650','1950','650','850','260');assert f'已显示全部 {expected} 条记忆' in texts();shot('memory-history')
 visible_titles=[n.get('text') for n in ui().iter('node') if n.get('text')=='合成页面 · 自动记忆验收'];assert visible_titles
 tap(visible_titles[-1]);tap('返回');assert f'已显示全部 {expected} 条记忆' in texts(),'Back must preserve history scroll position'
 # Search must retain keyboard focus when a real provider notification arrives.
 tap('搜索标题、内容或分类');sh('input','text','UI_REVIEW_');call('configure',{'queryEnabled':True})
 fields=[n for n in ui().iter('node') if n.get('class')=='android.widget.EditText'];assert fields and fields[0].get('text')=='UI_REVIEW_' and fields[0].get('focused')=='true'
 marker=load('capture-marker.json')['marker'];sh('input','text',marker.removeprefix('UI_REVIEW_'));sh('input','keyevent','66');assert '搜索结果 · 3 条' in texts();shot('memory-search');tap('清除搜索')
 tap('搜索标题、内容或分类');sh('input','text','NO_SUCH_MEMORY_20261022');sh('input','keyevent','66');assert '没有找到相关记忆' in texts();shot('memory-empty-search');tap('清除筛选')
 tap('建议');assert '需要时，再向前一步' in texts();shot('memory-suggestions')
 tap('设置');assert '后台自动记忆' in texts() and '记录哪些应用' in texts();shot('memory-settings')
 # Open and cancel each independent consent dialog; do not enable analysis or capture.
 tap('后台自动记忆');assert '开启后台自动记忆' in texts();tap('Cancel');assert not call('status')['enabled']
 sh('input','swipe','650','2000','650','900','400');tap('模型分析（Jev / Magpie）');assert '开启模型分析' in texts();tap('Cancel');assert not call('status')['analysisEnabled']
 # Query and suggestions can change independently while capture and analysis stay off.
 reveal('允许 Agent 查询记忆');before_y=next(n.get('bounds') for n in ui().iter('node') if n.get('text')=='允许 Agent 查询记忆')
 tap('允许 Agent 查询记忆');assert next(n.get('bounds') for n in ui().iter('node') if n.get('text')=='允许 Agent 查询记忆')==before_y
 s=call('status');assert not s['queryEnabled'] and not s['analysisEnabled'] and not s['enabled']
 tap('允许 Agent 查询记忆');reveal('主动建议');tap('主动建议');s=call('status');assert s['proactive'] and s['queryEnabled'] and not s['analysisEnabled'];tap('主动建议')
 reveal('运行详情');tap('运行详情');assert '先合并约 20 秒' in texts();shot('memory-diagnostics');tap('返回')
 # Exercise the same native layout at a larger user font size.
 sh('settings','put','system','font_scale','1.3');time.sleep(1);tap('记忆');shot('memory-large-font');assert '让看过的信息，随时找得到' in texts()
 original=load('baseline.json')['fontScale']
 if original=='null':sh('settings','delete','system','font_scale')
 else:sh('settings','put','system','font_scale',original)
 time.sleep(1)
 assert call('status')['analysisBatchAttempts']==before
 save('ui.json',{'passed':True,'nativeHomeDetailFiltersSearchSuggestionsSettings':True,'deleteCancelled':True,'separateConsentDialogsCancelled':True,'allFourControlsIndependent':True,'paginationBeyondTwenty':True,'searchSurvivesProviderNotification':True,'backPreservesScroll':True,'settingsTogglePreservesScroll':True,'largeFontScale':1.3,'modelRequests':0})
 print('Native UI navigation, filters, search and independent consent passed.',flush=True)
else:
 b=load('baseline.json');old=b['config'];call('configure',{k:old[k] for k in ['enabled','analysisEnabled','queryEnabled','proactive','model','sources','retentionDays']})
 for namespace,key,value in [('secure','enabled_accessibility_services',b['accessibility']),('secure','accessibility_enabled',b['accessibilityEnabled']),('system','font_scale',b['fontScale'])]:
  if value=='null':sh('settings','delete',namespace,key)
  else:sh('settings','put',namespace,key,value)
  assert sh('settings','get',namespace,key)==value
 s=call('status');assert s['analysisBatchAttempts']==old['analysisBatchAttempts'];assert s['count']>=old['count'];assert not s['analysisInFlight']
 save('cleanup.json',{'passed':True,'configurationRestored':True,'accessibilityRestored':True,'fontScaleRestored':True,'oldRecordsRetained':True,'modelRequests':0,'syntheticUiRecordsRetained':True})
 if b['avdInitiallyPaused']:assert 'OK' in adb('emu','avd','stop')
 print('Original settings, records and emulator pause restored.',flush=True)
