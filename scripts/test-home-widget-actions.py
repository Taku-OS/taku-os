#!/usr/bin/env python3
"""Widget click integration checks on an explicitly selected isolated emulator."""
import argparse,json,subprocess,shlex,time,pathlib
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--output',required=True,type=pathlib.Path)
parser.add_argument('--fixture',default='system/desktop-widgets/out/test-fixture/fixture.apk',type=pathlib.Path)
parser.add_argument('--with-pixel-weather',action='store_true',help='Also verify the separately installed original Pixel Weather app')
parser.add_argument('--adb',default=str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'))
args=parser.parse_args()
if not args.serial.startswith('emulator-'):parser.error('Use an isolated emulator')
adb=[args.adb,'-s',args.serial]
uri='content://org.agentos.agent.home-layout'
proof={}
def shell(s):return subprocess.check_output(adb+['shell',s],text=True,timeout=30)
def call(method,p=None,authority=uri):
 raw=shell('content call --uri '+authority+' --method '+method+' --arg '+shlex.quote(json.dumps(p or {})))
 obj=json.loads(raw.split('result=',1)[1].rsplit('}]',1)[0]);assert obj['ok'],obj
 return obj['result']
def home():
 shell('am start -n org.agentos.agent/.home.HomeActivity -a android.intent.action.MAIN -c android.intent.category.HOME');time.sleep(.8)
def resumed():
 s=shell('dumpsys activity activities')
 return next((l.strip() for l in s.splitlines() if 'topResumedActivity=' in l),'')
def click(w,control=None):
 home();tree=call('widgets/content',{'appWidgetId':w})['view']
 def walk(n):
  if n['clickable'] and n['enabled'] and n['visible'] and (n['description']==control if control else n['class']=='android.widget.ImageView' and n['description']):return n['path']
  for c in n.get('children',[]):
   p=walk(c)
   if p is not None:return p
 path=walk(tree);assert path is not None,tree
 call('widgets/action',{'appWidgetId':w,'path':path});time.sleep(1)
 return resumed()
home();baseline=call('layout/get')['pages']
widgets=call('widgets/list')['widgets'];ids={x['provider'].split('.')[-1]:x['appWidgetId'] for x in widgets}
assert 'org.taku.review.music' not in shell('pm list packages org.taku.review.music'), 'fixture already installed'
installed=False
try:
 for kind,package in [('ClockWidget','com.android.deskclock'),('DayWidget','com.android.deskclock'),('DateWidget','com.android.calendar'),('AlarmWidget','com.android.deskclock')]:
  result=click(ids[kind]);proof[kind]=result
  # The emulator's calendar hands off to Google account setup when no calendars exist.
  assert package in result or (kind=='DateWidget' and 'com.google.android.gms/.auth.' in result),result
 command=call('command',{'command':'clock'},'content://org.taku.widgets.data')
 home();shell(command['agentCommand']);time.sleep(.5);proof['agentClock']=resumed();assert 'com.android.deskclock' in proof['agentClock']
 if args.with_pixel_weather:
  result=click(ids['WeatherWidget']);proof['WeatherWidget']=result;assert 'com.google.android.apps.weather' in result,result
  command=call('open',authority='content://org.taku.weather.data');assert command['package']=='com.google.android.apps.weather',command
  home();shell(command['agentCommand']);time.sleep(.5);proof['agentWeather']=resumed();assert 'com.google.android.apps.weather' in proof['agentWeather']
 subprocess.run(adb+['install','-r',str(args.fixture)],check=True,capture_output=True);installed=True
 time.sleep(.5)
 result=click(ids['MusicWidget']);proof['musicWithoutSession']=result;assert 'org.taku.review.music/.MusicFixture' in result,result
 result=click(ids['MusicWidget'],'Play');proof['playWithoutSession']=result;assert 'org.taku.review.music/.MusicFixture' in result,result
 command=call('command',{'command':'music'},'content://org.taku.widgets.data');assert command['effect']=='activity-launch-required'
 home();shell(command['agentCommand']);time.sleep(.5);proof['agentMusic']=resumed();assert 'org.taku.review.music' in proof['agentMusic']
 shell('am force-stop org.taku.review.music')
 shell('am start -n org.taku.review.music/.MusicFixture --ez active true');time.sleep(1)
 data=call('get',authority='content://org.taku.widgets.data');assert data['media']['available'],data
 result=click(ids['MusicWidget']);proof['activeSessionPlayer']=result;assert 'MusicFixture$Player' in result,result
 click(ids['MusicWidget'],'Play');time.sleep(.5)
 data=call('get',authority='content://org.taku.widgets.data');assert data['media']['playing'],data;proof['playActiveSession']=True
 click(ids['MusicWidget'],'Pause');time.sleep(.5)
 data=call('get',authority='content://org.taku.widgets.data');assert not data['media']['playing'],data;proof['pauseActiveSession']=True
finally:
 if installed:subprocess.run(adb+['uninstall','org.taku.review.music'],check=True,capture_output=True)
 home();assert call('layout/get')['pages']==baseline,'layout changed'
 args.output.parent.mkdir(parents=True,exist_ok=True)
 args.output.write_text(json.dumps(proof,indent=2)+'\n')
print(json.dumps(proof,indent=2))
