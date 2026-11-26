#!/usr/bin/env python3
"""Snapshot source inputs and run the isolated, pinned Pixel 9 builder."""
import argparse,datetime,fcntl,hashlib,json,os,pathlib,shutil,subprocess,sys,runpy
import time
import urllib.error,urllib.request
from image_build_config import ROOT,CONFIG,SOURCE_LOCK,OUT,NAME,VOLUME,REFERENCE,PREVIOUS,IMAGE
def run(*args,**kw):return subprocess.run(args,check=True,**kw)
def digest(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(8*1024*1024),b''):h.update(chunk)
    return h.hexdigest()

# These describe one candidate, unlike the source/kernel checkpoints reused by
# an incremental build. Never let a new candidate inherit a previous proof.
PER_RUN_OUTPUTS = (
    'timing-baseline.json', 'kernel-timing.json',
    'baseline-receipt.json', 'updater-integration.json', 'updater-apk-verification.json',
    'product-branding-integration.json', 'product-branding-migration.json', 'resume-check.json', 'incremental-check.json',
    'ninja-fifo-guard.log', 'resource-adjustments.json', 'system-ai-rom.json', 'google-services-artifacts.json',
    'google-apps-artifacts.json', 'audio-rom.json',
    'carrier-defaults-source.json', 'carrier-defaults-apk.json',
    'carrier-defaults-apk-signature.txt', 'carrier-defaults-apk-badging.txt',
)
RUN_EVIDENCE = (
    'build.log', 'status.json', 'run-input.json', 'resolved-manifest.xml',
    'resolved-kernel-manifest.xml', 'kernel-artifacts.json', 'build-workarounds.json',
    *PER_RUN_OUTPUTS,
)
SNAPSHOT_ADDITIONAL_FILES = frozenset({
    'tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java',
})


def fsync_directory(path):
    fd=os.open(path,os.O_RDONLY)
    try:os.fsync(fd)
    finally:os.close(fd)


def archive_run_evidence(info,history,new_candidate=False):
    """Seal exact old bytes before retiring only the new candidate's outputs.

    Resume/verify keep their current proofs and checkpoints. A failed archive,
    corrupt existing seal, or concurrent source change prevents any deletion.
    Callers hold source-operation.lock and have checked the builder is stopped.
    """
    sources={name:OUT/name for name in RUN_EVIDENCE if (OUT/name).exists() or (OUT/name).is_symlink()}
    if any(path.is_symlink() or not path.is_file() for path in sources.values()):
        raise SystemExit('Run evidence must be ordinary files')
    files=[{'path':name,'bytes':path.stat().st_size,'sha256':digest(path)} for name,path in sorted(sources.items())]
    record={'schema':'taku.image-run-evidence/1','containerId':info['Id'],'files':files}
    history.parent.mkdir(parents=True,exist_ok=True)
    if history.exists():
        seal=history/'evidence-manifest.json'
        if history.is_symlink() or seal.is_symlink() or not seal.is_file() or json.loads(seal.read_text())!=record:
            raise SystemExit('Existing run evidence archive differs; preserve both attempts')
    else:
        history.mkdir()  # Exclusive: incomplete archives are never silently replaced.
        for item in files:
            with sources[item['path']].open('rb') as source,(history/item['path']).open('xb') as target:
                shutil.copyfileobj(source,target);target.flush();os.fsync(target.fileno())
        with (history/'evidence-manifest.json').open('x') as stream:
            json.dump(record,stream,indent=2);stream.write('\n');stream.flush();os.fsync(stream.fileno())
    # Verify the persisted archive and all live bytes before the first unlink.
    for item in files:
        saved,current=history/item['path'],sources[item['path']]
        if (saved.is_symlink() or not saved.is_file() or saved.stat().st_size!=item['bytes']
                or digest(saved)!=item['sha256'] or current.is_symlink() or not current.is_file()
                or current.stat().st_size!=item['bytes'] or digest(current)!=item['sha256']):
            raise SystemExit('Run evidence changed during archival; nothing may be retired')
    # A previous attempt may have created complete files but failed fsync. Reuse
    # must establish the same durability barrier before retiring any live proof.
    with (history/'evidence-manifest.json').open('rb') as seal:os.fsync(seal.fileno())
    fsync_directory(history);fsync_directory(history.parent)
    if new_candidate:
        for name in PER_RUN_OUTPUTS:
            if name in sources:(OUT/name).unlink()
        fsync_directory(OUT)
    return history
def ensure_report_server():
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{CONFIG['reportPort']}/pixel9-image-build-state.json",timeout=2) as r:
            data=json.load(r)
        if data.get('target')!='Pixel 9 / tokay' or data.get('platform')!=SOURCE_LOCK['platform']:
            raise SystemExit('Report port is serving a different image baseline')
        return
    except urllib.error.URLError:pass
    with (OUT/'publication.log').open('a') as log:
        subprocess.Popen([sys.executable,str(ROOT/'scripts/serve-image-status.py')],stdout=log,stderr=log,start_new_session=True)
