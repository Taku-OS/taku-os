#!/usr/bin/env python3
"""Pinned Android WebSocket dependencies, separate from model preparation."""
import hashlib, pathlib, urllib.request, zipfile, shutil
root=pathlib.Path(__file__).resolve().parents[1]/'out'
items=[('com/squareup/okhttp3/okhttp/4.12.0/okhttp-4.12.0.jar','b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0'),
       ('com/squareup/okio/okio-jvm/3.6.0/okio-jvm-3.6.0.jar','67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412'),
       ('org/msgpack/msgpack-core/0.9.10/msgpack-core-0.9.10.jar','a9a3d6702661c24c08fd750c4ba81e9eb764b52188326bb0b66fc6419a282687')]
for artifact,digest in items:
 path=root/'network'/pathlib.Path(artifact).name;path.parent.mkdir(parents=True,exist_ok=True)
 old=root.parents[2]/'out/whisper-proxy'/path.name
 if not path.exists() and old.is_file():shutil.copyfile(old,path)
 if not path.exists():
  data=urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+artifact,timeout=40).read()
  if hashlib.sha256(data).hexdigest()!=digest:raise SystemExit('Dependency hash mismatch')
  path.write_bytes(data)
 if hashlib.sha256(path.read_bytes()).hexdigest()!=digest:raise SystemExit('Dependency hash mismatch: '+path.name)
 with zipfile.ZipFile(path) as jar:
  notices=[n for n in jar.namelist() if n.startswith('META-INF/') and ('LICENSE' in n.upper() or 'NOTICE' in n.upper())]
  for name in notices:
   dest=root/'assets/licenses/network'/path.stem/pathlib.Path(name).name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(jar.read(name))
print('Pinned OkHttp 4.12.0 / Okio 3.6.0 / MessagePack 0.9.10 verified.')
notices=root/'assets/licenses/network';notices.mkdir(parents=True,exist_ok=True)
shutil.copyfile(pathlib.Path(__file__).with_name('LICENSE-2.0.txt'),notices/'LICENSE-2.0.txt')
shutil.copyfile(pathlib.Path(__file__).with_name('NOTICE.txt'),notices/'NOTICE.txt')
