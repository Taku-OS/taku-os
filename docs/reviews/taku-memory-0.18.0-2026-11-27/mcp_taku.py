# Minimal MCP stdio server giving the reader the phone's two memory tools, with the names, descriptions and input schemas
# PhoneNativeTools registers (read from the Java source). Calls go to server.py for one question's memory.
# Usage: mcp_taku.py question_id port
import json, os, re, sys, urllib.request
ROOT = os.environ.get('TAKU_REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../..'))
qid, port = sys.argv[1], int(sys.argv[2])
src = open(ROOT + '/system/assistant/broker/org/agentos/lab/PhoneNativeTools.java', encoding='utf-8').read()
def literals(segment): return ''.join(json.loads('"' + s + '"') for s in re.findall(r'"((?:[^"\\]|\\.)*)"', segment))
seg = src[src.index('JSONObject memorySearch=tool("memory_search",') + len('JSONObject memorySearch=tool("memory_search",'):src.index('   new JSONObject().put("query",new JSONObject().put("type","string").put("maxLength",500))')]
SEARCH = literals(seg.rsplit(',', 1)[0])
GET = re.search(r'tool\("memory_get","((?:[^"\\]|\\.)*)"', src).group(1)
TOOLS = [{'name': 'memory_search', 'description': SEARCH, 'inputSchema': {'type': 'object', 'additionalProperties': False, 'required': ['query'], 'properties': {
            'query': {'type': 'string', 'maxLength': 500}, 'limit': {'type': 'integer', 'minimum': 1, 'maximum': 50},
            'from': {'type': 'string', 'description': 'YYYY-MM-DD, first day records were seen (device time zone)'},
            'to': {'type': 'string', 'description': 'YYYY-MM-DD, last day records were seen'},
            'takuReplies': {'type': 'boolean', 'description': "true: include Taku's own earlier replies; false: leave them out; omit to decide from the query"}}}},
         {'name': 'memory_get', 'description': GET, 'inputSchema': {'type': 'object', 'additionalProperties': False, 'required': ['id'], 'properties': {'id': {'type': 'string'}}}}]
def call(path, args):
    body = json.dumps({'qid': qid, 'args': args}).encode()
    r = json.loads(urllib.request.urlopen(urllib.request.Request(f'http://127.0.0.1:{port}{path}', body, {'Content-Type': 'application/json'}), timeout=60).read())
    if not r['ok']: return True, r['message']
    return False, json.dumps(r['result'], ensure_ascii=False)
def reply(i, result): sys.stdout.write(json.dumps({'jsonrpc': '2.0', 'id': i, 'result': result}) + '\n'); sys.stdout.flush()
for line in sys.stdin:
    if not line.strip(): continue
    m = json.loads(line); meth = m.get('method'); i = m.get('id')
    if i is None: continue
    if meth == 'initialize': reply(i, {'protocolVersion': m['params'].get('protocolVersion', '2025-06-18'), 'capabilities': {'tools': {}}, 'serverInfo': {'name': 'taku_phone', 'version': '0.18'}})
    elif meth == 'tools/list': reply(i, {'tools': TOOLS})
    elif meth == 'tools/call':
        name = m['params']['name']; args = m['params'].get('arguments') or {}
        err, text = call('/search' if name == 'memory_search' else '/get', args)
        reply(i, {'content': [{'type': 'text', 'text': text}], 'isError': err})
    elif meth == 'ping': reply(i, {})
    else: sys.stdout.write(json.dumps({'jsonrpc': '2.0', 'id': i, 'error': {'code': -32601, 'message': 'unknown method'}}) + '\n'); sys.stdout.flush()
