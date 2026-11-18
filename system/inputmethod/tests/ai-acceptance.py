#!/usr/bin/env python3
"""Real screenshot/reply UI checks. Never supplies or mocks model answers."""
import argparse,json,pathlib,re,shlex,subprocess,time

ROOT=pathlib.Path(__file__).resolve().parents[3]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('serial')
p.add_argument('--allow-physical',action='store_true')
p.add_argument('--require-generation',action='store_true',help='Also require a real Luna image reply and insert it through the visible chip.')
p.add_argument('--output',type=pathlib.Path,default=ROOT/'out/inputmethod/ai-acceptance')
args=p.parse_args()
if not args.serial.startswith('emulator-') and not args.allow_physical:
 p.error('Physical testing requires the device owner request and --allow-physical.')
OUT=args.output;OUT.mkdir(parents=True,exist_ok=True)
IME='org.agentos.inputmethod';CHAT=IME+'.chatcase';PROBE=IME+'.probe'

def shell(*parts,check=True):
 r=subprocess.run(['adb','-s',args.serial,'shell',shlex.join(parts)],capture_output=True,text=True,timeout=45)
 if check and r.returncode:raise RuntimeError(r.stderr.strip() or r.stdout.strip())
 return r.stdout
def nodes():
 raw=shell('am','instrument','-w',PROBE+'/.UiProbe')
 line=next((s.split('=',1)[1] for s in raw.splitlines() if s.startswith('INSTRUMENTATION_RESULT: nodes=')),None)
 return [n for n in json.loads(line or '[]') if n.get('package') in [IME,CHAT]]
def tap(n):
 a,b,c,d=map(int,re.findall(r'\d+',n['bounds']));shell('input','tap',str((a+c)//2),str((b+d)//2))
def screenshot(name):
 (OUT/(name+'.png')).write_bytes(subprocess.check_output(['adb','-s',args.serial,'exec-out','screencap','-p']))
def control(**request):
 request.update(op='imeReplies')
 raw=shell('env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin',
           'org.agentos.tests.NativeSocketProbe','exchange',json.dumps(request,separators=(',',':')),check=False)
 try:return json.loads(raw)
 except ValueError:return {}
def latest():return control(action='latest',foreground=CHAT)
def launch(**flags):
 shell('cmd','voiceinteraction','hide',check=False)
 shell('am','force-stop',CHAT)
 command=['am','start','-W','-n',CHAT+'/.ChatActivity']
 for key,value in flags.items():command+=['--ez',key,str(value).lower()]
 shell(*command);time.sleep(.4)
 for _ in range(8):
  editor=next((n for n in nodes() if n.get('content-desc')=='群聊输入框'),None)
  if editor:tap(editor);return
  time.sleep(.2)
 raise AssertionError('Synthetic chat editor missing')
def ai_visible():return any(n.get('content-desc')=='Taku AI reply suggestions' for n in nodes())

assert shell('id','-u').strip()=='0','Root is required only for development diagnostics; the IME keeps its own UID.'
report={'modelResponsesSimulated':False,'serial':args.serial,'androidSdk':shell('getprop','ro.build.version.sdk').strip(),
        'checks':[],'generationVerified':False}
def passed(label):report['checks'].append(label);print('PASS',label,flush=True)
try:
 if args.require_generation:
  launch();time.sleep(.3);screenshot('01-pending')
  deadline=time.monotonic()+32;result={}
  while time.monotonic()<deadline:
   result=latest()
   if result.get('state') in ['completed','failed']:break
   time.sleep(.25)
  if result.get('state')=='completed':
   reply=result['result'];receipt=reply['receipt'];texts=[c['text'] for c in reply['candidates']]
   assert texts and receipt['model']=='codex/gpt-6-luna' and receipt['inputMode']=='image',reply
   assert re.fullmatch('[a-f0-9]{64}',receipt['imageSha256']) and max(receipt['imageWidth'],receipt['imageHeight'])<=1280
   for _ in range(8):
    chip=next((n for n in nodes() if n.get('content-desc')=='Reply suggestion: '+texts[0]),None)
    if chip:break
    time.sleep(.2)
   assert chip,'Actual model result did not reach the visible keyboard'
   screenshot('02-real-replies');report['reply']=reply;report['generationVerified']=True
   passed('real Luna image JSON reaches the existing candidate strip')
   tap(chip);time.sleep(.2)
   state=json.loads(shell('run-as',CHAT,'cat','files/state.json'))
   assert state['text']==texts[0] and not ai_visible(),state
   screenshot('03-inserted');passed('visible model suggestion inserts exact text without sending')
  else:
   report['modelBlocker']=result or {'state':'unavailable'};screenshot('02-model-unavailable')

 launch();time.sleep(.35);screenshot('04-pending-cancel')
 a=nodes();assert any(n.get('text')=='Thinking of a reply…' for n in a),'Pending copy missing; enable AI and allow the synthetic chat in IME settings.'
 strip=next(n for n in a if n.get('content-desc')=='Taku AI reply suggestions')
 bounds=list(map(int,re.findall(r'\d+',strip['bounds'])))
 density=int(re.findall(r'\d+',shell('wm','density'))[-1])/160
 assert bounds[3]-bounds[1] <= 42*density,strip
 passed('natural pending copy uses the existing 40dp candidate strip')
 key=next(n for n in a if n.get('package')==IME and n.get('text')=='n');tap(key)
 assert not ai_visible(),'Reply row survived the first real key'
 screenshot('05-typing');passed('first real pinyin key immediately hides AI row')
 time.sleep(4);assert not ai_visible();passed('canceled request cannot return to keyboard')

 launch(secure=True);time.sleep(.7);secure=latest();report['secureJob']=secure
 assert secure.get('state')=='failed' and secure.get('message')=='This page stays with you',secure
 passed('FLAG_SECURE rejected before screenshot capture')
 launch(password=True);time.sleep(.4);assert not ai_visible();passed('password editor never shows AI row')
 report['status']='verified' if report['generationVerified'] else 'local_verified_model_pending'
finally:
 (OUT/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
if args.require_generation and not report['generationVerified']:
 raise SystemExit('Real model generation remains blocked; see result.json. Local checks are separate.')
