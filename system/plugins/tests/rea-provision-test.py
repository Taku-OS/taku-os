#!/usr/bin/env python3
"""Exercise the first-boot REA provisioning script with stubbed tools."""
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'system/plugins/taku-rea-provision'


def make_env(tmp, lock_body=b'{"archiveSha256":"0"*64}', lock_pin=None):
    tmp = pathlib.Path(tmp)
    bindir = tmp / 'bin'
    bindir.mkdir(exist_ok=True)
    calls = tmp / 'calls.log'
    (bindir / 'curl').write_text(
        '#!/bin/sh\n'
        'out=""; url=""\n'
        'while [ $# -gt 0 ]; do\n'
        '  case "$1" in\n'
        '    -o) out="$2"; shift 2 ;;\n'
        '    --fail|--location|--silent|--show-error) shift ;;\n'
        '    --retry|--retry-delay) shift 2 ;;\n'
        '    *) url="$1"; shift ;;\n'
        '  esac\n'
        'done\n'
        'printf "%s\\n" "curl $url" >> "$FAKE_CALLS"\n'
        'case "$url" in\n'
        '  *lock*) cp "$FAKE_LOCK" "$out" ;;\n'
        '  *) cp "$FAKE_ARCHIVE" "$out" ;;\n'
        'esac\n')
    (bindir / 'packcli').write_text(
        '#!/bin/sh\n'
        'printf "%s\\n" "packcli $*" >> "$FAKE_CALLS"\n'
        'mkdir -p "$4/bin"; : > "$4/bin/rea-mcp"; : > "$4/bin/node"; chmod +x "$4/bin/rea-mcp" "$4/bin/node"\n')
    (bindir / 'plugin').write_text(
        '#!/bin/sh\n'
        'printf "%s\\n" "plugin $*" >> "$FAKE_CALLS"\n'
        'if [ "$2" = list ]; then printf \'[]\'; fi\n')
    for name in ('curl', 'packcli', 'plugin'):
        (bindir / name).chmod(0o755)
    lock = tmp / 'lock.json'
    lock.write_bytes(lock_body)
    archive = tmp / 'archive.zip'
    archive.write_bytes(b'PK-fake')
    conf = tmp / 'rea-pack.conf'
    conf.write_text(
        'REA_PACK_URL=https://example.invalid/pack.zip\n'
        'REA_PACK_LOCK_URL=https://example.invalid/lock.json\n'
        f'REA_PACK_LOCK_SHA256={lock_pin or hashlib.sha256(lock_body).hexdigest()}\n')
    provider = tmp / 'provider'
    provider.mkdir()
    (provider / 'manifest.json').write_text('{"id":"org.taku.rea"}')
    env = dict(os.environ)
    env.update({
        'PATH': f'{bindir}:{env["PATH"]}',
        'TAKU_AGENTOS_ROOT': str(tmp / 'root'),
        'TAKU_REA_CONF': str(conf),
        'TAKU_REA_PROVIDER': str(provider),
        'TAKU_PLUGIN_CLI': str(bindir / 'plugin'),
        'TAKU_REA_PACK_CLI': str(bindir / 'packcli'),
        'FAKE_CALLS': str(calls),
        'FAKE_LOCK': str(lock),
        'FAKE_ARCHIVE': str(archive),
    })
    return tmp, env, calls


def run(env, *args):
    return subprocess.run(['sh', str(SCRIPT), *args], env=env, capture_output=True, text=True)


def check_provider_copy():
    shipped = ROOT / 'system/plugins/rea-provider'
    example = ROOT / 'examples/godmode/rea-provider'
    for name in ('manifest.json', 'skills/rea/SKILL.md'):
        assert (shipped / name).read_bytes() == (example / name).read_bytes(), f'provider copy drifted: {name}'


def main():
    check_provider_copy()
    with tempfile.TemporaryDirectory() as tmp:
        work, env, calls = make_env(tmp, lock_pin='0' * 64)
        result = run(env)
        assert result.returncode == 1, result
        assert 'checksum mismatch' in result.stderr, result.stderr
        assert 'packcli' not in calls.read_text()

    with tempfile.TemporaryDirectory() as tmp:
        work, env, calls = make_env(tmp)
        result = run(env)
        assert result.returncode == 0, (result.stdout, result.stderr)
        log = calls.read_text()
        assert 'curl https://example.invalid/lock.json' in log, log
        assert 'curl https://example.invalid/pack.zip' in log, log
        assert 'packcli ' in log and str(work / 'root/rea-pack') in log, log
        assert 'plugin godmode install' in log, log
        assert (work / 'root/rea-pack/bin/rea-mcp').exists()

        before = log.count('curl ')
        result = run(env)
        assert result.returncode == 0, result.stderr
        after = calls.read_text()
        assert after.count('curl ') == before, after
        assert after.count('packcli ') == log.count('packcli '), after

        status = run(env, '--status')
        assert status.returncode == 0, status.stderr
        state = json.loads(status.stdout)
        assert state['pack'] is True, state

    print('rea-provision tests passed')


if __name__ == '__main__':
    main()
