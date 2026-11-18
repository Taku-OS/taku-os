#!/usr/bin/env python3
"""Actual native dictation controls with quiet emulator input; never sends a message."""
import argparse,json,re,subprocess,time,xml.etree.ElementTree as ET
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--serial',required=True);p.add_argument('--adb',default='adb');a=p.parse_args()
if not re.fullmatch(r'emulator-[0-9]+',a.serial):p.error('Select an explicit development emulator')
def call(*args):return subprocess.check_output([a.adb,'-s',a.serial,*args],text=True,stderr=subprocess.STDOUT)
def dump():
 for attempt in range(3):
  output=call('shell','uiautomator','dump','/data/local/tmp/taku-audio-ui.xml')
  if 'dumped to:' in output:return ET.fromstring(call('shell','cat','/data/local/tmp/taku-audio-ui.xml'))
  time.sleep(.2)
 raise RuntimeError('UI hierarchy unavailable; no stale dump used')
def find(root,desc):return next((n for n in root.iter('node') if n.get('content-desc')==desc),None)
def tap(node):
 if node is None:raise RuntimeError('Composer microphone not visible')
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
 call('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def wait_status(expected):
 deadline=time.monotonic()+10
 while time.monotonic()<deadline:
  tree=dump()
  if any(expected in n.get('text','') for n in tree.iter('node')):return tree
  if any('听写已停止（' in n.get('text','') for n in tree.iter('node')):return None
 return None
call('shell','am','start','-n','org.agentos.assistant/.PanelActivity')
root=dump();editor=find(root,'Taku 对话输入框');deadline=time.monotonic()+7
while editor is None and time.monotonic()<deadline:
 time.sleep(.2);root=dump();editor=find(root,'Taku 对话输入框')
if editor is None:raise SystemExit('Open the native Agent composer before this test')
before=editor.get('text','')
tap(find(root,'语音输入'));running=wait_status('正在聆听，点击麦克风完成')
if running is None:raise SystemExit('Native microphone did not reach listening; inspect the voice status on device')
tap(find(running,'语音输入'));finished=wait_status('语音文字已加入草稿')
if finished is None:raise SystemExit('Native microphone did not finish normally')
after=find(finished,'Taku 对话输入框').get('text','')
report={'passed':before==after,'nativeComposerVisible':True,'listeningObserved':True,'finishedObserved':True,'draftPreserved':before==after,'scope':'actual native microphone controls with quiet emulator input; not a text insertion quality test'}
print(json.dumps(report));raise SystemExit(0 if report['passed'] else 1)
