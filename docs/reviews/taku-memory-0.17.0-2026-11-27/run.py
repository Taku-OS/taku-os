"""Phone-style set: same-language and cross-language retrieval per model. Usage: run.py models_dir name..."""
import sys, time, json, statistics
import os
import numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); from cases import TARGETS, DISTRACTORS; from spec import SPEC
MD = sys.argv[1]
docs = [t[0] for t in TARGETS] + DISTRACTORS
def lang(s): return 'zh' if any('一' <= c <= '鿿' for c in s) else 'en'
qs = []
for i, (d, same, cross) in enumerate(TARGETS):
    qs.append((same, i, lang(same) + '>' + lang(d))); qs.append((cross, i, lang(cross) + '>' + lang(d)))
for name in sys.argv[2:]:
    sp = SPEC[name]; tok = Tokenizer.from_file(f'{MD}/{name}/tokenizer.json')
    o = ort.SessionOptions(); o.intra_op_num_threads = 2
    s = ort.InferenceSession(f'{MD}/{name}/{sp["file"]}', o, providers=['CPUExecutionProvider'])
    ins = [i.name for i in s.get_inputs()]
    def enc(text):
        e = tok.encode(text); ids = np.array([e.ids[:512]], dtype=np.int64); m = np.ones_like(ids)
        feed = {'input_ids': ids, 'attention_mask': m}
        if 'token_type_ids' in ins: feed['token_type_ids'] = np.zeros_like(ids)
        out = s.run(None, feed)
        if sp['pool'] == 'cls': v = out[0][0][0]
        elif sp['pool'] == 'out1': v = out[1][0]
        else: v = out[0][0].mean(0)
        v = v / np.linalg.norm(v); return v, ids.shape[1]
    t0 = time.time(); D = []; lens = []
    for d in docs: v, n = enc(sp['d'] + d); D.append(v); lens.append(n)
    tdoc = (time.time() - t0) / len(docs) * 1000; D = np.stack(D)
    by = {}
    for q, gold, kind in qs:
        v, _ = enc(sp['q'] + q); sc = D @ v; rank = int((sc > sc[gold]).sum()) + 1
        for k in (kind, 'all'): by.setdefault(k, []).append(rank)
    print(f'== {name}  dim={D.shape[1]}  {tdoc:.1f} ms/doc (mean {statistics.mean(lens):.0f} tokens)')
    for k in ['all', 'zh>zh', 'en>en', 'zh>en', 'en>zh']:
        r = by[k]; print(f'  {k:6s} n={len(r):3d}  R@1={sum(x==1 for x in r)/len(r):.2f}  R@5={sum(x<=5 for x in r)/len(r):.2f}  MRR={statistics.mean(1/x for x in r):.3f}')
