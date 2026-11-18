#!/usr/bin/env python3
"""Physical UI acceptance for reply preview, refresh/retry, exact-frame reuse and cancellation.
Synthetic source pages only; model answers always come from the configured live model.
"""
import argparse,json,pathlib,re,shlex,subprocess,time,uuid
p=argparse.ArgumentParser(description=__doc__);p.add_argument('serial');p.add_argument('--allow-physical',action='store_true');p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
if not a.serial.startswith('emulator-') and not a.allow_physical:p.error('Device-owner authorization required')
IME='org.agentos.inputmethod';CHAT=IME+'.chatcase';PROBE=IME+'.probe';out=a.output;out.mkdir(parents=True,exist_ok=True);adb=['adb','-s',a.serial]
report={'modelResponsesSimulated':False,'sourcePageSynthetic':True,'checks':[],'passed':False};original=None

def sh(*v,check=True):
 r=subprocess.run(adb+['shell',shlex.join(v)],capture_output=True,text=True,timeout=50)
 if check and r.returncode:raise RuntimeError('ADB '+v[0]+' failed')
 return r.stdout.strip()
def exchange(**v):
 raw=sh('env','CLASSPATH=/data/local/tmp/taku-ime-native-probe.jar','app_process','/system/bin','org.agentos.tests.NativeSocketProbe','exchange',json.dumps(v,separators=(',',':')))
 return json.loads(raw)
def ai(**v):return exchange(op='imeReplies',**v)
def policy(**v):return exchange(op='systemAi',**v)
def snapshot():
 raw=sh('am','instrument','-w',PROBE+'/.UiProbe');data={}
 for k in ['nodes','windows']:
  prefix='INSTRUMENTATION_RESULT: '+k+'=';line=next((s[len(prefix):] for s in raw.splitlines() if s.startswith(prefix)),None);data[k]=json.loads(line or '[]')
 data['nodes']=[n for n in data['nodes'] if n.get('package') in [IME,CHAT]]
 return data
def find(description=None,text=None,data=None):
 for n in (data or snapshot())['nodes']:
  if description is not None and n.get('content-desc')!=description:continue
  if text is not None and n.get('text')!=text:continue
  return n
 raise AssertionError('UI item missing: '+str(description or text))
