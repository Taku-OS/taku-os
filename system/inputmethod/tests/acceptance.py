#!/usr/bin/env python3
# SPDX-License-Identifier: LGPL-2.1-or-later
"""Tap the real keyboard on a test emulator or an explicitly authorized physical device."""
import argparse,json,os,pathlib,re,shutil,subprocess,time,types
ROOT=pathlib.Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial')
parser.add_argument('--allow-physical', action='store_true',
 help='Use only after the device owner requests installation, default IME selection and testing.')
parser.add_argument('--output', type=pathlib.Path,
 help='Separate evidence directory; defaults to out/inputmethod/acceptance[-SERIAL].')
args=parser.parse_args()
physical=not args.serial.startswith('emulator-')
if physical and not args.allow_physical:
 raise SystemExit('Physical testing changes the default IME; require owner authorization and --allow-physical.')
SDK=pathlib.Path(os.environ.get('ANDROID_SDK_ROOT',os.environ.get('ANDROID_HOME',str(pathlib.Path.home()/'Library/Android/sdk'))))
ADB=shutil.which('adb') or str(SDK/'platform-tools/adb')
OUT=args.output or ROOT/('out/inputmethod/acceptance-'+args.serial if physical else 'out/inputmethod/acceptance')
OUT.mkdir(parents=True,exist_ok=True)
PACKAGE='org.agentos.inputmethod'
COMPONENT=PACKAGE+'/org.fcitx.fcitx5.android.input.FcitxInputMethodService'
FIXTURE='org.agentos.inputmethod.fixture'

def adb(*parts):
 return subprocess.check_output([ADB,'-s',args.serial,*parts],text=True,timeout=60)
def shell(*parts): return adb('shell',*parts)
def nodes():
 raw=shell('am','instrument','-w','org.agentos.inputmethod.probe/.UiProbe')
 prefix='INSTRUMENTATION_RESULT: nodes='
 line=next((line[len(prefix):] for line in raw.splitlines() if line.startswith(prefix)),None)
 assert line is not None, raw
 # Keep evidence limited to the synthetic fixture, keyboard and its setup settings.
 data=[item for item in json.loads(line) if item.get('package') in
       {PACKAGE,FIXTURE,'org.agentos.inputmethod.probe','com.android.settings'}]
 (OUT/'latest-ui.json').write_text(json.dumps(data,ensure_ascii=False,indent=2))
 return [types.SimpleNamespace(attrib=item) for item in data]
def tap(node):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
 shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
 time.sleep(.12)
def find(text=None,resource=None,description=None):
 for attempt in range(5):
  for n in nodes():
   a=n.attrib
   if text is not None and a.get('text')!=text: continue
   if resource is not None and not a.get('resource-id','').endswith('/'+resource): continue
   if description is not None and a.get('content-desc')!=description: continue
   return n
  time.sleep(.3)
 raise AssertionError(f'UI item missing: {text or resource or description}')
def state(): return json.loads(shell('run-as',FIXTURE,'cat','files/state.json'))
def launch(mode='text'):
 shell('am','force-stop',FIXTURE)
 shell('am','start','-n',FIXTURE+'/.InputActivity','--es','mode',mode)
 time.sleep(.8)
 tap(find(description='Taku test editor'))
 time.sleep(.5)
def screenshot(name):
 data=subprocess.check_output([ADB,'-s',args.serial,'exec-out','screencap','-p'])
 (OUT/(name+'.png')).write_bytes(data)
def write_letters(value):
 # Resolve key bounds from visible text. No programmatic text injection.
 for attempt in range(5):
  visible=nodes()
  if all(any(n.attrib.get('package')==PACKAGE and n.attrib.get('text','').lower()==letter.lower() for n in visible) for letter in value): break
  time.sleep(.3)
 for letter in value:
  matches=[n for n in visible if n.attrib.get('package')==PACKAGE and n.attrib.get('text','').lower()==letter.lower()]
  assert matches, f'Keyboard letter missing: {letter}'
  # A letter can also be an engine candidate. The keyboard key is below the candidate strip.
  tap(max(matches,key=lambda n:int(re.findall(r'\d+',n.attrib['bounds'])[1])))

def assert_text(expected):
 actual=state()['text'];assert actual==expected,(expected,actual)

def assert_preedit(expected):
 # Fcitx can keep composition in its own preedit strip rather than the app EditText.
 visible=[n.attrib.get('text','').replace(' ','') for n in nodes()
          if n.attrib.get('package')==PACKAGE]
 assert expected in visible, (expected,visible)

initial_night=shell('cmd','uimode','night').strip().split(': ',1)[-1]
assert initial_night in {'yes','no','auto','custom'}, initial_night
initial={'defaultIme':shell('settings','get','secure','default_input_method').strip(),
         'enabledImes':shell('settings','get','secure','enabled_input_methods').strip(),
         'nightMode':initial_night}
(OUT/'initial-state.json').write_text(json.dumps(initial,indent=2)+'\n')
completed=[]
def passed(name):
 completed.append(name)
 print('PASS '+name,flush=True)

