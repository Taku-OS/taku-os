#!/usr/bin/env python3
"""Check builder isolation and immutable inputs without Docker or a ROM build."""
import datetime
import hashlib
import importlib.util
import json
import os
import pathlib
import runpy
import shutil
import sys
import tempfile
import unittest
from unittest import mock


ROOT = pathlib.Path(__file__).resolve().parents[1]


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


config = load_module('image_build_config', ROOT / 'scripts/image_build_config.py')
with mock.patch.dict(sys.modules, {'image_build_config': config}):
    builder = load_module('image_builder', ROOT / 'scripts/pixel9-image.py')


class BuilderIsolation(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.out = self.root / config.OUT.relative_to(ROOT)
        self.old_status = self.root / 'out/pixel9/status.json'
        self.old_status.parent.mkdir(parents=True)
        self.old_status.write_text('preserved Android 16 evidence')

    def test_android_17_identity_is_separate_from_android_16(self):
        old_out = ROOT / 'out/pixel9'
        self.assertNotEqual(config.OUT, old_out)
        self.assertNotIn(old_out, config.OUT.parents)
        self.assertNotEqual(config.NAME, 'taku-pixel9-build')
        self.assertNotEqual(config.VOLUME, 'taku-pixel9-lineage23-source')
        self.assertNotEqual(config.VOLUME, config.REFERENCE)
        self.assertEqual(config.REFERENCE, 'agentos-lineage23-source')
        self.assertEqual(config.PREVIOUS, 'taku-pixel9-lineage23-source')
        self.assertEqual(len({config.VOLUME, config.REFERENCE, config.PREVIOUS}), 3)
        self.assertNotEqual(config.REPORT, ROOT / 'docs/research/pixel9-image-build-state.json')
        self.assertNotEqual(config.CONFIG['reportPort'], 4343)
        self.assertEqual(config.SOURCE_LOCK['androidVersion'], 17)
        self.assertEqual(config.SOURCE_LOCK['apiLevel'], 37)

    def test_builder_override_requires_a_pinned_digest(self):
        expected = 'sha256:' + 'a' * 64
        with mock.patch.dict(os.environ, {'TAKU_BUILDER_IMAGE': expected}):
            self.assertEqual(load_module('config_override', ROOT / 'scripts/image_build_config.py').IMAGE, expected)
        with mock.patch.dict(os.environ, {'TAKU_BUILDER_IMAGE': 'builder:latest'}):
            with self.assertRaisesRegex(ValueError, 'pinned by digest'):
                load_module('config_bad_override', ROOT / 'scripts/image_build_config.py')

    def test_existing_builder_refuses_start_before_writing_status(self):
        with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                mock.patch.object(builder.subprocess, 'run', return_value=mock.Mock(returncode=0)), \
                mock.patch.object(builder, 'snapshot') as snapshot:
            with self.assertRaisesRegex(SystemExit, 'Builder already exists'):
                builder.start()
            snapshot.assert_not_called()
        self.assertFalse(self.out.exists())
        self.assertEqual(self.old_status.read_text(), 'preserved Android 16 evidence')

    def test_go_runtime_limits_reject_invalid_or_unbounded_configuration(self):
        config.validate_go_runtime(config.CONFIG)
        for key, value in [('soongGoMemoryLimitGiB', 0), ('soongGoMemoryLimitGiB', True),
                           ('soongGoMemoryLimitGiB', '24'),
                           ('soongGoMemoryLimitGiB', config.CONFIG['memoryGiB']),
                           ('soongGoMaxProcs', 0), ('soongGoMaxProcs', False),
                           ('soongGoMaxProcs', config.CONFIG['cpuQuota'] + 1)]:
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError):
                    config.validate_go_runtime({**config.CONFIG, key: value})

    def test_start_mounts_opt_in_cache_with_inspected_toolchain_identity(self):
        snapshot = self.out / 'snapshot'
        (snapshot / 'image').mkdir(parents=True)
        (snapshot / 'image/kernel_cache.py').touch()
        (snapshot / 'input-manifest.json').write_text(json.dumps({'files': []}))
        cache = self.root / 'kernel prebuilts'
        image_id = 'sha256:' + 'c' * 64
        with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                mock.patch.object(builder.subprocess, 'run', return_value=mock.Mock(returncode=1)), \
                mock.patch.object(builder.subprocess, 'check_output', side_effect=['b' * 40, b'', image_id]), \
                mock.patch.object(builder.subprocess, 'Popen'), \
                mock.patch.object(builder, 'run') as commands, \
                mock.patch.object(builder, 'ensure_report_server'), \
                mock.patch.object(builder, 'state'):
            builder.start(snapshot_input=(snapshot, 'a' * 64), kernel_cache_dir=cache)
        launch, = [call.args for call in commands.call_args_list if call.args[:2] == ('docker', 'run')]
        self.assertIn(f'type=bind,source={cache.resolve()},target=/kernel-cache', launch)
        self.assertIn('TAKU_KERNEL_CACHE_DIR=/kernel-cache', launch)
        self.assertIn(f'TAKU_KERNEL_BUILDER_IMAGE_ID={image_id}', launch)
        self.assertEqual(launch[-4], image_id)

    def test_start_mounts_reference_read_only_and_preserves_old_evidence(self):
        snapshot = self.out / 'snapshots' / ('a' * 64)
        snapshot.mkdir(parents=True)
        (snapshot / 'input-manifest.json').write_text(json.dumps({'files': []}))
        with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                mock.patch.object(builder.subprocess, 'run', return_value=mock.Mock(returncode=1)), \
                mock.patch.object(builder.subprocess, 'check_output', side_effect=['b' * 40, b'']), \
                mock.patch.object(builder.subprocess, 'Popen'), \
                mock.patch.object(builder, 'run') as commands, \
                mock.patch.object(builder, 'ensure_report_server'), \
                mock.patch.object(builder, 'state'):
            builder.start(snapshot_input=(snapshot, 'a' * 64))
        launches = [call.args for call in commands.call_args_list if call.args[:2] == ('docker', 'run')]
        self.assertEqual(len(launches), 1)
        args = launches[0]
        mounts = [args[i + 1] for i, arg in enumerate(args) if arg == '--mount']
        self.assertIn(f'type=volume,source={config.REFERENCE},target=/reference,readonly', mounts)
        self.assertIn(f'type=volume,source={config.PREVIOUS},target=/previous,readonly', mounts)
        self.assertIn(f'type=volume,source={config.VOLUME},target=/src', mounts)
        self.assertIn(f'type=bind,source={snapshot},target=/taku,readonly', mounts)
        self.assertIn(f'type=bind,source={self.out},target=/results', mounts)
        self.assertEqual(self.old_status.read_text(), 'preserved Android 16 evidence')
        self.assertTrue((self.out / 'run-input.json').is_file())

    def test_google_mount_is_frozen_read_only_and_has_no_host_path_in_receipt(self):
        self.out.mkdir(parents=True)
        snapshot=self.root/'snapshot';(snapshot/'image').mkdir(parents=True)
        (snapshot/'image/google-services.lock.json').write_text('{}')
        (snapshot/'input-manifest.json').write_text(json.dumps({'files':[]}))
        mounted={'path':'/private/native-cache','commit':'c'*40,'lockSha256':'d'*64,
                 'mountPath':'/src/vendor/gapps','readOnly':True}
        with mock.patch.multiple(builder,ROOT=self.root,OUT=self.out), \
                mock.patch.object(builder.subprocess,'run',return_value=mock.Mock(returncode=1)), \
                mock.patch.object(builder.subprocess,'check_output',return_value='b'*40), \
                mock.patch.object(builder.subprocess,'Popen'), \
                mock.patch.object(builder,'run') as commands, \
                mock.patch.object(builder,'ensure_report_server'), mock.patch.object(builder,'state'), \
                mock.patch('runpy.run_path',return_value={'build_mount':lambda s,r:mounted}):
            builder.start(snapshot_input=(snapshot,'a'*64))
        launches=[call.args for call in commands.call_args_list if call.args[:2]==('docker','run')]
        self.assertIn('type=bind,source=/private/native-cache,target=/src/vendor/gapps,readonly',launches[0])
        inputs=json.loads((self.out/'run-input.json').read_text())
        self.assertEqual(inputs['googleServices'],{k:v for k,v in mounted.items() if k!='path'})

    def test_snapshot_carries_build_config_and_patch_without_host_scripts(self):
        files = {
            'Android.bp': 'fixture product',
            'image/build-config.json': (ROOT / 'image/build-config.json').read_text(),
            'image/apply-build-workarounds.py': (ROOT / 'image/apply-build-workarounds.py').read_text(),
            'image/runtime-assets.lock.json': '{"assets": []}',
            'system/assistant/markdown-dependencies.json': '{"assets": []}',
            'system/inputmethod/rom-input-lock.json': json.dumps({'sourceInputs': {}, 'path': 'out/fixture-ime.apk', 'sha256': hashlib.sha256(b'fixture IME').hexdigest(), 'jni': []}),
            'out/fixture-ime.apk': 'fixture IME',
            'system/audio-engine/rom-input-lock.json': json.dumps({'sourceInputs': {'scripts/audio-build-fixture.sh': hashlib.sha256(b'audio compiler input').hexdigest()}, 'assets': [{'path': 'system/audio-engine/prebuilt/models/fixture.onnx', 'sha256': hashlib.sha256(b'audio model').hexdigest()}]}),
            'system/audio-engine/prebuilt/models/fixture.onnx': 'audio model',
            'tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java': 'package org.agentos.runtime;',
            'scripts/audio-build-fixture.sh': 'audio compiler input',
            'scripts/image_build_config.py': 'host only',
            'docs/user-notes.md': 'unrelated work',
            'system/agent-app/scripts/export-reply-contract.ts': (
                'const fs=require("node:fs"),p=require("node:path");'
                'const target=p.resolve(__dirname,"../../assistant/out/reply-assets/native/reply-contract.json");'
                'fs.mkdirSync(p.dirname(target),{recursive:true});'
                'fs.writeFileSync(target,JSON.stringify({schema:"taku.reply-contract/1",fixture:true}));'),
        }
        for name, content in files.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        listing = ('\0'.join(files) + '\0').encode()
        with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                mock.patch.object(builder.subprocess, 'check_output', return_value=listing), \
                mock.patch.object(builder.runpy, 'run_path', return_value={'validate_inputs': mock.Mock()}) as validator:
            snapshot, _ = builder.snapshot()
        self.assertEqual(validator.call_args_list, [
            mock.call(str(self.root / 'image/agent_ui.py')),
            mock.call(str(self.root / 'system/audio-engine/prepare-rom.py')),
        ])
        records = json.loads((snapshot / 'input-manifest.json').read_text())['files']
        paths = {record['path'] for record in records}
        self.assertIn('image/build-config.json', paths)
        self.assertIn('image/apply-build-workarounds.py', paths)
        self.assertIn('scripts/audio-build-fixture.sh', paths)
        self.assertIn('system/audio-engine/prebuilt/models/fixture.onnx', paths)
        self.assertIn('tooling/app-kit/apk-shell/src/org/agentos/runtime/AppApiClient.java', paths)
        self.assertNotIn('scripts/image_build_config.py', paths)
        self.assertNotIn('docs/user-notes.md', paths)
        for record in records:
            self.assertEqual(builder.digest(snapshot / record['path']), record['sha256'])

    def test_verification_preserves_the_existing_candidate_build_number(self):
        snapshot = self.out / 'snapshots' / ('a' * 64)
        snapshot.mkdir(parents=True)
        (snapshot / 'input-manifest.json').write_text(json.dumps({'files': []}))
        (self.out / 'run-input.json').write_text(json.dumps({'buildNumber': '20261020024900'}))
        with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                mock.patch.object(builder.subprocess, 'run', return_value=mock.Mock(returncode=1)), \
                mock.patch.object(builder.subprocess, 'check_output', side_effect=['b' * 40, b'']), \
                mock.patch.object(builder.subprocess, 'Popen'), \
                mock.patch.object(builder, 'run'), \
                mock.patch.object(builder, 'ensure_report_server'), \
                mock.patch.object(builder, 'state'):
            builder.start(verify=True, snapshot_input=(snapshot, 'a' * 64))
        self.assertEqual(json.loads((self.out / 'run-input.json').read_text())['buildNumber'],
                         '20261020024900')

    def lifecycle_fixture(self):
        snapshot = self.out / 'snapshots' / ('a' * 64)
        snapshot.mkdir(parents=True)
        (snapshot / 'input-manifest.json').write_text(json.dumps({'files': []}))
        (self.out / 'run-input.json').write_text(json.dumps({'buildNumber': '20261019233845'}))
        (self.out / 'status.json').write_text(json.dumps({'romBuilt': False, 'phase': 'failed-exit-1'}))
        for name in ['build.log', 'resolved-manifest.xml', 'resolved-kernel-manifest.xml',
                     'kernel-artifacts.json', 'build-workarounds.json']:
            (self.out / name).write_text('preserved failed attempt: ' + name)
        return snapshot

    def test_resume_retains_build_number_and_archives_failed_attempt(self):
        snapshot = self.lifecycle_fixture()
        for name in builder.PER_RUN_OUTPUTS:
            (self.out / name).write_text('same candidate evidence: ' + name)
        previous = {p.name: p.read_bytes() for p in self.out.iterdir() if p.is_file()}
        info = {'Id': '3e6719b3d817' + '0' * 52,
                'State': {'Running': False, 'ExitCode': 1},
                'HostConfig': {'NanoCpus': 8_000_000_000, 'Memory': 36 * 1024**3,
                               'MemorySwap': 40 * 1024**3}}
        with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                mock.patch.object(builder.subprocess, 'run', return_value=mock.Mock(returncode=1)), \
                mock.patch.object(builder.subprocess, 'check_output',
                                  side_effect=[json.dumps([info]).encode(), 'b' * 40, b'']), \
                mock.patch.object(builder.subprocess, 'Popen'), \
                mock.patch.object(builder, 'snapshot', return_value=(snapshot, 'a' * 64)), \
                mock.patch.object(builder, 'run') as commands, \
                mock.patch.object(builder, 'ensure_report_server'), \
                mock.patch.object(builder, 'state'):
            builder.retry(resume=True)
        recorded = json.loads((self.out / 'run-input.json').read_text())
        self.assertEqual(recorded['buildNumber'], '20261019233845')
        self.assertTrue(recorded['resumeCompile'])
        self.assertFalse(recorded['incrementalIntegration'])
        history = self.out / 'attempts' / info['Id'][:12]
        for name, content in previous.items():
            self.assertEqual((history / name).read_bytes(), content)
        for name in builder.PER_RUN_OUTPUTS:
            self.assertEqual((self.out / name).read_bytes(), previous[name])
        commands.assert_any_call('docker', 'rename', config.NAME,
                                 config.NAME + '-failed-' + info['Id'][:12])
        launch = next(c.args for c in commands.call_args_list if c.args[:2] == ('docker', 'run'))
        self.assertEqual(launch[-1], 'bash /taku/image/build.sh --resume > /results/build.log 2>&1')
        self.assertIn(f'type=volume,source={config.VOLUME},target=/src', launch)
        self.assertEqual((snapshot / 'input-manifest.json').read_text(), json.dumps({'files': []}))

    def test_new_retry_and_rebuild_allocate_new_build_number(self):
        snapshot = self.lifecycle_fixture()
        expected = '20261020053000'
        now = datetime.datetime(2026, 10, 20, 5, 30, tzinfo=datetime.timezone.utc)
        info = {'Id': 'c' * 64, 'State': {'Running': False, 'ExitCode': 1}, 'HostConfig': {}}
        for action in ['start', 'retry', 'rebuild']:
            with self.subTest(action=action):
                info['Id'] = hashlib.sha256(action.encode()).hexdigest()
                (self.out / 'run-input.json').write_text(json.dumps({'buildNumber': '20261019233845'}))
                outputs = ['b' * 40, b'']
                if action != 'start': outputs.insert(0, json.dumps([info]).encode())
                with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                        mock.patch.object(builder.subprocess, 'run', return_value=mock.Mock(returncode=1)), \
                        mock.patch.object(builder.subprocess, 'check_output', side_effect=outputs), \
                        mock.patch.object(builder.subprocess, 'Popen'), \
                        mock.patch.object(builder, 'snapshot', return_value=(snapshot, 'a' * 64)), \
                        mock.patch.object(builder.datetime, 'datetime') as clock, \
                        mock.patch.object(builder, 'run'), \
                        mock.patch.object(builder, 'ensure_report_server'), \
                        mock.patch.object(builder, 'state'):
                    clock.now.return_value = now
                    getattr(builder, action)()
                recorded = json.loads((self.out / 'run-input.json').read_text())
                self.assertEqual(recorded['buildNumber'], expected)
                self.assertFalse(recorded['resumeCompile'])
                self.assertEqual(recorded['incrementalIntegration'], action == 'rebuild')

    def test_new_candidate_seals_and_retires_only_run_outputs(self):
        self.lifecycle_fixture()
        for name in builder.PER_RUN_OUTPUTS:
            (self.out / name).write_text('old candidate proof: ' + name)
        previous={name:(self.out/name).read_bytes() for name in builder.RUN_EVIDENCE if (self.out/name).exists()}
        history=self.out/'attempts'/'failed-fixture'
        with mock.patch.object(builder,'OUT',self.out):
            builder.archive_run_evidence({'Id':'a'*64},history,new_candidate=True)
        seal=json.loads((history/'evidence-manifest.json').read_text())
        self.assertEqual(seal['containerId'],'a'*64)
        for record in seal['files']:
            self.assertEqual((history/record['path']).read_bytes(),previous[record['path']])
            self.assertEqual(record['sha256'],builder.digest(history/record['path']))
        for name in builder.PER_RUN_OUTPUTS:self.assertFalse((self.out/name).exists())
        for name in set(previous)-set(builder.PER_RUN_OUTPUTS):
            self.assertEqual((self.out/name).read_bytes(),previous[name])

    def test_corrupt_or_mismatched_sealed_archive_never_retires_live_outputs(self):
        self.lifecycle_fixture()
        proof=self.out/'updater-integration.json';proof.write_text('old v2 proof')
        history=self.out/'attempts'/'failed-fixture'
        with mock.patch.object(builder,'OUT',self.out):
            builder.archive_run_evidence({'Id':'a'*64},history)
            saved=history/proof.name;saved.write_text('corrupted archive')
            with self.assertRaisesRegex(SystemExit,'changed during archival'):
                builder.archive_run_evidence({'Id':'a'*64},history,new_candidate=True)
            self.assertEqual(proof.read_text(),'old v2 proof')
            saved.write_text('old v2 proof');proof.write_text('different current proof')
            with self.assertRaisesRegex(SystemExit,'archive differs'):
                builder.archive_run_evidence({'Id':'a'*64},history,new_candidate=True)
        self.assertEqual(proof.read_text(),'different current proof')

    def test_archive_copy_or_sync_failure_keeps_all_live_evidence(self):
        self.lifecycle_fixture()
        proof=self.out/'updater-integration.json';proof.write_text('preserved v2 proof')
        previous={p.name:p.read_bytes() for p in self.out.iterdir() if p.is_file()}
        for operation in ('copy','fsync'):
            history=self.out/'attempts'/operation
            target=mock.patch.object(builder.shutil,'copyfileobj',side_effect=OSError('disk full')) if operation=='copy' else mock.patch.object(builder,'fsync_directory',side_effect=OSError('sync failed'))
            with self.subTest(operation=operation),mock.patch.object(builder,'OUT',self.out),target:
                with self.assertRaises(OSError):
                    builder.archive_run_evidence({'Id':'a'*64},history,new_candidate=True)
            for name,raw in previous.items():self.assertEqual((self.out/name).read_bytes(),raw)

    def test_existing_complete_archive_must_resync_after_first_failed_seal(self):
        self.lifecycle_fixture()
        proof=self.out/'updater-integration.json';original='durability is required';proof.write_text(original)
        real_fsync=builder.os.fsync
        for stage in ('seal','directory'):
            history=self.out/'attempts'/stage
            def fail_seal(fd):
                seal=history/'evidence-manifest.json'
                if seal.exists() and builder.os.fstat(fd).st_ino==seal.stat().st_ino:
                    raise OSError('seal fsync failed')
                return real_fsync(fd)
            def failure():
                return mock.patch.object(builder.os,'fsync',side_effect=fail_seal) if stage=='seal' else mock.patch.object(builder,'fsync_directory',side_effect=OSError('directory fsync failed'))
            with self.subTest(stage=stage),mock.patch.object(builder,'OUT',self.out):
                for attempt in range(2):
                    with failure(),self.assertRaises(OSError):
                        builder.archive_run_evidence({'Id':'a'*64},history,new_candidate=True)
                    self.assertTrue((history/'evidence-manifest.json').is_file())
                    self.assertEqual(proof.read_text(),original)
                builder.archive_run_evidence({'Id':'a'*64},history,new_candidate=True)
                self.assertFalse(proof.exists())
                self.assertEqual((history/proof.name).read_text(),original)
                # Next stage gets its own complete source state and archive.
                proof.write_text(original)

    def test_direct_new_start_rejects_unretired_proof_before_any_mutation(self):
        self.lifecycle_fixture()
        proof=self.out/'updater-integration.json';proof.write_text('previous candidate proof')
        inputs=(self.out/'run-input.json').read_bytes()
        with mock.patch.object(builder,'OUT',self.out),mock.patch.object(builder.subprocess,'run',return_value=mock.Mock(returncode=1)),mock.patch.object(builder,'run') as commands:
            with self.assertRaisesRegex(SystemExit,'sealed and retired'):
                builder.start()
            commands.assert_not_called()
        self.assertEqual(proof.read_text(),'previous candidate proof')
        self.assertEqual((self.out/'run-input.json').read_bytes(),inputs)

    def test_resume_refuses_missing_or_invalid_recorded_number_before_launch(self):
        self.out.mkdir(parents=True)
        status = self.out / 'status.json'
        status.write_text('preserve previous failure')
        for record in [{}, {'buildNumber': None}, {'buildNumber': 20261019233845},
                       {'buildNumber': ''}, {'buildNumber': 'not-a-build-number'}]:
            with self.subTest(record=record):
                inputs = self.out / 'run-input.json'
                inputs.write_text(json.dumps(record))
                with mock.patch.object(builder, 'OUT', self.out), \
                        mock.patch.object(builder.subprocess, 'run', return_value=mock.Mock(returncode=1)), \
                        mock.patch.object(builder, 'run') as commands, \
                        mock.patch.object(builder, 'snapshot') as snapshot:
                    with self.assertRaisesRegex(SystemExit, 'recorded build number'):
                        builder.start(resume=True)
                commands.assert_not_called()
                snapshot.assert_not_called()
                self.assertEqual(json.loads(inputs.read_text()), record)
                self.assertEqual(status.read_text(), 'preserve previous failure')

    def test_same_source_rebuilds_export_separate_baselines_with_evidence(self):
        snapshot = self.out / 'snapshots' / ('a' * 64)
        (snapshot / 'image').mkdir(parents=True)
        for name in ['input-manifest.json', 'image/source-lock.json', 'image/kernel-source-lock.json']:
            (snapshot / name).write_text('{}\n')
        validator = snapshot / 'verify-signatures.py'
        validator.write_text('# immutable verifier fixture\n')
        for name in ['resolved-manifest.xml', 'resolved-kernel-manifest.xml',
                     'kernel-artifacts.json', 'build-workarounds.json']:
            (self.out / name).write_text('baseline evidence\n')
        artifact = self.out / 'fixture-target-files.zip'
        artifact.write_bytes(b'build candidate fixture')
        state = {'romBuilt': True, 'artifacts': [{'path': 'out/target-files.zip',
                                                 'sha256': builder.digest(artifact)}]}
        for number in ['20261020024900', '20261020035000']:
            (self.out / 'run-input.json').write_text(json.dumps({
                'buildNumber': number, 'snapshotDigest': 'a' * 64, 'snapshotPath': str(snapshot)}))
            receipt = self.out / 'baseline-receipt.json'
            receipt_data={'buildNumber': number,'runInputSha256': builder.digest(self.out / 'run-input.json')}
            if number=='20261020035000':
                receipt_data['systemAiIntegration']={'syntheticCopyBoundaryOnly':True}
                for path in (self.out/'system-ai-rom.json',snapshot/'image/system-ai-handoff.json',
                             snapshot/'system/inputmethod/rom-input-lock.json',snapshot/'image/verify-system-ai.py'):
                    path.parent.mkdir(parents=True,exist_ok=True);path.write_text('fixture '+path.name)
            receipt.write_text(json.dumps(receipt_data))
            state['baselineReceipt'] = {'sha256': builder.digest(receipt)}
            (self.out / 'status.json').write_text(json.dumps(state))
            crypto = self.out / 'crypto' / number
            crypto.mkdir(parents=True)
            (crypto / 'signature-verification.json').write_text(json.dumps({'buildNumber': number}))
            def copy_artifact(*args):
                self.assertEqual(args[:2], ('docker', 'cp'))
                shutil.copy2(artifact, args[-1])
            with mock.patch.object(builder, 'OUT', self.out), \
                    mock.patch.object(builder.subprocess, 'check_output', return_value=json.dumps([
                        {'State': {'Running': False, 'ExitCode': 0}}]).encode()), \
                    mock.patch.object(builder, 'run', side_effect=copy_artifact), \
                    mock.patch.object(builder, 'verify_signatures', return_value=(crypto, validator)), \
                    mock.patch('builtins.print'):
                builder.export()
        deliveries = sorted((self.out / 'artifacts').iterdir())
        self.assertEqual(len(deliveries), 2)
        for directory, number in zip(deliveries, ['20261020024900', '20261020035000']):
            self.assertTrue(directory.name.startswith(number + '-'))
            self.assertEqual(json.loads((directory / 'baseline-receipt.json').read_text())['buildNumber'], number)
            self.assertEqual((directory / 'target-files.zip').read_bytes(), artifact.read_bytes())
            self.assertTrue((directory / 'source-lock.json').is_file())
            self.assertTrue((directory / 'resolved-manifest.xml').is_file())
            self.assertTrue((directory / 'signature-verification/verify-signatures.py').is_file())
            for name in ('system-ai-rom.json','system-ai-handoff.json','rom-input-lock.json','verify-system-ai.py'):
                self.assertEqual((directory/name).exists(),number=='20261020035000')

    def export_fixture(self):
        snapshot = self.out / 'snapshots' / ('a' * 64)
        (snapshot / 'image').mkdir(parents=True)
        for name in ['input-manifest.json', 'image/source-lock.json', 'image/kernel-source-lock.json']:
            (snapshot / name).write_text('{}\n')
        validator = snapshot / 'verify-signatures.py'
        validator.write_text('# immutable verifier fixture\n')
        for name in ['resolved-manifest.xml', 'resolved-kernel-manifest.xml',
                     'kernel-artifacts.json', 'build-workarounds.json']:
            (self.out / name).write_text('baseline evidence\n')
        artifact = self.out / 'fixture-target-files.zip'
        artifact.write_bytes(b'complete candidate fixture')
        number = '20261020024900'
        (self.out / 'run-input.json').write_text(json.dumps({
            'buildNumber': number, 'snapshotDigest': 'a' * 64, 'snapshotPath': str(snapshot)}))
        receipt = self.out / 'baseline-receipt.json'
        receipt.write_text(json.dumps({'runInputSha256': builder.digest(self.out / 'run-input.json')}))
        (self.out / 'status.json').write_text(json.dumps({
            'romBuilt': True, 'baselineReceipt': {'sha256': builder.digest(receipt)},
            'artifacts': [{'path': 'out/target-files.zip', 'sha256': builder.digest(artifact)}]}))
        crypto = self.out / 'crypto' / number
        crypto.mkdir(parents=True)
        (crypto / 'signature-verification.json').write_text(json.dumps({'buildNumber': number}))
        for patcher in [mock.patch.object(builder, 'OUT', self.out),
                        mock.patch.object(builder.subprocess, 'check_output', return_value=json.dumps([
                            {'State': {'Running': False, 'ExitCode': 0}}]).encode()),
                        mock.patch.object(builder, 'verify_signatures', return_value=(crypto, validator)),
                        mock.patch('builtins.print')]:
            patcher.start()
            self.addCleanup(patcher.stop)
        return artifact, self.out / 'artifacts' / (number + '-aaaaaaaaaaaaaaaa')

    def test_export_retries_interrupted_copy_then_reuses_complete_delivery(self):
        artifact, directory = self.export_fixture()
        target = directory / 'target-files.zip'
        copies = []
        def copy(*args):
            copies.append(args)
            self.assertEqual(args[:2], ('docker', 'cp'))
            self.assertEqual(pathlib.Path(args[-1]).parent, directory)
            self.assertNotEqual(pathlib.Path(args[-1]), target)
            if len(copies) == 1:
                pathlib.Path(args[-1]).write_bytes(b'partial copy')
                raise builder.subprocess.CalledProcessError(1, args)
            shutil.copy2(artifact, args[-1])
        with mock.patch.object(builder, 'run', side_effect=copy):
            with self.assertRaises(builder.subprocess.CalledProcessError):
                builder.export()
            self.assertFalse(target.exists())
            self.assertEqual(pathlib.Path(copies[0][-1]).read_bytes(), b'partial copy')
            builder.export()
            self.assertEqual(target.read_bytes(), artifact.read_bytes())
            self.assertFalse(pathlib.Path(copies[0][-1]).exists())
            before = {p.relative_to(directory): (builder.digest(p), p.stat().st_mtime_ns, p.stat().st_ino)
                      for p in directory.rglob('*') if p.is_file()}
            builder.export()
            after = {p.relative_to(directory): (builder.digest(p), p.stat().st_mtime_ns, p.stat().st_ino)
                     for p in directory.rglob('*') if p.is_file()}
        self.assertEqual(len(copies), 2)
        self.assertEqual(before, after)

    def test_export_preserves_and_rejects_existing_mismatched_delivery(self):
        _, directory = self.export_fixture()
        directory.mkdir(parents=True)
        target = directory / 'target-files.zip'
        target.write_bytes(b'previous complete delivery must be preserved')
        before = (target.read_bytes(), target.stat().st_mtime_ns, target.stat().st_ino)
        with mock.patch.object(builder, 'run') as copier:
            with self.assertRaisesRegex(SystemExit, 'Existing export differs'):
                builder.export()
        copier.assert_not_called()
        self.assertEqual((target.read_bytes(), target.stat().st_mtime_ns, target.stat().st_ino), before)

    def test_export_does_not_publish_copy_with_wrong_hash(self):
        _, directory = self.export_fixture()
        def copy(*args):
            pathlib.Path(args[-1]).write_bytes(b'truncated copy despite exit code zero')
        with mock.patch.object(builder, 'run', side_effect=copy):
            with self.assertRaisesRegex(SystemExit, 'hash mismatch'):
                builder.export()
        self.assertFalse((directory / 'target-files.zip').exists())

    def test_export_retries_interrupted_evidence_copy_without_recopying_artifact(self):
        artifact, directory = self.export_fixture()
        real_copy = shutil.copy2
        interrupted = False
        def copy_evidence(source, target):
            nonlocal interrupted
            if pathlib.Path(source).name == 'baseline-receipt.json' and not interrupted:
                interrupted = True
                pathlib.Path(target).write_bytes(b'partial receipt')
                raise OSError('injected evidence copy interruption')
            return real_copy(source, target)
        with mock.patch.object(builder, 'run', side_effect=lambda *args: real_copy(artifact, args[-1])) as copier, \
                mock.patch.object(builder.shutil, 'copy2', side_effect=copy_evidence):
            with self.assertRaisesRegex(OSError, 'injected evidence copy interruption'):
                builder.export()
            self.assertFalse((directory / 'baseline-receipt.json').exists())
            self.assertEqual((directory / 'target-files.zip').read_bytes(), artifact.read_bytes())
            builder.export()
        self.assertEqual(copier.call_count, 1)
        self.assertEqual((directory / 'baseline-receipt.json').read_bytes(),
                         (self.out / 'baseline-receipt.json').read_bytes())
        self.assertFalse(list(directory.rglob('*.partial')))

    def test_signature_validation_uses_read_only_volumes_and_bound_proof(self):
        (self.root / 'image').mkdir()
        (self.root / 'image/verify-signatures.py').write_text('# verifier fixture\n')
        self.out.mkdir(parents=True)
        (self.out / 'baseline-receipt.json').write_text('{}\n')
        inputs = {'buildNumber': '20261020024900', 'snapshotDigest': 'a' * 64,
                  'volume': config.VOLUME, 'referenceVolume': config.REFERENCE,
                  'previousSourceVolume': config.PREVIOUS, 'builderImage': config.IMAGE}
        (self.out / 'run-input.json').write_text(json.dumps(inputs))
        expected = self.out / 'crypto/20261020024900-aaaaaaaaaaaaaaaa'
        def emit_report(*args, **kwargs):
            mounts = [args[i + 1] for i, arg in enumerate(args) if arg == '--mount']
            for target in ['/src', '/reference', '/previous']:
                self.assertTrue(any(mount.endswith('target=' + target + ',readonly') for mount in mounts))
            self.assertIn('--network=none', args)
            (expected / 'signature-verification.json').write_text(json.dumps({
                'schema': 'taku.image-signatures/1',
                'baselineReceiptSha256': builder.digest(self.out / 'baseline-receipt.json'),
                'runInputSha256': builder.digest(self.out / 'run-input.json'),
                'checks': {name: True for name in ['otaPackageSignatureVerified',
                          'payloadSignatureVerified', 'avbSignatureVerified']}}))
        with mock.patch.multiple(builder, ROOT=self.root, OUT=self.out), \
                mock.patch.object(builder, 'run', side_effect=emit_report):
            evidence, frozen = builder.verify_signatures(inputs)
        self.assertEqual(evidence, expected)
        self.assertEqual(frozen.read_bytes(), (self.root / 'image/verify-signatures.py').read_bytes())


