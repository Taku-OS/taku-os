"""Mock-only checks: no adb, launchctl, emulator or account interaction."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import plistlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import watch
import install as installer

BOOT1 = '11111111-1111-1111-1111-111111111111'
BOOT2 = '22222222-2222-2222-2222-222222222222'


class Device:
    def __init__(self, config):
        self.config = config
        self.online = True
        self.avd = watch.AVD
        self.boot_completed = '1'
        self.user = 'RUNNING_UNLOCKED'
        self.uid = '0'
        self.boot = BOOT1
        self.healthy = True
        self.bad_hash = None
        self.restore_fails = False
        self.restore_timeout = False
        self.restore_leaves_unhealthy = False
        self.calls = []

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        args = command[3:]
        code, output = 0, ''
        if args == ['get-state']:
            code, output = (0, 'device') if self.online else (1, '')
        elif args == ['emu', 'avd', 'name']:
            output = self.avd + '\nOK'
        elif args == ['shell', 'getprop', 'sys.boot_completed']:
            output = self.boot_completed
        elif args == ['shell', 'am', 'get-started-user-state', '0']:
            output = self.user
        elif args == ['shell', 'id', '-u']:
            output = self.uid
        elif args == ['root']:
            self.uid = '0'
        elif args == ['wait-for-device']:
            pass
        elif args == ['shell', 'cat', '/proc/sys/kernel/random/boot_id']:
            output = self.boot
        elif args[:2] == ['shell', 'sha256sum']:
            output = ('0' * 64 if args[2] == self.bad_hash else
                      self.config['deviceFiles'][args[2]]) + '  ' + args[2]
        elif args == ['shell', 'sh', self.config['healthScript']]:
            code = 0 if self.healthy else 1
        elif args == ['shell', 'sh', self.config['restoreScript']]:
            if self.restore_timeout:
                raise subprocess.TimeoutExpired(command, kwargs['timeout'])
            if self.restore_fails:
                code = 1
            elif not self.restore_leaves_unhealthy:
                self.healthy = True
        else:
            raise AssertionError(command)
        return subprocess.CompletedProcess(command, code, output, '')

    def restores(self):
        return [call for call in self.calls if call[0][-3:] ==
                ['shell', 'sh', self.config['restoreScript']]]


class Launchd:
    def __init__(self, base):
        self.base = base
        self.running = False
        self.foreign = False
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append(args)
        if args[1] == 'print':
            arguments = installer.make_plist(self.base)['ProgramArguments']
            if self.foreign:
                arguments = ['/bin/unknown']
            text = 'arguments = {\n' + '\n'.join(arguments) + '\n}\n'
            return subprocess.CompletedProcess(args, 0 if self.running else 113, text, '')
        if args[1] == 'bootstrap':
            self.running = True
        elif args[1] == 'bootout':
            self.running = False
        else:
            raise AssertionError(args)
        return subprocess.CompletedProcess(args, 0, '', '')


class WatchTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve()
        adb = self.directory / 'adb'
        adb.write_text('mock only')
        adb.chmod(0o700)
        files = {'/data/local/agentos/dev-runtime/' + name: 'a' * 64 for name in
                 ('common.sh', 'health.sh', 'restore.sh', 'probe.mjs', 'runtime.conf',
                  'assets.sha256', 'targets.sha256', 'self.sha256', 'owner.json')}
        self.config = {'schemaVersion': 1, 'managedBy': watch.MANAGED_BY,
                       'serial': watch.SERIAL, 'avd': watch.AVD, 'adb': str(adb),
                       'restoreScript': '/data/local/agentos/dev-runtime/restore.sh',
                       'healthScript': '/data/local/agentos/dev-runtime/health.sh',
                       'restoreSha256': 'a' * 64, 'healthSha256': 'a' * 64,
                       'deviceFiles': files}
        self.device = Device(self.config)
        self.watcher = watch.RuntimeWatcher(self.config, self.directory / 'state', self.device)

    @patch('watch.now', return_value='2026-10-19T00:00:00+00:00')
    def test_same_boot_healthy_does_not_restore_or_rewrite_state(self, clock):
        self.assertTrue(self.watcher.tick())
        before = self.watcher.path.read_bytes()
        self.assertTrue(self.watcher.tick())
        self.assertEqual(self.device.restores(), [])
        self.assertEqual(self.watcher.path.read_bytes(), before)

    def test_reboot_and_same_boot_service_failure_are_recovered(self):
        self.assertTrue(self.watcher.tick())
        self.device.boot = BOOT2
        self.device.healthy = False
        self.assertTrue(self.watcher.tick())
        self.assertEqual(self.watcher.state['bootId'], BOOT2)
        self.assertEqual(self.watcher.state['restoreCount'], 1)
        self.assertEqual(self.watcher.state['successfulRestoreCount'], 1)
        self.assertIsNotNone(self.watcher.state['restoredAt'])
        self.device.healthy = False
        self.assertTrue(self.watcher.tick())
        self.assertEqual(self.watcher.state['restoreCount'], 2)
        self.assertEqual(self.device.restores()[0][1]['timeout'], 90)

    def test_offline_booting_locked_and_wrong_avd_do_not_root(self):
        for field, value, status in [('online', False, 'offline'),
                                     ('avd', 'Other_AVD', 'wrong-target'),
                                     ('boot_completed', '0', 'booting'),
                                     ('user', 'RUNNING_LOCKED', 'locked')]:
            with self.subTest(field=field):
                device = Device(self.config)
                setattr(device, field, value)
                watcher = watch.RuntimeWatcher(self.config, self.directory / field, device)
                self.assertFalse(watcher.tick())
                self.assertEqual(watcher.state['status'], status)
                self.assertFalse(any(call[0][3:] == ['root'] for call in device.calls))
                self.assertEqual(device.restores(), [])

    def test_root_only_after_avd_boot_and_unlock_checks(self):
        self.device.uid = '2000'
        self.assertTrue(self.watcher.tick())
        args = [call[0][3:] for call in self.device.calls]
        root_at = args.index(['root'])
        self.assertIn(['emu', 'avd', 'name'], args[:root_at])
        self.assertIn(['shell', 'am', 'get-started-user-state', '0'], args[:root_at])
        self.assertEqual(args.count(['emu', 'avd', 'name']), 2)

    def test_all_managed_files_bound_before_execution(self):
        self.device.healthy = False
        self.device.bad_hash = '/data/local/agentos/dev-runtime/common.sh'
        self.assertFalse(self.watcher.tick())
        self.assertEqual(self.device.restores(), [])
        self.assertIn('hash mismatch', self.watcher.state['lastError'])
        self.assertFalse(any(call[0][3:5] == ['shell', 'sh'] for call in self.device.calls))

    def test_timeout_backoff_is_bounded_and_retriable(self):
        self.device.healthy = False
        self.device.restore_timeout = True
        for expected in [10, 20, 40, 60, 60]:
            self.assertFalse(self.watcher.tick())
            self.assertEqual(self.watcher.delay(), expected)
        self.device.restore_timeout = False
        self.assertTrue(self.watcher.tick())
        self.assertEqual(self.watcher.state['restoreCount'], 6)
        self.assertEqual(self.watcher.delay(), 5)

    def test_restore_exit_failure_never_marked_healthy(self):
        self.device.healthy = False
        self.device.restore_fails = True
        self.assertFalse(self.watcher.tick())
        self.assertEqual(self.watcher.state['successfulRestoreCount'], 0)
        self.assertEqual(self.watcher.state['status'], 'error')

    def test_restore_requires_independent_health_success(self):
        self.device.healthy = False
        self.device.restore_leaves_unhealthy = True
        self.assertFalse(self.watcher.tick())
        self.assertEqual(self.watcher.state['successfulRestoreCount'], 0)
        self.assertEqual(self.watcher.state['lastHealth'], 'failed')
        self.assertEqual(self.watcher.state['status'], 'error')

    def test_existing_restore_count_and_state_survive_watcher_restart(self):
        self.device.healthy = False
        self.watcher.tick()
        restarted = watch.RuntimeWatcher(self.config, self.directory / 'state', self.device)
        self.assertEqual(restarted.state['restoreCount'], 1)
        self.assertEqual(restarted.state['restoredBootId'], BOOT1)

    def test_single_instance_lock(self):
        with watch.single_instance(self.directory):
            with self.assertRaises(BlockingIOError):
                with watch.single_instance(self.directory):
                    self.fail('second owner acquired lock')

    def test_invalid_target_or_incomplete_hash_configuration_refused(self):
        for key, value in [('serial', 'emulator-5554'), ('avd', 'Other'),
                           ('restoreScript', '/tmp/untrusted.sh'), ('deviceFiles', {})]:
            config = copy.deepcopy(self.config)
            config[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                watch.validate_config(config)

    def test_launchagent_install_status_uninstall_and_update(self):
        config_file = self.directory / 'input.json'
        config_file.write_text(json.dumps(self.config))
        base = self.directory / 'installed'
        plist = self.directory / 'agents' / 'monitor.plist'
        launchd = Launchd(base)
        result = installer.install(config_file, base, plist, launchd)
        self.assertTrue(result['loaded'])
        definition = plistlib.loads(plist.read_bytes())
        self.assertEqual(definition['ProgramArguments'][0], os.path.realpath(os.sys.executable))
        self.assertTrue(installer.status(base, plist, launchd)['installed'])
        self.config['restoreSha256'] = 'b' * 64
        self.config['deviceFiles'][self.config['restoreScript']] = 'b' * 64
        config_file.write_text(json.dumps(self.config))
        installer.install(config_file, base, plist, launchd)
        self.assertEqual(json.loads((base / 'config.json').read_text())['restoreSha256'], 'b' * 64)
        result = installer.uninstall(base, plist, launchd)
        self.assertFalse(result['loaded'])
        self.assertFalse(plist.exists())
        self.assertTrue((base / 'config.json').exists())
        self.assertFalse(any('adb' in arg for command in launchd.calls for arg in command))

    def test_unknown_disk_configuration_and_loaded_job_refused(self):
        config_file = self.directory / 'input.json'
        config_file.write_text(json.dumps(self.config))
        base = self.directory / 'installed'
        plist = self.directory / 'agents' / 'monitor.plist'
        launchd = Launchd(base)
        launchd.running = True
        with self.assertRaisesRegex(ValueError, 'unknown launchd'):
            installer.install(config_file, base, plist, launchd)
        launchd.running = False
        base.mkdir()
        (base / 'config.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'no managed install manifest'):
            installer.install(config_file, base, plist, launchd)

    def test_foreign_running_job_and_tampered_install_refused(self):
        config_file = self.directory / 'input.json'
        config_file.write_text(json.dumps(self.config))
        base = self.directory / 'installed'
        plist = self.directory / 'agents' / 'monitor.plist'
        launchd = Launchd(base)
        installer.install(config_file, base, plist, launchd)
        launchd.foreign = True
        with self.assertRaisesRegex(ValueError, 'does not match'):
            installer.install(config_file, base, plist, launchd)
        with self.assertRaisesRegex(ValueError, 'does not match'):
            installer.uninstall(base, plist, launchd)
        launchd.foreign = False
        (base / 'config.json').write_text('{}')
        with self.assertRaisesRegex(ValueError, 'changed outside'):
            installer.install(config_file, base, plist, launchd)


if __name__ == '__main__':
    unittest.main()
