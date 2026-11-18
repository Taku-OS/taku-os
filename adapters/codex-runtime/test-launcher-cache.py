#!/usr/bin/env python3
"""Exercise cache upgrades and concurrent publication on the lifecycle AVD."""
import concurrent.futures
import hashlib
import io
import pathlib
import subprocess
import sys
import tarfile
import tempfile
import uuid

serial = sys.argv[1] if len(sys.argv) == 2 else ''
if not serial.startswith('emulator-'):
    raise SystemExit('usage: test-launcher-cache.py EMULATOR_SERIAL')
def adb(*args, **kwargs):
    return subprocess.run(['adb', '-s', serial, *args], check=True, **kwargs)
if adb('emu', 'avd', 'name', capture_output=True, text=True).stdout.splitlines()[0].strip() != 'Taku_SkillsLifecycle_20261030':
    raise SystemExit('Wrong AVD')
if adb('shell', 'id', '-u', capture_output=True, text=True).stdout.strip() != '0':
    raise SystemExit('Dedicated AVD must use root adb for this isolated test')
root = pathlib.Path(__file__).resolve().parent
remote = '/data/local/tmp/taku-launcher-cache-' + uuid.uuid4().hex

def archive(path, name, content):
    with tarfile.open(path, 'w:gz') as tar:
        data = content.encode()
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = 0o755
        tar.addfile(info, io.BytesIO(data))

