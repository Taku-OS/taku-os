#!/usr/bin/env python3
"""Actual Jelly/WebView, Android Settings and Messaging IME regression.
Only synthetic text is entered; no message is sent and no existing conversation is opened.
"""
import argparse,http.server,json,pathlib,re,shlex,subprocess,threading,time,xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');p.add_argument('--allow-physical',action='store_true');p.add_argument('--phase',choices=['all','browser','native'],default='all');p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
if not a.serial.startswith('emulator-') and not a.allow_physical:p.error('Device-owner authorization required')
IME='org.agentos.inputmethod';COMP=IME+'/org.fcitx.fcitx5.android.input.FcitxInputMethodService';PROBE=IME+'.probe';BROWSER='org.lineageos.jelly';MESSAGES='com.android.messaging';SETTINGS='com.android.settings';adb=['adb','-s',a.serial];out=a.output;out.mkdir(parents=True,exist_ok=True)
report={'phase':a.phase,'passed':False,'checks':[],'modelResponsesSimulated':False,'browserPageSynthetic':True,'messagesSent':False};state={};server=None;original_pref=None;port=None;original_rotation=None;original_accel=None

def sh(*v,check=True):
 r=subprocess.run(adb+['shell',shlex.join(v)],capture_output=True,text=True,timeout=50)
 if check and r.returncode:raise RuntimeError('ADB '+v[0]+' failed')
 return r.stdout.strip()
def ui():
 raw=sh('am','instrument','-w',PROBE+'/.UiProbe');prefix='INSTRUMENTATION_RESULT: nodes=';s=next((l[len(prefix):] for l in raw.splitlines() if l.startswith(prefix)),None)
 return [n for n in json.loads(s or '[]') if n.get('package') in [IME,BROWSER,MESSAGES,SETTINGS,'com.android.settings.intelligence']]
def pick(description=None,text=None,resource=None,package=None):
 for _ in range(7):
  for n in ui():
   if package and n.get('package')!=package:continue
   if description and description not in [n.get('content-desc'),n.get('text'),n.get('hint')]:continue
   if text and n.get('text')!=text:continue
   if resource and n.get('resource-id','')!=resource and not n.get('resource-id','').endswith('/'+resource):continue
   return n
  time.sleep(.2)
 raise AssertionError('UI item missing: '+str(description or text or resource))
def tap(n):
 x,y,z,w=map(int,re.findall(r'\d+',n['bounds']));sh('input','tap',str((x+z)//2),str((y+w)//2));time.sleep(.1)
def browser_field(resource):
 # WebView can retain the old portrait scroll offset after rotation.
 # Reveal the actual editor by scrolling; never inject its text or focus through JS.
 for direction in [0,-1,-1,1,1,1,1]:
  data=ui()
  if direction:
   boxes=[list(map(int,re.findall(r'\d+',n['bounds']))) for n in data if n.get('package')==BROWSER]
   width=max(b[2] for b in boxes);height=max(b[3] for b in boxes)
   x=str(width//2);upper=str(int(height*.4));lower=str(int(height*.8))
   sh('input','swipe',x,lower if direction<0 else upper,x,upper if direction<0 else lower,'300');data=ui()
  for n in data:
   if n.get('package')==BROWSER and n.get('resource-id')==resource:
    box=list(map(int,re.findall(r'\d+',n['bounds'])))
    if box[3]-box[1]>=60:return n
 raise AssertionError('Browser field remains outside the visible viewport: '+resource)
def shot(name):(out/(name+'.png')).write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
def wait(fn,timeout=8):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  if v:=fn():return v
  time.sleep(.15)
 raise AssertionError('Expected synthetic editor state missing')
def letters(s):
 for _ in range(12):
  data=ui()
  if all(any(n.get('package')==IME and n.get('text','').lower()==c.lower() for n in data) for c in s):break
  time.sleep(.2)
 for c in s:
  matches=[n for n in data if n.get('package')==IME and n.get('text','').lower()==c.lower()]
  assert matches,'Letter key missing: '+c
  tap(max(matches,key=lambda n:int(re.findall(r'\d+',n['bounds'])[1])))
def chinese(yes):
 data=ui();is_pinyin=any('Pinyin' in n.get('text','') or '拼音' in n.get('text','') for n in data if n.get('package')==IME)
 if is_pinyin!=yes:tap(pick(resource='button_lang',package=IME))
def clear():sh('input','keycombination','113','29');sh('input','keyevent','DEL');time.sleep(.2)
def passed(s):report['checks'].append(s);print('PASS '+s,flush=True)
def native(**v):
 raw=sh('env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','exchange',json.dumps(v,separators=(',',':')))
 return json.loads(raw)
def preferences(raw):
 path='/data/user/0/'+IME+'/shared_prefs/taku-ai-replies.xml';meta=sh('stat','-c','%u:%g:%a',path).split(':');label=sh('ls','-Z',path).split()[0];sh('am','force-stop',IME)
 subprocess.run(adb+['shell','cat > '+path+'.test-tmp'],input=raw,check=True)
 sh('chown',meta[0]+':'+meta[1],path+'.test-tmp');sh('chmod',meta[2],path+'.test-tmp');sh('chcon',label,path+'.test-tmp');sh('mv',path+'.test-tmp',path);sh('ime','set',COMP)
class Page(http.server.BaseHTTPRequestHandler):
 def log_message(self,*v):pass
 def do_GET(self):
  raw=(pathlib.Path(__file__).parent/'browser-case.html').read_bytes();self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
 def do_POST(self):
  value=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))));state.update(value);self.send_response(204);self.end_headers()
