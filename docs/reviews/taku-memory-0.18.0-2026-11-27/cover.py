# One search with the question as the query, as 0.17 was measured: how often every evidence turn is among the results,
# by question type, for memory 0.18 (Taku chat records: replies searched only when the question asks about them).
# Usage: cover.py models_dir longmemeval.json lmeg_dir
import json, sys, collections, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
sys.path.insert(0, sys.argv[3]); import memsim
models, raw_path, work = sys.argv[1:4]
raw = json.load(open(raw_path)); recs = json.load(open(work + '/records.json')); index = {k: i for i, k in enumerate(recs['keys'])}; V = np.load(work + '/vec.npy')
tok = Tokenizer.from_file(f'{models}/a8/tokenizer.json'); tok.enable_truncation(512)
s = ort.InferenceSession(f'{models}/a8/model.onnx', providers=['CPUExecutionProvider'])
def qv(t):
    ids = np.array([tok.encode(t).ids], dtype=np.int64); h = s.run(None, {'input_ids': ids, 'attention_mask': np.ones_like(ids)})[0][0].mean(0); return (h / np.linalg.norm(h)).astype(np.float32)
K = [10, 20, 30, 40, 50]; cov = {k: collections.Counter() for k in K}; n = collections.Counter(); dropped = collections.Counter(); asked = collections.Counter(); chars = {k: [] for k in K}
for q in raw:
    if q['question_id'].endswith('_abs'): continue
    ev = {f'{sid}#{i}' for sid, sess in zip(q['haystack_session_ids'], q['haystack_sessions']) for i, t in enumerate(sess) if t.get('has_answer')}
    if not ev: continue
    ph = memsim.Phone(q, index, V); t = q['question_type']; n[t] += 1; n['all'] += 1
    kept = {r['key'] for r in ph.rows}
    if not ev <= kept: dropped[t] += 1
    v = qv(q['question']); id2key = {r['id']: r['key'] for r in ph.rows}
    for k in K:
        res, taku = ph.search(q['question'], k, v); got = {id2key[x['id']] for x in res['items']}
        ok = ev <= got; cov[k][t] += ok; cov[k]['all'] += ok; chars[k].append(sum(len(x['body']) for x in res['items']))
        if k == 20: asked[t] += taku
types = ['single-session-user', 'single-session-preference', 'single-session-assistant', 'multi-session', 'temporal-reasoning', 'knowledge-update']
print('questions', dict(n)); print('evidence dropped by the chat filter (questions):', dict(dropped)); print('question read as asking what Taku said (limit 20):', dict(asked))
for k in K: print(f'limit {k}: all {cov[k]["all"] / n["all"]:.3f} | ' + ' '.join(f'{t.split("-")[0][:1]}{t.split("-")[1][:3]} {cov[k][t] / n[t]:.3f}' for t in types) + f' | excerpt chars {np.mean(chars[k]) / 1000:.1f}k')
