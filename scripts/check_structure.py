#!/usr/bin/env python3
"""Offline repository structure/link/source-only gates; no device or network access."""
import json, pathlib, re, subprocess, urllib.parse
ROOT=pathlib.Path(__file__).resolve().parents[1]
REQUIRED=['README.md','ARCHITECTURE.md','PHILOSOPHY.md','LICENSE','NOTICE','CONTRIBUTING.md','CODE_OF_CONDUCT.md','SECURITY.md','SUPPORT.md','GOVERNANCE.md','CHANGELOG.md','.github/CODEOWNERS','docs/README.md','docs/project-structure.md','docs/getting-started.md','docs/development.md','docs/standards.md','evidence/README.md','spec/protocol.schema.json','scripts/build-android.sh','tooling/diagnostics/build.sh']
errors=[];links=0
for name in REQUIRED:
 if not (ROOT/name).is_file():errors.append('Missing entry: '+name)
paths=set(subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0'))-{''}
for name in sorted(paths):
 p=ROOT/name
 if not p.exists():continue # removed files still in index until commit
 if p.is_symlink():errors.append('Source symlink: '+name)
 if any(x in p.relative_to(ROOT).parts for x in ('out','node_modules','.codex')):errors.append('Generated/private directory tracked: '+name)
 if p.suffix.lower() in ('.apk','.dex','.jar','.img','.qcow2','.pk8','.pem','.key','.jks','.keystore','.zip','.tgz'):errors.append('Forbidden generated/signing artifact: '+name)
 if p.name in ('auth.json','.env'):errors.append('Authentication artifact: '+name)
 if p.suffix=='.md':
  text=re.sub(r'```.*?```','',p.read_text(),flags=re.S)
  for target in re.findall(r'!?\[[^\]]*\]\(([^\s)]+)(?:\s+"[^"]*")?\)',text):
   target=target.strip('<>');url=urllib.parse.urlsplit(target)
   if url.scheme or target.startswith('#'):continue
   dest=(p.parent/urllib.parse.unquote(url.path)).resolve()
   try:dest.relative_to(ROOT)
   except ValueError:errors.append(f'Outside-repo link: {name} -> {target}');continue
   links+=1
   if not dest.exists():errors.append(f'Broken local link: {name} -> {target}')
  if 'docs/evidence/' in text:errors.append('Stale evidence location: '+name)
package=json.loads((ROOT/'package.json').read_text())
if package.get('private') is not True or package.get('license')!='Apache-2.0':errors.append('Unpublished package and Apache-2.0 metadata required')
for k in ('verify','check:inventory','check:structure','build:android'):
 if k not in package.get('scripts',{}):errors.append('Missing npm script: '+k)
ci=(ROOT/'.github/workflows/ci.yml').read_text()
for action in re.findall(r'uses:\s*(\S+)',ci):
 if not re.search(r'@[0-9a-f]{40}$',action):errors.append('Unpinned Action: '+action)
if 'contents: read' not in ci or 'timeout-minutes:' not in ci:errors.append('CI minimal permission/timeout missing')
if any((ROOT/'system/display/src/org/agentos/lab'/x).exists() for x in ('CodecListProbe.java','DecodeProbe.java','TextureDecodeProbe.java')):errors.append('Diagnostics mixed into display runtime')
for name in ['scripts/build-android.sh','scripts/aosp-test-keys.py','examples/scenarios/run-phone-generation.sh']:
 if '/Users/' in (ROOT/name).read_text():errors.append('Host-specific portable entry: '+name)
report={'ok':not errors,'localMarkdownLinks':links,'sourcePaths':len(paths),'errors':errors,'boundary':'Offline structure gate; not licensing certification, device test or ROM acceptance'}
out=ROOT/'out/checks/structure.json';out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(report,indent=2,ensure_ascii=False)+'\n')
print(json.dumps(report,indent=2,ensure_ascii=False));raise SystemExit(1 if errors else 0)
