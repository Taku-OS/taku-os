#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 Taku AI Inc.
# SPDX-License-Identifier: Apache-2.0
import concurrent.futures
import io
import json
import pathlib
import socket
import tempfile
import threading
import time
import types
import unittest
from unittest import mock

import service_ready


class ServiceReadyTest(unittest.TestCase):
    def test_bound_socket_is_not_ready_until_ping_succeeds(self):
        with tempfile.TemporaryDirectory(prefix='taku-ready-', dir='/tmp') as temporary:
            path = pathlib.Path(temporary) / 'service.sock'
            real_socket = socket.socket
            refused = threading.Event()
            requests = []

            class RecordingSocket(real_socket):
                def connect(self, address):
                    try:
                        return super().connect(address)
                    except ConnectionRefusedError:
                        refused.set()
                        raise

            service = types.SimpleNamespace(poll=lambda: None, stderr=io.StringIO())
            with real_socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                listener.bind(str(path))
                self.assertTrue(path.exists())
                with mock.patch.object(socket, 'socket', RecordingSocket), concurrent.futures.ThreadPoolExecutor(max_workers=1) as worker:
                    waiting = worker.submit(service_ready.wait_for_service, service, path, timeout=1)
                    deadline = time.monotonic() + .5
                    while not refused.is_set() and not waiting.done() and time.monotonic() < deadline:
                        time.sleep(.001)
                    self.assertFalse(waiting.done(), 'A socket file must not count as a responsive service')
                    self.assertTrue(refused.is_set(), 'The test must reach the bound-but-not-listening window')
                    listener.listen()

                    def respond():
                        with listener.accept()[0] as connection:
                            with connection.makefile('rb') as incoming:
                                requests.append(json.loads(incoming.readline()))
                            connection.sendall(b'{"ok":true,"result":{"running":true}}\n')

                    server = threading.Thread(target=respond, daemon=True)
                    server.start()
                    waiting.result(timeout=2)
                    server.join(timeout=1)
                    self.assertFalse(server.is_alive())
            self.assertEqual(requests, [{'operation': 'ping'}])

    def test_exited_service_reports_its_error_even_with_a_stale_path(self):
        with tempfile.TemporaryDirectory(prefix='taku-ready-', dir='/tmp') as temporary:
            path = pathlib.Path(temporary) / 'service.sock'
            path.touch()
            service = types.SimpleNamespace(poll=lambda: 7, stderr=io.StringIO('startup fixture failed'))
            with self.assertRaisesRegex(RuntimeError, r'exited \(7\).*startup fixture failed'):
                service_ready.wait_for_service(service, path)

    def test_bound_socket_that_never_listens_times_out(self):
        with tempfile.TemporaryDirectory(prefix='taku-ready-', dir='/tmp') as temporary:
            path = pathlib.Path(temporary) / 'service.sock'
            service = types.SimpleNamespace(poll=lambda: None, stderr=io.StringIO())
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as listener:
                listener.bind(str(path))
                with self.assertRaises(TimeoutError):
                    service_ready.wait_for_service(service, path, timeout=.03)


if __name__ == '__main__':
    unittest.main()
