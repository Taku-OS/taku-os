import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import zipfile

java, jar = sys.argv[1:]
with tempfile.TemporaryDirectory() as directory:
    root = pathlib.Path(directory)
    files = {name: ('fixture:' + name).encode() for name in [
        'bin/node', 'bin/java', 'rea/package.json', 'rea/scripts/rea.mjs',
        'lib/jadx-headless-mcp-0.7.1-all.jar', 'share/target', 'lib/jvm/bin/java']}
    archive = root / 'pack.zip'
    with zipfile.ZipFile(archive, 'w') as out:
        for name, value in files.items():
            out.writestr(name, value)
    digest = lambda data: hashlib.sha256(data).hexdigest()
    lock = {'platform': 'android-arm64', 'node': '22.23.4', 'rea': '6.8.0', 'java': '17', 'jadx': '0.7.1',
            'archiveSha256': digest(archive.read_bytes()), 'files': {name: digest(value) for name, value in files.items()}, 'links': {'bin/node-link': '../share/target'}, 'executables': ['bin/node', 'lib/jvm/bin/java']}
    lock_path = root / 'lock.json'
    lock_path.write_text(json.dumps(lock))
    command = [java, '-cp', jar, 'org.taku.plugins.host.RuntimePackCli', str(archive), str(lock_path), digest(lock_path.read_bytes()), str(root / 'installed')]
    result = subprocess.run(command, text=True, capture_output=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert (root / 'installed/bin/node').read_bytes() == files['bin/node']
    assert (root / 'installed/bin/node-link').is_symlink()
    assert (root / 'installed/bin/node-link').read_bytes() == files['share/target']
    assert os.access(root / 'installed/lib/jvm/bin/java', os.X_OK)
    assert subprocess.run(command, capture_output=True).returncode != 0
    command[-1] = str(root / 'failed')
    command[-2] = '0' * 64
    assert subprocess.run(command, capture_output=True).returncode != 0
    assert not (root / 'failed').exists()
    lock['links'] = {'bin/escape': '../../escape'}
    lock_path.write_text(json.dumps(lock))
    command = [java, '-cp', jar, 'org.taku.plugins.host.RuntimePackCli', str(archive), str(lock_path), digest(lock_path.read_bytes()), str(root / 'bad-link')]
    assert subprocess.run(command, capture_output=True).returncode != 0
    assert not (root / 'bad-link').exists()
    print('PASS: runtime-pack checksum, links, atomic install and duplicate rejection (fixture bytes, not a runtime)')
