# Two LoCoMo judges for each answer: the Mem0 paper's J-score prompt (ACCURACY_PROMPT in mem0 evaluation/metrics/llm_judge.py,
# commit aae5989e, the one most published LoCoMo numbers use) and Mem0's 2026 unified judge (benchmarks/locomo/prompts.py
# in mem0ai/memory-benchmarks, more lenient: partial lists, 50% duration tolerance; open-domain gold cut at ";").
# Both prompts are read from those files, not copied. Usage: loco_judge.py llm_judge.py memory_benchmarks_dir questions.json answers.jsonl out_prefix
import ast, json, sys
paper = next(n.value.value for n in ast.parse(open(sys.argv[1], encoding='utf-8').read()).body if isinstance(n, ast.Assign) and n.targets[0].id == 'ACCURACY_PROMPT')
sys.path.insert(0, sys.argv[2]); from benchmarks.locomo import prompts as mb
qs = {q['question_id']: q for q in json.load(open(sys.argv[3]))}; pj, uj = [], []
for l in open(sys.argv[4]):
    a = json.loads(l); q = qs[a['question_id']]
    pj.append({'question_id': q['question_id'], 'judge': paper.format(question=q['question'], gold_answer=q['answer'], generated_answer=a['output'])})
    uj.append({'question_id': q['question_id'], 'judge': mb.JUDGE_SYSTEM_PROMPT + '\n\n' + mb.get_judge_prompt(q['category'], q['question'], mb.preprocess_answer(q['category'], q['answer']), a['output'])})
json.dump(pj, open(sys.argv[5] + '-paper.json', 'w')); json.dump(uj, open(sys.argv[5] + '-unified.json', 'w')); print(len(pj), 'answers, two judge prompts each')
