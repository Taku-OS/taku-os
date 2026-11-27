# Vectors for every LongMemEval_S turn stored the way memory 0.18 stores a Taku chat turn: title "Taku", empty head, the
# message with runs of spaces and tabs collapsed and trimmed (MemoryStore.chat), embedded as Recall.evidenceText cut to
# 600 UTF-16 units (MemoryStore.embedNow). a8m batched encoding is exact (checked for 0.17).
import json, re, sys, time, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
models, raw_path, work = sys.argv[1], sys.argv[2], sys.argv[3]
raw = json.load(open(raw_path)); text = {}
for q in raw:
    for sid, sess in zip(q['haystack_session_ids'], q['haystack_sessions']):
        for i, t in enumerate(sess):
            k = f'{sid}#{i}'
            if k not in text: text[k] = (t['role'], re.sub(r'[ \t]+', ' ', t['content']).strip())
keys = sorted(text)
def cut(s, n=600):
    b = s.encode('utf-16-le', 'surrogatepass')[:2 * n]; return b.decode('utf-16-le', 'ignore')
emb = [cut('Taku ' + '' + '\n' + text[k][1]) for k in keys]
json.dump({'keys': keys, 'role': [text[k][0] for k in keys], 'text': [text[k][1] for k in keys]}, open(work + '/records.json', 'w'))
tok = Tokenizer.from_file(f'{models}/a8/tokenizer.json'); tok.enable_truncation(512); tok.no_padding()
o = ort.SessionOptions(); o.intra_op_num_threads = 2; s = ort.InferenceSession(f'{models}/a8/model.onnx', o, providers=['CPUExecutionProvider'])
enc = [tok.encode(e).ids for e in emb]; order = sorted(range(len(keys)), key=lambda i: len(enc[i])); out = np.zeros((len(keys), 384), dtype=np.float32); t0 = time.time()
print(len(keys), 'turns, mean tokens', np.mean([len(e) for e in enc]), flush=True)
for b in range(0, len(order), 32):
    idx = order[b:b + 32]; L = max(len(enc[i]) for i in idx); ids = np.zeros((len(idx), L), dtype=np.int64); m = np.zeros_like(ids)
    for r, i in enumerate(idx): ids[r, :len(enc[i])] = enc[i]; m[r, :len(enc[i])] = 1
    h = s.run(None, {'input_ids': ids, 'attention_mask': m})[0]; v = (h * m[..., None]).sum(1) / m.sum(1, keepdims=True)
    out[idx] = v / np.linalg.norm(v, axis=1, keepdims=True)
    if b // 32 % 500 == 0: print(f'{b}/{len(order)} {time.time() - t0:.0f}s', flush=True)
np.save(work + '/vec.npy', out); print('done', f'{time.time() - t0:.0f}s', flush=True)
