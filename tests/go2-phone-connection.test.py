"""Phone configuration tests: persistence, permissions and bounded probes."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, MagicMock

spec = importlib.util.spec_from_file_location('phone_connection', Path(__file__).resolve().parents[1] / 'tooling/go2-lab/phone/connection.py')
connection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(connection)


class PhoneConnectionTests(unittest.TestCase):
    def test_private_key_round_trip_and_replacement(self):
        with tempfile.TemporaryDirectory() as folder:
            store = connection.DeviceKey(Path(folder) / 'keys', '192.168.5.9')
            self.assertIsNone(store.read())
            key = os.urandom(16).hex()
            store.save(key.upper())
            self.assertEqual(store.read(), key)
            self.assertEqual(store.path.stat().st_mode & 0o777, 0o600)
            self.assertEqual(store.path.parent.stat().st_mode & 0o777, 0o700)
            store.save('a' * 32)
            self.assertEqual(store.read(), 'a' * 32)
            self.assertFalse(store.path.with_suffix('.pending').exists())

    def test_keys_are_scoped_to_address(self):
        with tempfile.TemporaryDirectory() as folder:
            connection.DeviceKey(folder, '192.168.5.9').save('a' * 32)
            self.assertIsNone(connection.DeviceKey(folder, '192.168.5.10').read())

    def test_invalid_inputs_never_overwrite_key(self):
        with tempfile.TemporaryDirectory() as folder:
            store = connection.DeviceKey(folder, '10.1.1.9')
            store.save('b' * 32)
            for value in (None, {}, 123, '', 'a' * 31, 'g' * 32, 'a' * 33, ' ' + 'a' * 32):
                with self.assertRaises(ValueError): store.save(value)
                self.assertEqual(store.read(), 'b' * 32)

    def test_unsafe_permissions_are_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            store = connection.DeviceKey(folder, '10.1.1.9')
            store.save('c' * 32)
            store.path.chmod(0o644)
            with self.assertRaisesRegex(ValueError, 'KEY_FILE_PERMISSIONS'): store.read()

    def test_path_traversal_is_rejected(self):
        with self.assertRaises(ValueError): connection.DeviceKey('/tmp', '../../private')

    def test_probe_reports_reachability_not_robot_identity(self):
        with patch.object(connection.socket, 'create_connection', side_effect=[OSError(), MagicMock()]) as connect:
            result = connection.diagnose('192.168.5.9')
            self.assertTrue(result['reachable'])
            self.assertFalse(result['robotConnected'])
            self.assertEqual(result['scope'], 'tcp-only')
            self.assertEqual(result['signalingPorts'], {'9991': False, '8081': True})
            self.assertEqual([call.kwargs['timeout'] for call in connect.call_args_list], [1.5, 1.5])

    def test_unreachable_probe(self):
        with patch.object(connection.socket, 'create_connection', side_effect=TimeoutError()):
            self.assertFalse(connection.diagnose('10.1.1.9')['reachable'])


if __name__ == '__main__': unittest.main()
