# LoCoMo (snap-research/locomo, data/locomo10.json) as ten phones: each conversation's messages are captured chat records
# from a messaging app (speaker "", titled with the sender, like a message notification), first seen at the session's
# time plus one second per message; a shared photo adds its caption. Questions of categories 1-4 (multi-hop, temporal,
# open-domain, single-hop; adversarial 5 is left out, as everyone scores it) are asked on the day of the last session.
# Writes loco.json (phones, questions) and loco-vec.npy (bekko-embedding-v1-a8m, as MemoryStore.embedNow encodes).
# Usage: loco_prep.py models_dir locomo10.json out_dir
import json, re, sys, datetime, numpy as np, onnxruntime as ort
from tokenizers import Tokenizer
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__))); import memsim
models, src, out = sys.argv[1:4]
data = json.load(open(src)); WD = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
def when(s): return datetime.datetime.strptime(s.strip(), '%I:%M %p on %d %B, %Y').replace(tzinfo=datetime.timezone.utc)
phones, questions, texts = [], [], []
for ci, x in enumerate(data):
    c = x['conversation']; rows = []; last = None
    for n in sorted(int(k.split('_')[1]) for k in c if re.fullmatch(r'session_\d+', k)):
        t0 = when(c[f'session_{n}_date_time']); last = t0 if last is None or t0 > last else last
        for i, t in enumerate(c[f'session_{n}']):
            text = t['text'].strip() + (f" [shared a photo: {t['blip_caption']}]" if t.get('blip_caption') else '')
            body = re.sub(r'[ \t]+', ' ', text).strip()
            if len(body) < 8 or memsim.SECRET.search(body): continue
            rows.append({'key': f"conv{ci}:{t['dia_id']}", 'idx': len(texts), 'seen': int(t0.timestamp() * 1000) + 1000 * i, 'speaker': '',
                         'title': t['speaker'], 'source': 'com.google.android.apps.messaging', 'text': body})
            texts.append(t['speaker'] + ' \n' + body)
    day = last.replace(hour=0, minute=0); now = int((day + datetime.timedelta(days=1)).timestamp() * 1000) - 1
    phones.append({'id': ci, 'speakers': [c['speaker_a'], c['speaker_b']], 'now': now, 'rows': rows})
    stamp = f"{last:%Y/%m/%d} ({WD[last.weekday()]}) {last:%H:%M}"
    for j, q in enumerate(x['qa']):
        if q['category'] == 5: continue
        ev = [f'conv{ci}:{e.strip()}' for part in q.get('evidence', []) for e in re.split(r'[;,]', part) if e.strip()]
        questions.append({'question_id': f'conv{ci}-q{j}', 'phone': ci, 'category': q['category'], 'question': q['question'], 'answer': str(q['answer']),
                          'question_date': stamp, 'evidence': ev})
tok = Tokenizer.from_file(f'{models}/a8/tokenizer.json'); tok.enable_truncation(512); tok.no_padding()
s = ort.InferenceSession(f'{models}/a8/model.onnx', providers=['CPUExecutionProvider']); V = np.zeros((len(texts), 384), np.float32)
enc = [tok.encode(memsim_cut) .ids for memsim_cut in (t.encode('utf-16-le', 'surrogatepass')[:1200].decode('utf-16-le', 'ignore') for t in texts)]
order = sorted(range(len(texts)), key=lambda i: len(enc[i]))
for b in range(0, len(order), 32):
    idx = order[b:b + 32]; L = max(len(enc[i]) for i in idx); ids = np.zeros((len(idx), L), np.int64); m = np.zeros_like(ids)
    for r, i in enumerate(idx): ids[r, :len(enc[i])] = enc[i]; m[r, :len(enc[i])] = 1
    h = s.run(None, {'input_ids': ids, 'attention_mask': m})[0]; v = (h * m[..., None]).sum(1) / m.sum(1, keepdims=True); V[idx] = v / np.linalg.norm(v, axis=1, keepdims=True)
np.save(out + '/loco-vec.npy', V); json.dump({'phones': phones, 'questions': questions}, open(out + '/loco.json', 'w'))
print(len(phones), 'phones,', len(texts), 'records,', len(questions), 'questions', {k: sum(q['category'] == k for q in questions) for k in (1, 2, 3, 4)})
