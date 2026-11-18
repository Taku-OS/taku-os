#!/usr/bin/env python3
"""Opt-in fixture acceptance on a named disposable emulator; never accepts a physical serial."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[3]
PKG='org.example.taku.observer'
HOST='org.agentos.proactivity'
SURFACES='org.agentos.surfaces'
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--expected-avd',required=True)
parser.add_argument('--adb',default='adb')
parser.add_argument('--apksigner',default='apksigner')
parser.add_argument('--out',required=True,type=Path)
parser.add_argument('--install',action='store_true')
parser.add_argument('--reset-fixtures',action='store_true')
args=parser.parse_args()
if not re.fullmatch(r'emulator-\d+',args.serial):parser.error('A disposable emulator serial is required')
args.out.mkdir(parents=True,exist_ok=True)
prefix=[args.adb,'-s',args.serial]
checks=[]
review_count=0
fixture_device=False
ui_nodes=[]
surface_position=None
surface_position_at=0

def adb(*parts,binary=False):
    return subprocess.check_output(prefix+list(parts),text=not binary,stderr=subprocess.STDOUT,timeout=45)

def check(condition,name):
    if not condition:raise AssertionError(name)
    checks.append(name)
    print('ok - '+name,flush=True)

def wait(function,label,timeout=25):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            result=function()
            if result:return result
        except (AssertionError,ValueError,KeyError,IndexError,StopIteration,subprocess.CalledProcessError):pass
        time.sleep(.15)
    raise AssertionError('Timed out: '+label)

def receipts():return json.loads(adb('shell','run-as',PKG,'cat','files/receipts.json'))

def last(kind):
    return next(p for p in reversed(receipts()['receipts']) if p.get('type')==kind)

def nodes():
    path='/data/local/tmp/taku-proactivity-ui.xml'
    adb('shell','uiautomator','dump',path)
    global ui_nodes
    ui_nodes=list(ET.fromstring(adb('exec-out','cat',path)).iter('node'))
    return ui_nodes

def node(description=None,text=None,package=None):
    # Stable test aliases resolve to actual Chinese accessibility names, not developer IDs.
    aliases={
        'open-manager':['Review and authorize in Proactivity'], 'change-page':['Update page'],
        'diagnostics-toggle':['Developer diagnostics'], 'duplicate-event':['Replay last event'],
        'approve-observer':['Allow and enable','Confirm update and enable','Enable observer'],
        'disable-observer':['Disable observer'], 'rollback-policy':['Roll back to previous policy'],
        'remove-observer':['Remove observer'], 'tab-0':['Overview'], 'tab-1':['Observers'], 'tab-2':['Activity log'],
        'policy-maxRecords':['Maximum records (1–16)']
    }
    def named(n):
        if description is None:return True
        if description.startswith('observer-'):return n.get('content-desc','').startswith('Page settling observer，')
        return n.get('content-desc') in aliases.get(description,[description])
    matches=[n for n in nodes() if named(n)
             and (text is None or n.get('text')==text) and (package is None or n.get('package')==package)]
    if len(matches)!=1:raise AssertionError(f'Expected one UI node: {description or text}; got {len(matches)}')
    bounds=list(map(int,re.findall(r'\d+',matches[0].get('bounds'))))
    if bounds[2]<=bounds[0] or bounds[3]<=bounds[1]:raise AssertionError('UI control is clipped: '+str(description or text))
    return bounds

def tap_bounds(bounds):adb('shell','input','tap',str((bounds[0]+bounds[2])//2),str((bounds[1]+bounds[3])//2))

def tap(description=None,text=None,package=None):
    def swipe(down=False):
        candidates=[]
        for n in ui_nodes:
            if n.get('class')!='android.widget.ScrollView' or (package and n.get('package')!=package):continue
            box=list(map(int,re.findall(r'\d+',n.get('bounds'))))
            if box[2]>box[0] and box[3]>box[1]:candidates.append(box)
        if not candidates:return
        box=max(candidates,key=lambda b:(b[2]-b[0])*(b[3]-b[1]))
        x=(box[0]+box[2])//2;upper=box[1]+(box[3]-box[1])//4;lower=box[1]+(box[3]-box[1])*3//4
        adb('shell','input','swipe',str(x),str(upper if down else lower),str(x),str(lower if down else upper),'220')
    for index in range(10):
        try:
            bounds=node(description,text,package)
            try:
                frame=surface_frame();x,y=(bounds[0]+bounds[2])//2,(bounds[1]+bounds[3])//2
                overlap=frame[0]<=x<=frame[2] and frame[1]<=y<=frame[3]
            except (AssertionError,AttributeError,ValueError):overlap=False
            if description is not None and overlap:swipe();continue
            tap_bounds(bounds);nodes();return
        except AssertionError:
            # Scroll inside the actual native viewport, excluding header, keyboard and footer.
            if index==0:
                for _ in range(3):swipe(down=True)
            else:swipe()
    raise AssertionError('UI control not found: '+str(description or text))

def shot(name):
    nodes() # Wait for the native transition to settle before collecting pixels.
    data=adb('exec-out','screencap','-p',binary=True)
    check(data.startswith(b'\x89PNG'),'Screenshot '+name)
    (args.out/(name+'.png')).write_bytes(data)

def surface_frame():
    dump=adb('shell','dumpsys','window','windows')
    windows=[block for block in re.split(r'(?m)^  Window #',dump)
             if 'u0 Taku System Surface}' in block.split('\n')[0] and 'isOnScreen=true' in block
             and 'Surface: shown=true' in block and 'mDrawState=HAS_DRAWN' in block]
    if len(windows)!=1:raise AssertionError('Expected one visible system surface')
    frame=list(map(int,re.search(r' frame=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]',windows[0]).groups()))
    if frame[2]<=frame[0] or frame[3]<=frame[1]:raise AssertionError('Empty system surface')
    global surface_position,surface_position_at
    position=(windows[0].split('\n')[0],tuple(frame))
    if position!=surface_position:
        surface_position=position;surface_position_at=time.monotonic()
        raise AssertionError('Waiting for surface layout')
    if time.monotonic()-surface_position_at<.2:raise AssertionError('Surface layout is settling')
    return frame

def start(version,probe=None):
    try:old=last('register')['requestId']
    except Exception:old=''
    adb('shell','am','force-stop',PKG)
    command=['shell','am','start','-W','-n',PKG+'/.ObserverActivity']
    if probe:command+=['--es','probe',probe]
    result=adb(*command)
    check('Status: ok' in result,'Open plugin v'+str(version)+((' probe '+probe) if probe else ''))
    wait(lambda:receipts()['codeVersion']==version and last('register')['requestId']!=old,'new plugin registration')
    return last('register')

def return_to_plugin():
    old=last('register')['requestId']
    tap('Back',package=HOST)
    wait(lambda:last('register')['requestId']!=old,'plugin resumed through host navigation')

def approve():
    global review_count
    review_count+=1
    tap('open-manager',package=PKG);shot('review-'+str(review_count));tap('approve-observer',package=HOST);shot('manager-approved')
    return_to_plugin()
    wait(lambda:last('register').get('result',{}).get('enabled'),'active registration')
    check(last('register')['result']['pendingRevision']==0,'User activation committed before events')

def offer(version,burst=1,producer=None):
    try:old=last('published')['result']['candidateId']
    except Exception:old=''
    bounds=node('change-page',package=PKG)
    for _ in range(burst):tap_bounds(bounds)
    wait(lambda:last('published')['result']['candidateId']!=old,'real published ACK')
    receipt=last('published')['result']
    check(receipt['producerCodeVersion']==(producer or version),'Actual producer version '+str(producer or version))
    check(receipt['sourcePackage']==PKG,'Binder caller owns event source')
    check(not receipt['imageDecision'] and not receipt['agentExecution'],'No model or execution claim in fixture')
    check(1<=len(receipt['records'])<=8,'Bounded recent context')
    check(len({r['id'] for r in receipt['records']})==len(receipt['records']),'Unique selected context records')
    # Shell uiautomator dumps only the focused app, not this non-focusable overlay.
    wait(surface_frame,'visible system surface window')
    return receipt

def accept(receipt):
    receipt.pop('_titleVersion',None)
    tap_bounds(surface_frame())
    wait(lambda:last('accepted')['result']['candidateId']==receipt['candidateId'],'actual surface action callback')
    check(last('accepted')['result']['records']==receipt['records'],'Acceptance retains original selected records')

def installed_hash(package):
    path=adb('shell','pm','path',package).strip().split('package:')[-1]
    return hashlib.sha256(adb('exec-out','cat',path,binary=True)).hexdigest()

def signer(path):
    value=subprocess.check_output([args.apksigner,'verify','--print-certs',str(path)],text=True,timeout=30)
    return re.search(r'certificate SHA-256 digest: ([0-9a-f]+)',value).group(1)

def page_counter():
    xml=ET.fromstring(adb('shell','run-as',PKG,'cat','shared_prefs/ObserverActivity.xml'))
    return int(next(v.get('value') for v in xml if v.get('name')=='pageRevision'))

try:
    check(adb('shell','getprop','ro.kernel.qemu').strip()=='1','Emulator only')
    check(adb('emu','avd','name').splitlines()[0]==args.expected_avd,'Explicit named disposable AVD')
    fixture_device=True
    adb('shell','settings','put','system','font_scale','1.0')
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','settings','put','system','user_rotation','0')
    artifacts={HOST:ROOT/'system/proactivity/out/TakuProactivity.apk',SURFACES:ROOT/'system/surfaces/out/TakuSurfaces.apk',PKG:ROOT/'examples/proactivity-observer/out/Observer-v1.apk'}
    v2=ROOT/'examples/proactivity-observer/out/Observer-v2.apk'
    if args.install:
        for package in (SURFACES,HOST,PKG):
            # A previous interrupted fixture may still have v2 installed.
            options=['-r','-d'] if package==PKG and args.reset_fixtures else ['-r']
            check('Success' in adb('install',*options,str(artifacts[package])),'Install '+package)
    if args.reset_fixtures:
        for package in (PKG,HOST,SURFACES):check('Success' in adb('shell','pm','clear',package),'Reset explicit fixture '+package)
    initial_width=int(re.findall(r'(\d+)x\d+',adb('shell','wm','size'))[-1])
    adb('logcat','-c')
    adb('shell','appops','set',SURFACES,'SYSTEM_ALERT_WINDOW','allow')
    adb('shell','settings','put','system','screen_off_timeout','1800000')
    adb('shell','input','keyevent','WAKEUP');adb('shell','wm','dismiss-keyguard')
    hashes={p:installed_hash(p) for p in artifacts}
    for p,path in artifacts.items():check(hashes[p]==hashlib.sha256(path.read_bytes()).hexdigest(),'Installed APK hash '+p)
    signers={p:signer(path) for p,path in artifacts.items()}
    check(signers[PKG]!=signers[HOST] and signers[HOST]==signers[SURFACES],'Plugin has independent signing key; host owns surface bridge')
    check(signer(v2)==signers[PKG],'Plugin upgrade uses its own same signer')
    uids={p:int(re.search(r'uid:(\d+)',adb('shell','cmd','package','list','packages','-U',p)).group(1)) for p in artifacts}
    check(len(set(uids.values()))==3,'Host, plugin and display have three distinct UIDs')
    adb('shell','am','start','-W','-n',HOST+'/.ProactivityActivity')
    wait(lambda:node('tab-0',package=HOST),'native host overview')
    shot('ui-empty');adb('shell','input','keyevent','BACK')
    registered=start(1)
    check(registered['ok'] and not registered['result']['approved'],'Registration requires real user consent')
    tap('change-page',package=PKG)
    wait(lambda:last('event').get('code')=='not_authorized','unapproved event rejection')
    shot('01-pending-approval')
    approve()
    first=offer(1,burst=3)
    check(len(first['records'])==3,'Three actual page callbacks coalesced into one context')
    shot('02-v1-native-hint')
    tap('diagnostics-toggle',package=PKG);tap('duplicate-event',package=PKG)
    wait(lambda:last('event').get('result',{}).get('duplicate'),'duplicate event acknowledgement')
    check(last('published')['result']['candidateId']==first['candidateId'],'Duplicate does not replace hint')
    first['_titleVersion']=1;accept(first);shot('03-real-acceptance')
    for probe,kind in [('admin','approve'),('namespace','register'),('event_type','event')]:
        start(1,probe)
        wait(lambda:last(kind).get('ok') is False,'public API rejects '+probe)
        check(not last(kind)['ok'],'Public API rejects '+probe)
    start(1)
    tap('open-manager',package=PKG);tap('disable-observer',package=HOST);return_to_plugin()
    wait(lambda:not last('register').get('result',{}).get('enabled',True),'disabled state persisted')
    tap('change-page',package=PKG);wait(lambda:last('event').get('ok') is False,'disabled event rejected')
    approve()
    tap('open-manager',package=PKG);tap(text='Edit',package=HOST)
    tap('policy-maxRecords',package=HOST)
    adb('shell','input','keyevent','KEYCODE_MOVE_END');adb('shell','input','keyevent','KEYCODE_DEL');adb('shell','input','text','0')
    tap(text='Save settings',package=HOST)
    check(bool(node('policy-maxRecords',package=HOST)),'Invalid policy stays in editor without committing')
    tap('policy-maxRecords',package=HOST)
    adb('shell','input','keyevent','KEYCODE_MOVE_END');adb('shell','input','keyevent','KEYCODE_DEL');adb('shell','input','text','2')
    shot('ui-policy-keyboard')
    tap(text='Save settings',package=HOST)
    wait(lambda:node(text='Trigger policy',package=HOST),'policy editor dismissed');shot('policy-edited')
    return_to_plugin()
    wait(lambda: any(h.get('author')=='user' and h['spec']['maxRecords']==2 for h in last('register')['result']['history']),'user policy survives plugin re-register')
    limited=offer(1,burst=3)
    check(len(limited['records'])==2,'User sets recent N through native policy UI')
    accept(limited)
    before_upgrade=page_counter()
    check('Success' in adb('install','-r',str(v2)),'Actual APK v1 to v2 upgrade')
    adb('shell','am','force-stop',PKG)
    adb('shell','am','start','-W','-n',PKG+'/.ObserverActivity','--es','probe','event_without_register')
    wait(lambda:receipts()['codeVersion']==2 and last('event').get('code')=='not_authorized','new APK cannot emit before re-registering')
    check(last('event')['code']=='not_authorized','APK upgrade cannot bypass review by skipping register')
    registered=start(2)
    check(registered['result']['pendingRevision']>0,'New version waits for user review')
    check(page_counter()==before_upgrade,'Plugin data survives upgrade')
    tap('change-page',package=PKG);wait(lambda:last('event').get('ok') is False,'unapproved upgrade cannot trigger')
    approve();second=offer(2);shot('04-v2-native-hint');second['_titleVersion']=2;accept(second)
    tap('open-manager',package=PKG);tap('rollback-policy',package=HOST);tap(text='Restore policy',package=HOST);return_to_plugin()
    wait(lambda:last('register')['result']['pendingRevision']==0,'re-register preserves user rollback')
    rolled=offer(1,producer=2);shot('05-policy-rollback-code-v2');rolled['_titleVersion']=1;accept(rolled)
    check(rolled['policyRevision']>second['policyRevision'],'Policy rollback creates a new revision')
    before_downgrade=page_counter()
    check('Success' in adb('install','-r','-d',str(artifacts[PKG])),'Actual debug APK v2 to v1 rollback')
    registered=start(1)
    check(registered['result']['pendingRevision']>0,'APK rollback is reviewed independently from policy rollback')
    check(page_counter()==before_downgrade,'Plugin data survives APK rollback')
    approve();restored=offer(1);shot('06-apk-rollback-v1');restored['_titleVersion']=1;accept(restored)
    check(installed_hash(PKG)==hashes[PKG],'Installed plugin bytes restored exactly to v1')
    core={'passed':True,'scope':'emulator_verified_core','at':datetime.now(timezone.utc).isoformat(),'serial':args.serial,'avd':args.expected_avd,
          'checks':list(checks),'uids':uids,'signers':signers,'initialApkSha256':hashes,
          'v2Sha256':hashlib.sha256(v2.read_bytes()).hexdigest(),'sdkSha256':hashlib.sha256((ROOT/'system/proactivity/out/taku-proactivity-client.jar').read_bytes()).hexdigest(),
          'v1':first,'v2':second,'policyRollback':rolled,'apkRollback':restored}
    (args.out/'core-acceptance.json').write_text(json.dumps(core,ensure_ascii=False,indent=2)+'\n')
    adb('shell','am','start','-W','-n',HOST+'/.ProactivityActivity')
    wait(lambda:node('tab-0',package=HOST),'overview navigation');shot('ui-overview')
    tap('tab-1',package=HOST);shot('ui-observers')
    tap('observer-org.example.taku.observer.page',package=HOST);shot('ui-detail')
    tap(text='Edit',package=HOST);shot('ui-policy-editor');tap('Cancel',package=HOST)
    adb('shell','input','keyevent','BACK');tap('tab-2',package=HOST);shot('ui-activity')
    tap('tab-0',package=HOST)
    adb('shell','settings','put','system','font_scale','1.3')
    # A native configuration change must preserve navigation and readable touch targets.
    wait(lambda:node('tab-1',package=HOST),'large-font native navigation');shot('ui-large-font')
    for index in range(3):
        bounds=node('tab-'+str(index),package=HOST)
        check(bounds[2]>bounds[0] and bounds[3]>bounds[1],'Large-font tab '+str(index)+' reachable')
    tap('tab-1',package=HOST);tap('observer-org.example.taku.observer.page',package=HOST);shot('ui-detail-large-font')
    tap('disable-observer',package=HOST)
    check(bool(node('approve-observer',package=HOST)),'Large-font detail action reachable')
    tap('approve-observer',package=HOST)
    adb('shell','input','keyevent','BACK');tap('tab-0',package=HOST)
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','settings','put','system','user_rotation','1')
    wait(lambda: int(re.search(r'cur=(\d+)x',adb('shell','dumpsys','window','displays')).group(1))>initial_width,'landscape rotation')
    wait(lambda:node('tab-1',package=HOST),'landscape navigation');shot('ui-landscape')
    adb('shell','settings','put','system','user_rotation','0');adb('shell','settings','put','system','font_scale','1.0')
    wait(lambda: int(re.search(r'cur=(\d+)x',adb('shell','dumpsys','window','displays')).group(1))==initial_width,'portrait restored')
    tap('tab-1',package=HOST);tap('observer-org.example.taku.observer.page',package=HOST)
    tap('remove-observer',package=HOST);tap(text='Remove',package=HOST)
    wait(lambda:node(text='No observers yet',package=HOST),'explicit removal empty state');shot('ui-removed')
    registered=start(1)
    check(not registered['result']['approved'] and not registered['result']['enabled'],'Removed plugin must ask for consent again')
    runtime_log=adb('logcat','-d','-s','AndroidRuntime:E')
    check('FATAL EXCEPTION' not in runtime_log,'No Android runtime crash during acceptance')
    report={'passed':True,'scope':'emulator_verified','at':datetime.now(timezone.utc).isoformat(),'serial':args.serial,'avd':args.expected_avd,
            'fingerprint':adb('shell','getprop','ro.build.fingerprint').strip(),'checks':checks,'uids':uids,'signers':signers,'initialApkSha256':hashes,
            'v2Sha256':hashlib.sha256(v2.read_bytes()).hexdigest(),'sdkSha256':hashlib.sha256((ROOT/'system/proactivity/out/taku-proactivity-client.jar').read_bytes()).hexdigest(),
            'v1':first,'v2':second,'policyRollback':rolled,'apkRollback':restored,'realModels':False,'globalScreenObservation':False,'agentExecution':False,'physicalDevice':False}
    (args.out/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'passed':True,'checks':len(checks),'report':str(args.out/'acceptance.json')},ensure_ascii=False))
except Exception as error:
    details=str(error)+('\n'+str(error.output) if isinstance(error,subprocess.CalledProcessError) else '')
    (args.out/'failure.json').write_text(json.dumps({'passed':False,'error':details,'checks':checks},ensure_ascii=False,indent=2))
    try:(args.out/'android-runtime.txt').write_text(adb('logcat','-d','-s','AndroidRuntime:E'))
    except Exception:pass
    try:shot('failure')
    except Exception:pass
    raise
finally:
    if fixture_device:
        try:
            adb('shell','settings','put','system','user_rotation','0')
            adb('shell','settings','put','system','font_scale','1.0')
        except Exception:pass