try:
 assert sh('id','-u')=='0';original_rotation=sh('settings','get','system','user_rotation');original_accel=sh('settings','get','system','accelerometer_rotation')
 for pkg in [BROWSER,MESSAGES,SETTINGS]:assert sh('pm','path',pkg).startswith('package:'),pkg
 if a.phase in ['all','browser']:
  original_pref=subprocess.check_output(adb+['exec-out','cat','/data/user/0/'+IME+'/shared_prefs/taku-ai-replies.xml'])
  xml=ET.fromstring(original_pref);enabled=xml.find("boolean[@name='enabled']");enabled.set('value','true');apps=xml.find("set[@name='apps']")
  if apps is None:apps=ET.SubElement(xml,'set',{'name':'apps'})
  if BROWSER not in [x.text for x in apps]:ET.SubElement(apps,'string').text=BROWSER
  preferences(ET.tostring(xml,encoding='utf-8',xml_declaration=True));sh('cmd','voiceinteraction','hide',check=False)
  server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Page);port=server.server_port;threading.Thread(target=server.serve_forever,daemon=True).start()
  sh('input','keyevent','HOME');subprocess.run(adb+['reverse','tcp:'+str(port),'tcp:'+str(port)],check=True,capture_output=True)
  sh('am','start','-W','-a','android.intent.action.VIEW','-d','http://127.0.0.1:'+str(port)+'/case','-p',BROWSER);time.sleep(.8)
  tap(pick(resource='compose',package=BROWSER));wait(lambda:state.get('active')=='compose');chinese(True)
  letters('nihao');tap(pick(text='你好',package=IME));wait(lambda:state.get('compose')=='你好');passed('Jelly WebView commits real Chinese candidate')
  chinese(False);letters('taku');tap(pick(text='?123',package=IME))
  for t in ['1','2','!']:tap(pick(text=t,package=IME))
  wait(lambda:state.get('compose')=='你好taku12!');shot('09-browser-mixed-input');passed('Jelly WebView Chinese English digits and punctuation stay in one draft')
  tap(pick(text='ABC',package=IME));chinese(True);sh('input','keycombination','113','29');letters('zaijian');tap(pick(text='再见',package=IME));wait(lambda:state.get('compose')=='再见');passed('Jelly WebView selected text is replaced by an actual Chinese candidate')
  sh('settings','put','system','accelerometer_rotation','0');sh('settings','put','system','user_rotation','1');time.sleep(.7);sh('input','keyevent','BACK');tap(browser_field('compose'));letters('nihao');tap(pick(text='你好',package=IME));wait(lambda:state.get('compose')=='再见你好');shot('10-browser-landscape');passed('Jelly landscape recreates keyboard and continues Chinese input')
  sh('settings','put','system','user_rotation','0');time.sleep(.7);tap(browser_field('compose'));clear();wait(lambda:state.get('compose')=='')
  # Browser focus scrolls this actual HTML input into view. No JS text injection is used.
  protected_before=native(op='imeReplies',action='diagnostics')
  sh('input','keyevent','BACK');tap(browser_field('password'));time.sleep(.4)
  assert not any(n.get('content-desc')=='Taku AI reply suggestions' for n in ui());letters('safe');wait(lambda:state.get('passwordLength')==4);shot('11-browser-password');clear();passed('Jelly password input works while AI is excluded')
  sh('input','keyevent','BACK');tap(browser_field('number'));time.sleep(.3)
  assert not any(n.get('content-desc')=='Taku AI reply suggestions' for n in ui())
  for digit in ['3','4','5']:tap(pick(text=digit,package=IME))
  wait(lambda:state.get('number')=='345');shot('11b-browser-number');clear();passed('Jelly numeric field uses numeric keyboard and excludes chat-reply AI')
  protected_after=native(op='imeReplies',action='diagnostics');assert protected_after['captures']==protected_before['captures'] and protected_after['modelRequests']==protected_before['modelRequests']
  report['protectedEditorCountersUnchanged']=True
  sh('input','keyevent','BACK');tap(browser_field('compose'));sh('input','keyevent','BACK');tap(browser_field('compose'))
  def browser_reply():
   r=native(op='imeReplies',action='latest',foreground=BROWSER)
   if r.get('state')=='failed':raise AssertionError(r)
   return r.get('result') if r.get('state')=='completed' else None
  reply=wait(browser_reply,32);assert reply['candidates'];report['browserReply']=reply
  wait(lambda:any(n.get('content-desc')=='Reply suggestion: '+reply['candidates'][0]['text'] for n in ui()));shot('12-browser-live-replies');passed('real browser page screenshot reaches live model and native reply strip')
  text=reply['candidates'][0]['text'];tap(pick(description='Reply suggestion: '+text,package=IME));wait(lambda:state.get('compose')==text)
  assert not any(n.get('content-desc') in ['Insert full reply','Taku AI reply suggestions'] for n in ui());shot('12b-browser-direct-insert');clear();passed('one ordinary tap inserts the exact AI reply directly without confirmation or preview')
 if a.phase in ['all','native']:
  sh('input','keyevent','BACK');sh('am','start','-W','-a','android.settings.SETTINGS');time.sleep(.4)
  def search_surface():
   data=ui()
   return data if any(n.get('resource-id','').endswith(('/search_src_text','/search_action_bar')) or n.get('content-desc') in ['Search settings','Search'] for n in data) else None
  data=wait(search_surface);search=next((n for n in data if n.get('resource-id','').endswith('/search_action_bar') or n.get('content-desc') in ['Search settings','Search']),None)
  if not any(n.get('resource-id','').endswith('/search_src_text') for n in data):
   assert search,'Settings search entry missing';tap(search);time.sleep(.4)
  field=pick(resource='search_src_text',package='com.android.settings.intelligence');tap(field);chinese(False);letters('keyboard');shot('13-system-settings-search')
  assert any(n.get('text')=='keyboard' and n.get('editable') for n in ui());assert not any(n.get('content-desc')=='Taku AI reply suggestions' for n in ui());clear();passed('native Android Settings search accepts IME input without AI opt-in')
  sh('input','keyevent','BACK');sh('am','start','-W','-a','android.intent.action.SENDTO','-d','smsto:5550102000','-p',MESSAGES);time.sleep(.6)
  editor=pick(resource='compose_message_text',package=MESSAGES);tap(editor);chinese(True);letters('nihao');tap(pick(text='你好',package=IME));chinese(False);letters('taku')
  assert pick(resource='compose_message_text',package=MESSAGES).get('text')=='你好taku';shot('14-native-message-draft');clear();empty=pick(resource='compose_message_text',package=MESSAGES);assert empty.get('text') in ['',empty.get('hint')]
  sh('input','keyevent','BACK');sh('input','keyevent','BACK');passed('native Messaging mixed draft is entered and cleared without sending')
 report['browserFinalState']={k:v for k,v in state.items() if k!='password'};report['passed']=True
except Exception as error:
 report['error']=str(error);report['failureUi']=ui();report['browserStateAtFailure']=dict(state);shot('90-diagnostic')
 raise
finally:
 if original_pref is not None:preferences(original_pref)
 for key,value in [('user_rotation',original_rotation),('accelerometer_rotation',original_accel)]:
  if value is not None:sh('settings','delete' if value=='null' else 'put','system',key,*([] if value=='null' else [value]))
 if port is not None:subprocess.run(adb+['reverse','--remove','tcp:'+str(port)],capture_output=True)
 if server is not None:server.shutdown();server.server_close()
 (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
