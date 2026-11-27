# What the reader did with the tools: searches and reads per question, and how often it set limit, dates or takuReplies.
import json, sys, collections, numpy as np
calls = [json.loads(l) for l in open(sys.argv[1])]; keep = set(json.loads(l)['question_id'] for l in open(sys.argv[2]))
per = collections.defaultdict(lambda: collections.Counter())
for c in calls:
    if c['qid'] not in keep: continue
    k = per[c['qid']]; a = c.get('args') or {}
    if c['path'] == '/search':
        k['search'] += 1; k['dated'] += bool(a.get('from') or a.get('to')); k['taku_arg'] += 'takuReplies' in a; k['taku_on'] += bool(c.get('taku')); k['limit>20'] += a.get('limit', 20) > 20; k['error'] += 'error' in c
    else: k['get'] += 1
q = list(per.values()); n = len(keep)
print(f'questions {n}, with any call {len(q)}')
for f in ['search', 'get', 'dated', 'taku_arg', 'taku_on', 'limit>20', 'error']:
    v = [x[f] for x in q] + [0] * (n - len(q)); print(f'{f}: mean {np.mean(v):.2f} per question, questions with any {sum(1 for x in v if x)}')
