#!/usr/bin/env python3
"""Verify a completed Pixel 9 build; never equate archive presence with boot."""
import datetime,hashlib,io,json,pathlib,runpy,ssl,struct,sys,tempfile,zipfile
EXPECTED_ANDROID_RELEASE='17'
EXPECTED_ANDROID_SDK='37'
product=pathlib.Path(sys.argv[1]);results=pathlib.Path(sys.argv[2])
try:
    run_input_bytes=(results/'run-input.json').read_bytes()
    run_input=json.loads(run_input_bytes)
except (OSError,ValueError) as error:raise SystemExit('Missing or invalid build run-input.json: '+str(error))
build_number=run_input.get('buildNumber')
if isinstance(build_number,bool) or not isinstance(build_number,(str,int)) or not str(build_number).strip():
    raise SystemExit('Missing or invalid run-input buildNumber')
build_number=str(build_number)
packages=list((product/'obj/PACKAGING/target_files_intermediates').glob('*.zip'))
if len(packages)!=1:raise SystemExit('Expected one target-files package')
updater_integration=None
product_branding=None
branding_boot_properties=None
google_services_integration=None
google_apps_integration=None
carrier_defaults_integration=None
agent_ui_integration=None
with zipfile.ZipFile(packages[0]) as archive:
    names=set(archive.namelist())
    audio_root=pathlib.Path(__file__).resolve().parents[1]/'system/audio-engine'
    if run_input.get('audioIntegration') or 'SYSTEM_EXT/priv-app/TakuAudioEngine/TakuAudioEngine.apk' in names:
        if run_input.get('audioIntegration') and run_input['audioIntegration']['lockSha256']!=hashlib.sha256((audio_root/'rom-input-lock.json').read_bytes()).hexdigest():
            raise SystemExit('Audio release lock differs from build input')
        audio=runpy.run_path(str(audio_root/'verify-rom.py'))
        audio['verify'](archive.read,archive.namelist(),json.loads((audio_root/'rom-input-lock.json').read_text()))
    if not run_input.get('googleApps') and any('/TakuInternal' in name for name in names):
        raise SystemExit('Undeclared internal Google app content in an unscoped image')
    if archive.testzip():raise SystemExit('target-files archive failed CRC')
    def properties(path):
        return dict(line.split('=',1) for line in archive.read(path).decode().splitlines() if '=' in line and not line.startswith('#'))
    system=properties('SYSTEM/build.prop')
    product_props=properties('PRODUCT/etc/build.prop')
    vendor=properties('VENDOR/build.prop')
    # Lineage's reusable system partition identifies itself as generic. Device
    # identity is defined by the product/vendor partitions and OTA pre-device.
    if (system.get('ro.product.system.device') not in ('generic','tokay')
            or product_props.get('ro.product.product.device')!='tokay'
            or vendor.get('ro.product.vendor.device')!='tokay'):
        raise SystemExit('Wrong target device')
    if system.get('ro.build.type')!='userdebug' or product_props.get('ro.product.build.type')!='userdebug':
        raise SystemExit('Wrong target build variant')
    # This is the ROM platform contract, independent of the Agent APK's targetSdk.
    release=system.get('ro.build.version.release')
    sdk=system.get('ro.build.version.sdk')
    if release!=EXPECTED_ANDROID_RELEASE or sdk!=EXPECTED_ANDROID_SDK:
        raise SystemExit('Wrong Android platform: expected Android '+EXPECTED_ANDROID_RELEASE+
                         ' / API '+EXPECTED_ANDROID_SDK+', got release='+str(release)+' / API '+str(sdk))
    # Releasetools uses these independent properties. A Pixel fingerprint may
    # retain the stock build ID, so do not derive our incremental ID from it.
    build_props=['ro.build.version.incremental','ro.build.fingerprint',
                 'ro.build.date.utc','ro.build.version.security_patch']
    for key in build_props:
        if not system.get(key):raise SystemExit('Missing target-files build identity: '+key)
    if system['ro.build.version.incremental']!=build_number:
        raise SystemExit('Target-files build number differs from run-input buildNumber')
    if not system['ro.build.date.utc'].isdigit():raise SystemExit('Invalid target-files build timestamp')
    for key in build_props:
        if key in product_props and product_props[key]!=system[key]:
            raise SystemExit('Product/system build identity differs: '+key)
    identity={'systemDevice':system['ro.product.system.device'],'productDevice':product_props['ro.product.product.device'],
              'vendorDevice':vendor['ro.product.vendor.device'],'buildType':system['ro.build.type'],
              'buildTags':system.get('ro.build.tags'),'androidRelease':release,'androidSdk':int(sdk),
              'platformSecurityPatch':system['ro.build.version.security_patch'],
              'buildNumber':build_number,'buildFingerprint':system['ro.build.fingerprint'],
              'buildTimestamp':int(system['ro.build.date.utc'])}
    required_metadata=['META/misc_info.txt','META/ab_partitions.txt','META/apkcerts.txt']
    for entry in required_metadata:
        if entry not in names:raise SystemExit('Missing OTA baseline metadata: '+entry)
    misc_info=properties('META/misc_info.txt')
    if misc_info.get('ab_update')!='true':raise SystemExit('Target-files is not an A/B update baseline')
    ab_partitions=archive.read('META/ab_partitions.txt').decode().split()
    if not ab_partitions:raise SystemExit('Empty OTA baseline A/B partition list')
    metadata_hashes={entry:hashlib.sha256(archive.read(entry)).hexdigest() for entry in required_metadata}
    signing_evidence={
        'declaredDefaultCertificate':misc_info.get('default_system_dev_certificate'),
        'declaredAvbEnabled':misc_info.get('avb_enable'),
        'declaredAvbAlgorithm':misc_info.get('avb_vbmeta_algorithm'),
        'trustedOtaCertificates':[],
        # These public certificates describe image trust, not proof that an
        # OTA's ZIP footer, payload or AVB signature was actually verified.
        'otaPackageSignatureVerified':False,'payloadSignatureVerified':False,
        'avbSignatureVerified':False,
    }
    for entry in sorted(n for n in names if n.endswith('/etc/security/otacerts.zip')):
        try:
            with zipfile.ZipFile(io.BytesIO(archive.read(entry))) as certificates:
                for certificate in sorted(n for n in certificates.namelist() if not n.endswith('/')):
                    der=ssl.PEM_cert_to_DER_cert(certificates.read(certificate).decode('ascii'))
                    signing_evidence['trustedOtaCertificates'].append({
                        'archive':entry,'certificate':certificate,'derSha256':hashlib.sha256(der).hexdigest()})
        except (zipfile.BadZipFile,UnicodeError,ValueError) as error:
            raise SystemExit('Invalid target-files OTA certificate evidence: '+str(error))
    required=['IMAGES/'+p+'.img' for p in ['boot','init_boot','vendor_boot','vendor_kernel_boot','dtbo','system','system_ext','product','vendor','vendor_dlkm','system_dlkm','vbmeta']]
    missing=[p for p in required if p not in names]
    if missing:raise SystemExit('Missing images: '+str(missing))
    with archive.open('IMAGES/vbmeta.img') as image:vbmeta_header=image.read(256)
    if len(vbmeta_header)!=256 or vbmeta_header[:4]!=b'AVB0':
        raise SystemExit('Invalid root vbmeta header')
    vbmeta_flags=struct.unpack_from('>I',vbmeta_header,120)[0]
    if vbmeta_flags & ~1:
        raise SystemExit('Root vbmeta disables boot verification or has unsupported flags; cannot measure OTA baseline')
    signing_evidence['vbmetaFlags']=vbmeta_flags
    for suffix in ['/TakuAssistant.apk','/agentos-task-broker.jar','/agentos-virtual-display.jar','/agentos-resolver-bridge.jar','/phone-display','/taku-device','/device-probe.sh','/taku-runtime-start','/codex/codex.tar.gz','/bin/magpie','/bin/magpie-runtime','/etc/init/taku-magpie.rc','/etc/magpie-LICENSE']:
        if not any(n.endswith(suffix) for n in names):raise SystemExit('Missing Taku integration: '+suffix)
    if 'SYSTEM_EXT/etc/default-permissions/taku-assistant.xml' not in names:
        raise SystemExit('Missing Taku integration: SYSTEM_EXT/etc/default-permissions/taku-assistant.xml')
    # Gesture navigation (one bottom handle) is the default mode; TakuGestureOverlay moves the
    # assistant from the corner swipe to the handle's double tap (image/taku-core.mk).
    if 'com.android.internal.systemui.navbar.gestural' not in product_props.get('ro.boot.vendor.overlay.theme','').split(';'):
        raise SystemExit('Gesture navigation is not the default navigation mode')
    for suffix in ['/NavigationBarModeGesturalOverlay.apk','/TakuGestureOverlay.apk']:
        if not any(n.endswith(suffix) for n in names):raise SystemExit('Missing navigation integration: '+suffix)
    # Package presence alone does not prove that this source snapshot's Agent was built.
    repository=pathlib.Path(__file__).resolve().parents[1]
    # Historical baselines did not contain the independent Home APK. New frozen
    # inputs declare it explicitly; omission from either the image or run-input
    # must not silently fall back to the old native home.
    input_manifest=repository/'input-manifest.json'
    agent_ui_declared=(input_manifest.is_file() and any(
        item['path']=='system/agent-app/out/TakuAgent.apk'
        for item in json.loads(input_manifest.read_text())['files']))
    if agent_ui_declared or run_input.get('agentUi') or 'SYSTEM_EXT/app/TakuAgent/TakuAgent.apk' in names:
        ui_gate=runpy.run_path(str(repository/'image/agent_ui.py'))
        agent_ui_integration=ui_gate['verify_archive'](
            repository, archive, pathlib.Path.cwd()/'out/host/linux-x86/bin', results)
        if run_input.get('agentUi')!=ui_gate['input_identity'](ui_gate['load_lock'](repository)):
            raise SystemExit('New Home APK run-input differs from frozen input lock')
    expected_root=repository/'system/assistant'
    asset_hashes={}
    apk_name=next(n for n in names if n.endswith('/TakuAssistant.apk'))
    try:
        with zipfile.ZipFile(io.BytesIO(archive.read(apk_name))) as apk:
            for source,entry in [
                ('assets/native/protocol.json','assets/native/protocol.json'),
                ('assets/skills/taku-app-development/SKILL.md','assets/skills/taku-app-development/SKILL.md'),
                ('assets/skills/taku-app-development/references/native.md','assets/skills/taku-app-development/references/native.md'),
                ('res/raw/taku_harness.json','res/raw/taku_harness.json')]:
                actual=apk.read(entry)
                if actual!=(expected_root/source).read_bytes():raise SystemExit('Agent asset differs from immutable source: '+entry)
                asset_hashes[entry]=hashlib.sha256(actual).hexdigest()
        broker_name=next(n for n in names if n.endswith('/agentos-task-broker.jar'))
        with zipfile.ZipFile(io.BytesIO(archive.read(broker_name))) as broker:
            dex=broker.read('classes.dex')
            for runtime_class in ['TurnProcesses','HarnessContext','SkillCommunity','NativeBridge','PhoneNativeTools','ModelConnection']:
                if ('Lorg/agentos/lab/'+runtime_class+';').encode() not in dex:
                    raise SystemExit('Missing current Agent runtime class: '+runtime_class)
    except (zipfile.BadZipFile,KeyError) as error:raise SystemExit('Current Agent package contents missing: '+str(error))
    if b'nameserver 127.0.0.1' not in archive.read('SYSTEM/etc/resolv.conf'):
        raise SystemExit('Missing loopback resolver configuration')
    if run_input.get('integrationProfile') in ('updater-canary','taku-branding-v1'):
        branded=run_input['integrationProfile']=='taku-branding-v1'
        helper=runpy.run_path(str(pathlib.Path(__file__).parent/('apply-product-branding.py' if branded else 'apply-updater-patches.py')),run_name='updater_artifact_verifier')
        if branded:
            import product_branding as branding
            config,_,_=helper['load_contract']()
            product_branding=branding.build_identity(config,build_number)
            try:
                branding_boot_properties=branding.verify_boot_properties(archive,product_branding)
            except ValueError as error:
                raise SystemExit('Target-files product branding differs from immutable source: '+str(error))
            contract={'uri':helper['BASE']['URI']}
        else:
            contract,_=helper['load_contract'](pathlib.Path(__file__).parent/'updater/manifest.json')
        ext_props=properties('SYSTEM_EXT/etc/build.prop')
        if ext_props.get('lineage.updater.uri')!=contract['uri']:
            raise SystemExit('Updater canary URI differs from immutable integration contract')
        apk_entry='SYSTEM_EXT/priv-app/Updater/Updater.apk'
        if apk_entry not in names:raise SystemExit('Missing platform Updater integration')
        raw=archive.read(apk_entry)
        with tempfile.TemporaryDirectory(prefix='updater-apk-',dir=results) as temporary:
            apk=pathlib.Path(temporary)/'Updater.apk';apk.write_bytes(raw)
            apk_proof=helper['verify_apk'](pathlib.Path.cwd(),apk,results)
        updater_integration={
            'uri':contract['uri'],'versionCode':config['updaterVersionCode'] if branded else 2,'apkEntry':apk_entry,
            'apkSha256':hashlib.sha256(raw).hexdigest(),'apkBytes':len(raw),
            'platformCertificateSha256':apk_proof['platformCertificateSha256'],
            'manifestSha256':apk_proof['manifestSha256'],
            'sourceEvidenceSha256':hashlib.sha256((results/'updater-integration.json').read_bytes()).hexdigest(),
            'apkEvidenceSha256':hashlib.sha256((results/'updater-apk-verification.json').read_bytes()).hexdigest(),
            'apkSignatureVerified':True,'physicalDeviceVerified':False,
        }
    if 'googleServices' in run_input:
        import google_services
        google_services_integration=google_services.verify_artifacts(
            archive,results,run_input,pathlib.Path.cwd()/'out/host/linux-x86/bin/aapt2')
    if 'googleApps' in run_input:
        import google_apps
        google_apps_integration=google_apps.verify_artifacts(
            archive,results,run_input,pathlib.Path.cwd()/'out/host/linux-x86/bin/aapt2')
    if 'carrierDefaults' in run_input:
        import carrier_defaults
        carrier_defaults_integration=carrier_defaults.verify_archive(archive,results,run_input)
    prohibited=['NexusLauncher','Gemini']+([] if google_services_integration else ['Velvet'])
    forbidden=[n for n in names if n.endswith('.apk') and any(p in n for p in prohibited)]
    if forbidden:raise SystemExit('Unexpected Google launcher/assistant packages: '+str(forbidden))
    firmware=[n for n in names if n.startswith('RADIO/')]
