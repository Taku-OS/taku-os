#!/usr/bin/env python3
"""Native follow/identity/evidence boundaries, using fake devices only."""
import copy
import base64
import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile
from types import SimpleNamespace
from unittest import mock

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import ota_device as DEVICE
import ota_updater as UPDATER

SERIAL = "fixture-tokay"
BOOT = "d37cb7ef-a8a7-4b81-b871-2a8f0a9e991c"
ZIP_PATH = "/data/lineageos_updates/fixture-ota.zip"
APK_PATH = "/data/app/~~fixture/org.lineageos.updater-xyz/base.apk"


def status(name, code, progress=0):
    # These spellings come from update_status_utils.cc and the pinned Android
    # client, including UPDATE_STATUS_ and ErrorCode:: prefixes.
    return f"[INFO:update_engine_client_android.cc(90)] onStatusUpdate(UPDATE_STATUS_{name} ({code}), {progress})\n"


def complete(name="kSuccess", code=0):
    return f"[INFO:update_engine_client_android.cc(100)] onPayloadApplicationComplete(ErrorCode::{name} ({code}))\n"


SUCCESS = status("IDLE", 0) + status("DOWNLOADING", 3, 0.2) + status("FINALIZING", 5, 0.9) + status("UPDATED_NEED_REBOOT", 6, 1) + complete()
SUCCESS_COMPLETION_FIRST = (status("IDLE", 0) + status("UPDATE_AVAILABLE", 2) + status("CLEANUP_PREVIOUS_UPDATE", 11)
                            + status("DOWNLOADING", 3, 1) + status("VERIFYING", 4, 1)
                            + status("FINALIZING", 5, 1) + complete() + status("UPDATED_NEED_REBOOT", 6))


class NativeEvents(unittest.TestCase):
    def test_exact_native_success_and_explicit_exit(self):
        UPDATER.validate_events(UPDATER.parse_events(SUCCESS), 0)
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "exit 0"):
            UPDATER.validate_events(UPDATER.parse_events(SUCCESS), -15)

    def test_success_accepts_both_terminal_callback_orders(self):
        for log in (SUCCESS, SUCCESS_COMPLETION_FIRST,
                    SUCCESS + status("UPDATED_NEED_REBOOT", 6),
                    SUCCESS_COMPLETION_FIRST + status("UPDATED_NEED_REBOOT", 6)):
            with self.subTest(log=log):
                UPDATER.validate_events(UPDATER.parse_events(log), 0)

    def test_no_backward_or_post_completion_phase_bounces(self):
        prefix = status("IDLE", 0) + status("DOWNLOADING", 3)
        suffix = status("UPDATED_NEED_REBOOT", 6) + complete()
        failures = [
            prefix + status("IDLE", 0) + suffix,
            prefix + status("UPDATE_AVAILABLE", 2) + suffix,
            prefix + status("CLEANUP_PREVIOUS_UPDATE", 11) + suffix,
            prefix + status("FINALIZING", 5) + status("VERIFYING", 4) + suffix,
            prefix + status("UPDATED_NEED_REBOOT", 6) + status("FINALIZING", 5) + suffix,
            prefix + complete() + status("FINALIZING", 5) + status("UPDATED_NEED_REBOOT", 6),
            SUCCESS_COMPLETION_FIRST + status("IDLE", 0),
            SUCCESS_COMPLETION_FIRST + complete(),
            SUCCESS_COMPLETION_FIRST.replace(complete(), complete("kUpdatedButNotActive", 52)),
            SUCCESS_COMPLETION_FIRST.replace(complete(), complete("kUpdateAlreadyInstalled", 66)),
        ]
        for name, code in (("REPORTING_ERROR_EVENT", 7), ("ATTEMPTING_ROLLBACK", 8),
                           ("DISABLED", 9), ("NEED_PERMISSION_TO_UPDATE", 10)):
            failures.append(prefix + status(name, code) + suffix)
        for log in failures:
            with self.subTest(log=log), self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.validate_events(UPDATER.parse_events(log), 0)

    def test_no_exit_zero_shortcuts(self):
        failures = [
            status("IDLE", 0) + complete(),
            SUCCESS.replace(complete(), complete("kUpdatedButNotActive", 52)),
            SUCCESS.replace(complete(), complete("kUpdateAlreadyInstalled", 66)),
            SUCCESS.replace(status("IDLE", 0), ""),
            SUCCESS.replace(status("UPDATED_NEED_REBOOT", 6, 1), ""),
            SUCCESS.replace(complete(), ""),
            SUCCESS + complete(),
            SUCCESS + status("IDLE", 0),
            SUCCESS.replace(status("FINALIZING", 5, 0.9), status("REPORTING_ERROR_EVENT", 7)),
        ]
        for log in failures:
            with self.subTest(log=log), self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.validate_events(UPDATER.parse_events(log), 0)

    def test_malformed_or_wrong_code_callbacks_rejected(self):
        for log in ["onStatusUpdate(IDLE (0), 0)", status("IDLE", 3), status("IDLE", 0, 1.1),
                    "onPayloadApplicationComplete(kSuccess (0))", "onStatusUpdate(UPDATE_STATUS_IDLE (0), nan)"]:
            with self.subTest(log=log), self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.parse_events(log)

    def test_real_host_process_follows_only_and_exits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = pathlib.Path(temporary)
            fake = root / "fixture-adb"
            fake.write_text("#!/usr/bin/env python3\nimport sys\nassert sys.argv[1:] == " + repr(["-s", SERIAL, *UPDATER.FOLLOW]) + "\nprint(" + repr(SUCCESS) + ", end='', flush=True)\n")
            fake.chmod(0o755)
            adb = DEVICE.Adb(SERIAL, str(fake))
            ready = []
            result = UPDATER.follow(adb, root / "follow.log", 5, lambda: ready.append(True))
            self.assertEqual(ready, [True])
            self.assertEqual(result["exitCode"], 0)
            self.assertIsNone(result["observerError"])
            UPDATER.validate_events(result["events"], result["exitCode"])
            self.assertEqual((root / "follow.log").read_text(), SUCCESS)