class ConfigFailureGates(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        (self.root / 'image').mkdir()
        self.script = self.root / 'scripts/image_build_config.py'
        shutil.copy2(ROOT / 'scripts/image_build_config.py', self.script)
        shutil.copy2(ROOT / 'image/source-lock.json', self.root / 'image/source-lock.json')

    def reject(self, changes, message):
        data = dict(config.CONFIG)
        data.update(changes)
        (self.root / 'image/build-config.json').write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, message):
            runpy.run_path(str(self.script))

    def test_source_and_both_cache_volumes_must_be_distinct(self):
        for changes in [{'sourceVolume': config.REFERENCE}, {'sourceVolume': config.PREVIOUS},
                        {'previousSourceVolume': config.REFERENCE}]:
            with self.subTest(changes=changes):
                self.reject(changes, 'must differ')

    def test_unpinned_builder_image_is_rejected(self):
        self.reject({'builderImage': 'taku-builder:latest'}, 'pinned by digest')

    def test_output_cannot_escape_project_or_enter_source_directories(self):
        for path in ['/tmp/build-output', '../out/build', 'out/../../old', 'system/output']:
            with self.subTest(path=path):
                self.reject({'outputDirectory': path}, 'Invalid builder path')


if __name__ == '__main__':
    unittest.main()