if product_branding:
    ota=[product/product_branding['fullOtaFileName']]
    if not ota[0].is_file() or ota[0].is_symlink():raise SystemExit('Missing completed branded full OTA')
else:
    ota=list(dict.fromkeys(list(product.glob('*ota*.zip'))+list(product.glob('lineage-*.zip'))+list(product.glob('taku-*.zip'))))
if not ota:raise SystemExit('Missing OTA package')
ota_metadata={}
for p in ota:
    with zipfile.ZipFile(p) as archive:
        if archive.testzip():raise SystemExit('OTA archive failed CRC')
        metadata=dict(line.split('=',1) for line in archive.read('META-INF/com/android/metadata').decode().splitlines() if '=' in line)
        if metadata.get('pre-device')!='tokay':raise SystemExit('Wrong OTA device')
        if metadata.get('post-sdk-level')!=EXPECTED_ANDROID_SDK:
            raise SystemExit('Wrong OTA Android platform: expected post-sdk-level='+EXPECTED_ANDROID_SDK+
                             ', got '+str(metadata.get('post-sdk-level')))
        if metadata.get('ota-type')!='AB':raise SystemExit('Expected a full A/B OTA package')
        if 'pre-build' in metadata or 'pre-build-incremental' in metadata:
            raise SystemExit('Incremental OTA cannot serve as the full-image baseline')
        expected_metadata={
            'post-build':system['ro.build.fingerprint'],
            'post-build-incremental':build_number,
            'post-timestamp':system['ro.build.date.utc'],
            'post-security-patch-level':system['ro.build.version.security_patch'],
        }
        for key,expected in expected_metadata.items():
            if metadata.get(key)!=expected:
                raise SystemExit('OTA build identity differs from target-files: '+key+' in '+p.name)
        if 'payload.bin' not in archive.namelist():raise SystemExit('Missing A/B OTA payload')
        ota_metadata[str(p)]={key:metadata[key] for key in ['ota-type','pre-device','post-sdk-level',*expected_metadata]}
