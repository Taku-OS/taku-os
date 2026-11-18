"""Run only on a disposable isolated AVD with the debug Memory build; uses synthetic reminders."""
import argparse,json,subprocess,shlex,time
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb',required=True)
parser.add_argument('--serial',required=True)
parser.add_argument('--resume-id',type=int,help='Resume the feedback/UI checks for a previously seeded reminder')
args=parser.parse_args()
if not args.serial.startswith('emulator-'):parser.error('An isolated emulator is required')
adb=[args.adb,'-s',args.serial]
Path('out/pr4-feed-sync').mkdir(parents=True,exist_ok=True)
def shell(*parts):return subprocess.check_output(adb+['shell',shlex.join(parts)],text=True,timeout=160)
def call(method,p=None,auth='org.agentos.memory'):
 raw=shell('content','call','--uri','content://'+auth,'--method',method,'--arg',json.dumps(p or {}))
 r=json.JSONDecoder().raw_decode(raw[raw.index('{"'):])[0]
 assert r.get('ok'),r
 return r.get('result',r)
subprocess.run(adb+['install','--no-incremental','-r','system/memory/out/TakuMemory.apk'],check=True)
call('configure',{'nudges':True,'feedCards':True,'feedDescribe':False,'queryEnabled':True,'enabled':False,'analysisEnabled':False,'autoOrganize':False})
if args.resume_id is None:
 r=call('deliverNudge',{'kind':'message','title':'Feed sync verification','text':'Synthetic event replay check','say':'Check the saved Feed selection.'})
 nid=r['id'];time.sleep(2)
 n=call('nudge',{'id':nid});cid=n['card'];assert cid,n
 feed='org.agentos.agent.home-feed'
 c=call('feed/get',{'id':cid},feed)['card']
 # A none-intent on a real reminder verifies accepted selection without invoking any model.
 c['actions']['probe']={'label':'Verify selection','intent':{'type':'none'}}
 call('feed/publish',{'card':c},feed)
 for i in range(75):call('feed/action',{'id':cid,'actionId':'probe','values':{'/ui/value':i}},feed)
 call('feed/pin',{'id':cid},feed)
else:
 nid=args.resume_id
feed='org.agentos.agent.home-feed'
tidy=call('feedTidy');n=call('nudge',{'id':nid})
assert len(n['feedback'])==20,n
assert all('cursor' in e for e in n['feedback']),n
public=call('nudges',{'days':7,'limit':30},'org.agentos.memory.open-access')['items']
row=next(row for row in public if row['id']==nid)
assert row['feedback']==n['feedback'] and row['reactions']==['selected','pinned'],row
n['reactions']=row['reactions']
checkpoint=call('status',auth='org.agentos.memory.open-access')['feedFeedbackCursor']
assert checkpoint==call('feed/list',auth=feed)['eventCursor']
before=n['feedback'];again=call('feedTidy');assert again['read']==0,again
shell('am','force-stop','org.agentos.memory')
assert call('nudge',{'id':nid})['feedback']==before
assert call('feedTidy')['read']==0
assert call('status',auth='org.agentos.memory.open-access')['feedFeedbackCursor']==checkpoint
shell('input','keyevent','KEYCODE_WAKEUP')
shell('wm','dismiss-keyguard')
shell('am','start','-n','org.agentos.memory/.MemoryActivity','--es','tab','suggestions')
time.sleep(2)
shell('uiautomator','dump','/sdcard/pr4-memory.xml')
xml=shell('cat','/sdcard/pr4-memory.xml')
Path('out/pr4-feed-sync/memory-ui.xml').write_text(xml)
assert ('selected an action' in xml or '选择了卡片操作' in xml),xml[:400]
Path('out/pr4-feed-sync/memory-feedback.json').write_text(json.dumps({'nudge':n,'readBack':tidy,'secondRead':again,'checkpoint':checkpoint},indent=2))
Path('out/pr4-feed-sync/memory-ui.png').write_bytes(subprocess.check_output(adb+['exec-out','screencap','-p']))
call('feedTidy',{'withdraw':True})
call('configure',{'nudges':False,'feedCards':False,'queryEnabled':False})
print('PASS Memory: 75 durable selections, cursor dedupe, restart, GUI/Agent feedback agreement')
