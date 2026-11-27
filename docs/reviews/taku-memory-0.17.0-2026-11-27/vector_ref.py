#!/usr/bin/env python3
"""Reference vectors for VectorParity.java: the Hugging Face tokenizer, the same ONNX model, mean of the last hidden states,
L2 normalized (the model's sentence-transformers pooling). Usage: vector_ref.py model_dir out_dir [tokenizer_texts_dir]
Texts: the phone-style set, plus lines 200-260 of the tokenizer parity texts when given (blank texts skipped)."""
import base64, os, sys, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
sys.path.insert(0, os.path.dirname(__file__)); from cases import TARGETS, DISTRACTORS
texts = [t for x in TARGETS for t in x] + DISTRACTORS
if len(sys.argv) > 3: texts += [base64.b64decode(l).decode() for l in open(sys.argv[3] + '/texts.b64').read().split('\n')[200:260]]
texts = [t for t in texts if t.strip()]
tk = Tokenizer.from_file(sys.argv[1] + '/tokenizer.json'); s = ort.InferenceSession(sys.argv[1] + '/model.onnx', providers=['CPUExecutionProvider'])
rows = []
for t in texts:
    ids = np.array([tk.encode(t).ids[:512]], dtype=np.int64); h = s.run(None, {'input_ids': ids, 'attention_mask': np.ones_like(ids)})[0][0].mean(0); rows.append(' '.join('%.7f' % x for x in h / np.linalg.norm(h)))
open(sys.argv[2] + '/vtexts.b64', 'w').write(''.join(base64.b64encode(t.encode()).decode() + '\n' for t in texts))
open(sys.argv[2] + '/vref.txt', 'w').write('\n'.join(rows) + '\n')
print(len(texts), 'texts')
