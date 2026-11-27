# Local stand-in for the phone's memory provider during the agentic LongMemEval run: POST /search and /get with a
# question id, answered by memsim.Phone over that question's haystack. Every call is logged for later analysis.
# Usage: server.py models_dir longmemeval.json lmeg_dir port
import json, sys, time, threading, numpy as np, onnxruntime as ort
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from tokenizers import Tokenizer
sys.path.insert(0, sys.argv[3]); import memsim
models, raw_path, work, port = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
raw = {q['question_id']: q for q in json.load(open(raw_path))}
recs = json.load(open(work + '/records.json')); index = {k: i for i, k in enumerate(recs['keys'])}; V = np.load(work + '/vec.npy')
tok = Tokenizer.from_file(f'{models}/a8/tokenizer.json'); tok.enable_truncation(512)
o = ort.SessionOptions(); o.intra_op_num_threads = 2; sess = ort.InferenceSession(f'{models}/a8/model.onnx', o, providers=['CPUExecutionProvider'])
phones, qcache, lock, log = {}, {}, threading.Lock(), open(work + '/calls.jsonl', 'a')
def phone(qid):
    with lock:
        if qid not in phones:
            if len(phones) > 24: phones.pop(next(iter(phones)))
            phones[qid] = memsim.Phone(raw[qid], index, V)
        return phones[qid]
def qvec(text):
    if text in qcache: return qcache[text]
    ids = np.array([tok.encode(text).ids], dtype=np.int64); h = sess.run(None, {'input_ids': ids, 'attention_mask': np.ones_like(ids)})[0][0].mean(0)
    v = (h / np.linalg.norm(h)).astype(np.float32); qcache[text] = v; return v
class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_POST(self):
        t0 = time.time(); p = json.loads(self.rfile.read(int(self.headers['Content-Length']))); qid = p['qid']; out = {}
        try:
            ph = phone(qid)
            if self.path == '/search':
                a = p['args']; res, taku = ph.search(a['query'], a.get('limit', 20), qvec(a['query']), a.get('takuReplies'), a.get('from') or None, a.get('to') or None)
                out = {'ok': True, 'result': res}; info = {'n': len(res['items']), 'taku': taku}
            else:
                out = {'ok': True, 'result': ph.get(p['args']['id'])}; info = {}
        except Exception as e:
            out = {'ok': False, 'message': str(e)}; info = {'error': str(e)}
        with lock: log.write(json.dumps({'qid': qid, 'path': self.path, 'args': p.get('args'), **info, 'ms': int(1000 * (time.time() - t0))}) + '\n'); log.flush()
        b = json.dumps(out, ensure_ascii=False).encode(); self.send_response(200); self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)
print('ready', len(raw), 'questions', flush=True)
ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()
