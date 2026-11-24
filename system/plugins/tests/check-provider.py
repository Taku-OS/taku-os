import json
import os
import sys
import time

for line in sys.stdin:
    request = json.loads(line)
    if 'id' not in request:
        continue
    method = request['method']
    if method == 'initialize':
        assert 'TAKU_TEST_HOST_ONLY' not in os.environ
        assert os.environ.get('TAKU_TEST_PLUGIN_ENV') == 'plugin-fixture' or os.environ.get('DUPLICATE')
        result = {'protocolVersion': '2024-11-05', 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'fixture', 'version': '1'}}
    elif method == 'tools/list':
        result = {'tools': [{'name': name, 'toolset': toolset, 'inputSchema': {'type': 'object'}, 'extraFixture': 42} for name, toolset in [('pass', 'checks'), ('fail', 'checks'), ('slow', 'slow'), ('hello', 'general')]]}
        if os.environ.get('DUPLICATE'):
            result['tools'].append(result['tools'][0])
    elif method == 'tools/call':
        name = request['params']['name']
        if name == 'slow':
            time.sleep(2)
        if name == 'fail':
            result = {'isError': True, 'content': []}
        else:
            result = {'structuredContent': {'passed': False, 'similarity': 87.5, 'summary': 'partial result', 'unknowns': ['not inspected']}}
    else:
        result = {}
    print(json.dumps({'jsonrpc': '2.0', 'id': request['id'], 'result': result}), flush=True)