files=[packages[0]]+ota
records=[]
for p in dict.fromkeys(files):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for chunk in iter(lambda:f.read(8*1024*1024),b''):h.update(chunk)
    records.append({'path':str(p),'bytes':p.stat().st_size,'sha256':h.hexdigest()})
state={'phase':'artifact-verified','updatedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'romBuilt':True,'target':'tokay','variant':'userdebug','artifacts':records,'firmwareEntries':firmware,'physicalDeviceVerified':False,'coldBootVerified':False,'safeToFlashWithoutDeviceFirmwareAssessment':False,'signing':'development build; certificate evidence in baseline receipt; OTA/payload/AVB signatures not verified','security':'upstream userdebug su domain is permissive; not a production enforcing agent domain'}
state['identity']=identity
state['agentAssetsSha256']=asset_hashes
receipt={
    'schema':'taku.image-baseline/1','verifiedAt':state['updatedAt'],
    'buildIdentity':identity,
    'buildInput':{key:run_input.get(key) for key in ['buildNumber','snapshotDigest','sourceCommit']},
    'runInputSha256':hashlib.sha256(run_input_bytes).hexdigest(),
    'targetFiles':records[0],
    'fullOtas':[dict(record,metadata=ota_metadata[record['path']]) for record in records[1:]],
    'targetFilesMetadataSha256':metadata_hashes,'abPartitions':ab_partitions,
    'signingEvidence':signing_evidence,
    'physicalDeviceVerified':False,'coldBootVerified':False,'incrementalOtaReady':False,
    'remainingValidation':['OTA/payload/AVB signature verification',
                           'Install and boot this exact full-image candidate',
                           'Validate a future incremental package against this preserved baseline'],
}
if updater_integration:receipt['updaterIntegration']=updater_integration
if product_branding:receipt['productBranding']=product_branding
if branding_boot_properties:receipt['productBrandingBootProperties']=branding_boot_properties
if google_services_integration:receipt['googleServicesIntegration']=google_services_integration
if google_apps_integration:receipt['googleAppsIntegration']=google_apps_integration
if carrier_defaults_integration:receipt['carrierDefaultsIntegration']=carrier_defaults_integration
if agent_ui_integration:receipt['agentUiIntegration']=agent_ui_integration
receipt_path=results/'baseline-receipt.json'
receipt_bytes=(json.dumps(receipt,indent=2)+'\n').encode()
temporary=receipt_path.with_suffix('.tmp');temporary.write_bytes(receipt_bytes);temporary.replace(receipt_path)
state['baselineReceipt']={'path':str(receipt_path),'sha256':hashlib.sha256(receipt_bytes).hexdigest()}
(results/'status.json').write_text(json.dumps(state,indent=2)+'\n')
print(json.dumps(state,indent=2))
