#!/usr/bin/env python3
"""Pinned public AOSP DEVELOPMENT test material; never production identity."""
from pathlib import Path
import base64,hashlib,urllib.request,sys
REVISION='874fa586c48ae230ebfcff0df1b9c1b004fa8f7c'
SHA256={'platform.pk8':'1ad8ef556870edb70f69a9d3c112544c07de5162ba440d84d33f8bb0c5962875','platform.x509.pem':'9837de028f460c35cc8d3fa45f14eecce30f6fbfe4b93d399aef1acb80c20d14'}
if len(sys.argv)!=2:raise SystemExit('Usage: aosp-test-keys.py OUTPUT_DIRECTORY')
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
for name,expected in SHA256.items():
 target=out/name
 if target.exists():
  if hashlib.sha256(target.read_bytes()).hexdigest()!=expected:raise SystemExit('Unexpected public test signing material: '+name+'; refusing reuse or overwrite')
 else:
  url=f'https://android.googlesource.com/platform/build/+/{REVISION}/target/product/security/{name}?format=TEXT'
  data=base64.b64decode(urllib.request.urlopen(url,timeout=45).read(),validate=True)
  if hashlib.sha256(data).hexdigest()!=expected:raise SystemExit('Public test material integrity mismatch: '+name)
  tmp=target.with_suffix(target.suffix+'.tmp');tmp.write_bytes(data);tmp.chmod(0o600);tmp.replace(target)
print('Pinned public AOSP test signing material verified (development only).')
