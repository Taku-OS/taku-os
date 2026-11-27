# Recall's evidence path on the phone-style set: keyword list (SQLite, as Recall builds it) + vector top 30, fused by rank
# (1/(60+r)), as in Recall.search without graph nodes. Old: bge-small-zh vectors + whole-query keywords (all must match).
# New: bekko-embedding-v1-a8m vectors + content words for questions with two or more of them (all must match, newest first).
import sys, sqlite3, numpy as np, onnxruntime as ort, statistics
from tokenizers import Tokenizer
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); from cases import TARGETS, DISTRACTORS; from spec import SPEC
MD, QW = sys.argv[1], sys.argv[2]   # models_dir, content words per query from DumpWords.java
docs = [t[0] for t in TARGETS] + DISTRACTORS
qs = [(q, i) for i, t in enumerate(TARGETS) for q in t[1:]]
words = [None if l == '-' else l.split('\x01') for l in open(QW, encoding='utf-8').read().split('\n')[:len(qs)]]
db = sqlite3.connect(':memory:'); db.execute('CREATE TABLE m(id INTEGER, t TEXT, seen INTEGER)')
db.executemany('INSERT INTO m VALUES(?,?,?)', [(i, d, i) for i, d in enumerate(docs)])   # later rows count as newer
def caseless(t): return all(c.lower() == c.upper() for c in t)
def cond(t, args):
    if caseless(t): args.append(t); return 'instr(t,?)>0'
    args.append('%' + t.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'); return "t LIKE ? ESCAPE '\\'"
def keyword(q, w):
    args = []
    terms = [t.lower() for t in q.split()] if w is None else w   # Recall: a question's content words replace its pieces; all must match
    where = ' AND '.join(cond(t, args) for t in terms) or '1'; order = 'seen DESC'
    return [r[0] for r in db.execute(f'SELECT id FROM m WHERE {where} ORDER BY {order} LIMIT 30', args)]
def lang(x): return 'zh' if any('一' <= c <= '鿿' for c in x) else 'en'
for name, use_words in (('bge', False), ('a8', True)):
    sp = SPEC[name]; tok = Tokenizer.from_file(f'{MD}/{name}/tokenizer.json'); o = ort.SessionOptions(); o.intra_op_num_threads = 2
    s = ort.InferenceSession(f'{MD}/{name}/{sp["file"]}', o, providers=['CPUExecutionProvider']); ins = [i.name for i in s.get_inputs()]
    def enc(t, q):
        e = tok.encode((sp['q'] if q else sp['d']) + t); ids = np.array([e.ids[:512]], dtype=np.int64); f = {'input_ids': ids, 'attention_mask': np.ones_like(ids)}
        if 'token_type_ids' in ins: f['token_type_ids'] = np.zeros_like(ids)
        out = s.run(None, f); v = out[0][0][0] if sp['pool'] == 'cls' else out[0][0].mean(0); return v / np.linalg.norm(v)
    D = np.stack([enc(d, False) for d in docs]); by = {}; kwhits = 0
    for (q, gold), w in zip(qs, words):
        kl = keyword(q, w if use_words else None); kwhits += gold in kl
        sc = D @ enc(q, True); vl = list(np.argsort(-sc, kind='stable')[:30])
        score = {}
        for l in (kl, vl):
            for r, x in enumerate(l): score[int(x)] = score.get(int(x), 0) + 1 / (60 + r)
        ranked = sorted(score, key=lambda x: -score[x]); rank = ranked.index(gold) + 1 if gold in ranked else 99
        kind = lang(q) + '>' + lang(docs[gold])
        for k in (kind, 'all'): by.setdefault(k, []).append(rank)
    print(f'== {"before (bge-small-zh, whole-query keywords)" if name == "bge" else "after (bekko a8m, content words)"}: keyword list holds the answer for {kwhits}/{len(qs)} queries')
    for k in ['all', 'zh>zh', 'en>en', 'zh>en', 'en>zh']:
        r = by[k]; print(f'  {k:6s} R@1={sum(x == 1 for x in r) / len(r):.2f}  R@5={sum(x <= 5 for x in r) / len(r):.2f}  MRR={statistics.mean(1 / x for x in r):.3f}')
