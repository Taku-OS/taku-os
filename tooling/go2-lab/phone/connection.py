"""Private per-device configuration and bounded LAN checks; no cloud login."""
import ipaddress
import os
from pathlib import Path
import re
import socket


class DeviceKey:
    def __init__(self, directory, ip):
        self.path = Path(directory) / (str(ipaddress.IPv4Address(ip)) + '.key')

    def read(self):
        if not self.path.exists(): return None
        if self.path.stat().st_mode & 0o077: raise ValueError('KEY_FILE_PERMISSIONS')
        value = self.path.read_text().strip()
        if not re.fullmatch('[a-fA-F0-9]{32}', value): raise ValueError('INVALID_DEVICE_KEY')
        return value.lower()

    def save(self, value):
        if not isinstance(value, str) or not re.fullmatch('[a-fA-F0-9]{32}', value):
            raise ValueError('INVALID_DEVICE_KEY')
        self.path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = self.path.with_suffix('.pending')
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w') as output:
            output.write(value.lower()); output.flush(); os.fsync(output.fileno())
        os.replace(temporary, self.path)


def diagnose(ip):
    ports = {}
    for port in (9991, 8081):
        try:
            with socket.create_connection((ip, port), timeout=1.5):
                ports[str(port)] = True
        except OSError:
            ports[str(port)] = False
    return {'robotIp': ip, 'signalingPorts': ports, 'reachable': any(ports.values()),
            'scope': 'tcp-only', 'robotConnected': False}
