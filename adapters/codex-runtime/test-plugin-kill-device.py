#!/usr/bin/env python3
import json, pathlib, select, shlex, subprocess, sys, tempfile, uuid
if len(sys.argv)!=3 or not sys.argv[1].startswith('emulator-'):raise SystemExit('usage: test-plugin-kill-device.py EMULATOR_SERIAL LOCAL_CODEX_BINARY')
serial,binary=sys.argv[1:];root=pathlib.Path(__file__).resolve().parents[2];remote='/data/local/tmp/taku-round11-kill-'+uuid.uuid4().hex
market=remote+'/fixture/.agents/plugins/marketplace.json';cache=remote+'/home/plugins/cache/taku-test-marketplace/taku-local-example'; stage=remote+'/home/plugins/.transaction/staged/2.0.0/big.dat'; proc=None; watcher=None; seq=0
def adb(*args,**kw):kw.setdefault('check',True);return subprocess.run(['adb','-s',serial,*args],**kw)
if adb('emu','avd','name',text=True,capture_output=True).stdout.splitlines()[0].strip()!='Taku_SkillsLifecycle_20261030':raise RuntimeError('wrong AVD')
def call(method,params):
 global seq
 seq+=1;proc.stdin.write(json.dumps({'id':seq,'method':method,'params':params})+'\n');proc.stdin.flush()
 while True:
  ready,_,_=select.select([proc.stdout],[],[],45)
  if not ready:raise RuntimeError(method+' timed out')
  line=proc.stdout.readline()
  if not line:raise RuntimeError(method+' server exited')
  r=json.loads(line)
  if r.get('id')==seq:
   if 'error' in r:raise RuntimeError(method+': '+str(r['error']))
   return r.get('result')
def start():
 global proc,seq
 seq=0
 shell='echo $$ > {0}/server.pid; exec env HOME={0} CODEX_HOME={0}/home TMPDIR={0} {0}/codex app-server'.format(remote)
 proc=subprocess.Popen(['adb','-s',serial,'shell',shell],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
 call('initialize',{'clientInfo':{'name':'round11_kill','version':'1'},'capabilities':{'experimentalApi':True}})
 proc.stdin.write('{"method":"initialized"}\n');proc.stdin.flush()
def stop():
 global proc
 proc.terminate();proc.wait(timeout=10);proc=None
try:
 adb('shell','mkdir','-p',remote+'/home')
 adb('push',binary,remote+'/codex',stdout=subprocess.DEVNULL);adb('shell','chmod','755',remote+'/codex')
 adb('push',str(root/'examples/codex-plugin-lifecycle'),remote+'/fixture',stdout=subprocess.DEVNULL)
 start();call('plugin/install',{'marketplacePath':market,'pluginName':'taku-local-example'});print('PASS installed 1.0.0',flush=True)
 manifest=json.loads((root/'examples/codex-plugin-lifecycle/plugin/.codex-plugin/plugin.json').read_text());manifest['version']='2.0.0'
 with tempfile.NamedTemporaryFile(mode='w') as f:
  json.dump(manifest,f);f.flush();adb('push',f.name,remote+'/fixture/plugin/.codex-plugin/plugin.json',stdout=subprocess.DEVNULL)
 adb('shell','dd','if=/dev/zero','of='+remote+'/fixture/plugin/big.dat','bs=1048576','count=1024',stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 pid=adb('shell','cat',remote+'/server.pid',text=True,capture_output=True).stdout.strip()
 if not pid.isdigit():raise RuntimeError('invalid app-server PID')
 check='while [ ! -e {stage} ]; do if ! kill -0 {pid} 2>/dev/null; then exit 2; fi; sleep 0.01; done; echo staging > {flag}; kill -9 {pid}'.format(stage=shlex.quote(stage),pid=pid,flag=shlex.quote(remote+'/killed'))
 watcher=subprocess.Popen(['adb','-s',serial,'shell',check],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 try:
  call('plugin/install',{'marketplacePath':market,'pluginName':'taku-local-example'})
  raise RuntimeError('upgrade completed before kill')
 except RuntimeError as e:
  if 'server exited' not in str(e):raise
 if watcher.wait(timeout=20)!=0:raise RuntimeError('kill watcher failed')
 proc.wait(timeout=10);proc=None
 adb('shell','test','-f',remote+'/killed');adb('shell','test','-d',remote+'/home/plugins/.transaction/staged')
 if adb('shell','test','-e',remote+'/home/plugins/.transaction/journal.json',check=False).returncode==0:raise RuntimeError('kill occurred after journal preparation')
 print('PASS killed app-server with SIGKILL during staging before journal preparation',flush=True)
 start()
 adb('shell','test','-f',cache+'/1.0.0/.codex-plugin/plugin.json')
 if adb('shell','test','-e',remote+'/home/plugins/.transaction',check=False).returncode==0:raise RuntimeError('transaction remains after restart')
 print('PASS restart recovered 1.0.0 and removed interrupted staging',flush=True)
finally:
 if watcher is not None and watcher.poll() is None:watcher.terminate();watcher.wait(timeout=10)
 if proc is not None:stop()
 adb('shell','rm','-rf',remote)