def rect(n):return list(map(int,re.findall(r'\d+',n['bounds'])))
def tap(n):
 x,y,z,w=rect(n);sh('input','tap',str((x+z)//2),str((y+w)//2))
def longpress(n):
 x,y,z,w=rect(n);sh('input','swipe',str((x+z)//2),str((y+w)//2),str((x+z)//2),str((y+w)//2),'650')
def shot(name):(out/(name+'.png')).write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
def wait(fn,timeout=32):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  v=fn()
  if v:return v
  time.sleep(.15)
 raise AssertionError('Timed out waiting for expected state')
def latest():return ai(action='latest',foreground=CHAT)
def next_job(previous=''):
 return wait(lambda:(r if r.get('id') and r['id']!=previous else None) if (r:=latest()) else None,5)
def completed(job):
 value=wait(lambda:(r if r.get('state') in ['failed','completed','canceled'] else None) if (r:=ai(action='poll',id=job['id'],owner=uid)) else None)
 assert value['state']=='completed',value
 return value['result']
def visible_reply(reply):
 text=reply['candidates'][0]['text']
 return wait(lambda:next((n for n in snapshot()['nodes'] if n.get('content-desc')=='Reply suggestion: '+text),None),5)
def preview_chip(text):
 for _ in range(4):
  data=snapshot();strip=rect(find(description='Taku AI reply suggestions',data=data));right=rect(find(description='Generate another set of replies',data=data))[0]
  for n in data['nodes']:
   if n.get('content-desc')!='Reply suggestion: '+text:continue
   x,y,z,w=rect(n);left=max(x,strip[0]);end=min(z,right)
   if end-left>35:return dict(n,bounds='['+str(left)+','+str(y)+']['+str(end)+','+str(w)+']')
  sh('input','swipe',str(right-20),str((strip[1]+strip[3])//2),str(strip[0]+20),str((strip[1]+strip[3])//2),'250')
 raise AssertionError('Long reply is not reachable in the horizontal candidate strip')
def bounds(data):
 strip=rect(find(description='Taku AI reply suggestions',data=data));key=rect(next(n for n in data['nodes'] if n.get('package')==IME and n.get('text')=='q'))
 win=max((w for w in data['windows'] if w.get('package')==IME and w['type']==2),key=lambda w:(int(w['bounds'].split()[3])-int(w['bounds'].split()[1])))
 return {'strip':strip,'key':key,'window':win}
def check_bounds(expected):
 actual=bounds(snapshot());assert actual['strip']==expected['strip'] and actual['key']==expected['key'],(expected,actual)
 assert actual['window']['bounds']==expected['window']['bounds'],(expected,actual)
def passed(s):report['checks'].append(s);print('PASS '+s,flush=True)
def open_chat(**flags):
 sh('cmd','voiceinteraction','hide',check=False);sh('am','force-stop',CHAT);command=['am','start','-W','-n',CHAT+'/.ChatActivity']
 for k,v in flags.items():command+=['--ez',k,str(v).lower()]
 sh(*command);time.sleep(.4);tap(find(description='群聊输入框'))
def reopen():
 sh('input','keyevent','BACK');time.sleep(.25);tap(find(description='群聊输入框'))
def clear():
 sh('input','keycombination','113','29');sh('input','keyevent','DEL');time.sleep(.15)
 assert json.loads(sh('run-as',CHAT,'cat','files/state.json'))['text']=='','Real select-all/delete must clear the editor'
try:
 assert sh('id','-u')=='0';uid=int(re.search(r'^package:'+re.escape(IME)+r' uid:(\d+)$',sh('pm','list','packages','-U',IME),re.M).group(1));original=policy(action='read');assert original['apps']['ime']
 before=ai(action='diagnostics');open_chat(stableCursor=True);initial=snapshot();baseline=bounds(initial);report['keyboardBounds']=baseline
 density=int(re.findall(r'\d+',sh('wm','density'))[-1])/160;assert baseline['strip'][3]-baseline['strip'][1]<=42*density
 job=next_job();shot('01-thinking');wait(lambda:ai(action='diagnostics')['modelRequests']>before['modelRequests'],5)
 cold=completed(job);report['cold']=cold;visible_reply(cold);shot('02-replies');check_bounds(baseline)
 # With the actual source window settled, two independent requests coalesce.
 # Force a new live generation rather than reuse the completed cold result.
 before_pair=ai(action='diagnostics');clones=[str(uuid.uuid4()),str(uuid.uuid4())]
 for clone in clones:ai(action='start',id=clone,owner=uid,foreground=CHAT,height=baseline['strip'][1],top=cold['receipt']['captureTop'],model='',fresh=True)
 first=completed({'id':clones[0]});copy=completed({'id':clones[1]});report['coalescedReplies']=[first,copy]
 assert first['receipt']['deduplicated'] or copy['receipt']['deduplicated'],[first['receipt'],copy['receipt']]
 assert ai(action='diagnostics')['modelRequests']==before_pair['modelRequests']+1
 passed('identical in-flight frame shares one real model request')
 # A stable-cursor source isolates exact image equality; each reopen still takes a fresh screenshot.
 warm=None
 for _ in range(3):
  previous=latest()['id'];reopen();warm=completed(next_job(previous));visible_reply(warm)
  if warm['receipt']['cacheHit']:break
 assert warm['receipt']['cacheHit'],warm;report['warm']=warm
 before_fresh=ai(action='diagnostics');assert before_fresh['cacheHits']>=1
 passed('reopening an unchanged frame uses bounded memory cache without another model call')
 old_texts=[c['text'] for c in warm['candidates']];previous=latest()['id'];tap(find(description='Generate another set of replies'));fresh=completed(next_job(previous));visible_reply(fresh);check_bounds(baseline)
 assert not fresh['receipt']['cacheHit'] and not set(old_texts)&{c['text'] for c in fresh['candidates']}
 assert ai(action='diagnostics')['modelRequests']==before_fresh['modelRequests']+1
 report['fresh']=fresh;shot('03-new-group');passed('change group bypasses cache and excludes previous exact replies')
 text=max((c['text'] for c in fresh['candidates']),key=len);report['previewedText']=text
 chip=preview_chip(text);longpress(chip);data=snapshot();assert find(description='Full reply: '+text,data=data)['text']==text
 assert rect(find(description='Insert full reply',data=data))[3]<=baseline['strip'][1],'Preview must remain above the candidate strip, including the physical status-bar inset'
 check_bounds(baseline);shot('04-full-preview');passed('long press shows full untruncated reply without changing keyboard or strip bounds')
 tap(find(description='Close full reply',data=data));assert not any(n['content-desc']=='Full reply: '+text for n in snapshot()['nodes']);check_bounds(baseline)
 longpress(preview_chip(text));tap(find(description='Insert full reply'));time.sleep(.2)
 assert json.loads(sh('run-as',CHAT,'cat','files/state.json'))['text']==text
 assert not any(n.get('content-desc')=='Taku AI reply suggestions' for n in snapshot()['nodes']);shot('05-preview-inserted');passed('preview inserts exact live model text without sending')
 clear();policy(action='configure',patch={'apps':{'ime':False}});reopen();failed=wait(lambda:(r if r.get('state')=='failed' else None) if (r:=latest()) else None,6)
 find(description='Retry reply suggestions');shot('06-retry');report['actualDenial']=failed
 policy(action='configure',patch={'apps':{'ime':True}});tap(find(description='Retry reply suggestions'));retry=completed(next_job(failed['id']));visible_reply(retry);check_bounds(baseline);report['retry']=retry;shot('07-retry-success')
 passed('real permission failure can recover through visible retry after permission is restored')
 previous=latest()['id'];tap(find(description='Generate another set of replies'));pending=next_job(previous)
 data=snapshot();key=next(n for n in data['nodes'] if n.get('package')==IME and n.get('text')=='n');tap(key);time.sleep(.15)
 assert not any(n.get('content-desc')=='Taku AI reply suggestions' for n in snapshot()['nodes']);time.sleep(1)
 assert ai(action='poll',id=pending['id'],owner=uid).get('state')=='canceled';shot('08-typing');passed('typing cancels refresh and late completion cannot restore the strip')
 open_chat(secure=True);secure=wait(lambda:(r if r.get('state')=='failed' else None) if (r:=latest()) else None,6);assert secure['message']=='This page stays with you';passed('protected window is rejected before capture and cache reuse')
 open_chat(password=True);time.sleep(.3);assert not any(n.get('content-desc')=='Taku AI reply suggestions' for n in snapshot()['nodes']);passed('password field has no AI controls or preview')
 report['diagnostics']=ai(action='diagnostics');report['passed']=True
finally:
 if original is not None:policy(action='configure',patch={'apps':{'ime':original['apps']['ime']}})
 (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
