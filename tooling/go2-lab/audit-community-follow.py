#!/usr/bin/env python3
"""Offline audit of two reviewed source snapshots, never importing their modules.

Pass the directory containing the exact snapshots listed below. Only an inspected
numeric controller and one method with fake transport are evaluated. Refuse any
different source content; never instantiate a driver, camera or robot connection.
"""
import ast
import asyncio
import hashlib
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from typing import Dict, Tuple

EXPECTED = {
    'object_tracker.py': '955ef50b8bf041dbee05fd5961e3047f3b938135dd056e225b849d4aff7b37ca',
    'follow_behavior.py': '7087dd9e279eba605f76b25174873306f48a062c2773e6f946586a2bf3b79f1a',
}


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python3 audit-community-follow.py SNAPSHOT_DIRECTORY')
    sources = {}
    for filename, expected in EXPECTED.items():
        raw = (Path(sys.argv[1]) / filename).read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected:
            raise SystemExit(f'Refusing unreviewed source: {filename}')
        sources[filename] = ast.parse(raw.decode('utf8'))
    controller_node = next(node for node in sources['object_tracker.py'].body
                           if isinstance(node, ast.ClassDef) and node.name == 'FollowController')
    namespace = {'Dict': Dict, 'Tuple': Tuple}
    exec(compile(ast.Module(body=[controller_node], type_ignores=[]), '<reviewed-numeric-controller>', 'exec'), namespace)
    controller = namespace['FollowController']()
    numeric_cases = []
    for width, height in [(10, 10), (20, 20), (50, 100), (100, 200), (200, 400)]:
        numeric_cases.append({'bboxWidth': width, 'bboxHeight': height, 'area': width * height,
                              'command': controller.calculate_movement((320, 240), (0, 0, width, height))})
    methods = {node.name: node for node in ast.walk(sources['follow_behavior.py'])
               if isinstance(node, ast.AsyncFunctionDef)}
    frame_returns = [ast.unparse(node.value) for node in ast.walk(methods['_get_latest_frame'])
                     if isinstance(node, ast.Return)]
    sent = []

    async def fake_publish(*args):
        sent.append(args)

    namespace = {'RTC_TOPIC': {'SPORT_MOD': 'fake-only'}, 'SPORT_CMD': {'Move': 1008},
                 'logger': SimpleNamespace(error=lambda text: (_ for _ in ()).throw(RuntimeError(text)))}
    exec(compile(ast.Module(body=[methods['_execute_movement']], type_ignores=[]), '<reviewed-fake-transport>', 'exec'), namespace)
    fake = SimpleNamespace(datachannel=SimpleNamespace(pub_sub=SimpleNamespace(publish_request_new=fake_publish)))
    asyncio.run(namespace['_execute_movement'](fake, {'x': 0., 'y': 0., 'z': 0.}))
    zero_count = len(sent)
    asyncio.run(namespace['_execute_movement'](fake, {'x': .15, 'y': 0., 'z': 0.}))
    print(json.dumps({
        'sourceHashes': EXPECTED, 'numericCases': numeric_cases,
        'getLatestFrameReturnValues': frame_returns,
        'messagesForZeroCommand': zero_count, 'messagesForNonzeroCommand': len(sent) - zero_count,
        'robotConnected': False, 'externalModuleImported': False,
        'limitations': 'Audited isolated functions with fake transport; no inference about actual robot persistence or braking.',
    }, indent=2))


if __name__ == '__main__':
    main()
