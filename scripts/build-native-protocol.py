#!/usr/bin/env python3
"""Lock the public phone runtime's generated protocol into the Android client."""
import json,pathlib,hashlib
ROOT=pathlib.Path(__file__).resolve().parents[1]
BASE=ROOT/'out/app-server-audit-2026-10-15/protocol'
full=json.loads((BASE/'experimental/ClientRequest.json').read_text());ordinary=json.loads((BASE/'stable/ClientRequest.json').read_text())
def name(x):return x['properties']['method']['enum'][0]
normal={name(x) for x in ordinary['oneOf']}
methods=[]
for item in full['oneOf']:
 n=name(item)
 if n=='mock/experimentalMethod':continue
 p=item.get('properties',{}).get('params',{})
 ref=p.get('$ref')
 if not ref:
  ref=next((v.get('$ref') for v in p.get('anyOf',[]) if v.get('$ref')),None)
 schema=full['definitions'].get(ref.rsplit('/',1)[-1],{}) if ref else {}
 methods.append({'method':n,'experimental':n not in normal,'params':schema,'required':schema.get('required',[])})
# Taku's patched 0.161.0 runtime adds rollback; older audit schemas do not contain it.
if not any(method['method']=='plugin/rollback' for method in methods):
 schema={'properties':{'pluginId':{'type':'string'}},'required':['pluginId'],'type':'object'}
 methods.append({'method':'plugin/rollback','experimental':False,'params':schema,'required':['pluginId']})
server=json.loads((BASE/'experimental/ServerRequest.json').read_text())
data={'version':'0.161.0','methods':methods,'definitions':full['definitions'],'serverDefinitions':server['definitions']}
p=ROOT/'system/assistant/assets/native/protocol.json';p.parent.mkdir(parents=True,exist_ok=True)
p.write_text(json.dumps(data,separators=(',',':'))+'\n')
print('Pinned native protocol:',len(methods),'methods',p.stat().st_size,'bytes',hashlib.sha256(p.read_bytes()).hexdigest())
for n in ['ThreadRealtimeAudioChunk','DynamicToolSpec','DynamicToolCallResponse','ToolRequestUserInputResponse','RealtimeOutputModality','ThreadRealtimeStartTransport','MultiAgentMode']:
 d=data['definitions'].get(n) or data['serverDefinitions'].get(n)
 if not d:
  f=BASE/'experimental'/(n+'.json')
  if f.exists():d=json.loads(f.read_text())
 print(n,json.dumps(d)[:1600])