def run_tests():
 setup_verified=False
 if COMPONENT not in shell('settings','get','secure','enabled_input_methods'):
  shell('am','start','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/org.fcitx.fcitx5.android.ui.main.MainActivity')
  button=find(resource='action_button')
  screenshot('setup-guide')
  tap(button)
  for attempt in range(5):
   current=nodes()
   if any(n.attrib.get('package')=='com.android.settings' and 'Taku' in n.attrib.get('text','') for n in current):
    setup_verified=True;break
   time.sleep(.3)
  assert setup_verified, 'Enable guide did not open Android input method settings'
  shell('input','keyevent','BACK')
 shell('ime','enable',COMPONENT);shell('ime','set',COMPONENT)
 launch()
 # A previous App regression can leave the persisted engine in English.
 if not any('Pinyin' in n.attrib.get('text','') or '拼音' in n.attrib.get('text','') for n in nodes()):
  tap(find(resource='button_lang'))
 # The full-color Taku logo remains an actionable toolbar toggle.
 logo=next(n for n in nodes() if n.attrib.get('content-desc','').startswith('Taku · '))
 original_description=logo.attrib['content-desc']
 screenshot('taku-logo-keyboard')
 tap(logo)
 expanded=next(n for n in nodes() if n.attrib.get('content-desc','').startswith('Taku · '))
 assert expanded.attrib['content-desc']!=original_description
 screenshot('taku-logo-toolbar')
 tap(expanded)
 assert_text('')
 passed('taku-logo-toolbar-toggle')
 write_letters('nihaox')
 assert_preedit('nihaox')
 tap(find(resource='button_backspace'))
 assert_preedit('nihao')
 passed('delete-composition')
 screenshot('chinese-candidates')
 tap(find(text='你好'))
 assert_text('你好')
 passed('pinyin-candidate-commit')
 # Composition deletion, commit deletion and switching are covered by real key presses.
 tap(find(resource='button_backspace'))
 assert_text('你')
 tap(find(resource='button_backspace'))
 assert_text('')
 passed('delete-committed-text')
 tap(find(resource='button_lang'))
 write_letters('taku')
 assert_text('taku')
 passed('chinese-english-switch')
 passed('english-typing')
 screenshot('english')
 tap(find(text='?123'))
 tap(find(text='!'))
 assert_text('taku!')
 passed('symbols')
 screenshot('symbols')
 launch('number')
 for digit in '123': tap(find(text=digit))
 assert_text('123')
 passed('numeric-editor')
 screenshot('number')
 launch('password')
 write_letters('safe')
 assert_text('safe')
 passed('password-editor')
 screenshot('password')
 # Recreate the IME process, return to text input and prove it can still commit Chinese.
 shell('am','force-stop',PACKAGE)
 shell('ime','set',COMPONENT)
 launch()
 # Mode is a persisted user choice: switch back if English was selected above.
 visible=[n.attrib.get('text','') for n in nodes()]
 if any('English' in t or '英语' in t or '英文' in t for t in visible): tap(find(resource='button_lang'))
 write_letters('zhongwen')
 screenshot('chinese-after-restart-candidates')
 tap(find(text='中文'))
 assert_text('中文')
 passed('ime-process-restart-chinese-commit')
 screenshot('chinese-committed')
 for mode,name in [('no','chinese-light'),('yes','chinese-dark')]:
  shell('cmd','uimode','night',mode)
  time.sleep(.8)
  tap(find(description='Taku test editor'))
  assert_text('中文')
  screenshot(name)
 passed('day-night-theme')
 selected=shell('settings','get','secure','default_input_method').strip()
 assert selected==COMPONENT, selected
 result={'ok':True,'setupGuideVerified':setup_verified,'serial':args.serial,
         'deviceType':'physical' if physical else 'emulator','package':PACKAGE,
         'model':shell('getprop','ro.product.model').strip(),
         'androidRelease':shell('getprop','ro.build.version.release').strip(),
         'androidSdk':shell('getprop','ro.build.version.sdk').strip(),
         'adbUid':shell('id','-u').strip(),'defaultIme':selected,'cases':completed,
         'inputMethod':'screen taps on IME keys and candidates; no setText, clipboard or ACTION_SET_TEXT'}
 return result

result=None
try:
 result=run_tests()
except Exception as error:
 (OUT/'failure.json').write_text(json.dumps({'ok':False,'completedCases':completed,
  'error':str(error)},ensure_ascii=False,indent=2)+'\n')
 raise
finally:
 # The requested Taku default remains selected. Restore the owner's theme even on failure.
 shell('cmd','uimode','night',initial_night)
 restored=shell('cmd','uimode','night').strip().split(': ',1)[-1]
 assert restored==initial_night, (initial_night,restored)
 if result is not None:
  result['nightModeRestored']=restored
  (OUT/'failure.json').unlink(missing_ok=True)
  (OUT/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
  print(json.dumps(result,ensure_ascii=False),flush=True)
