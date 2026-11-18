"""Fixture-only shell process checks; no adb, actual /proc scan, or device writes."""
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


BROKER = 'org.agentos.lab.TaskBroker'
DISPLAY = 'org.agentos.lab.VirtualDisplayHost'
COMMON = Path(__file__).resolve().parent / 'device' / 'common.sh'


class ProcessProbeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='taku-process-probe-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.proc = self.root / 'proc'
        self.proc.mkdir()
        self.stage = self.root / 'stage'
        self.stage.mkdir()
        self.display = self.root / 'display'
        self.display.mkdir()
        self.script = self.root / 'common.sh'
        # Redirect the source's literal /proc paths into fixtures. Production has
        # no environment override that could bypass real executable/UID checks.
        self.script.write_text(COMMON.read_text().replace('/proc/', str(self.proc) + '/'))

    def process(self, pid, args, executable='/system/bin/app_process64', uid=0):
        directory = self.proc / str(pid)
        directory.mkdir()
        (directory / 'cmdline').write_bytes(('\0'.join(args) + '\0').encode())
        if executable is not None:
            (directory / 'exe').symlink_to(executable)
        (directory / 'status').write_text(f'Name:\tfixture\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\n')

    def shell(self, command):
        prelude = ('. ' + shlex.quote(str(self.script)) + '\n' +
                   'STAGE=' + shlex.quote(str(self.stage)) + '\n' +
                   'DISPLAY_DIR=' + shlex.quote(str(self.display)) + '\n')
        return subprocess.run(['/bin/sh', '-c', prelude + command],
                              capture_output=True, text=True, timeout=10)

    def assert_ok(self, command, output=None):
        result = self.shell(command)
        self.assertEqual(result.returncode, 0, result.stderr)
        if output is not None:
            self.assertEqual(result.stdout, output)
        return result

    def assert_failure(self, command, reason=None):
        result = self.shell(command)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        if reason:
            self.assertIn('CHECK_FAIL:' + reason, result.stderr)
        return result

    def test_reported_grep_false_positive_is_reproduced_and_excluded(self):
        self.process(13309, ['grep', '-Fxq', '--', BROKER, ''], '/system/bin/toybox')
        self.assert_ok(f'process_has_arg 13309 {BROKER}')
        self.assert_failure(f'class_process 13309 {BROKER}')
        self.assert_ok(f'class_pids {BROKER}', '')

    def test_actual_system_app_process_variants_and_classes_match(self):
        for pid, executable, name in [(101, '/system/bin/app_process', BROKER),
                                      (102, '/system/bin/app_process32', BROKER),
                                      (103, '/system/bin/app_process64', DISPLAY)]:
            self.process(pid, ['app_process', '/system/bin', name, ''], executable)
            self.assert_ok(f'class_process {pid} {name}')
        self.assert_ok(f'class_pids {BROKER}', '101\n102\n')
        self.assert_ok(f'class_pids {DISPLAY}', '103\n')

    def test_argv_spoofs_and_concurrent_probes_are_excluded(self):
        for pid, args, executable in [
            (201, ['app_process', '/system/bin', BROKER], '/system/bin/toybox'),
            (202, ['sh', '/system/bin', BROKER], '/system/bin/sh'),
            (203, ['awk', '-v', 'expected=' + BROKER], '/system/bin/toybox'),
            (204, ['app_process', '/system/bin', BROKER], '/data/local/tmp/app_process64'),
            (205, ['app_process', '/system/bin', BROKER], '/system/bin/app_process64 (deleted)'),
        ]:
            self.process(pid, args, executable)
        self.assert_ok(f'class_pids {BROKER}', '')

    def test_wrong_launch_directory_or_class_position_is_excluded(self):
        for pid, args in [
            (301, ['app_process', '/data/local/tmp', BROKER]),
            (302, ['app_process', '/system/bin', 'org.example.OtherMain', '/system/bin', BROKER]),
            (303, ['app_process', '/system/bin', DISPLAY]),
            (304, ['app_process', '/system/bin']),
        ]:
            self.process(pid, args)
        self.assert_ok(f'class_pids {BROKER}', '')

    def test_dead_missing_or_invalid_pid_never_matches(self):
        self.process(401, ['app_process', '/system/bin', BROKER], executable=None)
        self.process(402, ['app_process', '/system/bin', BROKER])
        (self.proc / '402' / 'cmdline').unlink()
        for pid in ['401', '402', '999', "''", 'not-a-pid', '../401']:
            with self.subTest(pid=pid):
                self.assert_failure(f'class_process {pid} {BROKER}')
        self.assert_ok(f'class_pids {BROKER}', '')

    def test_broker_check_ignores_grep_and_accepts_real_root_process(self):
        self.process(501, ['app_process', '/system/bin', BROKER])
        self.process(502, ['grep', '-Fxq', '--', BROKER], '/system/bin/toybox')
        (self.stage / 'broker.pid').write_text('501\n')
        self.assert_ok('check_broker')

    def test_broker_root_and_pidfile_requirements_remain_enforced(self):
        self.process(601, ['app_process', '/system/bin', BROKER], uid=2000)
        (self.stage / 'broker.pid').write_text('601\n')
        self.assert_failure('check_broker', 'broker_not_root')
        (self.proc / '601' / 'status').write_text('Uid:\t0\t0\t0\t0\n')
        (self.stage / 'broker.pid').write_text('999\n')
        self.assert_failure('check_broker', 'broker_pidfile')

    def test_broker_missing_and_duplicate_processes_remain_refused(self):
        self.process(701, ['grep', '-Fxq', '--', BROKER], '/system/bin/toybox')
        self.assert_failure('check_broker', 'broker_count')
        self.process(702, ['app_process', '/system/bin', BROKER])
        self.process(703, ['app_process', '/system/bin', BROKER])
        self.assert_failure('check_broker', 'broker_count')

    def display_metadata(self, pid):
        (self.display / 'display.id').write_text('7\n')
        (self.display / 'display.json').write_text('{\n"displayId": 7,\n"pid": ' + str(pid) + '\n}\n')

    def test_display_host_uses_same_executable_and_argv_binding(self):
        self.process(801, ['app_process', '/system/bin', DISPLAY])
        self.display_metadata(801)
        self.assert_ok("cmd() { printf '7\\n'; }; check_display")
        (self.proc / '801' / 'exe').unlink()
        (self.proc / '801' / 'exe').symlink_to('/system/bin/toybox')
        self.assert_failure("cmd() { printf '7\\n'; }; check_display", 'display_host')

    def test_display_root_and_live_id_requirements_remain_enforced(self):
        self.process(901, ['app_process', '/system/bin', DISPLAY], uid=2000)
        self.display_metadata(901)
        self.assert_failure("cmd() { printf '7\\n'; }; check_display", 'display_host_not_root')
        (self.proc / '901' / 'status').write_text('Uid:\t0\t0\t0\t0\n')
        self.assert_failure("cmd() { printf '8\\n'; }; check_display", 'display_missing')

    def test_restore_uses_bound_display_pid_check(self):
        restore = (COMMON.parent / 'restore.sh').read_text()
        self.assertIn('if class_process "$prior_display_pid" ' + DISPLAY + '; then', restore)
        self.assertNotIn('if process_has_arg "$prior_display_pid" ' + DISPLAY, restore)


if __name__ == '__main__':
    unittest.main()
