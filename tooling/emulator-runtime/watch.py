#!/usr/bin/env python3
"""Recover one explicitly configured development emulator; never start an AVD."""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

MANAGED_BY = 'taku-emulator-runtime'
SERIAL = 'emulator-5582'
AVD = 'AgentOS_ImageCheck'
LABEL = 'build.taku.emulator-runtime.5582'
REQUIRED = {'schemaVersion', 'managedBy', 'serial', 'avd', 'adb', 'restoreScript',
            'restoreSha256', 'healthScript', 'healthSha256', 'deviceFiles'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def validate_config(config):
    if not isinstance(config, dict) or set(config) != REQUIRED:
        raise ValueError('Configuration fields do not match schemaVersion 1')
    if config['schemaVersion'] != 1 or config['managedBy'] != MANAGED_BY:
        raise ValueError('Unrecognized configuration owner or version')
    if config['serial'] != SERIAL or config['avd'] != AVD:
        raise ValueError('This monitor is restricted to emulator-5582 / AgentOS_ImageCheck')
    adb = config['adb']
    if not isinstance(adb, str) or not os.path.isabs(adb) or not os.access(adb, os.X_OK):
        raise ValueError('adb must be an absolute executable path')
    for kind in ('restore', 'health'):
        if config[kind + 'Script'] != '/data/local/agentos/dev-runtime/' + kind + '.sh':
            raise ValueError(kind + 'Script is not the managed device script path')
        sha = config[kind + 'Sha256']
        if not isinstance(sha, str) or not re.fullmatch('[0-9a-f]{64}', sha):
            raise ValueError(kind + 'Sha256 must be a lowercase SHA-256')
    expected_files = {'/data/local/agentos/dev-runtime/' + name for name in
                      ('common.sh', 'health.sh', 'restore.sh', 'probe.mjs', 'runtime.conf',
                       'assets.sha256', 'targets.sha256', 'self.sha256', 'owner.json')}
    device_files = config['deviceFiles']
    if not isinstance(device_files, dict) or set(device_files) != expected_files:
        raise ValueError('deviceFiles must bind every managed device script/configuration')
    if any(not isinstance(sha, str) or not re.fullmatch('[0-9a-f]{64}', sha)
           for sha in device_files.values()):
        raise ValueError('Every deviceFiles entry must be a lowercase SHA-256')
    for kind in ('restore', 'health'):
        if device_files[config[kind + 'Script']] != config[kind + 'Sha256']:
            raise ValueError('Entry script hash disagrees with deviceFiles')
    return config


def load_config(path):
    return validate_config(json.loads(Path(path).read_text()))


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds')


def atomic_json(path, value):
    path = Path(path)
    fd, tmp = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(tmp)


@contextlib.contextmanager
def single_instance(directory):
    """flock is released by the OS on exit, including launchd restarts."""
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / 'watcher.lock'
    fd = os.open(path, os.O_CREAT | os.O_RDWR | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'r+') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        lock.seek(0)
        lock.truncate()
        lock.write(str(os.getpid()) + '\n')
        lock.flush()
        yield


class DeviceError(Exception):
    pass


class RuntimeWatcher:
    def __init__(self, config, state_dir, runner=subprocess.run):
        self.config = validate_config(config)
        self.runner = runner
        self.directory = Path(state_dir)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.path = self.directory / 'state.json'
        self.state = {'schemaVersion': 1, 'managedBy': MANAGED_BY,
                      'serial': config['serial'], 'avd': config['avd'],
                      'restoreCount': 0, 'successfulRestoreCount': 0,
                      'bootId': None, 'restoredAt': None}
        if self.path.exists():
            previous = json.loads(self.path.read_text())
            if not isinstance(previous, dict):
                raise ValueError('Invalid monitor state object')
            if any(type(previous.get(key)) is not int or previous[key] < 0
                   for key in ('restoreCount', 'successfulRestoreCount')):
                raise ValueError('Invalid monitor recovery counters')
            if any(previous.get(key) != self.state[key]
                   for key in ('schemaVersion', 'managedBy', 'serial', 'avd')):
                raise ValueError('Refusing state belonging to a different target')
            self.state.update(previous)
        self.failures = 0

    def adb(self, *args, timeout=10):
        try:
            return self.runner([self.config['adb'], '-s', self.config['serial'], *args],
                               capture_output=True, text=True, timeout=timeout,
                               check=False)
        except (subprocess.TimeoutExpired, OSError) as error:
            # Do not log arbitrary device/script output, credentials or account data.
            raise DeviceError('adb command timed out' if isinstance(error, subprocess.TimeoutExpired)
                              else 'adb executable failed') from error

    def output(self, *args, timeout=10):
        result = self.adb(*args, timeout=timeout)
        if result.returncode:
            raise DeviceError('adb command failed')
        return result.stdout.strip()

    def mark(self, status, **fields):
        previous_status = self.state.get('status')
        candidate = dict(self.state, status=status, pid=os.getpid(), **fields)
        if candidate != self.state:
            candidate['updatedAt'] = now()
            atomic_json(self.path, candidate)
            self.state = candidate
        if status != previous_status:
            logging.info('%s: %s', self.config['serial'], status)

    def ready(self):
        result = self.adb('get-state')
        if result.returncode or result.stdout.strip() != 'device':
            return 'offline'
        avd = self.output('emu', 'avd', 'name').splitlines()
        if not avd or avd[0].strip() != self.config['avd']:
            return 'wrong-target'
        if self.output('shell', 'getprop', 'sys.boot_completed') != '1':
            return 'booting'
        if self.output('shell', 'am', 'get-started-user-state', '0') != 'RUNNING_UNLOCKED':
            return 'locked'
        return None

    def verify_scripts(self):
        for path, expected in sorted(self.config['deviceFiles'].items()):
            output = self.output('shell', 'sha256sum', path)
            words = output.split()
            if not words or words[0] != expected:
                raise DeviceError(Path(path).name + ' hash mismatch; refusing execution')

    def tick(self):
        try:
            unavailable = self.ready()
            if unavailable:
                self.failures = 0
                self.mark(unavailable, lastError=None)
                return False
            if self.output('shell', 'id', '-u') != '0':
                self.output('root', timeout=20)
                self.output('wait-for-device', timeout=20)
                # Serial numbers may be reused; validate identity again after adbd restarts.
                unavailable = self.ready()
                if unavailable:
                    self.mark(unavailable, lastError=None)
                    return False
                if self.output('shell', 'id', '-u') != '0':
                    raise DeviceError('adbd root is unavailable on this development image')
            boot_id = self.output('shell', 'cat', '/proc/sys/kernel/random/boot_id')
            if not re.fullmatch('[0-9a-fA-F-]{32,36}', boot_id):
                raise DeviceError('Invalid boot ID')
            if self.state.get('bootId') != boot_id or self.state.get('status') != 'healthy':
                self.mark('checking', bootId=boot_id, lastError=None)
            self.verify_scripts()
            healthy = self.adb('shell', 'sh', self.config['healthScript'], timeout=20)
            if not healthy.returncode:
                self.failures = 0
                self.mark('healthy', lastHealth='healthy', lastHealthAt=now(), lastError=None)
                return True
            self.mark('restoring', restoreCount=self.state['restoreCount'] + 1,
                      lastRestoreStartedAt=now(), lastHealth='failed', lastHealthAt=now())
            restored = self.adb('shell', 'sh', self.config['restoreScript'], timeout=90)
            if restored.returncode:
                raise DeviceError('Managed restore script returned failure')
            # A restore exit code alone is not proof that the services are usable.
            self.verify_scripts()
            healthy = self.adb('shell', 'sh', self.config['healthScript'], timeout=20)
            if healthy.returncode:
                raise DeviceError('Health check failed after restore')
            self.failures = 0
            self.mark('healthy', restoredAt=now(), restoredBootId=boot_id,
                      successfulRestoreCount=self.state['successfulRestoreCount'] + 1,
                      lastHealth='healthy', lastHealthAt=now(), lastError=None)
            return True
        except DeviceError as error:
            self.failures += 1
            self.mark('error', lastError=str(error))
            return False

    def delay(self):
        if not self.failures:
            return 5 if self.state.get('status') == 'healthy' else 3
        return min(60, 10 * (2 ** min(max(self.failures - 1, 0), 3)))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--state-dir', required=True)
    parser.add_argument('--once', action='store_true')
    args = parser.parse_args(argv)
    try:
        config = load_config(args.config)
        with single_instance(args.state_dir):
            handler = RotatingFileHandler(Path(args.state_dir) / 'watch.log',
                                          maxBytes=256 * 1024, backupCount=2)
            logging.basicConfig(level=logging.INFO,
                                format='%(asctime)s %(levelname)s %(message)s',
                                handlers=[handler])
            watcher = RuntimeWatcher(config, args.state_dir)
            while True:
                healthy = watcher.tick()
                if args.once:
                    return 0 if healthy else 1
                time.sleep(watcher.delay())
    except BlockingIOError:
        print('The configured runtime monitor is already running.', file=sys.stderr)
        return 0
    except (ValueError, OSError) as error:
        print('Runtime monitor configuration/state error: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
