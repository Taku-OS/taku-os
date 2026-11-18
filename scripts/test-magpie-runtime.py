#!/usr/bin/env python3
"""Real Android Codex + Magpie, with a labelled local Chat Completions fixture upstream.

Exercises protocol translation, Chinese deltas, dynamic phone tools, route groups,
busy switching and interruption. This is not a real-model quality test.
"""
import argparse, json, os, pathlib, shlex, subprocess, threading, time, uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = pathlib.Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--probe', default='/data/local/tmp/taku-magpie-probe.jar')
args = parser.parse_args()
adb = [os.environ.get('ADB', str(pathlib.Path.home() / 'Library/Android/sdk/platform-tools/adb')), '-s', args.serial]
env = dict(os.environ, ADB_SERVER_SOCKET=os.environ.get('ADB_SERVER_SOCKET', 'tcp:5037'))

def command(*parts):
    p = subprocess.run(adb + list(parts), capture_output=True, text=True, env=env, timeout=55)
    if p.returncode: raise RuntimeError(p.stderr.strip() or p.stdout.strip())
    return p.stdout

def shell(*parts): return command('shell', shlex.join(parts))
def exchange(value):
    raw = shell('/system/bin/env', 'CLASSPATH=' + args.probe, 'app_process', '/system/bin',
                'org.agentos.tests.NativeSocketProbe', 'exchange', json.dumps(value, ensure_ascii=False))
    return json.loads(raw)
