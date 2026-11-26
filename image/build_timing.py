#!/usr/bin/env python3
"""Record monotonic build-stage durations in seconds."""
import argparse
import json
import os
from pathlib import Path
import tempfile
import time

STAGES = ('prepare', 'sync', 'kernel', 'aosp_build', 'export', 'verify_signatures')


def write(path, data):
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as stream:
        temporary = Path(stream.name)
        try:
            json.dump(data, stream, indent=2)
            stream.write('\n')
            stream.flush()
            # The host controller reads reports written by the container user.
            temporary.chmod(0o644)
            temporary.replace(path)
        finally:
            temporary.unlink(missing_ok=True)


def read(path):
    return json.loads(path.read_text())


def record(results, event, now, exit_code=None, prepare_seconds=0):
    report = results / 'timing-baseline.json'
    state_path = results / '.build-timing-state.json'
    if event == 'start':
        (results / 'kernel-timing.json').unlink(missing_ok=True)
        state = {'stage': 'prepare', 'started': now}
        data = {'schema': 'taku.build-timing/1', 'unit': 'seconds',
                'stages': dict.fromkeys((*STAGES, 'total')), 'exit_code': None,
                'kernel': None}
        data['stages']['prepare'] = prepare_seconds
    else:
        state, data = read(state_path), read(report)
        stage = state['stage']
        stages = data['stages']
        stages[stage] = (stages[stage] or 0) + (now - state['started'])
        state = {'stage': event, 'started': now}
        data['exit_code'] = exit_code
        kernel = results / 'kernel-timing.json'
        if stage == 'kernel' and kernel.is_file():
            data['kernel'] = read(kernel)
    data['stages']['total'] = sum(data['stages'][name] or 0 for name in STAGES)
    write(report, data)
    if event == 'finish':
        state_path.unlink()
    else:
        write(state_path, state)


def add_host_stage(results, stage, seconds):
    path = results / 'timing-baseline.json'
    if path.is_file():
        data = read(path)
        stages = data['stages']
        stages[stage] = (stages[stage] or 0) + seconds
        stages['total'] = sum(stages[name] or 0 for name in STAGES)
        write(path, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('results', type=Path)
    parser.add_argument('event', choices=('start', 'finish', *STAGES))
    parser.add_argument('exit_code', nargs='?', type=int)
    args = parser.parse_args()
    # CLOCK_MONOTONIC is shared by the separate shell-invoked recorders.
    record(args.results, args.event, time.clock_gettime(time.CLOCK_MONOTONIC), args.exit_code,
           float(os.environ.get('TAKU_HOST_PREPARE_SECONDS', '0')))


if __name__ == '__main__':
    main()
