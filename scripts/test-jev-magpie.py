#!/usr/bin/env python3
"""Android Jev/Magpie regression on a disposable AVD with a synthetic model upstream.

Requires assistant/build.sh, assistant/build-jev-magpie-tests.sh,
surfaces/build.sh outputs, out/device-qa/virtual-display.jar and pinned runtime archives.
Never targets a personal device or the shared development AVD. No provider credentials.
"""
import argparse,hashlib,json,os,pathlib,shlex,subprocess,threading,time,uuid,xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
ROOT=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--agent-apk', type=pathlib.Path, required=True, help='Matching platform-signed TakuAgent APK with SystemAssistantService')
args=parser.parse_args()
assert args.agent_apk.is_file(), 'Build the matching TakuAgent APK first'
ADB=[os.environ.get('ADB',str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb')),'-s',args.serial]
OUT=ROOT/'out/jev-magpie-device-qa'/time.strftime('%Y%m%dT%H%M%SZ',time.gmtime());OUT.mkdir(parents=True)
def cmd(*parts,timeout=50):
 r=subprocess.run(ADB+list(parts),capture_output=True,text=True,timeout=timeout)
 if r.returncode:raise RuntimeError((r.stdout+r.stderr)[-1800:])
 return r.stdout
def shell(*parts,timeout=50):return cmd('shell',shlex.join(parts),timeout=timeout)
def push(local,remote):return cmd('push',str(local),remote,timeout=90)
def write(remote,data):
 path=OUT/('transfer-'+uuid.uuid4().hex);path.write_text(data)
 push(path,remote);path.unlink()
def exchange(data):
 out=shell('/system/bin/env','CLASSPATH=/data/local/tmp/taku-jev-qa.jar','app_process','/system/bin',
           'org.agentos.tests.NativeSocketProbe','exchange',json.dumps(data,ensure_ascii=False))
 return json.loads(out)
assert cmd('emu','avd','name').splitlines()[0]=='Taku_JevMagpie_20261021','Disposable Jev/Magpie AVD required'
assert shell('getprop','ro.build.version.sdk').strip()=='36'
assert shell('id','-u').strip()=='0','Root ADB required'
marker='/data/local/tmp/taku-jev-magpie-qa-owner'
present=shell('sh','-c','test -e /data/local/agentos/magpie/config/magpie/providers.json && echo present || true').strip()
if present:
 assert shell('cat',marker).strip()=='synthetic-jev-magpie-fixture','Refuse to replace non-fixture configuration'
write(marker,'synthetic-jev-magpie-fixture')
# Verify public runtime assets before any device installation.
lock=json.loads((ROOT/'image/runtime-assets.lock.json').read_text())
sources={}
for item in lock['assets']:
 if not any(x in item['path'] for x in ['codex-runtime','magpie-runtime','cacert.pem']):continue
 path=ROOT/item['path']
 if not path.is_file():
  path=ROOT.parents[2]/item['path']
 assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==item['sha256'],item['path']
 sources[item['path']]=path
# This is a fresh owned AVD; preserve app data on repeated fixture runs.
cmd('install','--no-incremental','-r',str(ROOT/'system/surfaces/out/TakuSurfaces.apk'))
shell('appops','set','org.agentos.surfaces','SYSTEM_ALERT_WINDOW','allow')
cmd('install','--no-incremental','-r',str(ROOT/'system/assistant/out/AgentOSAssistant.apk'))
cmd('install','--no-incremental','-r',str(args.agent_apk))
assert 'SystemAssistantService' in shell('cmd','package','query-services','--brief','-a','android.service.voice.VoiceInteractionService','-p','org.agentos.agent'), 'Agent APK is missing the new system assistant'
cmd('install','--no-incremental','-r',str(ROOT/'out/jev-magpie-device-tests/fixture.apk'))
# The accessibility assertions below use the Chinese resources. A fresh AVD
# may default to English even when a previously used snapshot did not.
shell('cmd','locale','set-app-locales','org.agentos.assistant','--user','0','--locales','zh-CN')
shell('mkdir','-p','/system_ext/bin','/system_ext/etc/codex','/system_ext/etc/agentos','/system_ext/framework',
      '/data/local/agentos/home/.codex','/data/local/agentos/work','/data/local/agentos/magpie/config/magpie')
for local,remote in [
 (ROOT/'adapters/codex-runtime/codex','/system_ext/bin/codex'),
 (ROOT/'system/display/phone-display','/system_ext/bin/phone-display'),
 (ROOT/'out/device-qa/virtual-display.jar','/system_ext/framework/agentos-virtual-display.jar'),
 (ROOT/'adapters/magpie-runtime/magpie','/system_ext/bin/magpie'),
 (sources['adapters/magpie-runtime/downloads/magpie-arm64'],'/system_ext/bin/magpie-runtime'),
 (sources['adapters/codex-runtime/downloads/codex.tar.gz'],'/system_ext/etc/codex/codex.tar.gz'),
 (sources['adapters/codex-runtime/downloads/code-mode-host.tar.gz'],'/system_ext/etc/codex/code-mode-host.tar.gz'),
 (sources['image/runtime/cacert.pem'],'/system_ext/etc/codex/cacert.pem'),
 (ROOT/'system/assistant/out/task-broker.jar','/system_ext/framework/agentos-task-broker.jar'),
 (ROOT/'system/assistant/task-result.schema.json','/system_ext/etc/agentos/task-result.schema.json'),
 (ROOT/'out/jev-magpie-device-tests/test.jar','/data/local/tmp/taku-jev-qa.jar')]:
 push(local,remote)
shell('chmod','755','/system_ext/bin/codex','/system_ext/bin/magpie','/system_ext/bin/magpie-runtime','/system_ext/bin/phone-display')
shell('restorecon','-R','/system_ext/bin','/system_ext/etc/codex','/system_ext/framework')
shell('cmd','role','add-role-holder','--user','0','android.app.role.ASSISTANT','org.agentos.agent')
shell('settings','put','secure','assistant','org.agentos.agent/.home.assistant.SystemAssistantService')
shell('settings','put','secure','voice_interaction_service','org.agentos.agent/.home.assistant.SystemAssistantService')
shell('settings','put','global','stay_on_while_plugged_in','3')
shell('settings','put','system','screen_off_timeout','1800000')
shell('svc','power','stayon','true')
shell('input','keyevent','224');shell('wm','dismiss-keyguard')
events=[];failures=[]
class Handler(BaseHTTPRequestHandler):
 def log_message(self,*a):pass
 def do_GET(self):
  data=[]
  for name,modalities in [('fixture-chat',['text','image']),('jev-text',['text']),('jev-vision',['text','image'])]:
   data.append({'id':name,'name':name,'object':'model','context_length':32000,
                'kind':'model' if name=='fixture-chat' else 'decision',
                'native_endpoints':['/v1/chat/completions' if name=='fixture-chat' else '/v1/systemone'],
                'architecture':{'input_modalities':modalities,'output_modalities':['text' if name=='fixture-chat' else 'decisions']},
                'modalities':{'input':modalities,'output':['text' if name=='fixture-chat' else 'decisions']},
                'input_modalities':modalities})
  body=json.dumps({'object':'list','data':data}).encode()
  self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(body)
 def do_POST(self):
  body=json.loads(self.rfile.read(int(self.headers['Content-Length'])))
  if self.path=='/v1/systemone':
   state=json.dumps(body.get('state'),ensure_ascii=False)
   event={'kind':'decision','model':body.get('model'),'image':'image_url' in state,
          'noTranscript':'taku-jev-magpie-fixture' not in state and 'fixture-password-must-not-be-collected' not in state,
          'noDraftLeak':'Jev 迁移回归草稿' not in state}
   events.append(event)
   if not all(event[k] for k in ['image','noTranscript','noDraftLeak']):failures.append(event)
   answers={}
   for name,question in body.get('questions',{}).items():
    if question['type']=='noul':answers[name]={'type':'noul','noul':.99};continue
    choice='chat' if name=='screen' else 'dining_plan'
    criteria=question.get('criteria',{})
    probabilities={k:(.99 if k==choice else .01/max(1,len(criteria)-1)) for k in criteria}
    answers[name]={'type':'choice','choice':choice,'confidence':.99,'probabilities':probabilities}
   packet=json.dumps({'answers':answers,'usage':{'input_tokens':100,'output_tokens':5}},ensure_ascii=False).encode()
   self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(packet)
  elif self.path=='/v1/chat/completions':
   text=json.dumps(body.get('messages'),ensure_ascii=False)
   if not body.get('stream',False):
    events.append({'kind':'suggestion','model':body.get('model'),'image':'image_url' in text})
    content=json.dumps({'actionable':True,'title':'周六聚餐','suggestion':'要看看餐厅和集合安排吗？','task':'整理周六聚餐的餐厅和集合安排。'},ensure_ascii=False)
    packet={'id':'chatcmpl-fixture','object':'chat.completion','created':1,'model':'fixture-chat',
            'choices':[{'index':0,'message':{'role':'assistant','content':content},'finish_reason':'stop'}]}
    data=json.dumps(packet,ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(data);return
   events.append({'kind':'agent','model':body.get('model'),'image':'image_url' in text,
                  'noDraftLeak':'Jev 迁移回归草稿' not in text})
   if 'Jev 迁移回归草稿' in text:failures.append({'kind':'draftLeak'})
   self.send_response(200);self.send_header('Content-Type','text/event-stream');self.end_headers()
   for delta,finish in [({'role':'assistant'},None),({'content':'已整理合成约饭方案。这是协议测试，没有真实检索或预订。'},None),({},'stop')]:
    packet={'id':'chatcmpl-fixture','object':'chat.completion.chunk','created':1,'model':'fixture-chat',
            'choices':[{'index':0,'delta':delta,'finish_reason':finish}]}
    self.wfile.write(('data: '+json.dumps(packet,ensure_ascii=False)+'\n\n').encode());self.wfile.flush()
   self.wfile.write(b'data: [DONE]\n\n')
  else:self.send_response(404);self.end_headers()
server=ThreadingHTTPServer(('127.0.0.1',0),Handler);server.daemon_threads=True
threading.Thread(target=server.serve_forever,daemon=True).start()
port=server.server_port
magpie_root='/data/local/agentos/magpie'
try:
 cmd('reverse','tcp:'+str(port),'tcp:'+str(port))
 provider={'id':'fixture','name':'Synthetic fixture','preset':'remote-magpie','chat':f'http://127.0.0.1:{port}/v1',
           'decide':f'http://127.0.0.1:{port}/v1','key':'fixture-only',
           'models':['fixture-chat','jev-text','jev-vision'],'modelsURL':f'http://127.0.0.1:{port}/v1/models'}
 write(magpie_root+'/config/magpie/providers.json',json.dumps({'providers':[provider]}))
 write(magpie_root+'/config/magpie/settings.json','{"gatewayMode":"on"}')
 write(magpie_root+'/web.key','a'*64)
 write(magpie_root+'/connection.json','{"mode":"magpie","model":"fixture/fixture-chat"}')
 shell('sh','-c','nohup /system_ext/bin/magpie serve --web=127.0.0.1:3430 >/data/local/tmp/taku-jev-magpie.log 2>&1 </dev/null &')
 time.sleep(2)
 shell('magpie','provider','fetch','fixture',timeout=40)
 shell('sh','-c','nohup env CLASSPATH=/system_ext/framework/agentos-task-broker.jar app_process /system/bin org.agentos.lab.TaskBroker >/data/local/tmp/taku-jev-broker.log 2>&1 </dev/null &')
 deadline=time.monotonic()+45
 while True:
  try:
   config=exchange({'op':'decision','action':'models'})
   assert len(config['models'])==2,config
   caps={m['id']:m['imageInput'] for m in config['models']}
   assert caps=={'fixture/jev-text':False,'fixture/jev-vision':True},config
   break
  except Exception:
   if time.monotonic()>deadline:raise
   time.sleep(.5)
 exchange({'op':'systemAi','action':'configure','patch':{'imageModel':'fixture/fixture-chat','apps':{'org.agentos.assistant':True}}})
 thread=exchange({'op':'rpc','method':'thread/start','params':{}})['thread']['id']
 prefs=ET.Element('map')
 def string(k,v):ET.SubElement(prefs,'string',name=k).text=v
 string('conversation',thread);string('theme','light');string('modelConnectionMode','magpie')
 string('draft-'+thread,'Jev 迁移回归草稿：这段不要发送。')
 attachment='/data/user/0/org.agentos.assistant/files/composer-attachments/jev-fixture/keep-jev-fixture.txt'
 shell('mkdir','-p',str(pathlib.PurePosixPath(attachment).parent));write(attachment,'Synthetic unsent attachment. Do not send.')
 string('composer.'+thread+'.attachments',json.dumps([{'path':attachment,'name':'keep-jev-fixture.txt','mime':'text/plain'}]))
 ET.SubElement(prefs,'boolean',name='includeScreenshot',value='true')
 ET.SubElement(prefs,'boolean',name='jevEnabled',value='false')
 shell('am','force-stop','org.agentos.assistant')
 shell('mkdir','-p','/data/user/0/org.agentos.assistant/shared_prefs')
 write('/data/user/0/org.agentos.assistant/shared_prefs/agent-settings.xml',ET.tostring(prefs,encoding='unicode'))
 shell('chown','-R','system:system','/data/user/0/org.agentos.assistant')
 shell('restorecon','-R','/data/user/0/org.agentos.assistant')
 device_output='/data/local/tmp/jev-magpie-ui-'+uuid.uuid4().hex+'/'
 result=cmd('shell','timeout','240','uiautomator','runtest','/system/framework/android.test.base.jar',
            '/data/local/tmp/taku-jev-qa.jar','-c','org.agentos.tests.JevMagpieUiTest#testJevMagpieChain',
            '-e','output',device_output,timeout=270)
 (OUT/'ui-test.log').write_text(result)
 cmd('pull',device_output,str(OUT/'device'))
 assert 'OK (1 test)' in result and 'FAILURES' not in result,result[-6000:]
 report=json.loads((OUT/'device/jev-magpie-ui.json').read_text())
 assert report['passed'] and not failures,failures
 decisions=[e for e in events if e['kind']=='decision'];agents=[e for e in events if e['kind']=='agent']
 suggestions=[e for e in events if e['kind']=='suggestion']
 assert len(decisions)==1 and decisions[0]['image'] and decisions[0]['noTranscript'],decisions
 assert len(suggestions)==1 and suggestions[0]['image'],suggestions
 assert len(agents)==1 and all(e['image'] and e['noDraftLeak'] for e in agents),agents
 # Exercise the real Magpie management API and private-store confirmation with a synthetic legacy key.
 legacy='sk-or-v1-0123456789abcdefghijklmnopqr'
 imported=exchange({'op':'decision','action':'importLegacy','key':legacy})
 repeated=exchange({'op':'decision','action':'importLegacy','key':legacy})
 stored=json.loads(shell('cat',magpie_root+'/config/magpie/providers.json'))
 assert imported['imported'] and imported['provider']==repeated['provider']
 assert sum(p['id']==imported['provider'] for p in stored['providers'])==1
 assert any(p['id']=='fixture' and p['key']=='fixture-only' for p in stored['providers'])
 report['realMagpieMigrationVerified']=True
 report['upstreamEvents']=events;report['physicalDeviceVerified']=False;report['romVerified']=False
 (OUT/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
 print(json.dumps({'passed':True,'output':str(OUT),'decisionCalls':len(decisions),'agentCalls':len(agents)},ensure_ascii=False))
finally:
 (OUT/'events.json').write_text(json.dumps({'events':events,'failures':failures},ensure_ascii=False,indent=2)+'\n')
 for path,name in [('/data/local/tmp/taku-jev-magpie.log','magpie.log'),('/data/local/tmp/taku-jev-broker.log','broker.log')]:
  try:(OUT/name).write_text(shell('cat',path)[-20000:])
  except Exception:pass
 try:cmd('reverse','--remove','tcp:'+str(port))
 except Exception:pass
 server.shutdown()
 # This AVD and all processes in it belong exclusively to this synthetic fixture.
 for name in ['codex-aarch64-unknown-linux-musl','magpie-runtime']:
  try:
   pids=shell('pidof',name).strip().split()
   if pids:shell('kill',*pids)
  except Exception:pass
 try:
  for row in shell('ps','-A','-o','PID,ARGS').splitlines():
   if 'org.agentos.lab.TaskBroker' in row:shell('kill',row.split()[0])
 except Exception:pass
