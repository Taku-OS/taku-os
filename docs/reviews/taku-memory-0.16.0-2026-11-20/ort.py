"""Times one query embedding with the same quantized bge-small-zh export, 2 intra-op threads like Embedder.java."""
import onnxruntime as ort, numpy as np, time, statistics, sys
model = sys.argv[1]
o = ort.SessionOptions(); o.intra_op_num_threads = 2
a = time.perf_counter(); s = ort.InferenceSession(model, o, providers=['CPUExecutionProvider']); init = (time.perf_counter() - a) * 1000
names = [i.name for i in s.get_inputs()]
for L in (24, 40, 64):
    feed = {'input_ids': np.random.randint(100, 20000, (1, L)).astype(np.int64), 'attention_mask': np.ones((1, L), np.int64)}
    if 'token_type_ids' in names: feed['token_type_ids'] = np.zeros((1, L), np.int64)
    for _ in range(3): s.run(None, feed)
    ts = []
    for _ in range(20):
        a = time.perf_counter(); s.run(None, feed); ts.append((time.perf_counter() - a) * 1000)
    print('tokens %d: median %.1f ms, p95 %.1f ms' % (L, statistics.median(ts), sorted(ts)[18]))
print('session init %.0f ms' % init)
