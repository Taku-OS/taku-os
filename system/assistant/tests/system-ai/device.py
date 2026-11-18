#!/usr/bin/env python3
"""Physical UI/default-routing checks. Requires the installed real broker and read-only UI probe."""
import argparse,json,pathlib,re,shlex,subprocess,time

p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');p.add_argument('--allow-physical',action='store_true');p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
if not a.serial.startswith('emulator-') and not a.allow_physical:p.error('Device-owner authorization and --allow-physical are required')
out=a.output;out.mkdir(parents=True,exist_ok=True);adb=['adb','-s',a.serial];report={'checks':[],'modelResponsesSimulated':False};original=None
def sh(*args,check=True):
 r=subprocess.run(adb+['shell',shlex.join(args)],text=True,capture_output=True,timeout=60)
 if check and r.returncode:raise RuntimeError('ADB failed: '+args[0])
 return r.stdout.strip()
def exchange(**v):return json.loads(sh('env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','exchange',json.dumps(v,separators=(',',':'))))
def denied(**v):
 r=subprocess.run(adb+['shell',shlex.join(['env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','exchange',json.dumps(v,separators=(',',':'))])],text=True,capture_output=True,timeout=60)
 assert r.returncode!=0 and 'System AI is paused' in r.stdout+r.stderr,{'code':r.returncode,'output':r.stdout+r.stderr}
 return True
def policy():return exchange(op='systemAi',action='read')
def memory():
 raw=sh('content','call','--user','0','--uri','content://org.agentos.memory','--method','status','--arg','{}');start=raw.index('result=')+7;end=raw.rindex('}]');r=json.loads(raw[start:end]);assert r['ok'],r;return r['result']
def nodes():
 raw=sh('am','instrument','-w','org.agentos.inputmethod.probe/.UiProbe');line=next((s.split('=',1)[1] for s in raw.splitlines() if s.startswith('INSTRUMENTATION_RESULT: nodes=')),None);return json.loads(line or '[]')
