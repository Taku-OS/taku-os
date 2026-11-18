# SPDX-FileCopyrightText: 2026 Taku AI Inc.
# SPDX-License-Identifier: Apache-2.0
"""Startup readiness for the owned plugin-host integration fixture."""
import json
import socket
import time


def wait_for_service(service, socket_path, timeout=5):
    deadline = time.monotonic() + timeout
    while True:
        code = service.poll()
        if code is not None:
            raise RuntimeError(f"plugin host exited ({code}): {service.stderr.read()}")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(f"plugin host did not become ready at {socket_path}")
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                connection.settimeout(remaining)
                connection.connect(str(socket_path))
                connection.sendall(b'{"operation":"ping"}\n')
                with connection.makefile('rb') as incoming:
                    reply = json.loads(incoming.readline(1024 * 1024))
            if reply.get('ok') is not True or reply.get('result', {}).get('running') is not True:
                raise RuntimeError(f"plugin host readiness ping failed: {reply}")
            return
        except (FileNotFoundError, ConnectionRefusedError):
            time.sleep(min(.01, remaining))