def rpc(method, params): return exchange(dict(op='rpc', method=method, params=params))
def connection(action='read', **kw): return exchange(dict(op='modelConnection', action=action, **kw))
def eventually(fn, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = fn()
        if value: return value
        time.sleep(.25)
    raise AssertionError('Timed out waiting for native state')

assert command('emu', 'avd', 'name').splitlines()[0] == 'Taku_ImageIntegration_20261010'
assert shell('id', '-u').strip() == '0', 'adb root required'
fixture = 'taku-fixture-' + uuid.uuid4().hex[:8]
events = []

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        body = json.dumps({'object':'list', 'data':[{'id':'fixture-chat', 'object':'model'}]}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(body)
    def do_POST(self):
        data = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        messages = data.get('messages', [])
        text = json.dumps(messages, ensure_ascii=False)
        tools = [x.get('function', {}).get('name','') for x in data.get('tools', [])]
        events.append({'path':self.path, 'model':data.get('model'), 'tools':tools})
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
        def event(delta, finish=None):
            item = dict(id='chatcmpl-fixture', object='chat.completion.chunk', created=1,
                        model='fixture-chat', choices=[dict(index=0, delta=delta, finish_reason=finish)])
            packet = ('data: ' + json.dumps(item,ensure_ascii=False) + '\n\n').encode()
            # Split UTF-8 at arbitrary byte boundaries, as network streams may do.
            for i in range(0,len(packet),7): self.wfile.write(packet[i:i+7]); self.wfile.flush()
        try:
            event({'role':'assistant'})
            if 'fixture-wait' in text:
                event({'content':'Waiting'}); time.sleep(30)
            elif 'fixture-tool' in text and not any(x.get('role')=='tool' for x in messages):
                name = next(x for x in tools if 'device_info' in x)
                event({'tool_calls':[{'index':0,'id':'fixture_call','type':'function','function':{'name':name,'arguments':'{}'}}]})
                event({},'tool_calls'); self.wfile.write(b'data: [DONE]\n\n'); return
            for part in ['## 本地路由验证\n\n','中文','流式','正常',' 🌏','\n\n- 工具结果已收到']:
                event({'content':part}); time.sleep(.04)
            event({},'stop'); self.wfile.write(b'data: [DONE]\n\n')
        except (BrokenPipeError, ConnectionResetError): pass

server = ThreadingHTTPServer(('127.0.0.1',0),Handler)
server.daemon_threads = True
threading.Thread(target=server.serve_forever, daemon=True).start()
port = server.server_port
initial = connection()
threads = []
provider_added = group_added = False
report = {'modelResponsesSimulated': True, 'gatewayOnAndroid': True, 'checks': []}
def check(name, value):
    assert value, name
    report['checks'].append(name)
    print('PASS:',name,flush=True)
def turn_state(thread, turn):
    view = json.loads(shell('cat','/data/user/0/org.agentos.assistant/files/runtime/agent-view/'+thread+'.json'))
    return next((t for t in view.get('turns',[]) if t['id']==turn),{})
def finished(thread, turn):
    value = turn_state(thread,turn)
    return value if value.get('status') in ('completed','failed','interrupted') else None

try:
    check('init gateway running', initial['running'])
    try: connection('select',mode='magpie',model=fixture+'/missing')
    except RuntimeError: pass
    else: raise AssertionError('Unknown model accepted')
    check('unknown model leaves connection unchanged',connection()['mode']==initial['mode'])
    command('reverse','tcp:'+str(port),'tcp:'+str(port))
    shell('magpie','provider','add',fixture,'url=http://127.0.0.1:'+str(port)+'/v1','key=fixture-only','models=fixture-chat')
    provider_added = True
    shell('magpie','group','add',fixture,'models='+fixture+'/fixture-chat','routing=order')
    group_added = True
    group = 'group/'+fixture
    eventually(lambda: any(m['id']==group for m in connection()['models']))
    connection('select', mode='magpie', model=group)
    models = rpc('model/list',{'limit':100})['data']
    config=rpc('config/read',{})['config']
    check('provider URL is valid TOML without JSON slash escaping',config['model_providers']['magpie']['base_url']=='http://127.0.0.1:3425/v1')
    check('Codex reads native gateway catalog',any(x['model']==group for x in models))
    created = rpc('thread/start',{'developerInstructions':'This is a labelled local protocol fixture. Use the offered read-only phone tool.'})
    thread = created['thread']['id']; threads.append(thread)
    check('native thread uses Magpie',created['thread']['modelProvider']=='magpie')
    started = rpc('turn/start',{'threadId':thread,'input':[{'type':'text','text':'fixture-tool'}]})
    turn = started['turn']['id']
    completed = eventually(lambda:finished(thread,turn),60)
    check('tool round completes',completed['status']=='completed')
    items = completed.get('items',[])
    check('dynamic phone tool actually executes',any(x.get('type')=='dynamicToolCall' and x.get('status')=='completed' for x in items))
    text = '\n'.join(x.get('text','') for x in items if x.get('type')=='agentMessage')
    check('Chinese and emoji survive streaming translation','中文流式正常 🌏' in text)
    check('group routes to Chat Completions provider',len(events)>=2 and all(x['model']=='fixture-chat' for x in events))
    waiting = rpc('turn/start',{'threadId':thread,'input':[{'type':'text','text':'fixture-wait'}]})['turn']['id']
    eventually(lambda:exchange(dict(op='state',threadId=thread)).get('activeTurnId'))
    try: connection('select',mode='openai')
    except RuntimeError as e: check('busy connection switch is rejected','任务仍在执行' in str(e))
    else: raise AssertionError('Changed connection during an active turn')
    rpc('turn/interrupt',{'threadId':thread,'turnId':waiting})
    ended = eventually(lambda:finished(thread,waiting))
    check('native interruption reaches terminal state',ended['status']=='interrupted')
    connection('select',mode='openai')
    resumed = rpc('thread/resume',{'threadId':thread})
    check('same history survives provider switch',resumed['thread']['modelProvider']=='openai' and len(resumed['thread']['turns'])>=2)
finally:
    for thread in threads:
        try:
            state=exchange(dict(op='state',threadId=thread))
            if state.get('activeTurnId'): rpc('turn/interrupt',dict(threadId=thread,turnId=state['activeTurnId']));time.sleep(.5)
            rpc('thread/archive',{'threadId':thread})
        except Exception: pass
    connection('select',mode=initial['mode'],model=initial.get('model',''))
    if group_added: shell('magpie','group','rm',fixture)
    if provider_added: shell('magpie','provider','rm',fixture)
    command('reverse','--remove','tcp:'+str(port))
    server.shutdown()
out=ROOT/'out/magpie-tests/fixture-result.json';out.parent.mkdir(parents=True,exist_ok=True)
out.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print('Restored original connection; removed fixture provider and route.',flush=True)