def match(ns,text):return next((n for n in ns if n.get('text')==text or n.get('content-desc')==text),None)
def tap(n):
 x,y,z,w=map(int,re.findall(r'\d+',n['bounds']));assert z>x and w>y,n;sh('input','tap',str((x+z)//2),str((y+w)//2))
def click(text):
 for _ in range(5):
  n=match(nodes(),text)
  if n:tap(n);return
  sh('input','swipe','540','1950','540','750','300');time.sleep(.2)
 raise AssertionError('Missing visible control: '+text)
def shot(name):
 (out/(name+'.png')).write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
def wait(test,seconds=15):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  if test():return
  time.sleep(.3)
 raise AssertionError('Expected state did not arrive')
def passed(text):report['checks'].append(text);print('PASS '+text,flush=True)
def system():
 sh('cmd','voiceinteraction','hide',check=False);sh('am','start','-W','-f','0x14000000','-a','org.agentos.action.AI_SETTINGS','-p','org.agentos.assistant');wait(lambda:match(nodes(),'Text model'));time.sleep(.4)
def pick(title,model):
 system();click(title);click(model['name']);key='textModel' if title=='Text model' else 'imageModel';wait(lambda:policy()[key]==model['id'])
try:
 assert sh('id','-u')=='0';original=policy();report['initialDefaults']={k:original[k] for k in ['enabled','textModel','imageModel','apps']}
 sh('cmd','locale','set-app-locales','org.agentos.memory','--user','0','--locales','zh-CN')
 sh('cmd','voiceinteraction','hide',check=False);sh('am','force-stop','com.android.settings');sh('am','start','-W','-a','android.settings.SETTINGS')
 for _ in range(7):
  n=match(nodes(),'Taku AI')
  if n:break
  sh('input','swipe','540','2000','540','650','350')
 assert n,'Taku AI missing from real Android Settings';shot('01-settings-entry');tap(n);wait(lambda:match(nodes(),'Text model'));time.sleep(.5);shot('02-system-ai');passed('Android Settings tile opens the shared native Taku AI page')
 available=original['models'];text=next(m for m in available if m.get('textInput') and m.get('kind')!='decision' and m['id']!=original['textModel'])
 pick('Text model',text);sh('am','start','-W','-n','org.agentos.memory/.MemoryActivity','--es','page','settings');wait(lambda:memory()['model']==text['id']);cfg=memory();assert cfg['followsSystem'] and cfg['modelOverride']=='';assert not cfg['enabled'] and not cfg['analysisEnabled'];shot('03-memory-follows-changed-system');passed('Memory follows a changed system text model without enabling capture or analysis')
 selected=next(m for m in available if m['id']==original['textModel']);pick('Text model',selected)
 images=next(m for m in available if m.get('imageInput') and m.get('kind')!='decision' and m['id']!=original['imageModel']);pick('Image model',images)
 system();click('Input method feature settings');wait(lambda:any('Follow system' in n.get('text','') and images['name'] in n.get('text','') for n in nodes()));resolved=exchange(op='systemAi',action='resolve',consumer='ime',role='image');assert resolved['id']==images['id'];passed('IME follows a changed system image model instead of a hardcoded Luna default')
 selected=next(m for m in available if m['id']==original['imageModel']);pick('Image model',selected)
 system();click('Input method feature settings');wait(lambda:any('Follow system' in n.get('text','') and selected['name'] in n.get('text','') for n in nodes()));shot('04-ime-follows-system');click('Taku AI system settings');wait(lambda:match(nodes(),'Text model'));passed('IME settings link returns to the same system AI page')
 click('Allow Memory to use AI');wait(lambda:not policy()['apps']['memory']);assert denied(op='systemAi',action='resolve',consumer='memory',role='text');assert exchange(op='systemAi',action='resolve',consumer='ime',role='image')['id']==original['imageModel'];passed('Per-app AI denial blocks Memory while IME remains available')
 system();click('Allow Memory to use AI');wait(lambda:policy()['apps']['memory'])
 click('Enable system AI');wait(lambda:not policy()['enabled']);assert denied(op='systemAi',action='resolve',consumer='ime',role='image');assert denied(op='systemAi',action='resolve',consumer='memory',role='text');assert denied(op='decision',action='decide');assert denied(op='rpc',method='turn/start',params={});passed('System AI master switch blocks IME, Memory, Agent and Jev')
 system();click('Enable system AI');wait(lambda:policy()['enabled']);sh('am','start','-W','-n','org.agentos.memory/.MemoryActivity','--es','page','settings');wait(lambda:memory()['model']==original['textModel']);shot('05-memory-follows-system');click('Taku AI system settings');wait(lambda:match(nodes(),'Text model'));shot('06-system-ai-restored');passed('Memory links to shared settings; defaults and permissions restored')
 # A separate, normally signed application has no credentials and must be explicitly enabled here.
 system();click('Allow Taku 记忆合成验收 to use AI');wait(lambda:policy()['apps'].get('org.agentos.memorytests') is True)
 raw=sh('am','instrument','-w','-e','model',original['textModel'],'org.agentos.memorytests/org.agentos.memory.SharedAiClientTests')
 (out/'shared-client.txt').write_text(raw);line=next((s.split('=',1)[1] for s in raw.splitlines() if s.startswith('INSTRUMENTATION_RESULT: result=')),None);shared=json.loads(line or '{}');assert shared.get('passed'),raw;report['sharedClient']=shared
 assert any(r['consumer']=='org.agentos.memorytests' and r['status']=='completed' for r in policy()['usage']);passed('Ordinary app reuses real system AI after user authorization; caller identity cannot be forged')
 system();click('Subscription limits and usage');wait(lambda:match(nodes(),'Account limits'));shot('09-quota-entry');passed('Unified settings opens existing native account quota and usage')
 system();shot('10-system-ai-final')
 report['passed']=True
finally:
 if not report.get('passed'):report['failureState']={'policy':policy(),'visibleNodes':[n for n in nodes() if n.get('package') in ['org.agentos.inputmethod','org.agentos.assistant','org.agentos.memory']]}
 if original is not None:exchange(op='systemAi',action='configure',patch={k:original[k] for k in ['enabled','textModel','imageModel','apps']})
 (out/'ui-acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
