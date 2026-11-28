# Full-context baseline: no memory search; Sonnet 5.5 gets the whole history in its system prompt (from a file, so it is
# cached across a LoCoMo chat's questions) and the question as the message. Writes answers like agent.py. Resumable.
# LongMemEval: the official reader template (history as JSON, step by step), with every haystack session in date order.
# LoCoMo: every session in order, "Speaker: text", a shared photo as its caption, each session headed by its date.
# Usage: fullctx.py lme longmemeval.json out_dir workers | fullctx.py loco loco.json locomo10.json out_dir workers [ids_file]
import json, os, re, sys, subprocess, concurrent.futures, threading, time
kind = sys.argv[1]
def lme_items(path, out):
    for q in json.load(open(path)):
        sess = sorted(zip(q['haystack_dates'], q['haystack_session_ids'], q['haystack_sessions']))
        hist = ''.join('\n### Session {}:\nSession Date: {}\nSession Content:\n{}\n'.format(n + 1, d, '\n' + json.dumps([{k: v for k, v in t.items() if k != 'has_answer'} for t in s]))
                       for n, (d, _, s) in enumerate(sess))
        ctx = ('I will give you several history chats between you and a user. Please answer the question based on the relevant chat history. '
               'Answer the question step by step: first extract all the relevant information, and then reason over the information to get the answer.\n\n\nHistory Chats:\n\n' + hist)
        f = f'{out}/ctx/{q["question_id"]}.txt'; open(f, 'w').write(ctx)
        yield {'question_id': q['question_id'], 'file': f, 'message': f"Current Date: {q['question_date']}\nQuestion: {q['question']}\nAnswer (step by step):"}
def loco_items(path, src, out):
    d = json.load(open(path)); raw = json.load(open(src)); files = {}
    for ci, x in enumerate(raw):
        c = x['conversation']; parts = [f"Below is the complete history of chats between {c['speaker_a']} and {c['speaker_b']}, in order. Each session starts with its date and time.\n"]
        for n in sorted(int(k.split('_')[1]) for k in c if re.fullmatch(r'session_\d+', k)):
            parts.append(f"\n=== Session {n}: {c[f'session_{n}_date_time']} ===")
            for t in c[f'session_{n}']: parts.append(f"{t['speaker']}: {t['text'].strip()}" + (f" [shared a photo: {t['blip_caption']}]" if t.get('blip_caption') else ''))
        files[ci] = f'{out}/ctx/conv{ci}.txt'; open(files[ci], 'w').write('\n'.join(parts) + '\n')
    for q in d['questions']: yield {'question_id': q['question_id'], 'file': files[q['phone']], 'message': f"Today is {q['question_date']}.\n{q['question']}"}
if kind == 'lme': out, workers = sys.argv[3], int(sys.argv[4]); os.makedirs(out + '/ctx', exist_ok=True); items = list(lme_items(sys.argv[2], out)); keep = None
else:
    out, workers = sys.argv[4], int(sys.argv[5]); os.makedirs(out + '/ctx', exist_ok=True); items = list(loco_items(sys.argv[2], sys.argv[3], out))
    keep = set(open(sys.argv[6]).read().split()) if len(sys.argv) > 6 else None
if kind == 'lme' and len(sys.argv) > 5: keep = set(open(sys.argv[5]).read().split())
if keep: items = [x for x in items if x['question_id'] in keep]
dst = out + '/answers.jsonl'; done = {json.loads(l)['question_id'] for l in open(dst)} if os.path.exists(dst) else set()
todo = [x for x in items if x['question_id'] not in done]; lock = threading.Lock()
nomcp = out + '/nomcp.json'; open(nomcp, 'w').write('{"mcpServers":{}}')
def run(x):
    cmd = ['claude', '-p', '--model', 'sonnet', '--output-format', 'json', '--system-prompt-file', x['file'], '--tools', '', '--strict-mcp-config', '--mcp-config', nomcp,
           '--setting-sources', '', '--no-session-persistence', '--disable-slash-commands']
    err = ''
    for attempt in range(4):
        try:
            p = subprocess.run(cmd, input=x['message'], capture_output=True, text=True, timeout=900, cwd='/tmp'); r = json.loads(p.stdout)
            if r.get('is_error') or not r.get('result'): raise RuntimeError(str(r.get('result'))[:200])
            return {'question_id': x['question_id'], 'output': r['result'], 'model': list(r.get('modelUsage', {})), 'cost': r.get('total_cost_usd'), 'usage': r.get('usage')}
        except Exception as e: err = str(e)[:300]; time.sleep(20 * (attempt + 1))
    return {'question_id': x['question_id'], 'error': err}
t0 = time.time(); n = 0
with concurrent.futures.ThreadPoolExecutor(workers) as ex:
    for r in ex.map(run, todo):
        if 'error' in r: print('failed', r['question_id'], r['error'], flush=True); continue
        with lock: open(dst, 'a').write(json.dumps(r) + '\n'); n += 1
        if n % 50 == 0: print(f'{n}/{len(todo)} {time.time() - t0:.0f}s', flush=True)
print('done', n, 'of', len(todo), flush=True)