def invoke():
    result = subprocess.run(['adb', '-s', serial, 'shell', 'sh', remote + '/launcher'], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('launcher failed: ' + result.stderr)
    return result.stdout.strip()

try:
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        launcher = (root / 'codex').read_text().replace('/data/local/agentos', remote + '/data').replace('/system_ext/etc/codex', remote + '/archives')
        (tmp / 'launcher').write_text(launcher)
        archive(tmp / 'host.tar.gz', 'codex-code-mode-host-aarch64-unknown-linux-musl', '#!/system/bin/sh\nexit 0\n')
        archive(tmp / 'codex.tar.gz', 'codex-aarch64-unknown-linux-musl', '#!/system/bin/sh\necho first\n')
        adb('shell', 'mkdir', '-p', remote + '/archives', remote + '/data/runtime/0.161.0')
        adb('push', str(tmp / 'launcher'), remote + '/launcher', stdout=subprocess.DEVNULL)
        adb('push', str(tmp / 'host.tar.gz'), remote + '/archives/code-mode-host.tar.gz', stdout=subprocess.DEVNULL)
        adb('push', str(tmp / 'codex.tar.gz'), remote + '/archives/codex.tar.gz', stdout=subprocess.DEVNULL)
        # A prior same-version cache must never be used, even if executable.
        (tmp / 'stale').write_text('#!/system/bin/sh\necho stale\n')
        stale = remote + '/data/runtime/0.161.0/codex-aarch64-unknown-linux-musl'
        adb('push', str(tmp / 'stale'), stale, stdout=subprocess.DEVNULL)
        adb('shell', 'chmod', '755', stale)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            results = list(pool.map(lambda _: invoke(), range(8)))
        if results != ['first'] * 8:
            raise AssertionError('concurrent first launch did not use new archive: ' + repr(results))
        archive(tmp / 'codex.tar.gz', 'codex-aarch64-unknown-linux-musl', '#!/system/bin/sh\necho second\n')
        adb('push', str(tmp / 'codex.tar.gz'), remote + '/archives/codex.tar.gz', stdout=subprocess.DEVNULL)
        if invoke() != 'second':
            raise AssertionError('changed archive did not invalidate cache')
        codex_hash = hashlib.sha256((tmp / 'codex.tar.gz').read_bytes()).hexdigest()
        host_hash = hashlib.sha256((tmp / 'host.tar.gz').read_bytes()).hexdigest()
        cache = remote + '/data/runtime-v2/0.161.0-' + codex_hash + '-' + host_hash
        adb('shell', 'test', '-x', cache + '/codex-code-mode-host')
        # An executable but modified cache must be rebuilt, not executed.
        adb('push', str(tmp / 'stale'), cache + '/codex-aarch64-unknown-linux-musl', stdout=subprocess.DEVNULL)
        adb('shell', 'chmod', '755', cache + '/codex-aarch64-unknown-linux-musl')
        if invoke() != 'second':
            raise AssertionError('corrupt executable cache was used')
        adb('shell', 'rm', '-f', cache + '/codex-code-mode-host')
        if invoke() != 'second':
            raise AssertionError('incomplete cache was not repaired')
        manifest = adb('exec-out', 'cat', cache + '/.sha256', capture_output=True).stdout.splitlines(keepends=True)
        (tmp / 'truncated-manifest').write_bytes(manifest[0])
        adb('push', str(tmp / 'truncated-manifest'), cache + '/.sha256', stdout=subprocess.DEVNULL)
        adb('push', str(tmp / 'stale'), cache + '/codex-code-mode-host', stdout=subprocess.DEVNULL)
        adb('shell', 'chmod', '755', cache + '/codex-code-mode-host')
        if invoke() != 'second' or b'stale' in adb('exec-out', 'cat', cache + '/codex-code-mode-host', capture_output=True).stdout:
            raise AssertionError('incomplete manifest was accepted')
        abandoned = remote + '/data/runtime-v2/.extract-abandoned'
        adb('shell', 'mkdir', abandoned)
        if invoke() != 'second' or subprocess.run(['adb', '-s', serial, 'shell', 'test', '-e', abandoned]).returncode == 0:
            raise AssertionError('abandoned extraction was not cleaned')
        archive(tmp / 'host.tar.gz', 'codex-code-mode-host-aarch64-unknown-linux-musl', '#!/system/bin/sh\necho new-host\n')
        adb('push', str(tmp / 'host.tar.gz'), remote + '/archives/code-mode-host.tar.gz', stdout=subprocess.DEVNULL)
        if invoke() != 'second':
            raise AssertionError('changed host archive broke launch')
        host_hash = hashlib.sha256((tmp / 'host.tar.gz').read_bytes()).hexdigest()
        cache = remote + '/data/runtime-v2/0.161.0-' + codex_hash + '-' + host_hash
        host_contents = adb('exec-out', 'cat', cache + '/codex-code-mode-host', capture_output=True).stdout
        if b'new-host' not in host_contents:
            raise AssertionError('changed host archive did not invalidate cache')
        base = remote + '/data/runtime-v2'
        adb('shell', 'chmod', '777', base)
        rejected = subprocess.run(['adb', '-s', serial, 'shell', 'sh', remote + '/launcher'], capture_output=True, text=True)
        if rejected.returncode == 0:
            raise AssertionError('insecure root-owned cache directory was trusted')
        adb('shell', 'chmod', '700', base)
        adb('shell', 'rm', '-f', base + '/.lock')
        (tmp / 'sentinel').write_text('preserve\n')
        adb('push', str(tmp / 'sentinel'), remote + '/sentinel', stdout=subprocess.DEVNULL)
        adb('shell', 'ln', '-s', remote + '/sentinel', base + '/.lock')
        rejected = subprocess.run(['adb', '-s', serial, 'shell', 'sh', remote + '/launcher'], capture_output=True, text=True)
        sentinel = adb('exec-out', 'cat', remote + '/sentinel', capture_output=True).stdout
        if rejected.returncode == 0 or sentinel != b'preserve\n':
            raise AssertionError('lock symlink was followed or accepted')
        adb('shell', 'rm', '-f', base + '/.lock')
        (tmp / 'codex.tar.gz').write_bytes(b'not a gzip archive')
        adb('push', str(tmp / 'codex.tar.gz'), remote + '/archives/codex.tar.gz', stdout=subprocess.DEVNULL)
        bad = subprocess.run(['adb', '-s', serial, 'shell', 'sh', remote + '/launcher'], capture_output=True, text=True)
        if bad.returncode == 0:
            raise AssertionError('corrupt archive was accepted')
        print('PASS: old cache ignored; 8 concurrent launches; archive changes; cache repair; abandoned staging; unsafe directory, symlink and corrupt archive rejected')
finally:
    adb('shell', 'rm', '-rf', remote)