class Evidence(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.apk = self.root / "Updater.apk"; self.apk.write_bytes(b"fixture signed APK")
        self.package_path=self.root/'incremental-ota.zip'
        payload=b'CrAU fixture payload bytes, never an actual OTA'
        props='\n'.join(['FILE_HASH='+base64.b64encode(hashlib.sha256(payload).digest()).decode(),
                          'FILE_SIZE='+str(len(payload)),
                          'METADATA_HASH='+base64.b64encode(hashlib.sha256(payload[:8]).digest()).decode(),
                          'METADATA_SIZE=8'])+'\n'
        with zipfile.ZipFile(self.package_path,'w',compression=zipfile.ZIP_STORED) as archive:
            archive.writestr('payload.bin',payload);archive.writestr('payload_properties.txt',props)
        self.package = {"sha256": DEVICE.digest(self.package_path), "bytes": self.package_path.stat().st_size}
        self.proof = {"schema": "taku.lineage-updater-apk/1", "packageName": UPDATER.PACKAGE,
                      "versionCode": 2, "apkSha256": DEVICE.digest(self.apk), "apkBytes": self.apk.stat().st_size,
                      "manifestSha256": DEVICE.sha_text("fixture manifest"),
                      "platformCertificateSha256": DEVICE.sha_text("fixture platform certificate"),
                      "apkSignatureVerified": True, "physicalDeviceVerified": False}
        self.proof_path = self.root / "updater-apk-verification.json"
        self.proof_path.write_text(json.dumps(self.proof))
        self.updater = UPDATER.apk_contract(self.apk, self.proof_path, DEVICE.digest(self.proof_path))
        self.before = {"device": {"adbSerialSha256": DEVICE.sha_text(SERIAL)},
                       "probe": {"fixture": "unchanged"}, "baselines": {"from": {"fixture": "D"}}}
        self.initial = {"bootId": BOOT, "device": self.before["device"], "probe": self.before["probe"],
                        "downloadedZip": {"devicePath": ZIP_PATH, **self.package},
                        "updater": {**self.updater, "devicePath": APK_PATH}}
        log = self.root / "update-engine-follow.log"; log.write_text(SUCCESS)
        self.record = {"method": "lineage-updater", "observerError": None, "commandCompleted": True,
                       "command": ["fake-adb", "-s", SERIAL, *UPDATER.FOLLOW], "exitCode": 0,
                       "log": log.name, "logSha256": DEVICE.digest(log), "events": UPDATER.parse_events(SUCCESS),
                       "initial": copy.deepcopy(self.initial), "final": copy.deepcopy(self.initial)}
        self.payload=UPDATER.payload_binding(self.package_path,ZIP_PATH)
        engine_log=self.root/'update-engine-apply.log'
        self.apply_line='[INFO:update_attempter_android.cc(250)] ApplyPayload('+self.payload['url']+', '+str(self.payload['offset'])+', 0) '+', '.join(self.payload['headers'])+'\n'
        engine_log.write_text(self.apply_line)
        self.record['engine']={'identity':{'path':'/data/misc/update_engine_log/update_engine.20261022-224855',
                                          'pid':1182,'fd':7,'startTicks':9981,'bootId':BOOT,'inode':'regular file:1234'},
                               'prefixSha256':DEVICE.sha_text('initial log'), 'prefixBytes':11,
                               'observedAfterInitialIdle':True,'binderCallerVerified':False,
                               'apply':UPDATER.validate_engine_apply(self.apply_line,self.payload),
                               'expectedPayload':self.payload,'log':engine_log.name,'logSha256':DEVICE.digest(engine_log)}
        self.record['engineBefore']=UPDATER.seal_engine_before(self.root,
            {**self.record['engine']['identity'],'text':'initial log'})

    def check(self):
        return UPDATER.check_record(self.record, self.before, {"package": self.package}, self.root, self.package_path)

    def test_engine_must_apply_this_actual_delta_once(self):
        for line in [self.apply_line.replace(ZIP_PATH,'/data/lineageos_updates/full-E.zip'),
                     self.apply_line.replace(', 0)',', 99)'),
                     self.apply_line.replace('FILE_SIZE=', 'DIFFERENT_SIZE='),
                     self.apply_line+self.apply_line, '',
                     self.apply_line.replace('file:///data/','https://unrelated.invalid/data/')]:
            with self.subTest(line=line),self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.validate_engine_apply(line,self.payload)
        # A verified expected file still present on disk cannot compensate for
        # a different actual engine source, even with a native success log.
        engine_log=self.root/self.record['engine']['log']
        engine_log.write_text(self.apply_line.replace(ZIP_PATH,'/data/lineageos_updates/full-E.zip'))
        self.record['engine']['logSha256']=DEVICE.digest(engine_log)
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError,'differ from the verified delta'):
            self.check()

    def test_engine_log_is_the_current_open_native_file(self):
        path=self.record['engine']['identity']['path']
        class Fake:
            executable='fixture-adb'
            serial=SERIAL
            def runner(fake,command,**kwargs):
                self.assertEqual(command,['fixture-adb','-s',SERIAL,'exec-out','/system/bin/cat',path])
                self.assertEqual(kwargs,{'capture_output':True,'timeout':30,'check':False})
                return subprocess.CompletedProcess(command,0,b'initial log\n')
            def shell(fake,*command):
                return {('pidof','update_engine'):'1182',('ls','-1','/proc/1182/fd'):'0\n1\n2\n7',
                        ('readlink','/proc/1182/fd/0'):'/dev/null',('readlink','/proc/1182/fd/1'):'/dev/null',
                        ('readlink','/proc/1182/fd/2'):'/dev/null',('readlink','/proc/1182/fd/7'):path,
                        ('stat','-L','-c','%i','/proc/1182/fd/7'):'1234',
                        ('cat','/proc/1182/stat'):'1182 (update_engine) '+' '.join(['S']+['0']*18+['9981']),
                        ('stat','-c','%F:%i',path):'regular file:1234',('stat','-c','%s',path):'11',
                        ('cat',path):'initial log',('cat','/proc/sys/kernel/random/boot_id'):BOOT}[command]
        snapshot=UPDATER.engine_snapshot(Fake(),path)
        self.assertEqual(snapshot['startTicks'],9981)
        self.assertEqual(snapshot['fd'],7)
        self.assertEqual(snapshot['text'],'initial log\n')
        original=Fake.shell
        def wrong_target(fake,*command):
            return '/old/unrelated.log' if command==('readlink','/proc/1182/fd/7') else original(fake,*command)
        with mock.patch.object(Fake,'shell',wrong_target):
            with self.assertRaisesRegex(DEVICE.DeviceVerificationError,'exact file currently opened'):
                UPDATER.engine_snapshot(Fake(),path)
        def duplicate_descriptor(fake,*command):
            if command==('ls','-1','/proc/1182/fd'):return '0\n1\n2\n7\n9'
            if command==('readlink','/proc/1182/fd/9'):return path
            if command==('stat','-L','-c','%i','/proc/1182/fd/9'):return '1234'
            return original(fake,*command)
        with mock.patch.object(Fake,'shell',duplicate_descriptor):
            with self.assertRaisesRegex(DEVICE.DeviceVerificationError,'one exact file currently opened'):
                UPDATER.engine_snapshot(Fake(),path)

    def test_revalidate_portable_log_and_apk_identity(self):
        self.check()
        self.apk.write_bytes(b"different APK")
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "APK differs"):
            UPDATER.apk_contract(self.apk, self.proof_path, DEVICE.digest(self.proof_path))

    def branded_source(self):
        return {"productBranding":{"schema":"taku.product-branding/1","productName":"TakuOS",
                "productVersion":"1.0","displayVersion":"TakuOS1.0","updaterVersionCode":3,
                "configSha256":DEVICE.sha_text('fixture product config')},
                "updaterIntegration":{key:self.proof[key] for key in ('versionCode','apkSha256','apkBytes',
                    'platformCertificateSha256','manifestSha256')} | {
                    'apkEntry':'SYSTEM_EXT/priv-app/Updater/Updater.apk','apkSignatureVerified':True,
                    'uri':'https://worker.taku.ai/os-update/v1/canary/{device}/{type}/{incr}',
                    'sourceEvidenceSha256':DEVICE.sha_text('fixture source audit'),
                    'apkEvidenceSha256':DEVICE.digest(self.proof_path)}}

    def test_v3_requires_the_exact_authenticated_source_and_revalidates_record(self):
        self.proof['versionCode']=3;self.proof_path.write_text(json.dumps(self.proof))
        source=self.branded_source()
        updater=UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path),source)
        self.assertEqual(updater['versionCode'],3)
        self.record['initial']['updater']={**updater,'devicePath':APK_PATH}
        self.record['final']=copy.deepcopy(self.record['initial'])
        UPDATER.check_record(self.record,self.before,{'package':self.package},self.root,self.package_path,source)
        with self.assertRaises(DEVICE.DeviceVerificationError):self.check()
        for key,value in (('versionCode',2),('apkSha256','a'*64),('apkBytes',1),
                          ('platformCertificateSha256','b'*64),('manifestSha256','c'*64),
                          ('apkEvidenceSha256','d'*64),('apkSignatureVerified',False)):
            wrong=copy.deepcopy(source);wrong['updaterIntegration'][key]=value
            with self.subTest(key=key),self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path),wrong)
        self.apk.write_bytes(b'different v3 APK')
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError,'APK differs'):
            UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path),source)

    def test_unknown_versions_or_partial_brand_contract_cannot_fall_back_to_legacy(self):
        self.check()  # Archived legacy v2 retains its unchanged proof shape.
        for version in (1,3,4):
            self.proof['versionCode']=version;self.proof_path.write_text(json.dumps(self.proof))
            with self.subTest(version=version),self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path))
        self.proof['versionCode']=3;self.proof_path.write_text(json.dumps(self.proof));source=self.branded_source()
        for key in ('productBranding','updaterIntegration'):
            wrong=copy.deepcopy(source);wrong.pop(key)
            with self.subTest(missing=key),self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path),wrong)

    def test_v4_requires_new_brand_generation_and_the_same_exact_apk_evidence(self):
        self.proof['versionCode']=4;self.proof_path.write_text(json.dumps(self.proof))
        source=self.branded_source()
        source['productBranding'].update(schema='taku.product-branding/2',productVersion='1.0.1',
                                        displayVersion='TakuOS1.0.1',updaterVersionCode=4)
        contract=UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path),source)
        self.assertEqual(contract['versionCode'],4)
        self.record['initial']['updater']={**contract,'devicePath':APK_PATH}
        self.record['final']=copy.deepcopy(self.record['initial'])
        UPDATER.check_record(self.record,self.before,{'package':self.package},self.root,self.package_path,source)
        for key,value in (('schema','taku.product-branding/1'),('productVersion','1.0'),
                          ('productVersion','1.0.01'),('updaterVersionCode',3),
                          ('displayVersion','TakuOS1.0')):
            wrong=copy.deepcopy(source);wrong['productBranding'][key]=value
            with self.subTest(key=key,value=value),self.assertRaises(DEVICE.DeviceVerificationError):
                UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path),wrong)
        wrong=copy.deepcopy(source);wrong['updaterIntegration']['apkSha256']='f'*64
        with self.assertRaises(DEVICE.DeviceVerificationError):
            UPDATER.apk_contract(self.apk,self.proof_path,DEVICE.digest(self.proof_path),wrong)

    def test_sealed_initial_engine_files_are_required_and_bound(self):
        original=copy.deepcopy(self.record)
        metadata_path=self.root/'engine-before.json'
        prefix_path=self.root/'update-engine-before.log'
        metadata_bytes,prefix_bytes=metadata_path.read_bytes(),prefix_path.read_bytes()
        for mutation in ('missing-field','missing-metadata','corrupt-metadata','missing-prefix',
                         'corrupt-prefix','prefix-symlink','identity','prefix-size','prefix-digest','wrong-name'):
            with self.subTest(mutation=mutation):
                self.record=copy.deepcopy(original)
                if prefix_path.is_symlink():prefix_path.unlink()
                metadata_path.write_bytes(metadata_bytes);prefix_path.write_bytes(prefix_bytes)
                if mutation=='missing-field':self.record.pop('engineBefore')
                elif mutation=='missing-metadata':metadata_path.unlink()
                elif mutation=='corrupt-metadata':metadata_path.write_bytes(metadata_bytes+b' ')
                elif mutation=='missing-prefix':prefix_path.unlink()
                elif mutation=='corrupt-prefix':prefix_path.write_bytes(prefix_bytes+b'\n')
                elif mutation=='prefix-symlink':
                    prefix_path.unlink();prefix_path.symlink_to(self.root/self.record['engine']['log'])
                else:
                    value=json.loads(metadata_bytes)
                    if mutation=='identity':value['identity']['startTicks']+=1
                    elif mutation=='prefix-size':value['prefixBytes']+=1
                    elif mutation=='prefix-digest':value['prefixSha256']='0'*64
                    elif mutation=='wrong-name':value['prefixLog']='another-prefix.log'
                    metadata_path.write_text(json.dumps(value))
                    self.record['engineBefore']={'path':metadata_path.name,'sha256':DEVICE.digest(metadata_path),**value}
                with self.assertRaises(DEVICE.DeviceVerificationError):self.check()
        self.record=original
        metadata_path.write_bytes(metadata_bytes);prefix_path.write_bytes(prefix_bytes)

    def test_mutated_log_boot_zip_apk_and_install_command_rejected(self):
        for modify in [
            lambda value: value["command"].append("--update"),
            lambda value: value["command"].__setitem__(2, "another-phone"),
            lambda value: value["final"].__setitem__("bootId", "another-boot"),
            lambda value: value["initial"]["downloadedZip"].__setitem__("sha256", "0" * 64),
            lambda value: value["initial"]["updater"].__setitem__("apkSha256", "0" * 64),
            lambda value: value.__setitem__("exitCode", -15),
            lambda value: value.__setitem__("events", []),
            lambda value: value.__setitem__("observerError", "terminated"),
        ]:
            original = copy.deepcopy(self.record)
            modify(self.record)
            with self.assertRaises(DEVICE.DeviceVerificationError):
                self.check()
            self.record = original
        (self.root / self.record["log"]).write_text(SUCCESS + "tampered")
        with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "log changed"):
            self.check()

    def test_source_observation_is_read_only_and_checks_installed_bytes(self):
        class Fake:
            def __init__(fake):
                fake.calls = []
            def shell(fake, *command):
                fake.calls.append(command)
                if command == ("cat", "/proc/sys/kernel/random/boot_id"):
                    return BOOT
                if command == ("pm", "path", "--user", "0", UPDATER.PACKAGE):
                    return "package:" + APK_PATH
                if command == ("dumpsys", "package", UPDATER.PACKAGE):
                    return "Packages:\n Package [org.lineageos.updater]:\n versionCode=2 minSdk=37 targetSdk=37\nHidden system packages:\n versionCode=1\n"
                for path, sha, size in [(ZIP_PATH, self.package["sha256"], self.package["bytes"]),
                                        (APK_PATH, self.updater["apkSha256"], self.updater["apkBytes"])]:
                    if command == ("/system/bin/sha256sum", path):
                        return sha + "  " + path
                    if command == ("stat", "-c", "%s", path):
                        return str(size)
                raise AssertionError("Unexpected command or mutation: " + str(command))
        adb = Fake()
        with mock.patch.object(DEVICE, "inspect_device", return_value=self.before["device"]), mock.patch.object(
                DEVICE, "probe_state", return_value=self.before["probe"]):
            self.assertEqual(UPDATER.source_observation(adb, self.before, ZIP_PATH, self.package, self.updater), self.initial)
            for path in ("/sdcard/unverified.zip", "/data/lineageos_updates/../../bad.zip", ZIP_PATH + ";reboot"):
                with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "complete ZIP"):
                    UPDATER.downloaded_zip(adb, path, self.package)

    def test_observe_preserves_failure_and_never_claims_target_boot(self):
        args = SimpleNamespace(updater_apk=self.apk, updater_verification=self.proof_path,
                               updater_verification_sha256=DEVICE.digest(self.proof_path), serial=SERIAL,
                               adb="fake-adb", output=self.root / "attempt", downloaded_package=ZIP_PATH,
                               plan_sha256="1" * 64, before_sha256="2" * 64, delivery_sha256="3" * 64, timeout=1)
        args.engine_log=self.record['engine']['identity']['path']
        def failure(adb, log_path, timeout, ready):
            pathlib.Path(log_path).write_text(status("IDLE", 0))
            return {"command": ["fake-adb", "-s", SERIAL, *UPDATER.FOLLOW], "exitCode": -15,
                    "events": UPDATER.parse_events(status("IDLE", 0)), "observerError": "timeout", "elapsedSeconds": 1}
        with mock.patch.object(UPDATER, "source_observation", return_value=self.initial), mock.patch.object(
                UPDATER,'engine_snapshot',return_value={**self.record['engine']['identity'],'text':'initial log'}), mock.patch.object(
                UPDATER, "follow", side_effect=failure):
            with self.assertRaisesRegex(DEVICE.DeviceVerificationError, "preserve log/evidence"):
                UPDATER.observe(args, {}, self.before, {"package": self.package},self.package_path)
        result = json.loads((args.output / "installation.json").read_text())
        self.assertFalse(result["commandCompleted"])
        self.assertFalse(result["targetBootVerified"])
        self.assertFalse(result["dataPreservationVerified"])
        self.assertNotIn("incrementalSideloadCompleted", result)

    def test_idle_prefix_is_sealed_before_ready_even_when_later_validation_fails(self):
        args = SimpleNamespace(updater_apk=self.apk, updater_verification=self.proof_path,
                               updater_verification_sha256=DEVICE.digest(self.proof_path), serial=SERIAL,
                               adb="fake-adb", output=self.root / "failed-after-ready", downloaded_package=ZIP_PATH,
                               plan_sha256="1" * 64, before_sha256="2" * 64, delivery_sha256="3" * 64, timeout=1,
                               engine_log=self.record['engine']['identity']['path'])
        prefix = "exact idle prefix with trailing whitespace  \n"
        def announcement(*values, **kwargs):
            sealed = json.loads((args.output / "engine-before.json").read_text())
            self.assertEqual((args.output / sealed['prefixLog']).read_bytes(), prefix.encode())
            self.assertEqual(sealed['prefixSha256'], DEVICE.sha_text(prefix))
            self.assertEqual(sealed['identity'], self.record['engine']['identity'])
            self.assertTrue(sealed['observedAfterInitialIdle'])
        def native_follow(adb, log_path, timeout, ready):
            pathlib.Path(log_path).write_text(status("IDLE", 0))
            ready()
            return {'command':['fake-adb','-s',SERIAL,*UPDATER.FOLLOW], 'exitCode':0,
                    'events':UPDATER.parse_events(status('IDLE',0)), 'observerError':None, 'elapsedSeconds':1}
        with mock.patch.object(UPDATER, 'source_observation', return_value=self.initial), mock.patch.object(
                UPDATER, 'engine_snapshot', return_value={**self.record['engine']['identity'], 'text':prefix}), mock.patch.object(
                UPDATER, 'follow', side_effect=native_follow), mock.patch('builtins.print', side_effect=announcement):
            with self.assertRaisesRegex(DEVICE.DeviceVerificationError, 'preserve log/evidence'):
                UPDATER.observe(args, {}, self.before, {'package':self.package}, self.package_path)
        result = json.loads((args.output / 'installation.json').read_text())
        self.assertFalse(result['commandCompleted'])
        self.assertIsNone(result['engine'])
        self.assertEqual(result['engineBefore']['sha256'], DEVICE.digest(args.output/'engine-before.json'))
        self.assertEqual(result['engineBefore']['prefixBytes'], len(prefix.encode()))

    def test_observe_requires_the_actual_engine_delta_after_ready(self):
        args=SimpleNamespace(updater_apk=self.apk,updater_verification=self.proof_path,
                             updater_verification_sha256=DEVICE.digest(self.proof_path),serial=SERIAL,
                             adb='fake-adb',output=self.root/'successful-attempt',downloaded_package=ZIP_PATH,
                             plan_sha256='1'*64,before_sha256='2'*64,delivery_sha256='3'*64,timeout=1,
                             engine_log=self.record['engine']['identity']['path'])
        first={**self.record['engine']['identity'],'text':'initial log'}
        last={**first,'text':'initial log\n'+self.apply_line}
        def native_follow(adb,log_path,timeout,ready):
            ready()
            pathlib.Path(log_path).write_text(SUCCESS)
            return {'command':['fake-adb','-s',SERIAL,*UPDATER.FOLLOW],'exitCode':0,
                    'events':UPDATER.parse_events(SUCCESS),'observerError':None,'elapsedSeconds':1}
        with mock.patch.object(UPDATER,'source_observation',return_value=self.initial),mock.patch.object(
                UPDATER,'engine_snapshot',side_effect=[first,first,last]),mock.patch.object(
                UPDATER,'follow',side_effect=native_follow):
            UPDATER.observe(args,{},self.before,{'package':self.package},self.package_path)
        record=json.loads((args.output/'installation.json').read_text())
        UPDATER.check_record(record,self.before,{'package':self.package},args.output,self.package_path)
        self.assertFalse(record['targetBootVerified'])
        self.assertFalse(record['engine']['binderCallerVerified'])
        self.assertEqual(record['engine']['apply']['url'],'file://'+ZIP_PATH)


if __name__ == "__main__":
    unittest.main()