def snapshot():
    subprocess.run(['node',str(ROOT/'system/agent-app/scripts/export-reply-contract.ts')],check=True,stdout=subprocess.DEVNULL)
    runpy.run_path(str(ROOT/'image/agent_ui.py'))['validate_inputs'](ROOT)
    asset_lock=json.loads((ROOT/'image/runtime-assets.lock.json').read_text())
    asset_lock['assets']+=json.loads((ROOT/'system/assistant/markdown-dependencies.json').read_text())['assets']
    reply_contract='system/assistant/out/reply-assets/native/reply-contract.json'
    asset_lock['assets'].append({'path':reply_contract,'sha256':digest(ROOT/reply_contract)})
    ime=json.loads((ROOT/'system/inputmethod/rom-input-lock.json').read_text())
    for name,expected in ime['sourceInputs'].items():
        if digest(ROOT/name)!=expected:raise SystemExit('ROM IME release source differs: '+name)
    # Generated APKs are ignored by Git, so explicitly seal this pinned release.
    asset_lock['assets'].append({'path':ime['path'],'sha256':ime['sha256']})
    asset_lock['assets'] += [{'path':i['path'],'sha256':i['sha256']} for i in ime.get('jni',[])]
    audio=json.loads((ROOT/'system/audio-engine/rom-input-lock.json').read_text())
    runpy.run_path(str(ROOT/'system/audio-engine/prepare-rom.py'))['validate_inputs'](ROOT,audio)
    asset_lock['assets'] += audio['assets']
    for item in asset_lock['assets']:
        p=ROOT/item['path']
        if not p.is_file() or digest(p)!=item['sha256']:raise SystemExit('Runtime asset missing/hash mismatch: '+item['path'])
    listed=subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard','-z'],cwd=ROOT).decode().split('\0')
    files=[]
    for name in listed:
        p=pathlib.PurePosixPath(name)
        if not name:continue
        # Proprietary extras are opt-in through their reviewed internal profile.
        # A broad fresh snapshot must not silently turn another build internal.
        if name in GOOGLE_APPS_ADDITIONS:continue
        if name=='Android.bp' or name in SNAPSHOT_ADDITIONAL_FILES or p.parts[0] in ('system','adapters','core','spec','image'):
            if (ROOT/name).is_file() and not (ROOT/name).is_symlink():files.append(name)
    files+=list(audio['sourceInputs'])
    files+= [x['path'] for x in asset_lock['assets']]
    records=[{'path':name,'sha256':digest(ROOT/name)} for name in sorted(set(files))]
    key=hashlib.sha256(json.dumps(records,sort_keys=True).encode()).hexdigest()
    dest=OUT/'snapshots'/key
    if not dest.exists():
        dest.mkdir(parents=True)
        for record in records:
            target=dest/record['path'];target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(ROOT/record['path'],target)
        manifest={'digest':key,'files':records}
        if 'image/branding/manifest.json' in {record['path'] for record in records}:manifest['integrationProfile']='taku-branding-v1'
        elif 'image/updater/manifest.json' in {record['path'] for record in records}:manifest['integrationProfile']='updater-canary'
        (dest/'input-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    return dest,key
UPDATER_OVERLAYS = (
    'image/build.sh', 'image/taku.mk', 'image/verify-artifacts.py',
    'image/apply-updater-patches.py', 'image/updater/manifest.json',
    'image/updater/download-integrity.patch',
)
UPDATER_ADDITIONS = frozenset(UPDATER_OVERLAYS[3:])
BRANDING_OVERLAYS = (
    'image/build.sh', 'image/taku.mk', 'image/verify-artifacts.py', 'image/apply-updater-patches.py',
    'image/branding/updater-v10.patch', 'image/branding/previous-v9-manifest.json',
    'image/apply-product-branding.py', 'image/product_branding.py', 'image/product-branding.json',
    'image/product-branding.mk', 'image/branding/manifest.json',
    'image/branding/updater-v9.patch', 'image/branding/previous-v8-manifest.json',
    'image/branding/updater-v8.patch', 'image/branding/previous-v7-manifest.json',
    'image/branding/updater-v7.patch', 'image/branding/previous-v6-manifest.json',
    'image/branding/updater-v6.patch', 'image/branding/updater-v5.patch', 'image/branding/previous-v5-manifest.json',
    'image/branding/updater-v4.patch', 'image/branding/previous-v4-manifest.json', 'image/branding/settings.patch',
    'image/branding-properties.json',
    'image/branding/legacy-v3-manifest.json', 'image/branding/updater-v3.patch',
)
BRANDING_ADDITIONS = frozenset(BRANDING_OVERLAYS[4:])
BRANDING_VERSION_OVERLAYS = (
    'image/branding/updater-v10.patch', 'image/branding/previous-v9-manifest.json',
    'image/apply-product-branding.py', 'image/apply-updater-patches.py', 'image/product_branding.py',
    'image/product-branding.json', 'image/product-branding.mk',
    'image/branding/manifest.json', 'image/branding/updater-v9.patch', 'image/branding/previous-v8-manifest.json',
    'image/branding/updater-v8.patch', 'image/branding/previous-v7-manifest.json',
    'image/branding/updater-v7.patch', 'image/branding/previous-v6-manifest.json',
    'image/branding/updater-v6.patch',
    'image/branding/updater-v5.patch', 'image/branding/previous-v5-manifest.json',
    'image/branding/updater-v4.patch', 'image/branding/previous-v4-manifest.json',
    'image/branding/legacy-v3-manifest.json', 'image/verify-artifacts.py',
)
GOOGLE_ADDITIONS = (
    'image/google_services.py', 'image/google-services.lock.json', 'image/google-services.mk',
    'image/google-services/overlay/Android.bp',
    'image/google-services/overlay/AndroidManifest.xml',
    'image/google-services/overlay/res/values/config.xml',
)
GOOGLE_OVERLAYS = ('image/build.sh', 'image/taku.mk', 'image/verify-artifacts.py', *GOOGLE_ADDITIONS)
GOOGLE_APPS_PARENT = 'a7421afe24a7c01bfce6595c8464fbce8e3239a4d8a76b8f0d6b6627ebb3a2e1'
GOOGLE_APPS_PARENT_MANIFEST = 'fb35c1f2766d3f0d713a4f8279814fe02d3696eeff7a966d8743bd71160a9724'
GOOGLE_APPS_ADDITIONS = ('image/google_apps.py', 'image/google-apps.lock.json', 'image/google-apps.mk')
GOOGLE_APPS_OVERLAYS = ('image/build.sh', 'image/taku.mk', 'image/verify-artifacts.py', *GOOGLE_APPS_ADDITIONS)


BRANDING_PROPERTIES_PARENT = 'a540a0f061143c9ea3e6d3351cd0698389794e8277b5ea3ef19a31d7f9e87fad'
BRANDING_PROPERTIES_MANIFEST = '01c08e4644944a8f2dcf4898b1dea2a7648dedb8257a5d9df9dad34694c06150'
BRANDING_PROPERTIES_OVERLAYS = ('image/product_branding.py','image/product-branding.mk','image/verify-artifacts.py')
MEMORY_PERMISSION_PARENT = '37237146f50fc109b35acce98e3816cf26faad2da6b1839569d2c962dca15f16'
MEMORY_PERMISSION_MANIFEST = '407fdcd793b55c40d1d3fd5bdb1dc2307cc9d1d70a7f875222dce50cd095ed4c'
MEMORY_PERMISSION_OVERLAYS = ('system/memory/Android.bp', 'system/memory/org.agentos.memory.xml',
                              'image/privapp_permissions.py', 'image/verify-system-ai.py')
MEMORY_PERMISSION_ADDITIONS = frozenset(MEMORY_PERMISSION_OVERLAYS[1:3])


def _derive_snapshot(base,overlays,additions=(),profile=None,content_overrides=None,pending_carrier_repair=False):
    if len(base)!=64 or any(c not in '0123456789abcdef' for c in base):raise SystemExit('Expected a snapshot SHA-256')
    previous=OUT/'snapshots'/base
    manifest=json.loads((previous/'input-manifest.json').read_text())
    if manifest['digest']!=base or hashlib.sha256(json.dumps(manifest['files'],sort_keys=True).encode()).hexdigest()!=base:
        raise SystemExit('Base snapshot manifest digest differs')
    if not overlays or len(set(overlays))!=len(overlays):raise SystemExit('Expected distinct explicit snapshot overlays')
    content_overrides=content_overrides or {}
    if set(content_overrides)-set(overlays):raise SystemExit('Derived content must be an explicit overlay')
    records={item['path']:dict(item) for item in manifest['files']}
    sources={name:previous/name for name in records}
    for name,item in records.items():
        p=sources[name]
        if p.is_symlink() or digest(p)!=item['sha256']:raise SystemExit('Base snapshot input differs: '+name)
    for name in overlays:
        p=ROOT/name
        if (name not in records and name not in additions) or p.is_symlink() or not p.is_file() or not p.resolve().is_relative_to(ROOT.resolve()):raise SystemExit('Invalid repair overlay: '+name)
        source=content_overrides.get(name,p)
        sources[name]=source;records[name]={'path':name,'sha256':hashlib.sha256(source).hexdigest() if isinstance(source,bytes) else digest(source)}
    entries=[records[name] for name in sorted(records)]
    key=hashlib.sha256(json.dumps(entries,sort_keys=True).encode()).hexdigest();dest=OUT/'snapshots'/key
    if not dest.exists():
        dest.mkdir(parents=True)
        for item in entries:
            target=dest/item['path'];target.parent.mkdir(parents=True,exist_ok=True)
            source=sources[item['path']]
            if isinstance(source,bytes):target.write_bytes(source)
            else:shutil.copy2(source,target)
            if digest(target)!=item['sha256']:raise SystemExit('Repair input changed during copy: '+item['path'])
        metadata={'digest':key,'files':entries,'parentSnapshotDigest':base,'overlayPaths':sorted(set(overlays))}
        if profile:metadata.update({'integrationProfile':profile,'addedPaths':sorted(name for name in overlays if name not in {r['path'] for r in manifest['files']}),
                                    'parentDerivedPaths':sorted(content_overrides)})
        if manifest.get('runtimeIntegration'):metadata['runtimeIntegration']=manifest['runtimeIntegration']
        if pending_carrier_repair:metadata['pendingCarrierRepair']=True
        (dest/'input-manifest.json').write_text(json.dumps(metadata,indent=2)+'\n')
    else:
        frozen=json.loads((dest/'input-manifest.json').read_text())
        if frozen.get('digest')!=key or frozen.get('files')!=entries:
            raise SystemExit('Existing derived snapshot manifest differs')
        if profile and frozen.get('integrationProfile')!=profile:
            raise SystemExit('Existing derived snapshot profile differs')
        if profile and (frozen.get('parentSnapshotDigest')!=base or frozen.get('overlayPaths')!=sorted(set(overlays))
                        or frozen.get('parentDerivedPaths')!=sorted(content_overrides)):
            raise SystemExit('Existing derived snapshot provenance differs')
        if frozen.get('runtimeIntegration')!=manifest.get('runtimeIntegration'):
            raise SystemExit('Existing derived runtime provenance differs')
        if bool(frozen.get('pendingCarrierRepair'))!=pending_carrier_repair:
            raise SystemExit('Existing carrier repair readiness differs')
        for item in entries:
            target=dest/item['path']
            if target.is_symlink() or digest(target)!=item['sha256']:
                raise SystemExit('Existing derived snapshot input differs: '+item['path'])
    return dest,key


def repair_snapshot(base,overlays):
    """Derive immutable build-controller repairs without importing unrelated WIP."""
    allowed={'Android.bp','image/build.sh','image/resume-check.py','image/check-incremental.py','image/verify-artifacts.py','image/ninja-fifo-guard.py'}
    if not overlays or any(name not in allowed for name in overlays):raise SystemExit('Only explicit build-controller repair overlays are supported')
    return _derive_snapshot(base,overlays)


def integration_snapshot(base,profile):
    """One reviewed feature overlay; never snapshot the caller's entire dirty tree."""
    if profile=='taku-telecom-volte-v1':
        import runpy
        hooks=runpy.run_path(str(ROOT/'image/carrier_snapshot.py'))
        if base!=hooks['PARENT']:raise SystemExit('Carrier integration requires the exact archived 1.3.0 candidate')
        parent=OUT/'snapshots'/base
        if digest(parent/'input-manifest.json')!=hooks['PARENT_MANIFEST']:
            raise SystemExit('Carrier parent manifest differs')
        frozen=json.loads((parent/'input-manifest.json').read_text())
        runtime=frozen.get('runtimeIntegration',{})
        if (frozen.get('integrationProfile')!='taku-branding-v1'
                or runtime.get('sourceRepair',{}).get('versions')!=hooks['VERSIONS']):
            raise SystemExit('Carrier parent runtime identity differs')
        runpy.run_path(str(parent/'image/source_repair.py'))['validate'](
            (parent/'image/source-repair.json').read_bytes(),runtime['sourceRepair'],
            {i['path']:i['sha256'] for i in frozen['files']})
        runpy.run_path(str(ROOT/'image/carrier_defaults.py'))['load_contract']()
        brand=runpy.run_path(str(ROOT/'image/apply-product-branding.py'))
        config,_,_=brand['load_contract']()
        if config['productVersion']!='1.3.1' or config['updaterVersionCode']!=9:
            raise SystemExit('Carrier candidate must use the reviewed 1.3.1/Updater9 version')
        content={'image/build.sh':hooks['build_hook']((parent/'image/build.sh').read_bytes()),
                 'image/verify-artifacts.py':hooks['artifact_hook']((parent/'image/verify-artifacts.py').read_bytes())}
        additions=(*hooks['ADDITIONS'],'image/branding/updater-v9.patch','image/branding/previous-v8-manifest.json')
        return _derive_snapshot(base,hooks['OVERLAYS'],additions,profile='taku-branding-v1',
                                content_overrides=content,pending_carrier_repair=True)
    if profile=='taku-google-apps-internal-v1':
        import runpy
        parent=OUT/'snapshots'/base
        if base!=GOOGLE_APPS_PARENT or digest(parent/'input-manifest.json')!=GOOGLE_APPS_PARENT_MANIFEST:
            raise SystemExit('Internal apps require the exact reviewed Google/branding mother layer')
        frozen=json.loads((parent/'input-manifest.json').read_text())
        if frozen.get('integrationProfile')!='taku-branding-v1' or any(
                i['path'] in GOOGLE_APPS_ADDITIONS for i in frozen['files']):
            raise SystemExit('Internal apps are already integrated or parent profile differs')
        for name in ('image/google-services.lock.json','image/google_services.py','image/source-lock.json',
                     'image/kernel-source-lock.json','image/product-branding.json'):
            if (parent/name).read_bytes()!=(ROOT/name).read_bytes():
                raise SystemExit('Internal apps cannot change the inherited core/platform: '+name)
        runpy.run_path(str(ROOT/'image/google_apps.py'))['load_lock']()
        product=(parent/'image/taku.mk').read_bytes()
        line=b'$(call inherit-product, vendor/taku/image/google-apps.mk)\n'
        if line in product:raise SystemExit('Duplicate internal app product integration')
        product+=b'\n'+line
        build=(parent/'image/build.sh').read_bytes()
        substitutions=[
            (b'    python3 /taku/system/inputmethod/prepare-rom.py --verify-only\n',
             b'    if [ -f /taku/image/google-apps.lock.json ]; then\n        python3 /taku/image/google_apps.py --source /src/vendor/taku-google-apps\n    fi\n    python3 /taku/system/inputmethod/prepare-rom.py --verify-only\n'),
            (b'if [[ "${1:-}" = "--incremental" || "${1:-}" = "--updater-only" ]]; then',
             b'if [[ "${1:-}" = "--incremental" || "${1:-}" = "--updater-only" || "${1:-}" = "--google-apps-only" ]]; then'),
            (b'p.write_text(s)\nPY\n',b'if p.read_text()!=s:p.write_text(s)\nPY\n'),
            (b'm -j"$jobs" target-files-package bacon\n',
             b'if [[ "${1:-}" = "--google-apps-only" ]]; then\n    m -j"$jobs" $(python3 /taku/image/google_apps.py --modules)\n    python3 /taku/image/google_apps.py --preview-product out/target/product/tokay --results /results\n    phase google-apps-preview-verified\n    exit 0\nfi\nm -j"$jobs" target-files-package bacon\n'),
        ]
        for before,after in substitutions:
            if build.count(before)!=1:raise SystemExit('Frozen internal app build anchor differs')
            build=build.replace(before,after)
        verifier=(parent/'image/verify-artifacts.py').read_bytes()
        substitutions=[
            (b'google_services_integration=None\n',b'google_services_integration=None\ngoogle_apps_integration=None\n'),
            (b"    prohibited=['NexusLauncher','Gemini']\n",
             b"    import google_apps\n    google_apps_integration=google_apps.verify_artifacts(archive,results,run_input,pathlib.Path.cwd()/'out/host/linux-x86/bin/aapt2')\n    prohibited=['NexusLauncher','Gemini']\n"),
            (b"receipt_path=results/'baseline-receipt.json'\n",b"receipt['googleAppsIntegration']=google_apps_integration\nreceipt_path=results/'baseline-receipt.json'\n"),
        ]
        for before,after in substitutions:
            if verifier.count(before)!=1:raise SystemExit('Frozen internal app artifact anchor differs')
            verifier=verifier.replace(before,after)
        return _derive_snapshot(base,GOOGLE_APPS_OVERLAYS,GOOGLE_APPS_ADDITIONS,profile='taku-branding-v1',
            content_overrides={'image/taku.mk':product,'image/build.sh':build,'image/verify-artifacts.py':verifier})
    if profile=='taku-google-services-v1':
        import runpy
        parent=OUT/'snapshots'/base
        frozen=json.loads((parent/'input-manifest.json').read_text())
        if (frozen.get('integrationProfile')!='taku-branding-v1'
                or not frozen.get('runtimeIntegration',{}).get('readyForBuild')
                or frozen.get('runtimeIntegration',{}).get('sourceRepair')):
            raise SystemExit('Google integration requires a clean frozen branded runtime before source repair')
        if any(i['path'] in GOOGLE_ADDITIONS for i in frozen['files']):
            raise SystemExit('Google services already integrated; do not replace its frozen dependency')
        gate=runpy.run_path(str(ROOT/'image/google_services.py'))
        gate['load_lock'](ROOT/'image/google-services.lock.json')
        product=(parent/'image/taku.mk').read_bytes()
        line=b'$(call inherit-product, vendor/taku/image/google-services.mk)\n'
        if line in product:raise SystemExit('Unexpected Google product inheritance')
        product+=b'\n'+line
        build=(parent/'image/build.sh').read_bytes()
        before=b'    phase integrate\n'
        after=before+b'    python3 /taku/image/google_services.py --source /src/vendor/gapps\n'
        if build.count(before)!=1:raise SystemExit('Frozen integration build entry differs')
        verifier=(parent/'image/verify-artifacts.py').read_bytes()
        if b"receipt['systemAiIntegration']=system_ai_integration" not in verifier:
            raise SystemExit('Google integration requires the accepted runtime artifact gate')
        substitutions=[
            (b'branding_boot_properties=None\n',b'branding_boot_properties=None\ngoogle_services_integration=None\n'),
            (b"    prohibited=['NexusLauncher','Gemini','Velvet']\n",
             b"    import google_services\n    google_services_integration=google_services.verify_artifacts(archive,results,run_input,pathlib.Path.cwd()/'out/host/linux-x86/bin/aapt2')\n    prohibited=['NexusLauncher','Gemini']\n"),
            (b"receipt_path=results/'baseline-receipt.json'\n",
             b"receipt['googleServicesIntegration']=google_services_integration\nreceipt_path=results/'baseline-receipt.json'\n"),
        ]
        for before_verify,after_verify in substitutions:
            if verifier.count(before_verify)!=1:raise SystemExit('Frozen Google artifact hook differs')
            verifier=verifier.replace(before_verify,after_verify)
        return _derive_snapshot(base,GOOGLE_OVERLAYS,GOOGLE_ADDITIONS,profile='taku-branding-v1',
            content_overrides={'image/build.sh':build.replace(before,after),
                               'image/taku.mk':product,'image/verify-artifacts.py':verifier})
    if profile=='taku-branding-version-v2':
        # Preserve the accepted runtime and its stronger artifact gate. The
        # developer checkout's generic verifier must not replace that closure.
        import runpy
        helper=runpy.run_path(str(ROOT/'image/apply-product-branding.py'),run_name='branding_version_contract')
        config,_,_=helper['load_contract']()
        if len(base)!=64 or any(c not in '0123456789abcdef' for c in base):raise SystemExit('Expected a snapshot SHA-256')
        parent=OUT/'snapshots'/base
        frozen=json.loads((parent/'input-manifest.json').read_text())
        if frozen.get('integrationProfile')!='taku-branding-v1' or not frozen.get('runtimeIntegration',{}).get('readyForBuild'):
            raise SystemExit('Version update requires a frozen branded runtime')
        parent_contract=digest(parent/'image/branding/manifest.json')
        if parent_contract not in (helper['LEGACY_MANIFEST_SHA256'],helper['PREVIOUS_MANIFEST_SHA256'],
                                   helper['PREVIOUS_V5_MANIFEST_SHA256'], helper['PREVIOUS_V6_MANIFEST_SHA256'], helper['PREVIOUS_V7_MANIFEST_SHA256'], helper['PREVIOUS_V8_MANIFEST_SHA256']):
            raise SystemExit('Version update requires an exact accepted predecessor branding contract')
        old_path=parent/'image/product-branding.json'
        old=json.loads(old_path.read_text())
        old_identity={**old,'configSha256':digest(old_path),
                      'displayVersion':str(old.get('productName'))+str(old.get('productVersion'))}
        helper['BRAND'].validate_branding(old_identity)
        legacy=json.loads((parent/'image/branding/manifest.json').read_text())
        if legacy['brandingSha256']!=old_identity['configSha256']:
            raise SystemExit('Legacy product configuration differs')
        if helper['BRAND'].version_parts(config['productVersion'])<=helper['BRAND'].version_parts(old['productVersion'],allow_legacy=True):
            raise SystemExit('Product version must increase')
        for name in ('image/updater/manifest.json','image/branding/updater-v3.patch',
                     'image/branding/settings.patch','image/branding-properties.json'):
            if (parent/name).read_bytes()!=(ROOT/name).read_bytes():
                raise SystemExit('Version update cannot replace an inherited contract: '+name)
        before=b"'versionCode':3 if branded else 2"
        after=b"'versionCode':config['updaterVersionCode'] if branded else 2"
        verifier=(parent/'image/verify-artifacts.py').read_bytes()
        if (verifier.count(before)!=1 and verifier.count(after)!=1) or b"receipt['systemAiIntegration']=system_ai_integration" not in verifier:
            raise SystemExit('Frozen runtime artifact verifier differs')
        inherited={item['path'] for item in frozen['files']}
        return _derive_snapshot(base,BRANDING_VERSION_OVERLAYS,
            set(BRANDING_VERSION_OVERLAYS)-inherited,
            profile='taku-branding-v1',content_overrides={'image/verify-artifacts.py':verifier.replace(before,after)})
    if profile=='taku-memory-privapp-v1':
        if base!=MEMORY_PERMISSION_PARENT:raise SystemExit('Memory permission repair requires exact reviewed H parent')
        parent=OUT/'snapshots'/base
        manifest_path=parent/'input-manifest.json'
        if manifest_path.is_symlink() or digest(manifest_path)!=MEMORY_PERMISSION_MANIFEST:
            raise SystemExit('Memory permission repair parent manifest differs')
        frozen=json.loads(manifest_path.read_text())
        if frozen.get('integrationProfile')!='taku-branding-v1' or not frozen.get('runtimeIntegration',{}).get('readyForBuild'):
            raise SystemExit('Memory permission repair requires frozen branded runtime')
        old=(parent/'system/memory/Android.bp').read_bytes()
        anchor=b'    privileged: true,\n'
        if old.count(anchor)!=1 or b'privapp_allowlist' in old:
            raise SystemExit('Frozen Memory permission module anchor differs')
        repaired=old.replace(anchor,anchor+b'    privapp_allowlist: "org.agentos.memory.xml",\n')
        if (ROOT/'system/memory/Android.bp').read_bytes()!=repaired:
            raise SystemExit('Memory module repair includes unrelated changes')
        if digest(ROOT/'system/memory/org.agentos.memory.xml')!='d95554c2ddf45f91ea2431edd100c232d774ac330b35fc6f4331e5d7deb2e3c0':
            raise SystemExit('Reviewed Memory DUMP permission policy differs')
        return _derive_snapshot(base,MEMORY_PERMISSION_OVERLAYS,MEMORY_PERMISSION_ADDITIONS,
                                profile='taku-branding-v1',content_overrides={'system/memory/Android.bp':repaired})
    if profile=='taku-branding-system-ext-v1':
        # This repairs one failed, reviewed runtime candidate. Neither arbitrary
        # product overlays nor fresh working-tree runtime inputs are accepted.
        if base!=BRANDING_PROPERTIES_PARENT:raise SystemExit('Property repair requires the exact reviewed runtime parent')
        parent=OUT/'snapshots'/base
        manifest_path=parent/'input-manifest.json'
        if manifest_path.is_symlink() or digest(manifest_path)!=BRANDING_PROPERTIES_MANIFEST:
            raise SystemExit('Property repair parent manifest differs')
        frozen=json.loads(manifest_path.read_text())
        if frozen.get('integrationProfile')!='taku-branding-v1' or not frozen.get('runtimeIntegration',{}).get('readyForBuild'):
            raise SystemExit('Property repair requires the frozen branding/runtime integration')
        changes={
            'image/product_branding.py':(b'PRODUCT_SYSTEM_PROPERTIES += ',b'PRODUCT_SYSTEM_EXT_PROPERTIES += ',1),
            'image/product-branding.mk':(b'PRODUCT_SYSTEM_PROPERTIES += ',b'PRODUCT_SYSTEM_EXT_PROPERTIES += ',4),
            'image/verify-artifacts.py':(b'if any(system.get(key)!=value for key,value in expected_brand.items()):',
                b"if 'SYSTEM_EXT/etc/build.prop' not in names or any(properties('SYSTEM_EXT/etc/build.prop').get(key)!=value for key,value in expected_brand.items()):",1),
        }
        content={}
        for name,(before,after,count) in changes.items():
            data=(parent/name).read_bytes()
            if data.count(before)!=count:raise SystemExit('Property repair frozen anchor differs: '+name)
            content[name]=data.replace(before,after)
            if name!='image/verify-artifacts.py' and (ROOT/name).read_bytes()!=content[name]:
                raise SystemExit('Property repair working generator differs: '+name)
        return _derive_snapshot(base,BRANDING_PROPERTIES_OVERLAYS,profile='taku-branding-v1',content_overrides=content)
    if profile=='taku-branding-v1':
        import runpy
        helper=runpy.run_path(str(ROOT/'image/apply-product-branding.py'),run_name='branding_snapshot_contract')
        helper['load_contract']()
        policy=ROOT/'image/branding-properties.json'
        if policy.is_symlink() or json.loads(policy.read_text())!=helper['BRAND'].BOOT_PROPERTY_POLICY:
            raise SystemExit('Branding boot-property policy differs')
        if len(base)!=64 or any(c not in '0123456789abcdef' for c in base):raise SystemExit('Expected a snapshot SHA-256')
        parent=OUT/'snapshots'/base
        manifest=json.loads((parent/'input-manifest.json').read_text())
        if manifest.get('integrationProfile')!='updater-canary':raise SystemExit('Branding requires a frozen Updater canary parent')
        if (parent/'image/updater/manifest.json').read_bytes()!=(ROOT/'image/updater/manifest.json').read_bytes():
            raise SystemExit('Branding cannot replace the accepted download-integrity contract')
        product=(parent/'image/taku.mk').read_bytes()
        marker=b'$(call inherit-product, vendor/taku/image/product-branding.mk)\n'
        uri=('PRODUCT_SYSTEM_EXT_PROPERTIES += lineage.updater.uri='+helper['BASE']['URI']+'\n').encode()
        if product.count(uri)!=1 or b'product-branding.mk' in product:raise SystemExit('Unexpected parent product integration')
        if (ROOT/'image/taku.mk').read_bytes().count(marker)!=1:raise SystemExit('Missing exact working branding integration')
        product=marker+b'\n'+product
        # Inherit the accepted canary's runtime, including its build entrypoint.
        # A newer developer build.sh can require IME/AI inputs absent from E.
        build=(parent/'image/build.sh').read_bytes()
        updater=b'python3 /taku/image/apply-updater-patches.py --source-root /src --results-dir /results'
        finish=b'm -j"$jobs" target-files-package bacon\nset -u\n\nverify_artifacts\n'
        if build.count(updater)!=2 or build.count(finish)!=1:
            raise SystemExit('Unexpected parent branding build hooks')
        build=build.replace(updater,b'python3 /taku/image/apply-product-branding.py --source-root /src --results-dir /results')
        build=build.replace(finish,finish.replace(b'\nverify_artifacts\n',
            b'\npython3 /taku/image/product_branding.py --stage-full-ota out/target/product/tokay --build-number "$BUILD_NUMBER"\n\nverify_artifacts\n'))
        return _derive_snapshot(base,BRANDING_OVERLAYS,BRANDING_ADDITIONS,profile,
                                {'image/taku.mk':product,'image/build.sh':build})
    if profile!='updater-canary':raise SystemExit('Unknown image integration profile')
    import runpy
    helper=runpy.run_path(str(ROOT/'image/apply-updater-patches.py'),run_name='updater_snapshot_contract')
    contract,_=helper['load_contract'](ROOT/'image/updater/manifest.json')
    # Product integration has unrelated work in the developer's checkout. E
    # inherits D's exact module set; only the reviewed URI is inserted here.
    if len(base)!=64 or any(c not in '0123456789abcdef' for c in base):raise SystemExit('Expected a snapshot SHA-256')
    product=(OUT/'snapshots'/base/'image/taku.mk').read_bytes()
    uri_line=('PRODUCT_SYSTEM_EXT_PROPERTIES += lineage.updater.uri='+contract['uri']+'\n').encode()
    current_product=(ROOT/'image/taku.mk').read_bytes()
    if current_product.count(uri_line)!=1:raise SystemExit('Working product does not contain the exact Updater URI')
    if b'lineage.updater.uri' in product:
        if product.count(uri_line)!=1:raise SystemExit('Parent product already has a different Updater URI')
    else:
        anchor=b'PRODUCT_SYSTEM_EXT_PROPERTIES += ro.taku.image.channel=development\n'
        if product.count(anchor)!=1:raise SystemExit('Parent product has no unique development-property anchor')
        product=product.replace(anchor,anchor+uri_line)
    return _derive_snapshot(base,UPDATER_OVERLAYS,UPDATER_ADDITIONS,profile,{'image/taku.mk':product})
def state():
    p=OUT/'status.json'
    if p.exists():print(p.read_text())
    else:print('No status recorded yet.')
    r=subprocess.run(['docker','inspect','--format','{{json .State}}',NAME],capture_output=True,text=True)
    if r.returncode==0:print(r.stdout.strip())
def verify_signatures(inputs):
    """Validate the completed candidate in Linux with its source volume read-only."""
    validator=ROOT/'image/verify-signatures.py'
    validator_hash=digest(validator)
    frozen=OUT/'verification-inputs'/validator_hash/'verify-signatures.py'
    frozen.parent.mkdir(parents=True,exist_ok=True)
    if not frozen.exists():shutil.copy2(validator,frozen)
    if digest(frozen)!=validator_hash:raise SystemExit('Signature verifier snapshot differs')
    candidate=inputs['buildNumber']+'-'+inputs['snapshotDigest'][:16]
    evidence=OUT/'crypto'/candidate
    evidence.mkdir(parents=True,exist_ok=True)
    with (OUT/'signature-publication.log').open('a') as log:
        run('docker','run','--rm','--platform','linux/amd64','--network=none',
            '--cpus=4','--memory=8g','--memory-swap=10g',
            '--mount',f"type=volume,source={inputs['volume']},target=/src,readonly",
            '--mount',f"type=volume,source={inputs['referenceVolume']},target=/reference,readonly",
            '--mount',f"type=volume,source={inputs['previousSourceVolume']},target=/previous,readonly",
            '--mount',f'type=bind,source={OUT},target=/results',
            '--mount',f'type=bind,source={frozen.parent},target=/verification,readonly',
            '--entrypoint','python3',inputs['builderImage'],'/verification/verify-signatures.py',
            '--source-root','/src','--results-dir','/results',
            '--verification-dir','/results/crypto/'+candidate,stdout=log,stderr=subprocess.STDOUT)
    report=evidence/'signature-verification.json'
    proof=json.loads(report.read_text())
    if (proof.get('schema')!='taku.image-signatures/1'
            or proof.get('baselineReceiptSha256')!=digest(OUT/'baseline-receipt.json')
            or proof.get('runInputSha256')!=digest(OUT/'run-input.json')
            or not all(proof.get('checks',{}).get(name) is True for name in
                       ['otaPackageSignatureVerified','payloadSignatureVerified','avbSignatureVerified'])):
        raise SystemExit('Signature verification does not prove this candidate')
    return evidence,frozen
def validate_carrier_candidate(snap):
    """Accept a rebound derivative or an exact clean full-workspace snapshot."""
    import runpy
    manifest=json.loads((snap/'input-manifest.json').read_text())
    if manifest.get('pendingCarrierRepair'):
        raise SystemExit('Carrier mother layer requires runtime source-repair rebinding before building')
    if not (snap/'image/carrier-defaults/manifest.json').exists():return None
    if hashlib.sha256(json.dumps(manifest['files'],sort_keys=True).encode()).hexdigest()!=manifest.get('digest'):
        raise SystemExit('Carrier snapshot digest differs')
    for item in manifest['files']:
        path=snap/item['path']
        if path.is_symlink() or digest(path)!=item['sha256']:raise SystemExit('Carrier snapshot file differs')
    repair=manifest.get('runtimeIntegration',{}).get('sourceRepair')
    if repair:
        runpy.run_path(str(snap/'image/source_repair.py'))['validate'](
            (snap/'image/source-repair.json').read_bytes(),repair,{i['path']:i['sha256'] for i in manifest['files']})
    else:
        # A full build may start from main after the old parent archive is lost.
        # Do not extend that exception to any derived or dirty candidate: seal
        # the entire current checkout again and require identical frozen bytes.
        if manifest.get('parentSnapshotDigest') or manifest.get('runtimeIntegration'):
            raise SystemExit('Carrier candidate requires its rebound source-repair capsule')
        commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).strip()
        if subprocess.check_output(['git','status','--porcelain'],cwd=ROOT):
            raise SystemExit('Fresh carrier snapshot requires a clean Git checkout')
        fresh,key=snapshot()
        if (key!=manifest['digest'] or fresh.resolve()!=snap.resolve()
                or subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)
                or subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).strip()!=commit):
            raise SystemExit('Fresh carrier snapshot differs from the clean checkout')
    return runpy.run_path(str(snap/'image/carrier_defaults.py'))['declaration']()


