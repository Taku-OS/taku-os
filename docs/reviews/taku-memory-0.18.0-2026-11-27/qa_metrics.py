# Same summary as LongMemEval's print_qa_metrics.py (abstention questions count inside their task), with the judge's
# "yes" read the same way ('yes' in the lowercased reply). Usage: qa_metrics.py prompts.json judgments.jsonl answers.jsonl
import json, sys, numpy as np
ref = {x['question_id']: x for x in json.load(open(sys.argv[1]))}
jud = {json.loads(l)['question_id']: json.loads(l) for l in open(sys.argv[2]) if l.strip()}
ans = {json.loads(l)['question_id']: json.loads(l) for l in open(sys.argv[3]) if l.strip()}
types = ['single-session-user', 'single-session-preference', 'single-session-assistant', 'multi-session', 'temporal-reasoning', 'knowledge-update']
t2 = {t: [] for t in types}; abst = []
for qid, j in jud.items():
    ok = 'yes' in j['output'].lower(); t2[ref[qid]['question_type']].append(ok)
    if '_abs' in qid: abst.append(ok)
for t in types: print(f'  {t}: {np.mean(t2[t]):.4f} ({len(t2[t])})')
allv = [x for t in types for x in t2[t]]
print(f'Task-averaged accuracy: {np.mean([np.mean(t2[t]) for t in types if t2[t]]):.4f}')
print(f'Overall accuracy: {np.mean(allv):.4f} ({sum(allv)}/{len(allv)})')
if abst: print(f'Abstention accuracy: {np.mean(abst):.4f} ({len(abst)})')
print(f'Cost (list price): answers ${sum(a.get("cost") or 0 for a in ans.values()):.2f}, judge ${sum(j.get("cost") or 0 for j in jud.values()):.2f}; models {sorted({m for a in list(ans.values()) + list(jud.values()) for m in a.get("model", [])})}')
