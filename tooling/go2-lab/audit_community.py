#!/usr/bin/env python3
"""Probe only the reviewed community query/set functions with a fake RPC client.

Usage: python3 audit_community.py /path/to/obstacles_avoid.py
The hash pins the inspected source; a changed upstream file needs a new review.
No upstream imports, main, drive loop or network transport are executed.
"""
import ast
import asyncio
import hashlib
import json
from pathlib import Path
import sys
from types import SimpleNamespace

def offline(event, args):
    if event in {'socket.connect', 'socket.sendto', 'socket.getaddrinfo'}:
        raise RuntimeError('OFFLINE_AUDIT_NETWORK_FORBIDDEN')

sys.addaudithook(offline)
source = Path(sys.argv[1]).read_text()
digest = hashlib.sha256(source.encode()).hexdigest()
if digest != '7035ea5dc37c438c67fa05d6c0646d109c08db287b3987dde6184f5496b6220b':
    raise SystemExit('Source differs from reviewed e0abc578 snapshot; review before executing.')
functions = [n for n in ast.parse(source).body
             if isinstance(n, ast.AsyncFunctionDef) and n.name in ('switch_get', 'switch_set')]
assert len(functions) == 2
namespace = {'json': json, 'RTC_TOPIC': {'OBSTACLES_AVOID': 'rt/api/obstacles_avoid/request'},
             'OBSTACLES_AVOID_API': {'SWITCH_SET': 1001, 'SWITCH_GET': 1002}}
exec(compile(ast.Module(body=functions, type_ignores=[]), '<reviewed-query-set>', 'exec'), namespace)

async def main():
    results = []
    for label, code, data, expected in [
        ('on', 0, '{"enable":true}', True), ('off', 0, '{"enable":false}', False),
        ('rejected', 42, '{"enable":true}', None), ('empty', 0, '', None),
        ('malformed', 0, '{', None),
    ]:
        calls = []
        async def request(*args):
            calls.append(args)
            return {'data': {'header': {'status': {'code': code}}, 'data': data}}
        conn = SimpleNamespace(datachannel=SimpleNamespace(pub_sub=SimpleNamespace(publish_request_new=request)))
        actual = await namespace['switch_get'](conn)
        assert actual == (code, expected)
        assert calls == [('rt/api/obstacles_avoid/request', {'api_id': 1002})]
        results.append({'case': label, 'pass': True, 'result': actual})
    calls = []
    async def request(*args):
        calls.append(args)
        return {'data': {'header': {'status': {'code': 0}}}}
    conn = SimpleNamespace(datachannel=SimpleNamespace(pub_sub=SimpleNamespace(publish_request_new=request)))
    assert await namespace['switch_set'](conn, True) == 0
    assert calls == [('rt/api/obstacles_avoid/request', {'api_id': 1001, 'parameter': {'enable': True}})]
    results.append({'case': 'set_enable_wire_shape', 'pass': True, 'readbackPerformed': False})
    print(json.dumps({'sourceSha256': digest, 'robotConnected': False,
                      'scope': 'Fake RPC contract only, no robot algorithm', 'results': results}, indent=2))

if __name__ == '__main__': asyncio.run(main())
