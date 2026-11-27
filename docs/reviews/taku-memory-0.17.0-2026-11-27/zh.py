"""300 Chinese MMarco dev queries against their positives plus 3,000 random passages. Usage: zh.py models_dir mmarco_dir name..."""
import sys, time, random, statistics
import numpy as np, onnxruntime as ort, pyarrow.parquet as pq
from tokenizers import Tokenizer
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__))); from spec import SPEC
MD, DD = sys.argv[1], sys.argv[2]   # models_dir, directory with C-MTEB/MMarcoRetrieval corpus/queries and -qrels dev parquet files
c = {x['id']: x['text'] for x in pq.read_table(f'{DD}/corpus.parquet').to_pylist()}
q = {x['id']: x['text'] for x in pq.read_table(f'{DD}/queries.parquet').to_pylist()}
rel = {}
for x in pq.read_table(f'{DD}/qrels.parquet').to_pylist():
    if x['score'] > 0 and x['pid'] in c: rel.setdefault(x['qid'], set()).add(x['pid'])
rnd = random.Random(7); qids = sorted(k for k in rel if k in q); rnd.shuffle(qids); qids = qids[:300]
pos = sorted({p for k in qids for p in rel[k]}); rest = sorted(set(c) - set(pos)); rnd.shuffle(rest)
docs = pos + rest[:3000]; idx = {d: i for i, d in enumerate(docs)}
for name in sys.argv[3:]:
    sp = SPEC[name]; tok = Tokenizer.from_file(f'{MD}/{name}/tokenizer.json')
    o = ort.SessionOptions(); o.intra_op_num_threads = 2
    s = ort.InferenceSession(f'{MD}/{name}/{sp["file"]}', o, providers=['CPUExecutionProvider']); ins = [i.name for i in s.get_inputs()]
    def enc(text):
        e = tok.encode(text); ids = np.array([e.ids[:512]], dtype=np.int64); feed = {'input_ids': ids, 'attention_mask': np.ones_like(ids)}
        if 'token_type_ids' in ins: feed['token_type_ids'] = np.zeros_like(ids)
        out = s.run(None, feed); v = out[0][0][0] if sp['pool'] == 'cls' else out[1][0] if sp['pool'] == 'out1' else out[0][0].mean(0)
        return v / np.linalg.norm(v), ids.shape[1]
    t0 = time.time(); D = []; L = []
    for d in docs: v, n = enc(sp['d'] + c[d]); D.append(v); L.append(n)
    ms = (time.time() - t0) / len(docs) * 1000; D = np.stack(D); rr = []; hit10 = 0
    for k in qids:
        v, _ = enc(sp['q'] + q[k]); sc = D @ v; order = np.argsort(-sc)
        ranks = [int(np.where(order == idx[p])[0][0]) + 1 for p in rel[k]]; r = min(ranks); rr.append(1 / r if r <= 10 else 0); hit10 += r <= 10
    print(f'{name:4s} MRR@10={statistics.mean(rr):.3f}  R@10={hit10/len(qids):.3f}  {ms:.1f} ms/doc (mean {statistics.mean(L):.0f} tokens)', flush=True)
