#!/usr/bin/env python3
"""Record the approved, native emulator Home flow; never submit a task."""
import os
import pathlib, subprocess, time, json, re
BASE=pathlib.Path(__file__).resolve().parents[1]
ADB=os.environ.get('ADB', 'adb')
SERIAL='emulator-5582'
OUT=BASE/'evidence/design-system'
REMOTE='/data/local/tmp/taku-video-home-flow.mp4'
def adb(*args, **kwargs):
 return subprocess.run([ADB,'-s',SERIAL,*args],check=True,**kwargs)
def shell(*args): return adb('shell',*args,capture_output=True,text=True).stdout
assert adb('emu','avd','name',capture_output=True,text=True).stdout.splitlines()[0]=='AgentOS_ImageCheck'
assert shell('wm','size').strip()=='Physical size: 1080x2400'
OUT.mkdir(parents=True,exist_ok=True)
shell('input','keyevent','KEYCODE_HOME');time.sleep(1)
record=subprocess.Popen([ADB,'-s',SERIAL,'shell','screenrecord','--size','720x1600','--bit-rate','5000000','--time-limit','24',REMOTE],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
start=time.monotonic();events=[]
def action(at,label,*args):
 time.sleep(max(0,start+at-time.monotonic()))
 shell('input',*args);events.append({'seconds':round(time.monotonic()-start,2),'action':label})
action(3,'Open native generation page; show the real previous task','tap','430','2160')
action(9,'Return Home','keyevent','KEYCODE_HOME')
action(11,'Open installed Task Board','tap','425','1410')
time.sleep(1.5)
assert re.search(r'mCurrentFocus=.*org\.agentos\.apiboard',shell('dumpsys','window')), 'Task Board did not become foreground'
action(16,'Return Home','keyevent','KEYCODE_HOME')
action(19,'Scroll generated tools','swipe','910','1410','100','1410','500')
action(22,'Return Home after tool browsing','keyevent','KEYCODE_HOME')
record.communicate(timeout=10)
assert record.returncode==0,record.returncode
adb('pull',REMOTE,str(OUT/'home-flow.mp4'),capture_output=True)
(OUT/'home-flow-events.json').write_text(json.dumps(events,ensure_ascii=False,indent=2))
print(OUT/'home-flow.mp4')
