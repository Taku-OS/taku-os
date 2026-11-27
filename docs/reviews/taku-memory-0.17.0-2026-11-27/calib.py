# The card picks extra items whose similarity to the incoming message is at least 0.45 (tuned on bge-small-zh).
# Find the a8m score that keeps the same share of unrelated pairs out, and see how many related pairs each lets in.
import sys, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); from cases import TARGETS, DISTRACTORS; from spec import SPEC
MD = sys.argv[1]   # models_dir
def model(name):
    sp = SPEC[name]; tok = Tokenizer.from_file(f'{MD}/{name}/tokenizer.json'); o = ort.SessionOptions(); o.intra_op_num_threads = 2
    s = ort.InferenceSession(f'{MD}/{name}/{sp["file"]}', o, providers=['CPUExecutionProvider']); ins = [i.name for i in s.get_inputs()]
    def enc(t, q):
        e = tok.encode((sp['q'] if q else sp['d']) + t); ids = np.array([e.ids[:512]], dtype=np.int64); f = {'input_ids': ids, 'attention_mask': np.ones_like(ids)}
        if 'token_type_ids' in ins: f['token_type_ids'] = np.zeros_like(ids)
        out = s.run(None, f); v = out[0][0][0] if sp['pool'] == 'cls' else out[0][0].mean(0); return v / np.linalg.norm(v)
    return enc
docs = [t[0] for t in TARGETS] + DISTRACTORS
def lang(x): return 'zh' if any('一' <= c <= '鿿' for c in x) else 'en'
res = {}
for name in ['bge', 'a8']:
    enc = model(name); D = np.stack([enc(d, False) for d in docs]); pos = []; neg = []; posx = []
    for i, (d, same, cross) in enumerate(TARGETS):
        for q, kind in ((same, 'same'), (cross, 'cross')):
            sc = D @ enc(q, True)
            (pos if kind == 'same' else posx).append(sc[i])
            neg += [sc[j] for j in range(len(docs)) if j != i and lang(docs[j]) == lang(q)]
    res[name] = (np.array(pos), np.array(posx), np.array(neg))
pos, posx, neg = res['bge']; fa = (neg >= 0.45).mean()
print(f'bge @0.45: unrelated pass {fa:.3f}, related same-language pass {(pos>=0.45).mean():.2f}, cross-language {(posx>=0.45).mean():.2f}')
pos, posx, neg = res['a8']; t = float(np.quantile(neg, 1 - fa))
print(f'a8 threshold with the same unrelated pass rate: {t:.3f}')
for th in [round(t, 2), 0.25, 0.3, 0.33, 0.35, 0.4, 0.45]:
    print(f'a8 @{th:.2f}: unrelated pass {(neg>=th).mean():.3f}, related same-language pass {(pos>=th).mean():.2f}, cross-language {(posx>=th).mean():.2f}')
print('a8 related median %.3f, unrelated median %.3f, unrelated 99th %.3f' % (np.median(pos), np.median(neg), np.quantile(neg, .99)))
