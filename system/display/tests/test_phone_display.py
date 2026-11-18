"""Exercise the real wrapper with fake Android executables; never contacts a device."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

SOURCE = Path(__file__).resolve().parents[1] / 'phone-display'
with tempfile.TemporaryDirectory(prefix='taku-display-test-') as directory:
    root = Path(directory)
    binary = root / 'bin'
    binary.mkdir()
    state = root / 'states'
    state.mkdir()
    log = root / 'calls.jsonl'
    stub = binary / 'android-stub'
    stub.write_text('''#!/usr/bin/env python3
import fcntl, json, os, pathlib, sys, time, uuid
state = pathlib.Path(os.environ['DISPLAY_TEST_STATE'])
args = sys.argv[1:]
name = pathlib.Path(sys.argv[0]).name
with open(os.environ['DISPLAY_TEST_LOG'], 'a') as f:
    f.write(json.dumps([name] + args) + '\\n')
if name == 'flock':
    try: fcntl.flock(int(args[-1]), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError: sys.exit(1)
elif name == 'getprop':
    print('running')
elif name == 'setprop':
    if args == ['ctl.start', 'taku_display']: (state / 'default').write_text(str(uuid.uuid4()))
    elif args == ['ctl.stop', 'taku_display']: (state / 'default').unlink(missing_ok=True)
elif name == 'setsid':
    os.execvp(args[0], args)
elif name == 'app_process':
    if args[1] == 'org.agentos.lab.VirtualDisplayHost':
        try: os.fstat(9)
        except OSError: pass
        else: sys.exit('host inherited lifecycle lock')
        (state / args[2]).write_text(str(uuid.uuid4()))
    else:
        command = args[2]
        key = args[3] if len(args) > 3 else 'default'
        if command == 'list':
            print(json.dumps({'screens': sorted(p.name for p in state.iterdir())}))
        elif command == 'status':
            if not (state / key).exists(): sys.exit(1)
            print(json.dumps({'screenKey': key, 'generation': (state / key).read_text()}))
        elif command == 'removal-status':
            actual = (state / key).read_text() if (state / key).exists() else None
            print(json.dumps({'state': 'gone' if actual is None else 'current' if actual == args[4] else 'replaced'}))
        elif command == 'stop':
            if len(args) > 4 and (state / key).read_text() != args[4]: sys.exit(1)
            (state / key).unlink()
            if os.environ.get('DISPLAY_TEST_BLOCK_STOP') == key:
                events = pathlib.Path(os.environ['DISPLAY_TEST_EVENTS'])
                (events / ('stopped-' + key)).touch()
                until = time.monotonic() + 10
                while not (events / ('release-' + key)).exists():
                    if time.monotonic() > until: sys.exit('stop barrier timed out')
                    time.sleep(0.01)
        elif command == 'capture':
            print(json.dumps({'screenKey': key}))
            print(str(state / key / 'screen.png'))
        elif command == 'id': print('42')
''')
    stub.chmod(0o755)
    for name in ('app_process', 'getprop', 'setprop', 'setsid', 'flock'):
        (binary / name).symlink_to(stub)
    wrapper = root / 'phone-display'
    wrapper.write_text(SOURCE.read_text().replace('/system/bin/app_process', str(binary / 'app_process'))
                       .replace('/system/bin/flock', str(binary / 'flock'))
                       .replace('/system/bin/sh', '/bin/sh')
                       .replace('/data/local/agentos/virtual-display', str(root / 'display')))
    environment = dict(os.environ, PATH=str(binary) + ':' + os.environ['PATH'],
                       DISPLAY_TEST_STATE=str(state), DISPLAY_TEST_LOG=str(log))
    count = 0
    def run(*args, code=0):
        global count
        result = subprocess.run(['/bin/sh', str(wrapper), *args], env=environment,
                                text=True, capture_output=True, timeout=15)
        assert result.returncode == code, (args, result.returncode, result.stdout, result.stderr)
        count += 1
        return result.stdout
    def calls():
        return [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []

    for command in ('start', 'id', 'status', 'capture', 'stop', 'removal-read', 'removal-status', 'removal-stop'):
        for bad in ('', '../default', '/maps', 'Maps', '1maps', '-maps', 'map x', 'map/x', 'a_b', 'a' * 33):
            before = calls()
            run(command, bad, code=2)
            assert calls() == before, 'invalid key reached an Android executable'
    run('list', 'maps', code=2)
    run('start', 'maps', 'unexpected', code=2)
    assert json.loads(run('start'))['screenKey'] == 'default'
    assert ['setprop', 'ctl.start', 'taku_display'] in calls()
    for key in ('xiaohongshu', 'maps', 'meituan'):
        before = len(calls())
        assert json.loads(run('start', key))['screenKey'] == key
        assert not any(call[0] in ('getprop', 'setprop') for call in calls()[before:]), 'named slot touched init'
    assert {p.name for p in state.iterdir()} == {'default', 'xiaohongshu', 'maps', 'meituan'}
    generation = (state / 'maps').read_text()
    assert json.loads(run('removal-read', 'maps'))['screenKey'] == 'maps'
    assert json.loads(run('removal-status', 'maps', generation))['state'] == 'current'
    assert json.loads(run('removal-status', 'maps', 'old-generation'))['state'] == 'replaced'
    assert json.loads(run('removal-status', 'absent', generation))['state'] == 'gone'
    run('removal-status', 'maps', code=2)
    run('removal-stop', 'default', generation, code=2)
    before = len(calls())
    assert len(json.loads(run('list'))['screens']) == 4
    assert len(calls()) == before + 1, 'list modified host state'
    run('capture', 'maps', '6c7f2455-6e82-4342-a4df-64d3f6f03e5a')
    assert calls()[-1][-3:] == ['capture', 'maps', '6c7f2455-6e82-4342-a4df-64d3f6f03e5a']
    before = len(calls())
    run('stop', 'maps', (state / 'maps').read_text())
    assert {p.name for p in state.iterdir()} == {'default', 'xiaohongshu', 'meituan'}
    assert not any(call[0] in ('getprop', 'setprop') for call in calls()[before:]), 'named stop touched default init'
    run('stop')
    assert ['setprop', 'ctl.stop', 'taku_display'] in calls()
    assert {p.name for p in state.iterdir()} == {'xiaohongshu', 'meituan'}
    events = root / 'events'
    events.mkdir()
    for key in ('default', 'maps'):
        run('start', key)
        old_generation = (state / key).read_text()
        stop_environment = dict(environment, DISPLAY_TEST_BLOCK_STOP=key, DISPLAY_TEST_EVENTS=str(events))
        stopping = subprocess.Popen(['/bin/sh', str(wrapper), 'removal-stop' if key == 'maps' else 'stop', key, old_generation],
                                    env=stop_environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        deadline = time.monotonic() + 10
        while not (events / ('stopped-' + key)).exists():
            assert time.monotonic() < deadline and stopping.poll() is None, 'stop did not reach race barrier'
            time.sleep(0.01)
        starting = subprocess.Popen(['/bin/sh', str(wrapper), 'start', key], env=environment,
                                    text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        time.sleep(0.3)
        assert starting.poll() is None and not (state / key).exists(), 'start bypassed lifecycle lock'
        # An unrelated slot must still be usable while this slot is stopping.
        assert json.loads(run('start', 'meituan'))['screenKey'] == 'meituan'
        (events / ('release-' + key)).touch()
        stop_output = stopping.communicate(timeout=10)
        start_output = starting.communicate(timeout=10)
        assert stopping.returncode == 0, stop_output
        assert starting.returncode == 0, start_output
        new_generation = (state / key).read_text()
        assert new_generation != old_generation, 'restart did not produce a new host'
        before = len(calls())
        run('stop', key, old_generation, code=1)
        assert (state / key).read_text() == new_generation, 'stale stop killed the replacement host'
        assert not any(call[0] == 'setprop' for call in calls()[before:]), 'stale stop reached init'
        count += 2
    print(f'phone-display wrapper: {count} invocations passed (no device)')
