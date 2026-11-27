# Agentic LongMemEval reader: Sonnet answers each question as Taku on a phone whose memory holds the haystack, calling
# memory_search / memory_get itself (MCP stand-ins for the phone tools). The system prompt is the phone harness's memory
# policy (read from taku_harness.json); the user message gives today's date and the question. Resumable.
# Usage: agent.py longmemeval.json out.jsonl port workers [ids_file]
import json, os, re, sys, subprocess, concurrent.futures, threading, time, tempfile
ROOT = os.environ.get('TAKU_REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../..'))
raw_path, dst, port, workers = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
qs = json.load(open(raw_path))
if len(sys.argv) > 5: keep = set(open(sys.argv[5]).read().split()); qs = [q for q in qs if q['question_id'] in keep]
done = {json.loads(l)['question_id'] for l in open(dst)} if os.path.exists(dst) else set()
todo = [q for q in qs if q['question_id'] not in done]; lock = threading.Lock(); here = os.path.dirname(os.path.abspath(__file__))
harness = json.load(open(ROOT + '/system/assistant/res/raw/taku_harness.json', encoding='utf-8'))
text = json.dumps(harness, ensure_ascii=False)
policy = json.loads('"' + re.search(r'(Search Taku Memory as needed.*?private directories\.)', text).group(1) + '"').replace('taku_phone.memory_search', 'memory_search')
SYSTEM = "You are Taku, the assistant on the user's phone. You and the user have talked many times before. " + policy + " Answer the user's question."
TOOLS = 'mcp__taku_phone__memory_search,mcp__taku_phone__memory_get'
def run(q):
    cfg = {'mcpServers': {'taku_phone': {'command': 'python3', 'args': ['-I', here + '/mcp_taku.py', q['question_id'], str(port)]}}}
    with tempfile.NamedTemporaryFile('w', suffix='.json', delete=False, dir=here + '/cfg') as f: json.dump(cfg, f); path = f.name
    cmd = ['claude', '-p', '--model', 'sonnet', '--output-format', 'json', '--system-prompt', SYSTEM, '--tools', '', '--allowedTools', TOOLS,
           '--strict-mcp-config', '--mcp-config', path, '--setting-sources', '', '--no-session-persistence', '--disable-slash-commands', '--max-turns', '40']
    msg = f"Today is {q['question_date']}.\n{q['question']}"; err = ''
    for attempt in range(3):
        try:
            p = subprocess.run(cmd, input=msg, capture_output=True, text=True, timeout=900, cwd='/tmp'); r = json.loads(p.stdout)
            if r.get('is_error') or not r.get('result'): raise RuntimeError(str(r.get('subtype')) + ' ' + str(r.get('result'))[:200])
            os.unlink(path)
            return {'question_id': q['question_id'], 'output': r['result'], 'turns': r.get('num_turns'), 'model': list(r.get('modelUsage', {})), 'cost': r.get('total_cost_usd')}
        except Exception as e: err = str(e)[:300]; time.sleep(15 * (attempt + 1))
    return {'question_id': q['question_id'], 'error': err}
os.makedirs(here + '/cfg', exist_ok=True); t0 = time.time(); n = 0
with concurrent.futures.ThreadPoolExecutor(workers) as ex:
    for r in ex.map(run, todo):
        if 'error' in r: print('failed', r['question_id'], r['error'], flush=True); continue
        with lock: open(dst, 'a').write(json.dumps(r) + '\n'); n += 1
        if n % 25 == 0: print(f'{n}/{len(todo)} {time.time() - t0:.0f}s', flush=True)
print('done', n, 'of', len(todo), f'{time.time() - t0:.0f}s', flush=True)