def start(resume=False,verify=False,incremental=False,snapshot_input=None,resources=None,kernel_cache_dir=None):
    prepare_started=time.monotonic()
    if subprocess.run(['docker','container','inspect',NAME],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:
        raise SystemExit('Builder already exists. Inspect status/logs; do not overwrite its inputs.')
    if not resume and not verify and any((OUT/name).exists() or (OUT/name).is_symlink() for name in PER_RUN_OUTPUTS):
        raise SystemExit('Previous per-run outputs must be sealed and retired before starting a new candidate')
    # Resume continues the same candidate; only a new build/retry gets a new ID.
    # Verification likewise retains the identity of the existing bytes.
    if verify or resume:
        build_number=json.loads((OUT/'run-input.json').read_text()).get('buildNumber')
        if not isinstance(build_number,str) or not build_number.isdigit():
            raise SystemExit('Cannot resume/verify a candidate without its recorded build number')
    else:
        build_number=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H%M%S')
    run('docker','image','inspect',IMAGE,stdout=subprocess.DEVNULL)
    run('docker','volume','inspect',REFERENCE,stdout=subprocess.DEVNULL)
    run('docker','volume','inspect',PREVIOUS,stdout=subprocess.DEVNULL)
    snap,key=snapshot_input if snapshot_input else snapshot();OUT.mkdir(exist_ok=True)
    carrier=validate_carrier_candidate(snap)
    import runpy
    google=None
    if (snap/'image/google-services.lock.json').is_file():
        google=runpy.run_path(str(ROOT/'image/google_services.py'))['build_mount'](snap,ROOT)
    google_mount=[] if google is None else ['--mount',f"type=bind,source={google['path']},target=/src/vendor/gapps,readonly"]
    apps=None
    if (snap/'image/google-apps.lock.json').is_file():
        apps=runpy.run_path(str(snap/'image/google_apps.py'))['build_mount'](snap,ROOT)
    apps_mount=[] if apps is None else ['--mount',f"type=bind,source={apps['path']},target=/src/vendor/taku-google-apps,readonly"]
    resources=resources or {}
    cpus=(resources.get('NanoCpus') or CONFIG['cpuQuota']*1_000_000_000)/1e9
    memory=resources.get('Memory') or CONFIG['memoryGiB']*1024**3
    swap=resources.get('MemorySwap') or CONFIG['memoryAndSwapGiB']*1024**3
    run('docker','volume','create',VOLUME,stdout=subprocess.DEVNULL)
    commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
    manifest=json.loads((snap/'input-manifest.json').read_text())
    runtime=manifest.get('runtimeIntegration')
    if runtime and runtime.get('readyForBuild') is not True:raise SystemExit('Runtime preparation is not approved build input')
    inputs={'snapshotDigest':key,'snapshotPath':str(snap),'sourceCommit':commit,'sourceTreeDirty':bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)),'container':NAME,'volume':VOLUME,'referenceVolume':REFERENCE,'builderImage':IMAGE,'resumeCompile':resume,'verifyOnly':verify,'incrementalIntegration':incremental,'ninjaJobs':None if verify else CONFIG['ninjaJobs'],'cpuQuota':cpus,'memoryGiB':memory/1024**3,'memoryAndSwapGiB':swap/1024**3,'platform':SOURCE_LOCK['platform'],'r8SerialGc':CONFIG['r8SerialGc'],'buildNumber':build_number}
    if any(item['path']=='system/agent-app/out/TakuAgent.apk' for item in manifest['files']):
        ui_gate=runpy.run_path(str(ROOT/'image/agent_ui.py'))
        inputs['agentUi']=ui_gate['input_identity'](ui_gate['validate_inputs'](snap))
    if (snap/'system/audio-engine/rom-input-lock.json').is_file():
        inputs['audioIntegration']={'lockSha256':digest(snap/'system/audio-engine/rom-input-lock.json')}
    if manifest.get('parentSnapshotDigest'):inputs.update({'snapshotParentDigest':manifest['parentSnapshotDigest'],'snapshotOverlayPaths':manifest['overlayPaths']})
    if manifest.get('integrationProfile'):inputs['integrationProfile']=manifest['integrationProfile']
    if runtime:inputs['runtimeIntegration']=runtime
    if carrier:inputs['carrierDefaults']=carrier
    if google:inputs['googleServices']={k:v for k,v in google.items() if k!='path'}
    if apps:
        inputs['googleApps']={k:v for k,v in apps.items() if k!='path'}
        inputs['distribution']='internal-test-only'
    inputs['previousSourceVolume']=PREVIOUS
    cache_mount=[]
    launch_image=IMAGE
    if not (resume or verify or incremental):
        cache_path=kernel_cache_dir or os.environ.get('TAKU_KERNEL_CACHE_DIR')
        if cache_path:
            if not (snap/'image/kernel_cache.py').is_file():
                raise SystemExit('Kernel caching requires a cache-aware build snapshot')
            cache_path=pathlib.Path(cache_path).expanduser().resolve()
            if ',' in str(cache_path):
                raise SystemExit('Kernel cache directory cannot contain a comma')
            cache_path.mkdir(parents=True,exist_ok=True)
            image_id=subprocess.check_output(
                ['docker','image','inspect','--format={{.Id}}',IMAGE],text=True).strip()
            launch_image=image_id
            cache_mount=['--mount',f'type=bind,source={cache_path},target=/kernel-cache',
                         '--env','TAKU_KERNEL_CACHE_DIR=/kernel-cache',
                         '--env',f'TAKU_KERNEL_BUILDER_IMAGE_ID={image_id}']
    (OUT/'run-input.json').write_text(json.dumps(inputs,indent=2)+'\n')
    (OUT/'status.json').write_text(json.dumps({'phase':'initialize','updatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'romBuilt':False,'physicalDeviceVerified':False},indent=2)+'\n')
    host_timing=['--env',f'TAKU_HOST_PREPARE_SECONDS={time.monotonic()-prepare_started}']
    run('docker','run','-d','--name',NAME,'--platform','linux/amd64','--cpus='+str(cpus),'--memory='+str(memory),'--memory-swap='+str(swap),
        '--mount',f'type=volume,source={VOLUME},target=/src',
        '--mount',f'type=volume,source={REFERENCE},target=/reference,readonly',
        '--mount',f'type=volume,source={PREVIOUS},target=/previous,readonly',
        '--mount',f'type=bind,source={snap},target=/taku,readonly',
        '--mount',f'type=bind,source={OUT},target=/results',
        *google_mount,*apps_mount,*cache_mount,*host_timing,launch_image,'bash','-c',('bash /taku/image/build.sh'+(' --incremental' if incremental else ' --verify-only' if verify else ' --resume' if resume else '')+' > /results/build.log 2>&1'))
    with (OUT/'publication.log').open('a') as publication:
        publisher=subprocess.Popen([sys.executable,str(ROOT/'scripts/publish-image-status.py'),'--watch'],stdout=publication,stderr=publication,start_new_session=True)
    ensure_report_server()
    state()
def export():
    # Serialize the publisher and manual retries around shared partial files.
    with (OUT/'export.lock').open('a') as guard:
        fcntl.flock(guard,fcntl.LOCK_EX)
        _export()
def _export():
    status=json.loads((OUT/'status.json').read_text())
    container=json.loads(subprocess.check_output(['docker','inspect',NAME]))[0]['State']
    if container['Running'] or container['ExitCode']!=0 or not status.get('romBuilt'):
        raise SystemExit('Build/artifact verification has not completed successfully')
    inputs=json.loads((OUT/'run-input.json').read_text())
    # Rebuilding identical source can produce a distinct OTA candidate. Never
    # identify a delivery solely by source digest or replace an older baseline.
    dest=OUT/'artifacts'/(inputs['buildNumber']+'-'+inputs['snapshotDigest'][:16])
    receipt=status['baselineReceipt']
    if digest(OUT/'baseline-receipt.json')!=receipt['sha256']:
        raise SystemExit('Baseline receipt changed after verification')
    receipt_data=json.loads((OUT/'baseline-receipt.json').read_text())
    if receipt_data['runInputSha256']!=digest(OUT/'run-input.json'):
        raise SystemExit('Build inputs changed after baseline verification')
    timing=runpy.run_path(str(ROOT/'image/build_timing.py'))['add_host_stage']
    export_started=time.monotonic()
    try:
        crypto,validator=verify_signatures(inputs)
    finally:
        timing(OUT,'verify_signatures',time.monotonic()-export_started)
    export_started=time.monotonic()
    status['artifactSignaturesVerified']=True
    status['signatureVerification']={'path':str(crypto/'signature-verification.json'),
                                    'sha256':digest(crypto/'signature-verification.json')}
    temporary=OUT/'status.tmp';temporary.write_text(json.dumps(status,indent=2)+'\n');temporary.replace(OUT/'status.json')
    dest.mkdir(parents=True,exist_ok=True)
    def copy_verified(target, expected, copier, label):
        if target.exists():
            if digest(target)!=expected:raise SystemExit('Existing '+label+' differs: '+str(target))
            return
        target.parent.mkdir(parents=True,exist_ok=True)
        partial=target.with_name('.'+target.name+'.partial')
        # An interrupted copy is disposable; accepted deliveries never are.
        if partial.exists() or partial.is_symlink():partial.unlink()
        copier(partial)
        if digest(partial)!=expected:raise SystemExit('Copied '+label+' hash mismatch: '+str(partial))
        partial.replace(target)
    for item in status['artifacts']:
        p=dest/pathlib.Path(item['path']).name
        copy_verified(p,item['sha256'],
                      lambda partial:run('docker','cp',f"{NAME}:/src/{item['path']}",str(partial)),
                      'export')
    evidence=[OUT/name for name in ['baseline-receipt.json','run-input.json',
              'resolved-manifest.xml','resolved-kernel-manifest.xml',
              'kernel-artifacts.json','build-workarounds.json']]
    if inputs.get('integrationProfile') in ('updater-canary','taku-branding-v1'):
        evidence += [OUT/'updater-integration.json',OUT/'updater-apk-verification.json']
    if inputs.get('integrationProfile')=='taku-branding-v1':
        evidence += [OUT/'product-branding-integration.json']
        if (OUT/'product-branding-migration.json').is_file():
            evidence += [OUT/'product-branding-migration.json']
    snapshot=pathlib.Path(inputs['snapshotPath'])
    if inputs.get('integrationProfile')=='taku-branding-v1':
        frozen_files={item['path']:item['sha256'] for item in json.loads((snapshot/'input-manifest.json').read_text())['files']}
        if ('image/branding-properties.json' in frozen_files)!=(receipt_data.get('productBrandingBootProperties') is not None):
            raise SystemExit('Branding boot-property receipt/policy is missing')
    if receipt_data.get('productBrandingBootProperties'):
        evidence += [snapshot/'image/branding-properties.json',snapshot/'image/product_branding.py']
    if inputs.get('agentUi'):
        if not receipt_data.get('agentUiIntegration'):raise SystemExit('Missing new Home APK artifact proof')
        evidence += [snapshot/'image/runtime-assets.lock.json',snapshot/'image/agent_ui.py']
    if receipt_data.get('systemAiIntegration'):
        evidence += [OUT/'system-ai-rom.json',snapshot/'image/system-ai-handoff.json',
                     snapshot/'system/inputmethod/rom-input-lock.json',snapshot/'image/verify-system-ai.py']
        if (snapshot/'system/memory/org.agentos.memory.xml').is_file():
            evidence += [snapshot/'system/memory/org.agentos.memory.xml',snapshot/'image/privapp_permissions.py']
        if inputs.get('runtimeIntegration',{}).get('sourceRepair'):
            evidence += [snapshot/'image/source-repair.json',snapshot/'image/source_repair.py']
    if inputs.get('googleServices'):
        if not receipt_data.get('googleServicesIntegration'):raise SystemExit('Missing Google artifact proof')
        evidence += [OUT/'google-services-artifacts.json',snapshot/'image/google-services.lock.json',
                     snapshot/'image/google_services.py']
    if inputs.get('googleApps'):
        if not receipt_data.get('googleAppsIntegration'):raise SystemExit('Missing internal Google app artifact proof')
        evidence += [OUT/'google-apps-artifacts.json',snapshot/'image/google-apps.lock.json',snapshot/'image/google_apps.py']
    if inputs.get('carrierDefaults'):
        if not receipt_data.get('carrierDefaultsIntegration'):raise SystemExit('Missing carrier artifact proof')
        evidence += [OUT/name for name in ('carrier-defaults-source.json','carrier-defaults-apk.json',
                     'carrier-defaults-apk-signature.txt','carrier-defaults-apk-badging.txt')]
        evidence += [snapshot/'image/carrier_defaults.py',snapshot/'image/apply-updater-patches.py']
        for name in ('manifest.json','telecom-volte.patch'):
            source=snapshot/'image/carrier-defaults'/name
            copy_verified(dest/'carrier-defaults'/name,digest(source),
                          lambda partial,source=source:shutil.copy2(source,partial),'carrier contract')
    evidence += [snapshot/'input-manifest.json',snapshot/'image/source-lock.json',
                 snapshot/'image/kernel-source-lock.json']
    for source in evidence:
        target=dest/source.name
        copy_verified(target,digest(source),lambda partial:shutil.copy2(source,partial),'export evidence')
    for source in [validator,*sorted(p for p in crypto.rglob('*') if p.is_file())]:
        if source.is_symlink():raise SystemExit('Unexpected link in signature verification evidence')
        relative=pathlib.Path('verify-signatures.py') if source==validator else source.relative_to(crypto)
        target=dest/'signature-verification'/relative
        copy_verified(target,digest(source),lambda partial:shutil.copy2(source,partial),'signature evidence')
    if digest(OUT/'run-input.json')!=receipt_data['runInputSha256']:
        raise SystemExit('Build candidate changed during export')
    timing(OUT,'export',time.monotonic()-export_started)
    status['artifactsExported']=True
    status['exportDirectory']=str(dest)
    temporary=OUT/'status.tmp';temporary.write_text(json.dumps(status,indent=2)+'\n');temporary.replace(OUT/'status.json')
    print('Exported development build to '+str(dest)+'. Physical boot and firmware compatibility remain unverified.')

def updater_preview(base,profile):
    """Build only the reviewed system Updater, preserving current ROM evidence."""
    info=json.loads(subprocess.check_output(['docker','inspect',NAME]))[0]
    current=json.loads((OUT/'run-input.json').read_text())
    status=json.loads((OUT/'status.json').read_text())
    if (info['State']['Running'] or info['State']['ExitCode']!=0 or not status.get('romBuilt')
            or not status.get('artifactSignaturesVerified') or not status.get('artifactsExported')
            or current['snapshotDigest']!=base):
        raise SystemExit('Updater preview requires the successful, exported current parent candidate')
    frozen_inputs=(OUT/'run-input.json').read_bytes()
    frozen_status=(OUT/'status.json').read_bytes()
    from ota_release import load_baseline
    accepted=load_baseline(status['exportDirectory'])
    if accepted['runInputSha256']!=hashlib.sha256(frozen_inputs).hexdigest():
        raise SystemExit('Updater preview parent differs from immutable export')
    snap,key=integration_snapshot(base,profile)
    validate_carrier_candidate(snap)
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    results=OUT/'updater-previews'/(stamp+'-'+key[:16]);results.mkdir(parents=True)
    preview_name=NAME+'-updater-'+stamp
    for name in ['resolved-manifest.xml','resolved-kernel-manifest.xml','kernel-artifacts.json','build-workarounds.json']:
        shutil.copy2(OUT/name,results/name)
    snapshot_manifest=json.loads((snap/'input-manifest.json').read_text())
    inputs={**current,'snapshotDigest':key,'snapshotPath':str(snap),'integrationProfile':profile,
            'snapshotParentDigest':snapshot_manifest['parentSnapshotDigest'],
            'snapshotOverlayPaths':snapshot_manifest['overlayPaths'],
            'moduleOnly':'Updater','container':preview_name,'parentSnapshotDigest':base}
    (results/'run-input.json').write_text(json.dumps(inputs,indent=2)+'\n')
    resources=info['HostConfig']
    command=['docker','run','--name',preview_name,'--platform','linux/amd64',
             '--cpus='+str((resources.get('NanoCpus') or CONFIG['cpuQuota']*1_000_000_000)/1e9),
             '--memory='+str(resources.get('Memory') or CONFIG['memoryGiB']*1024**3),
             '--memory-swap='+str(resources.get('MemorySwap') or CONFIG['memoryAndSwapGiB']*1024**3)]
    for mount in [f'type=volume,source={VOLUME},target=/src',
                  f'type=volume,source={REFERENCE},target=/reference,readonly',
                  f'type=volume,source={PREVIOUS},target=/previous,readonly',
                  f'type=bind,source={snap},target=/taku,readonly',
                  f'type=bind,source={results},target=/results']:
        command+=['--mount',mount]
    command += [IMAGE,'bash','/taku/image/build.sh','--updater-only']
    with (results/'build.log').open('xb') as log:
        completed=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    if completed.returncode!=0:
        raise SystemExit('Updater preview failed; preserve container and logs at '+str(results))
    run('docker','cp',preview_name+':/src/out/target/product/tokay/system_ext/priv-app/Updater/Updater.apk',str(results/'Updater.apk'))
    proof=json.loads((results/'updater-apk-verification.json').read_text())
    if digest(results/'Updater.apk')!=proof['apkSha256'] or proof.get('apkSignatureVerified') is not True:
        raise SystemExit('Exported Updater preview differs from verified APK')
    if (OUT/'run-input.json').read_bytes()!=frozen_inputs or (OUT/'status.json').read_bytes()!=frozen_status:
        raise SystemExit('ROM build evidence changed during Updater preview')
    record={'schema':'taku.updater-preview/1','snapshotDigest':key,'parentSnapshotDigest':base,
            'parentRunInputSha256':hashlib.sha256(frozen_inputs).hexdigest(),
            'container':preview_name,'apkSha256':proof['apkSha256'],'versionCode':proof['versionCode'],
            'platformCertificateSha256':proof['platformCertificateSha256'],
            'sourceVolumeModifiedByAuditedIntegration':True,'romBuilt':False,'physicalDeviceVerified':False}
    path=results/'preview.json'
    with path.open('x') as output:output.write(json.dumps(record,indent=2)+'\n')
    print(json.dumps({'path':str(path),'sha256':digest(path),'apk':str(results/'Updater.apk')},indent=2))


def google_apps_preview(candidate,telephony=False):
    """Compile and inspect the locked imports before a full candidate.

    Both dependency mounts are mandatory. This uses its own run/results and
    retains the accepted ROM container, manifest, receipt and exported bytes.
    """
    info=json.loads(subprocess.check_output(['docker','inspect',NAME]))[0]
    current=json.loads((OUT/'run-input.json').read_text())
    status=json.loads((OUT/'status.json').read_text())
    if info['State']['Running'] or info['State']['ExitCode']!=0 or not all(status.get(k) for k in
            ('romBuilt','artifactSignaturesVerified','artifactsExported')):
        raise SystemExit('Internal apps preview requires an idle, verified/exported parent build')
    if not isinstance(candidate,str) or len(candidate)!=64 or any(c not in '0123456789abcdef' for c in candidate):
        raise SystemExit('Expected the exact prepared internal candidate snapshot')
    frozen_inputs=(OUT/'run-input.json').read_bytes();frozen_status=(OUT/'status.json').read_bytes()
    from ota_release import load_baseline
    accepted=load_baseline(status['exportDirectory'])
    if accepted['runInputSha256']!=hashlib.sha256(frozen_inputs).hexdigest():
        raise SystemExit('Internal app preview parent differs from immutable export')
    snap=OUT/'snapshots'/candidate;manifest=json.loads((snap/'input-manifest.json').read_text())
    carrier=validate_carrier_candidate(snap)
    if telephony and carrier is None:raise SystemExit('Telephony preview requires declared carrier inputs')
    if manifest.get('digest')!=candidate or hashlib.sha256(json.dumps(manifest['files'],sort_keys=True).encode()).hexdigest()!=candidate:
        raise SystemExit('Prepared internal app snapshot differs')
    if not manifest.get('runtimeIntegration',{}).get('readyForBuild') or manifest.get('integrationProfile')!='taku-branding-v1':
        raise SystemExit('Internal app preview requires the final runtime-bound snapshot')
    for item in manifest['files']:
        path=snap/item['path']
        if path.is_symlink() or digest(path)!=item['sha256']:raise SystemExit('Prepared internal app file differs')
    import runpy
    repair=manifest['runtimeIntegration'].get('sourceRepair')
    if not repair:raise SystemExit('Internal app preview requires its final source-repair binding')
    runpy.run_path(str(snap/'image/source_repair.py'))['validate'](
        (snap/'image/source-repair.json').read_bytes(),repair,{i['path']:i['sha256'] for i in manifest['files']})
    google=runpy.run_path(str(snap/'image/google_services.py'))['build_mount'](snap,ROOT)
    apps=runpy.run_path(str(snap/'image/google_apps.py'))['build_mount'](snap,ROOT)
    if not google or not apps:raise SystemExit('Both core GMS and internal app inputs are required')
    stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    label='telephony' if telephony else 'google-apps'
    results=OUT/(label+'-previews')/(stamp+'-'+candidate[:16]);results.mkdir(parents=True)
    preview_name=NAME+'-'+label+'-'+stamp
    for name in ('resolved-manifest.xml','resolved-kernel-manifest.xml','kernel-artifacts.json','build-workarounds.json'):
        shutil.copy2(OUT/name,results/name)
    inputs={**current,'snapshotDigest':candidate,'snapshotPath':str(snap),
            'runtimeIntegration':manifest['runtimeIntegration'],'integrationProfile':'taku-branding-v1',
            'snapshotParentDigest':manifest['parentSnapshotDigest'],'snapshotOverlayPaths':manifest['overlayPaths'],
            'sourceCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
            'googleServices':{k:v for k,v in google.items() if k!='path'},
            'googleApps':{k:v for k,v in apps.items() if k!='path'},'distribution':'internal-test-only',
            'moduleOnly':'TeleService+Updater' if telephony else 'InternalGoogleApps','container':preview_name,
            'parentSnapshotDigest':current['snapshotDigest']}
    if carrier:inputs['carrierDefaults']=carrier
    (results/'run-input.json').write_text(json.dumps(inputs,indent=2)+'\n')
    resources=info['HostConfig']
    command=['docker','run','--name',preview_name,'--platform','linux/amd64',
        '--cpus='+str((resources.get('NanoCpus') or CONFIG['cpuQuota']*1_000_000_000)/1e9),
        '--memory='+str(resources.get('Memory') or CONFIG['memoryGiB']*1024**3),
        '--memory-swap='+str(resources.get('MemorySwap') or CONFIG['memoryAndSwapGiB']*1024**3)]
    for mount in [f'type=volume,source={VOLUME},target=/src',
                  f'type=volume,source={REFERENCE},target=/reference,readonly',
                  f'type=volume,source={PREVIOUS},target=/previous,readonly',
                  f'type=bind,source={snap},target=/taku,readonly',
                  f'type=bind,source={results},target=/results',
                  f"type=bind,source={google['path']},target=/src/vendor/gapps,readonly",
                  f"type=bind,source={apps['path']},target=/src/vendor/taku-google-apps,readonly"]:
        command+=['--mount',mount]
    command += [IMAGE,'bash','/taku/image/build.sh','--telephony-only' if telephony else '--google-apps-only']
    with (results/'build.log').open('xb') as log:
        completed=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
    if completed.returncode!=0:
        raise SystemExit('Internal app entry preview failed; retain container/logs: '+str(results))
    report_name='carrier-defaults-apk.json' if telephony else 'google-apps-preview.json'
    report=json.loads((results/report_name).read_text())
    if telephony:
        if (report.get('profile')!=carrier['profile'] or report.get('apkSignatureVerified') is not True
                or report.get('runInputSha256')!=digest(results/'run-input.json')):
            raise SystemExit('Incomplete TeleService entry preview')
        updater=json.loads((results/'updater-apk-verification.json').read_text())
        if updater.get('versionCode')!=9 or updater.get('apkSignatureVerified') is not True:
            raise SystemExit('Incomplete 1.3.1 Updater entry preview')
    elif (report.get('distribution')!='internal-test-only'
          or {r.get('id') for r in report.get('files',[])} != {
              a['id'] for a in json.loads((snap/'image/google-apps.lock.json').read_text())['apps']}
          or len(report.get('files',[])) != len(json.loads((snap/'image/google-apps.lock.json').read_text())['apps'])):
        raise SystemExit('Incomplete internal app entry preview')
    if (OUT/'run-input.json').read_bytes()!=frozen_inputs or (OUT/'status.json').read_bytes()!=frozen_status:
        raise SystemExit('Accepted ROM evidence changed during internal app entry preview')
    record={'schema':'taku.telephony-entry/1' if telephony else 'taku.internal-google-apps-entry/1','snapshotDigest':candidate,
            'parentRunInputSha256':hashlib.sha256(frozen_inputs).hexdigest(),
            'container':preview_name,'reportSha256':digest(results/report_name),
            'distribution':'internal-test-only','sourceAndKernelResynced':False,
            'romBuilt':False,'physicalDeviceVerified':False}
    (results/'entry-verified.json').write_text(json.dumps(record,indent=2)+'\n')
    print(json.dumps({'path':str(results/'entry-verified.json'),**record}))


def rebuild(base=None,overlays=(),integration=None,snapshot_input=None):
    info=json.loads(subprocess.check_output(['docker','inspect',NAME]))[0]
    status=json.loads((OUT/'status.json').read_text())
    if info['State']['Running']:
        raise SystemExit('Builder is still running; never replace its immutable inputs')
    # Preserve and verify the previous delivery before reusing its source/output volume.
    completed=info['State']['ExitCode']==0 and status.get('romBuilt')
    if completed:export()
    elif not all((OUT/name).is_file() for name in ['kernel-artifacts.json','resolved-manifest.xml','resolved-kernel-manifest.xml']):
        raise SystemExit('No source/kernel checkpoint available for incremental integration')
    candidate=snapshot_input or (integration_snapshot(base,integration) if integration else repair_snapshot(base,overlays) if base else snapshot())
    validate_carrier_candidate(candidate[0])
    label='completed-' if completed else 'failed-'
    archive_run_evidence(info,OUT/'attempts'/(label+info['Id'][:12]),new_candidate=True)
    run('docker','rename',NAME,NAME+'-'+label+info['Id'][:12])
    start(incremental=True,snapshot_input=candidate,resources=info['HostConfig'])
def retry(resume=False,verify=False):
    info=json.loads(subprocess.check_output(['docker','inspect',NAME]))[0]
    if info['State']['Running']:raise SystemExit('Builder is still running; inspect it rather than interrupting it.')
    if info['State']['ExitCode']==0:raise SystemExit('Successful builder retained; export/inspect its artifacts first.')
    candidate=None
    inputs=json.loads((OUT/'run-input.json').read_text())
    if inputs.get('carrierDefaults'):
        snap=pathlib.Path(inputs['snapshotPath'])
        manifest=json.loads((snap/'input-manifest.json').read_text())
        if manifest.get('digest')!=inputs['snapshotDigest']:
            raise SystemExit('Carrier retry must keep the recorded immutable snapshot')
        if validate_carrier_candidate(snap)!=inputs['carrierDefaults']:
            raise SystemExit('Carrier retry declaration differs')
        candidate=(snap,inputs['snapshotDigest'])
    # Preserve the failed container and immutable input snapshot for diagnosis.
    archive_run_evidence(info,OUT/'attempts'/info['Id'][:12],new_candidate=not (resume or verify))
    run('docker','rename',NAME,NAME+'-failed-'+info['Id'][:12])
    start(resume=resume,verify=verify,resources=info['HostConfig'],snapshot_input=candidate)
def main():
    parser=argparse.ArgumentParser();parser.add_argument('action',choices=['start','derive','prepare-runtime','derive-runtime','rebuild-runtime','derive-runtime-repair','rebuild-runtime-repair','updater-preview','google-apps-preview','telephony-preview','rebuild','retry','resume','verify','status','logs','export'])
    parser.add_argument('--from-snapshot',help='Derive a repair from an existing immutable snapshot, excluding current WIP')
    parser.add_argument('--overlay',action='append',default=[],help='Explicit build-controller file to overlay; repeatable')
    parser.add_argument('--integration',choices=['updater-canary','taku-branding-v1','taku-branding-system-ext-v1','taku-memory-privapp-v1','taku-branding-version-v2','taku-google-services-v1','taku-google-apps-internal-v1','taku-telecom-volte-v1'],help='Exact reviewed feature overlay; excludes unrelated working changes')
    parser.add_argument('--runtime-handoff',type=pathlib.Path,help='Review-only isolated component handoff directory')
    parser.add_argument('--runtime-manifest-sha256',help='Independently reviewed exact handoff manifest hash')
    parser.add_argument('--runtime-packet',type=pathlib.Path,help='Original immutable handoff packet')
    parser.add_argument('--runtime-packet-sha256',help='Independently reviewed exact original packet hash')
    parser.add_argument('--runtime-contract',type=pathlib.Path,help='Explicit reviewed binding for a new runtime handoff')
    parser.add_argument('--runtime-contract-sha256',help='Independently reviewed exact runtime contract hash')
    parser.add_argument('--minimum-runtime-versions',type=json.loads,help='Reviewed JSON version floors for explicit source repair only')
    parser.add_argument('--kernel-cache-dir',type=pathlib.Path,help='Opt-in persistent host kernel prebuilt cache for start')
    args=parser.parse_args()
    if args.kernel_cache_dir is not None and args.action!='start':
        parser.error('--kernel-cache-dir requires start')
    runtime=(args.runtime_handoff,args.runtime_manifest_sha256,args.runtime_packet,args.runtime_packet_sha256)
    repair_actions=('derive-runtime-repair','rebuild-runtime-repair')
    runtime_actions=('prepare-runtime','derive-runtime','rebuild-runtime',*repair_actions)
    if (args.runtime_contract is None)!=(args.runtime_contract_sha256 is None):parser.error('Runtime contract requires its reviewed hash')
    if args.runtime_contract is not None and args.action not in ('derive-runtime','rebuild-runtime'):parser.error('Runtime contract is only valid for final runtime derive/rebuild')
    if (args.action in repair_actions)!=(args.minimum_runtime_versions is not None):parser.error('Source repair requires explicit reviewed minimum runtime versions')
    if args.action in runtime_actions:
        if not all(runtime) or not args.from_snapshot or args.integration or args.overlay:parser.error('Runtime preparation requires its exact parent and all four reviewed handoff arguments')
    elif any(runtime):parser.error('Runtime handoff arguments are only valid for explicit runtime preparation/derive/rebuild')
    if args.integration and (args.overlay or not args.from_snapshot or args.action not in ('derive','updater-preview','rebuild')):parser.error('Integration requires --from-snapshot and cannot mix repair overlays')
    if args.action not in (*runtime_actions,'google-apps-preview','telephony-preview') and not args.integration and ((args.from_snapshot or args.overlay) and (args.action!='rebuild' or not args.from_snapshot or not args.overlay)):parser.error('Repair overlays require rebuild --from-snapshot DIGEST --overlay FILE')
    if args.action in ('google-apps-preview','telephony-preview') and (not args.from_snapshot or args.integration or args.overlay):parser.error('Internal apps preview requires only its exact prepared --from-snapshot')
    if args.action in ('derive','updater-preview') and not args.integration:parser.error('This action requires an explicit integration profile')
    if args.action in ('start','derive',*runtime_actions,'updater-preview','google-apps-preview','telephony-preview','rebuild','retry','resume','verify'):
        OUT.mkdir(parents=True,exist_ok=True)
        with (OUT/'source-operation.lock').open('a') as guard:
            fcntl.flock(guard,fcntl.LOCK_EX)
            dispatch(args)
    else:dispatch(args)

def dispatch(args):
    if args.action=='start':start(kernel_cache_dir=args.kernel_cache_dir)
    elif args.action=='prepare-runtime':
        from image_runtime_snapshot import prepare
        path,key=prepare(OUT,args.from_snapshot,args.runtime_handoff,args.runtime_manifest_sha256,
                         args.runtime_packet,args.runtime_packet_sha256)
        print(json.dumps({'preparationPath':str(path),'snapshotDigest':key,'readyForBuild':False}))
    elif args.action in ('derive-runtime','rebuild-runtime'):
        from image_runtime_snapshot import derive
        candidate=derive(OUT,ROOT,args.from_snapshot,args.runtime_handoff,args.runtime_manifest_sha256,
                         args.runtime_packet,args.runtime_packet_sha256,args.runtime_contract,args.runtime_contract_sha256)
        if args.action=='derive-runtime':print(json.dumps({'snapshotPath':str(candidate[0]),'snapshotDigest':candidate[1],'readyForBuild':True}))
        else:rebuild(snapshot_input=candidate)
    elif args.action in ('derive-runtime-repair','rebuild-runtime-repair'):
        from image_runtime_snapshot import repair
        candidate=repair(OUT,ROOT,args.from_snapshot,args.runtime_handoff,args.runtime_manifest_sha256,
                         args.runtime_packet,args.runtime_packet_sha256,args.minimum_runtime_versions)
        if args.action=='derive-runtime-repair':print(json.dumps({'snapshotPath':str(candidate[0]),'snapshotDigest':candidate[1],'readyForBuild':True}))
        else:rebuild(snapshot_input=candidate)
    elif args.action=='derive':
        path,key=integration_snapshot(args.from_snapshot,args.integration)
        print(json.dumps({'snapshotPath':str(path),'snapshotDigest':key,'integrationProfile':args.integration}))
    elif args.action=='updater-preview':updater_preview(args.from_snapshot,args.integration)
    elif args.action=='google-apps-preview':google_apps_preview(args.from_snapshot)
    elif args.action=='telephony-preview':google_apps_preview(args.from_snapshot,telephony=True)
    elif args.action=='rebuild':rebuild(args.from_snapshot,args.overlay,args.integration)
    elif args.action=='retry':retry()
    elif args.action=='resume':retry(resume=True)
    elif args.action=='verify':retry(verify=True)
    elif args.action=='status':state()
    elif args.action=='export':export()
    else:
        p=OUT/'build.log'
        if p.exists():
            with p.open('rb') as f:f.seek(max(0,p.stat().st_size-12000));print(f.read().decode(errors='replace'))

if __name__=='__main__':main()
