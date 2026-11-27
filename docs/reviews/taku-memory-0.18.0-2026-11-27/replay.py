# Replays every search the reader made (calls.jsonl) through memsim to see which evidence turns it was shown, and splits
# the judged answers into "all evidence shown" and "some evidence never shown".
# Usage: replay.py models_dir longmemeval.json lmeg_dir calls.jsonl answers.jsonl judgments.jsonl
import json, sys, collections, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
sys.path.insert(0, sys.argv[3]); import memsim
models, raw_path, work, calls_p, ans_p, jud_p = sys.argv[1:7]
raw = {q['question_id']: q for q in json.load(open(raw_path))}
recs = json.load(open(work + '/records.json')); index = {k: i for i, k in enumerate(recs['keys'])}; V = np.load(work + '/vec.npy')
tok = Tokenizer.from_file(f'{models}/a8/tokenizer.json'); tok.enable_truncation(512)
s = ort.InferenceSession(f'{models}/a8/model.onnx', providers=['CPUExecutionProvider'])
def qv(t):
    ids = np.array([tok.encode(t).ids], dtype=np.int64); h = s.run(None, {'input_ids': ids, 'attention_mask': np.ones_like(ids)})[0][0].mean(0); return (h / np.linalg.norm(h)).astype(np.float32)
answered = {json.loads(l)['question_id'] for l in open(ans_p)}
ok = {json.loads(l)['question_id']: 'yes' in json.loads(l)['output'].lower() for l in open(jud_p)}
calls = collections.defaultdict(list)
for l in open(calls_p):
    c = json.loads(l)
    if c['qid'] in answered: calls[c['qid']].append(c)
split = collections.defaultdict(collections.Counter)
for qid in answered:
    q = raw[qid]
    if qid.endswith('_abs'): continue
    ev = {f'{sid}#{i}' for sid, sess in zip(q['haystack_session_ids'], q['haystack_sessions']) for i, t in enumerate(sess) if t.get('has_answer')}
    if not ev: continue
    ph = memsim.Phone(q, index, V); shown = set(); id2key = {r['id']: r['key'] for r in ph.rows}; ref2key = {r['ref']: r['key'] for r in ph.rows}
    for c in calls[qid]:
        a = c.get('args') or {}
        if 'error' in c: continue
        if c['path'] == '/search':
            res, _ = ph.search(a['query'], a.get('limit', 20), qv(a['query']), a.get('takuReplies'), a.get('from') or None, a.get('to') or None)
            shown |= {id2key[x['id']] for x in res['items']}
        else:
            shown |= {k for k in (id2key.get(a.get('id')), ref2key.get(a.get('id'))) if k}
    full = ev <= shown; t = q['question_type']
    for key in (t, 'all'):
        split[key]['n'] += 1; split[key]['shown'] += full; split[key]['ok_shown'] += full and ok[qid]; split[key]['ok_missing'] += (not full) and ok[qid]
for t, c in split.items():
    m = c['n'] - c['shown']
    print(f"{t:26s} n {c['n']:3d}  all evidence shown {c['shown'] / c['n']:.3f}  correct when shown {c['ok_shown'] / max(1, c['shown']):.3f} ({c['shown']})  when not {c['ok_missing'] / max(1, m):.3f} ({m})")
