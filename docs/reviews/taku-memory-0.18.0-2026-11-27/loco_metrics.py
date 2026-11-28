# LoCoMo accuracy by category for one judge's labels (JSON "label": CORRECT/WRONG, as both Mem0 scripts read it).
# Usage: loco_metrics.py questions.json judgments.jsonl [answers.jsonl]
import json, re, sys, collections
qs = {q['question_id']: q for q in json.load(open(sys.argv[1]))}; names = {1: 'multi-hop', 2: 'temporal', 3: 'open-domain', 4: 'single-hop'}
def label(t):
    m = re.search(r'"label"\s*:\s*"(CORRECT|WRONG)"', t)
    if m: return m.group(1) == 'CORRECT'
    w, c = t.rfind('WRONG'), t.rfind('CORRECT'); return c > w
ok = collections.defaultdict(list); bad = 0
for l in open(sys.argv[2]):
    j = json.loads(l); t = j['output']; bad += not re.search(r'"label"\s*:', t); ok[qs[j['question_id']]['category']].append(label(t))
allv = [x for v in ok.values() for x in v]
for k in sorted(ok): print(f'  {names[k]}: {sum(ok[k]) / len(ok[k]):.4f} ({sum(ok[k])}/{len(ok[k])})')
print(f'Overall: {sum(allv) / len(allv):.4f} ({sum(allv)}/{len(allv)}); replies without a JSON label: {bad}')
if len(sys.argv) > 3: print(f'Answer cost (list price): ${sum(json.loads(l).get("cost") or 0 for l in open(sys.argv[3])):.2f}')
