#!/usr/bin/env python3
"""Extract declarations from pinned upstream Git, never from working-tree edits.

Run in a read-only source mount. This is an inventory, not a permission bypass
or device test. No app data, account state, logs or proprietary blobs are read.
"""
import argparse, collections, gzip, hashlib, json, os, re, subprocess, zipfile
from pathlib import Path
import xml.etree.ElementTree as ET

def git(root, *args):
    return subprocess.check_output(['git', '-c', 'safe.directory=*', '-C', str(root), *args])

def collect(source, sdk=None):
    sources, records, projects, diagnostics = [], [], [], []
    def add(kind, owner, declaration, index, line, **extra):
        records.append(dict(kind=kind, owner=owner, declaration=declaration,
                            source=index, line=line, status='declaration_only', **extra))
    def ingest(path, content, revision, project, url, kind):
        idx = len(sources)
        sources.append(dict(path=path, project=project, revision=revision, url=url,
                            sha256=hashlib.sha256(content).hexdigest(), bytes=len(content), kind=kind))
        try: text = content.decode('utf-8', errors='strict')
        except UnicodeDecodeError:
            if kind != 'toybox-help': raise
            text=content.decode('latin-1'); sources[idx]['encoding']='latin-1'
        if not text.strip():
            sources[idx]['emptyFile']=True
            return idx
        if kind == 'managed' and text.lstrip().startswith('props {'):
            kind='sysprop'; sources[idx]['kind']=kind
        if kind == 'sysprop':
            add(kind,path,text,idx,1,declarationKind='property_declarations')
        elif kind == 'managed':
            surface = Path(path).name.replace('-current.txt', '').replace('current.txt', 'public')
            package, owner = '', ''
            for n, line in enumerate(text.splitlines(), 1):
                s = line.strip()
                if s.startswith('package '): package = s.split()[1]
                elif re.search(r'\b(class|interface|enum|@interface)\b', s) and s.endswith('{'):
                    m = re.search(r'\b(?:class|interface|enum|@interface)\s+([^\s<{]+)', s)
                    owner = package + '.' + m.group(1) if m else package
                    add(surface, owner, s[:-1].strip(), idx, n, declarationKind='type')
                elif s.startswith(('ctor ', 'method ', 'field ', 'enum_constant ')):
                    add(surface, owner, s, idx, n, declarationKind=s.split()[0])
                elif s and s not in ('}', '// Signature format: 2.0', '// Signature format: 4.0', '// Signature format: 5.0') and not s.startswith('//'):
                    diagnostics.append(dict(source=idx, line=n, text=s, reason='unclassified signature line'))
                    add(surface, owner or package, s, idx, n, declarationKind='unclassified')
        elif kind in ('binder', 'hal'):
            # Preserve the whole declaration file. AIDL contains parcelables,
            # annotations and imports as well as callable interface methods.
            add(kind, path, text, idx, 1, declarationKind='source_file')
        elif kind == 'permissions':
            root = ET.fromstring(text); ns = '{http://schemas.android.com/apk/res/android}'
            for node in root.findall('permission'):
                attrs = {k.replace(ns, ''):v for k,v in node.attrib.items()}
                add(kind, attrs['name'], json.dumps(attrs, ensure_ascii=False), idx, 1,
                    protectionLevel=attrs.get('protectionLevel', 'normal'))
        elif kind == 'native':
            block = None
            for n, line in enumerate(text.splitlines(), 1):
                s=line.strip()
                if s.endswith('{'): block=s[:-1].strip()
                elif s.startswith('global:'): block='global'
                elif s.startswith('local:'): block='local'
                elif block != 'local' and re.match(r'^[A-Za-z_][A-Za-z_0-9.*]*;\s*(?:#.*)?$', s):
                    add(kind, path, s, idx, n, declarationKind='export_symbol_or_pattern')
        elif kind == 'syscalls':
            for n,line in enumerate(text.splitlines(),1):
                if line.strip() and not line.lstrip().startswith('#'):
                    add(kind,path,line.strip(),idx,n,declarationKind='bionic_syscall_mapping')
        elif kind in ('kernel-uapi','native-header'):
            add(kind,path,text,idx,1,declarationKind='header_file')
        elif kind == 'toybox-help':
            # Keep the config help and NEWTOY option grammar, omit implementation.
            head=text.split('#define FOR_',1)[0]
            add(kind,path,head,idx,1,declarationKind='toybox_config_help_and_option_grammar')
        elif kind == 'shell-source':
            # Exact printHelp bodies rather than guessed CLI syntax.
            lines=text.splitlines(); blocks=[]
            for n,line in enumerate(lines):
                if re.search(r'\b(?:onHelp|printHelp)\s*\(',line) and re.search(r'\b(?:void|public)\b',line):
                    depth=0; started=False; body=[]
                    for l in lines[n:]:
                        body.append(l); depth += l.count('{')-l.count('}')
                        if '{' in l: started=True
                        if started and depth<=0: break
                    blocks.append((n+1,'\n'.join(body)))
            for n,body in blocks: add(kind,path,body,idx,n,declarationKind='help_implementation')
        return idx
    prefixes=('frameworks/','packages/modules/','system/','hardware/','libcore','art','bionic','external/icu','external/toybox')
    plist=(source/'.repo/project.list').read_text().splitlines()
    for project in sorted(p for p in plist if p.startswith(prefixes)):
        root=source/project
        revision=git(root,'rev-parse','HEAD').decode().strip()
        files=git(root,'ls-tree','-r','--name-only','HEAD').decode().splitlines()
        remotes=git(root,'remote').decode().splitlines()
        origin=git(root,'config','--get','remote.'+remotes[0]+'.url').decode().strip() if remotes else ''
        # Only recognized public hosts are emitted, without userinfo/query.
        url=None
        m=re.search(r'(?:https://|git@)github.com[:/]([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?$',origin)
        if m: url='https://github.com/'+m.group(1)+'/blob/'+revision+'/'
        elif origin.startswith('https://android.googlesource.com/') and '?' not in origin and '@' not in origin:
            url=origin.rstrip('/')+'/+/'+revision+'/'
        projects.append(dict(path=project,revision=revision))
        selected=[]
        for f in files:
            path=project+'/'+f; kind=None
            if re.search(r'/api/(?:[\w-]+-)?current\.txt$',path) and project!='hardware/interfaces':
                kind='sysprop' if '/sysprop/' in path else 'managed'
            elif project=='frameworks/base' and f=='core/res/AndroidManifest.xml': kind='permissions'
            elif f.endswith(('.aidl','.hal')) and not re.search(r'/(?:tests?|testdata|testsrc|samples?|benchmarks?)/',path):
                kind='hal' if project.startswith('hardware/') else 'binder'
            elif f.endswith('.map.txt') and not re.search(r'/(?:tests?|testdata)/',path): kind='native'
            elif project=='bionic' and f=='libc/SYSCALLS.TXT': kind='syscalls'
            elif project=='bionic' and f.startswith('libc/kernel/uapi/') and f.endswith('.h'): kind='kernel-uapi'
            elif f.endswith('.h') and (re.search(r'/(?:include/android|ndk/include)/',path) or
                    project=='bionic' and f.startswith(('libc/include/','libm/include/','libdl/include/'))): kind='native-header'
            elif f.endswith('ShellCommand.java') and not re.search(r'/(?:tests?|testdata)/',path): kind='shell-source'
            elif project=='external/toybox' and f.startswith('toys/') and f.endswith('.c'): kind='toybox-help'
            if kind: selected.append((f,kind))
        if not selected: continue
        # cat-file --batch reads exact HEAD blobs with one process per project.
        request=''.join('HEAD:'+f+'\n' for f,_ in selected).encode()
        raw=subprocess.check_output(['git','-c','safe.directory=*','-C',str(root),'cat-file','--batch'],input=request)
        pos=0
        for f,kind in selected:
            end=raw.index(b'\n',pos); header=raw[pos:end].decode().split(); size=int(header[2]); pos=end+1
            data=raw[pos:pos+size]; pos+=size+1
            ingest(project+'/'+f,data,revision,project,(url+f if url else None),kind)
    # Non-SDK flags are a generated build artifact; preserve descriptor and flags
    # and explicitly mark it as modified-candidate output, not pristine AOSP.
    p=source/'out/soong/hiddenapi/hiddenapi-flags.csv'
    if p.exists():
        content=p.read_bytes(); idx=len(sources)
        sources.append(dict(path=str(p.relative_to(source)),sha256=hashlib.sha256(content).hexdigest(),bytes=len(content),kind='non-sdk',provenance='candidate_build_generated'))
        for n,line in enumerate(content.decode().splitlines(),1):
            descriptor,*flags=line.split(',')
            if descriptor.startswith('L') and 'sdk' not in flags:
                owner=descriptor.split(';',1)[0][1:].replace('/','.')
                if not owner.startswith(('org.agentos.','org.taku.','build.taku.')):
                    add('non-sdk',owner,descriptor,idx,n,flags=flags,declarationKind='dex_descriptor')
    # These names prove files in a build output, not runtime help or successful calls.
    product=source/'out/target/product/tokay'
    for partition in ('system','system_ext','product','vendor','odm'):
        directory=product/partition/'bin'
        if directory.is_dir():
            for p in sorted(directory.iterdir()):
                if p.name.startswith(('taku','codex','phone-display','agentos','resolver-bridge')): continue
                records.append(dict(kind='image-binary',owner=partition+'/bin/'+p.name,declaration=p.name,source=None,line=0,status='artifact_present_not_executed',declarationKind='image_binary',symlink=os.readlink(p) if p.is_symlink() else None))
    supplement=None
    if sdk:
        archive=sdk/'data/annotations.zip'; content=archive.read_bytes(); idx=len(sources)
        sources.append(dict(path='android-36/data/annotations.zip',sha256=hashlib.sha256(content).hexdigest(),bytes=len(content),kind='sdk-annotations',provenance='installed_android_sdk_supplement',url='https://developer.android.com/reference'))
        supplement=dict(sourceProperties=(sdk/'source.properties').read_text(),sameUpstreamRevision=False)
        with zipfile.ZipFile(archive) as z:
            for name in sorted(z.namelist()):
                if not name.endswith('.xml'):continue
                for item in ET.fromstring(z.read(name)).findall('item'):
                    annotations=[]
                    for annotation in item.findall('annotation'):
                        if any(key in annotation.attrib.get('name','') for key in ('RequiresPermission','RequiresFeature','RequiresApi')):
                            annotations.append(dict(name=annotation.attrib['name'],values={v.attrib['name']:v.attrib['val'] for v in annotation.findall('val')}))
                    if annotations:
                        owner=item.attrib['name'].split()[0]
                        add('sdk-annotations',owner,json.dumps(dict(item=item.attrib['name'],annotations=annotations,archiveEntry=name),ensure_ascii=False),idx,1,declarationKind='sdk_external_annotation')
    return dict(schema='taku.raw-android-interfaces/1',baseline='LineageOS 23.2 / Android 16 / API 36, Pixel 9 tokay development source',sdkSupplement=supplement,
                collectedAt='2026-10-12',scope='Pinned upstream HEAD declarations; generated non-SDK and binary outputs are separate evidence classes.',
                projects=projects,sources=sources,records=records,diagnostics=diagnostics,
                counts=dict(collections.Counter(r['kind'] for r in records)),
                physicalDeviceVerified=False,apiCallVerified=False,
                exclusions=['Proprietary vendor implementation/private ABI','Java hidden declarations outside the candidate hiddenapi CSV','Runtime Binder registrations and help','Vendor/kernel ioctls not represented by Bionic UAPI headers','GMS/Google Play/AndroidX/third-party app APIs','HAL availability and sensor/camera characteristics on a physical device'])

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--source',type=Path,required=True); parser.add_argument('--output',type=Path,required=True); parser.add_argument('--sdk',type=Path); args=parser.parse_args()
    data=collect(args.source,args.sdk); args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open('wb') as f:
        with gzip.GzipFile(filename='',mode='wb',fileobj=f,mtime=0) as z: z.write(json.dumps(data,ensure_ascii=False,separators=(',',':')).encode())
    print(json.dumps(dict(files=len(data['sources']),records=len(data['records']),counts=data['counts'],unclassified=len(data['diagnostics']),compressedBytes=args.output.stat().st_size),ensure_ascii=False))
