#!/usr/bin/env python3
"""Kill an isolated uninstall on the dedicated AVD and verify journal recovery."""
import json,pathlib,select,shlex,subprocess,sys,tempfile,tomllib,uuid
if len(sys.argv)!=3 or not sys.argv[1].startswith('emulator-'):raise SystemExit('usage: test-plugin-uninstall-kill-device.py EMULATOR_SERIAL LOCAL_CODEX_BINARY')
serial,binary=sys.argv[1:];root=pathlib.Path(__file__).resolve().parents[2];remote='/data/local/tmp/taku-round12-uninstall-'+uuid.uuid4().hex;market=remote+'/fixture/.agents/plugins/marketplace.json';cache=remote+'/home/plugins/cache/taku-test-marketplace/taku-local-example';proc=None;watcher=None;seq=0
def adb(*args,**kw):kw.setdefault('check',True);return subprocess.run(['adb','-s',serial,*args],**kw)
if adb('emu','avd','name',text=True,capture_output=True).stdout.splitlines()[0].strip()!='Taku_SkillsLifecycle_20261030':raise RuntimeError('wrong AVD')
def call(method,params):
 global seq
 seq+=1;proc.stdin.write(json.dumps({'id':seq,'method':method,'params':params})+'\n');proc.stdin.flush()
 while True:
  ready,_,_=select.select([proc.stdout],[],[],600)
  if not ready:raise RuntimeError(method+' timed out')
  line=proc.stdout.readline()
  if not line:raise RuntimeError(method+' server exited')
  r=json.loads(line)
  if r.get('id')==seq:
   if 'error' in r:raise RuntimeError(method+': '+str(r['error']))
   return r.get('result')
def start():
 global proc,seq
 seq=0;shell='echo $$ > {0}/server.pid; exec env HOME={0} CODEX_HOME={0}/home TMPDIR={0} {0}/codex app-server'.format(remote)
 proc=subprocess.Popen(['adb','-s',serial,'shell',shell],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
 call('initialize',{'clientInfo':{'name':'round12_uninstall_kill','version':'1'},'capabilities':{'experimentalApi':True}});proc.stdin.write('{"method":"initialized"}\n');proc.stdin.flush()
try:
 adb('shell','mkdir','-p',remote+'/home');adb('push',binary,remote+'/codex',stdout=subprocess.DEVNULL);adb('shell','chmod','755',remote+'/codex');adb('push',str(root/'examples/codex-plugin-lifecycle'),remote+'/fixture',stdout=subprocess.DEVNULL)
 start();call('plugin/install',{'marketplacePath':market,'pluginName':'taku-local-example'})
 manifest=json.loads((root/'examples/codex-plugin-lifecycle/plugin/.codex-plugin/plugin.json').read_text());manifest['version']='2.0.0'
 with tempfile.NamedTemporaryFile(mode='w') as f:
  json.dump(manifest,f);f.flush();adb('push',f.name,remote+'/fixture/plugin/.codex-plugin/plugin.json',stdout=subprocess.DEVNULL)
 bulk=remote+'/fixture/plugin/bulk';script='mkdir -p {0}; i=0; while [ "$i" -lt 20000 ]; do : > {0}/f$i; i=$((i+1)); done'.format(shlex.quote(bulk));adb('shell',script)
 call('plugin/install',{'marketplacePath':market,'pluginName':'taku-local-example'})
 backup=remote+'/home/plugins/.backups/taku-test-marketplace/taku-local-example'
 adb('shell','test','-f',backup+'/1.0.0/.codex-plugin/plugin.json')
 config=adb('shell','cat',remote+'/home/config.toml',text=True,capture_output=True).stdout
 if tomllib.loads(config).get('plugins',{}).get('taku-local-example@taku-test-marketplace',{}).get('enabled') is not True:raise RuntimeError('installed plugin not enabled in config')
 print('PASS upgraded to 2.0.0 with enabled config, 1.0.0 backup and 20000 fixture files',flush=True)
 pid=adb('shell','cat',remote+'/server.pid',text=True,capture_output=True).stdout.strip()
 if not pid.isdigit():raise RuntimeError('invalid PID')
 first=cache+'/2.0.0/bulk/f0';adb('shell','test','-f',first)
 script='while [ -e {first} ]; do if ! kill -0 {pid} 2>/dev/null; then exit 2; fi; sleep 0.01; done; echo unlinked > {flag}; kill -9 {pid}'.format(first=shlex.quote(first),pid=pid,flag=shlex.quote(remote+'/killed'))
 watcher=subprocess.Popen(['adb','-s',serial,'shell',script],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 try:
  call('plugin/uninstall',{'pluginId':'taku-local-example@taku-test-marketplace'});raise RuntimeError('uninstall completed before kill')
 except RuntimeError as e:
  if 'server exited' not in str(e):raise
 if watcher.wait(timeout=20)!=0:raise RuntimeError('watcher failed')
 proc.wait(timeout=10);proc=None;adb('shell','test','-f',remote+'/killed')
 if adb('shell','test','-d',cache+'/2.0.0/bulk',check=False).returncode!=0:raise RuntimeError('bulk already removed before kill')
 adb('shell','test','-f',backup+'/1.0.0/.codex-plugin/plugin.json')
 journal=remote+'/home/plugins/.transaction/journal.json'
 record=adb('shell','cat',journal,text=True,capture_output=True).stdout.strip()
 if json.loads(record)!={'uninstall':'taku-test-marketplace@taku-local-example'}:raise RuntimeError('missing uninstall journal at SIGKILL: '+record)
 print('PASS SIGKILL after first file removed with uninstall journal present and partial cache remaining',flush=True)
 start()
 if adb('shell','test','-d',cache+'/2.0.0/bulk',check=False).returncode==0:
  if adb('shell','test','-e',first,check=False).returncode==0:
   print('FAIL interrupted uninstall was not recovered: plugin is installed or reinstalled after restart',flush=True)
  else:
   print('FAIL interrupted uninstall was not recovered: partial cache remains after restart',flush=True)
  sys.exit(2)
 for path in [cache,remote+'/home/plugins/.backups/taku-test-marketplace/taku-local-example',remote+'/home/plugins/.transaction']:
  if adb('shell','test','-e',path,check=False).returncode==0:raise RuntimeError('uninstall residue remains: '+path)
 config=adb('shell','cat',remote+'/home/config.toml',text=True,capture_output=True).stdout
 if 'taku-local-example@taku-test-marketplace' in tomllib.loads(config).get('plugins',{}):raise RuntimeError('plugin config remains after recovery')
 print('PASS restart completed uninstall: cache, backup, journal and enabled config absent',flush=True)
finally:
 if watcher is not None and watcher.poll() is None:watcher.terminate();watcher.wait(timeout=10)
 if proc is not None:proc.terminate();proc.wait(timeout=10)
 adb('shell','rm','-rf',remote)
