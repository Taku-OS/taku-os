# replay.py for LoCoMo: which evidence messages the reader was shown (all its searches and reads replayed through memsim),
# and accuracy under one judge split by that. Questions whose evidence ids do not name a message (12, a dataset slip) are
# left out of the split. Usage: loco_replay.py models_dir loco_dir calls.jsonl answers.jsonl judgments.jsonl
import json, os, re, sys, collections, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); import memsim
models, work, calls_p, ans_p, jud_p = sys.argv[1:6]
d = json.load(open(work + '/loco.json')); V = np.load(work + '/loco-vec.npy'); phones = {p['id']: memsim.Phone.of(p['rows'], V, p['now']) for p in d['phones']}
qs = {q['question_id']: q for q in d['questions']}; names = {1: 'multi-hop', 2: 'temporal', 3: 'open-domain', 4: 'single-hop'}
tok = Tokenizer.from_file(f'{models}/a8/tokenizer.json'); tok.enable_truncation(512); s = ort.InferenceSession(f'{models}/a8/model.onnx', providers=['CPUExecutionProvider'])
def qv(t):
    ids = np.array([tok.encode(t).ids], dtype=np.int64); h = s.run(None, {'input_ids': ids, 'attention_mask': np.ones_like(ids)})[0][0].mean(0); return (h / np.linalg.norm(h)).astype(np.float32)
def label(t):
    m = re.search(r'"label"\s*:\s*"(CORRECT|WRONG)"', t)
    return m.group(1) == 'CORRECT' if m else t.rfind('CORRECT') > t.rfind('WRONG')
ok = {json.loads(l)['question_id']: label(json.loads(l)['output']) for l in open(jud_p)}; answered = {json.loads(l)['question_id'] for l in open(ans_p)}
calls = collections.defaultdict(list)
for l in open(calls_p):
    c = json.loads(l)
    if c['qid'] in answered: calls[c['qid']].append(c)
split = collections.defaultdict(collections.Counter)
for qid in answered:
    q = qs[qid]; ph = phones[q['phone']]; keys = {r['key'] for r in ph.rows}
    if not q['evidence'] or not set(q['evidence']) <= keys: continue
    id2key = {r['id']: r['key'] for r in ph.rows}; ref2key = {r['ref']: r['key'] for r in ph.rows}; shown = set()
    for c in calls[qid]:
        a = c.get('args') or {}
        if 'error' in c: continue
        if c['path'] == '/search':
            res, _ = ph.search(a['query'], a.get('limit', 20), qv(a['query']), a.get('takuReplies'), a.get('from') or None, a.get('to') or None); shown |= {id2key[x['id']] for x in res['items']}
        else: shown |= {k for k in (id2key.get(a.get('id')), ref2key.get(a.get('id'))) if k}
    full = set(q['evidence']) <= shown
    for k in (names[q['category']], 'all'):
        split[k]['n'] += 1; split[k]['shown'] += full; split[k]['ok_shown'] += full and ok[qid]; split[k]['ok_missing'] += (not full) and ok[qid]
for t, c in split.items():
    m = c['n'] - c['shown']
    print(f"{t:12s} n {c['n']:4d}  all evidence shown {c['shown'] / c['n']:.3f}  correct when shown {c['ok_shown'] / max(1, c['shown']):.3f} ({c['shown']})  when not {c['ok_missing'] / max(1, m):.3f} ({m})")
