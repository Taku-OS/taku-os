#!/usr/bin/env python3
"""Generate Android bindings from the authoritative external design tokens.

Never copies or edits the source token JSON. --check is read-only. Generated
resources remain in the app so an independent checkout builds without source.
"""
import argparse, hashlib, json, re, os
from pathlib import Path
from xml.sax.saxutils import escape
APP = Path(__file__).resolve().parents[1]
DEFAULT = os.environ.get('TAKU_DESIGN_TOKENS')
def slug(name):
 return 'ag_'+re.sub(r'([a-z0-9])([A-Z])',r'\1_\2',name).replace('.','_').lower()
def main():
 parser=argparse.ArgumentParser();parser.add_argument('--source',type=Path,default=Path(DEFAULT) if DEFAULT else None);parser.add_argument('--check',action='store_true');args=parser.parse_args()
 if args.source is None:
  generated=APP/'res/values/ag_design_tokens.xml'
  assert generated.exists(), 'Missing generated design bindings'
  print(json.dumps({'mode':'bundled-resources','generatedResources':'res/values/ag_design_tokens.xml','source':'External optional; pass --source or TAKU_DESIGN_TOKENS to sync/check source SHA'}))
  return
 raw=args.source.read_bytes();source=json.loads(raw);tokens=source['tokens'];sha=hashlib.sha256(raw).hexdigest()
 lines=['<?xml version="1.0" encoding="utf-8"?>',f'<!-- Generated from authoritative tokens v{source["meta"]["version"]}; SHA256 {sha}. Do not edit. -->','<resources>'];seen=set();counts={}
 for name,t in tokens.items():
  key=slug(name);assert key not in seen,name;seen.add(key);kind=t['type'];value=t['value'];counts[kind]=counts.get(kind,0)+1
  if kind=='color':
   assert re.fullmatch(r'#[0-9A-Fa-f]{6}',value),name;tag='color';value=value.upper()
  elif kind=='dimension':
   assert t['unit'] in ['dp','sp'] and isinstance(value,(int,float)) and value>=0,name;tag='dimen';value=f'{value:g}{t["unit"]}'
  elif kind in ['integer','fontWeight','duration']:
   assert isinstance(value,int),name;tag='integer';value=str(value)
  elif kind in ['opacity','number']:
   assert isinstance(value,(int,float)),name
   if kind=='opacity': assert 0<=value<=1,name
   tag='fraction';value=f'{value*100:g}%'
  elif kind=='easing': tag='string';value=escape(value)
  else: raise ValueError(f'Unsupported typed token {name}: {kind}')
  extra=' translatable="false"' if tag=='string' else ''
  lines.append(f'    <{tag} name="{key}"{extra}>{value}</{tag}>')
 lines.append('</resources>')
 provenance={'source':'external:Agentroid OS design-system tokens.json','name':source['meta']['name'],'version':source['meta']['version'],'date':source['meta']['date'],'sha256':sha,'tokenCount':len(tokens),'typeCounts':counts,'generatedResources':'res/values/ag_design_tokens.xml','localProductName':'Taku OS','note':'Numeric/color truth remains in source tokens.json. Local product name follows this task; source is not edited.'}
 outputs={APP/'res/values/ag_design_tokens.xml':'\n'.join(lines)+'\n',APP/'design-token-provenance.json':json.dumps(provenance,ensure_ascii=False,indent=2)+'\n'}
 for path,value in outputs.items():
  if args.check: assert path.exists() and path.read_text()==value,f'Stale generated binding: {path}'
  else: path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)
 print(json.dumps({'mode':'check' if args.check else 'sync','version':source['meta']['version'],'sha256':sha,'tokens':len(tokens),'outputs':[str(p.relative_to(APP)) for p in outputs]},ensure_ascii=False))
if __name__=='__main__':main()
