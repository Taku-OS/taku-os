# LongMemEval's own judge prompt (get_anscheck_prompt in src/evaluation/evaluate_qa.py), taken from a LongMemEval checkout
# without importing the rest of that file (which needs an API client), for each answer.
# Usage: judge_prompts.py longmemeval_checkout longmemeval.json answers.jsonl out.json
import ast, json, sys
src = open(sys.argv[1] + '/src/evaluation/evaluate_qa.py', encoding='utf-8').read()
fn = next(n for n in ast.parse(src).body if isinstance(n, ast.FunctionDef) and n.name == 'get_anscheck_prompt'); ns = {}
exec(compile(ast.Module([fn], []), 'evaluate_qa.py', 'exec'), ns); prompt = ns['get_anscheck_prompt']
qs = {q['question_id']: q for q in json.load(open(sys.argv[2]))}; out = []
for l in open(sys.argv[3]):
    a = json.loads(l); q = qs[a['question_id']]
    out.append({'question_id': q['question_id'], 'question_type': q['question_type'], 'judge': prompt(q['question_type'], q['question'], str(q['answer']), a['output'], abstention='_abs' in q['question_id'])})
json.dump(out, open(sys.argv[4], 'w')); print(len(out), 'judge prompts')
