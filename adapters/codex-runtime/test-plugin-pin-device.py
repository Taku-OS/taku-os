#!/usr/bin/env python3
import json, pathlib, select, subprocess, sys, tempfile, uuid
if len(sys.argv) != 3 or not sys.argv[1].startswith('emulator-'):
    raise SystemExit('usage: test-plugin-pin-device.py EMULATOR_SERIAL LOCAL_CODEX_BINARY')
serial,binary=sys.argv[1:]
root=pathlib.Path(__file__).resolve().parents[2]
def adb(*args, **kwargs):
    return subprocess.run(['adb','-s',serial,*args],check=True,**kwargs)
name=adb('emu','avd','name',capture_output=True,text=True).stdout.splitlines()[0].strip()
if name!='Taku_SkillsLifecycle_20261030': raise SystemExit('Wrong AVD')
remote='/data/local/tmp/taku-round11-'+uuid.uuid4().hex
cache=remote+'/home/plugins/cache/taku-test-marketplace/taku-local-example'
market=remote+'/fixture/.agents/plugins/marketplace.json'
id='taku-local-example@taku-test-marketplace'
proc=None
try:
    adb('shell','mkdir','-p',remote+'/home')
    adb('push',binary,remote+'/codex',stdout=subprocess.DEVNULL)
    adb('shell','chmod','755',remote+'/codex')
    adb('push',str(root/'examples/codex-plugin-lifecycle'),remote+'/fixture',stdout=subprocess.DEVNULL)
    def start():
        global proc,seq
        env='HOME={0} CODEX_HOME={0}/home TMPDIR={0}'.format(remote)
        proc=subprocess.Popen(['adb','-s',serial,'shell',env+' '+remote+'/codex app-server'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,bufsize=1)
        seq=0
        call('initialize',{'clientInfo':{'name':'round11','version':'1'},'capabilities':{'experimentalApi':True}})
        proc.stdin.write('{"method":"initialized"}\n');proc.stdin.flush()
    def call(method,params):
        global seq
        seq+=1; proc.stdin.write(json.dumps({'id':seq,'method':method,'params':params})+'\n');proc.stdin.flush()
        while True:
            ready,_,_=select.select([proc.stdout],[],[],45)
            if not ready: raise RuntimeError(method+' timed out')
            line=proc.stdout.readline()
            if not line: raise RuntimeError(method+' server exited')
            msg=json.loads(line)
            if msg.get('id')==seq:
                if 'error' in msg: raise RuntimeError(method+': '+str(msg['error']))
                return msg.get('result')
    def stop():
        global proc
        proc.terminate();proc.wait(timeout=10);proc=None
    start()
    call('plugin/install',{'marketplacePath':market,'pluginName':'taku-local-example'})
    print('PASS installed 1.0.0',flush=True)
    manifest=json.loads((root/'examples/codex-plugin-lifecycle/plugin/.codex-plugin/plugin.json').read_text());manifest['version']='2.0.0'
    with tempfile.NamedTemporaryFile(mode='w') as f:
        json.dump(manifest,f);f.flush()
        adb('push',f.name,remote+'/fixture/plugin/.codex-plugin/plugin.json',stdout=subprocess.DEVNULL)
    call('plugin/install',{'marketplacePath':market,'pluginName':'taku-local-example'})
    adb('shell','test','-f',cache+'/2.0.0/.codex-plugin/plugin.json')
    print('PASS upgraded to 2.0.0',flush=True)
    call('plugin/rollback',{'pluginId':id})
    adb('shell','test','-f',cache+'/1.0.0/.codex-plugin/plugin.json')
    adb('shell','test','-f',cache+'/.taku-rollback-pin')
    print('PASS rollback restored 1.0.0 and pin',flush=True)
    stop();start()
    call('plugin/list',{'cwds':[remote+'/fixture']})
    adb('shell','test','-f',cache+'/1.0.0/.codex-plugin/plugin.json')
    adb('shell','test','-f',cache+'/.taku-rollback-pin')
    print('PASS pin survived restart and plugin/list with upstream 2.0.0',flush=True)
    call('plugin/uninstall',{'pluginId':id})
    call('plugin/install',{'marketplacePath':market,'pluginName':'taku-local-example'})
    adb('shell','test','-f',cache+'/2.0.0/.codex-plugin/plugin.json')
    print('PASS explicit uninstall allows reinstall of 2.0.0',flush=True)
finally:
    if proc is not None:
        proc.terminate();proc.wait(timeout=10)
    adb('shell','rm','-rf',remote)
