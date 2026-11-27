#!/usr/bin/env python3
"""Texts for the tokenizer parity check, and the reference token ids from Hugging Face `tokenizers`.
Usage: tokenizer_texts.py tokenizer.json out_dir [mmarco_corpus.parquet]
Writes texts.b64 (one base64 UTF-8 text per line) and expect.txt (space-separated ids). The phone-style cases and the edge
cases are here; the 0.17.0 run also added 400 random passages from C-MTEB/MMarcoRetrieval's corpus (seed 3), not committed."""
import base64, json, random, sys
sys.path.insert(0, __import__('os').path.dirname(__file__))
from cases import TARGETS, DISTRACTORS
from tokenizers import Tokenizer
texts = [t for x in TARGETS for t in x] + DISTRACTORS + [
 '', ' ', '  ', 'a', ' a', 'a ', 'a  b', 'a   b ', '\n', '\n\n', 'x\ny', 'x\n\n\ny', ' \nfoo', 'foo \n bar', '\t\tindent', 'tab\tsep',
 'Hello, World!', 'HELLO world 123,456.78', "don't won't it's", 'email: a.b@c.com https://example.com/x?y=1',
 '😀👍🏽 family: 👨‍👩‍👧 flag: 🇨🇳', 'Ｆｕｌｌｗｉｄｔｈ　全角空格', 'non breaking thin　ideo', 'zero​width‍joiner',
 'café naïve résumé Ångström', 'combining é ä', '日本語のテキスト、カタカナとひらがな。', '한국어 텍스트입니다', 'Русский текст', 'نص عربي', 'हिन्दी पाठ', 'ข้อความภาษาไทย',
 '<bos>x<eos>', '<b>bold</b> <table><tr><td>1</td></tr></table>', '<mask> m', 'a <mask> b', 'a <mask>', '<unused5>', '[toxicity=0]', '<2mass>x',
 '验证码 123456 请勿泄露', '取件码 6-1-3305', 'MU5137 G102 SF1408829365', '\x00\x01ctrl\x7f\x0b\x1f', '� replacement', '𠀀𪚥 rare CJK ext', 'a' * 300, '的' * 300, '▁meta▁▁x',
 '   leading spaces', 'trailing spaces   ', '\r\nwindows\r\nlines', 'mixed中文English混合text123数字', '\U0010ffff private', '\U000F0000 pua']
if len(sys.argv) > 3:
    import pyarrow.parquet as pq
    texts += [x['text'] for x in random.Random(3).sample(pq.read_table(sys.argv[3]).to_pylist(), 400)]
tk = Tokenizer.from_file(sys.argv[1])
open(sys.argv[2] + '/texts.b64', 'w').write(''.join(base64.b64encode(t.encode()).decode() + '\n' for t in texts))
open(sys.argv[2] + '/expect.txt', 'w').write(''.join(' '.join(map(str, tk.encode(t).ids)) + '\n' for t in texts))
print(len(texts), 'texts')
