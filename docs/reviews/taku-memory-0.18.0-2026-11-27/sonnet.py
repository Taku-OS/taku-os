# Calls Claude Sonnet through the claude CLI, one fresh session per prompt, no tools, a one-line system prompt.
# Resumable: finished ids in the output JSONL are skipped. Usage: sonnet.py in.json out.jsonl field workers
import json, sys, subprocess, concurrent.futures, os, time, threading
src, dst, field, workers = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
items = json.load(open(src)); done = set()
if os.path.exists(dst): done = {json.loads(l)['question_id'] for l in open(dst) if l.strip()}
todo = [x for x in items if x['question_id'] not in done]; lock = threading.Lock()
NOMCP = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'nomcp.json'); open(NOMCP, 'w').write('{"mcpServers":{}}')
CMD = ['claude', '-p', '--model', 'sonnet', '--output-format', 'json', '--system-prompt', 'You are a helpful assistant.', '--tools', '', '--strict-mcp-config', '--mcp-config', NOMCP, '--setting-sources', '', '--no-session-persistence', '--disable-slash-commands']
def run(x):
    for attempt in range(4):
        try:
            p = subprocess.run(CMD, input=x[field], capture_output=True, text=True, timeout=600, cwd='/tmp')
            r = json.loads(p.stdout)
            if r.get('is_error') or not r.get('result'): raise RuntimeError(str(r.get('result'))[:200])
            return {'question_id': x['question_id'], 'output': r['result'], 'model': list(r.get('modelUsage', {})), 'cost': r.get('total_cost_usd')}
        except Exception as e:
            err = str(e)[:300]; time.sleep(20 * (attempt + 1))
    return {'question_id': x['question_id'], 'error': err}
t0 = time.time(); n = 0
with concurrent.futures.ThreadPoolExecutor(workers) as ex:
    for r in ex.map(run, todo):
        if 'error' in r: print('failed', r['question_id'], r['error'], flush=True); continue
        with lock:
            open(dst, 'a').write(json.dumps(r) + '\n'); n += 1
        if n % 25 == 0: print(f'{n}/{len(todo)} {time.time() - t0:.0f}s', flush=True)
print('done', n, 'of', len(todo), f'{time.time() - t0:.0f}s')
