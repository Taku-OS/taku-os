#!/usr/bin/env python3
"""Convert the embedding model's tokenizer.json (Hugging Face BPE with byte fallback, Metaspace, as used by
bekko-embedding-v1) into the compact table that Bpe.java reads on the phone.

The phone must not parse a 34 MB JSON file. This keeps only what BPE needs, as ids:
  header        magic "TKBPE001", then int32 bos, eos, unk, then int32[256] ids of <0x00>..<0xFF> (-1 when absent)
  added tokens  int32 count, then per token: int32 id, int32 flags (1 = lstrip, 2 = rstrip), int32 byte length, UTF-8 bytes
  characters    int32 count, int32[count] code points (ascending), int32[count] ids
  merges        int32 count, padding to 8 bytes, int64[count] keys (left id << 32 | right id, ascending),
                int32[count] ranks, int32[count] merged ids
All little-endian. Usage: tokenizer_bin.py tokenizer.json out.bin
"""
import json
import struct
import sys


def convert(src, dst):
    t = json.load(open(src, encoding='utf-8'))
    m = t['model']
    if m['type'] != 'BPE' or not m.get('byte_fallback') or m.get('continuing_subword_prefix') or m.get('end_of_word_suffix') or m.get('ignore_merges'):
        raise SystemExit('unsupported tokenizer model: ' + json.dumps({k: v for k, v in m.items() if k not in ('vocab', 'merges')}))
    if t.get('normalizer') != {'type': 'Replace', 'pattern': {'String': ' '}, 'content': '▁'}:
        raise SystemExit('unsupported normalizer: ' + json.dumps(t.get('normalizer')))
    pre = t.get('pre_tokenizer') or {}
    if (pre.get('type'), pre.get('replacement'), pre.get('prepend_scheme'), pre.get('split')) != ('Metaspace', '▁', 'always', True):
        raise SystemExit('unsupported pre-tokenizer: ' + json.dumps(pre))
    for a in t['added_tokens']:
        if a['normalized'] or a['single_word']:
            raise SystemExit('unsupported added token: ' + json.dumps(a))
    vocab = m['vocab']
    special = {a['content']: a['id'] for a in t['added_tokens'] if a['special']}
    out = bytearray(b'TKBPE001')
    out += struct.pack('<3i', special['<bos>'], special['<eos>'], vocab[m['unk_token']])
    out += struct.pack('<256i', *[vocab.get('<0x%02X>' % b, -1) for b in range(256)])
    out += struct.pack('<i', len(t['added_tokens']))
    for a in t['added_tokens']:
        b = a['content'].encode('utf-8')
        out += struct.pack('<3i', a['id'], (1 if a['lstrip'] else 0) | (2 if a['rstrip'] else 0), len(b)) + b
    chars = sorted((ord(k), v) for k, v in vocab.items() if len(k) == 1)
    out += struct.pack('<i', len(chars))
    out += struct.pack('<%di' % len(chars), *[c for c, _ in chars])
    out += struct.pack('<%di' % len(chars), *[i for _, i in chars])
    rows = []
    for rank, pair in enumerate(m['merges']):
        left, right = pair if isinstance(pair, list) else pair.split(' ', 1)
        rows.append(((vocab[left] << 32) | vocab[right], rank, vocab[left + right]))
    rows.sort()
    if any(rows[i][0] == rows[i + 1][0] for i in range(len(rows) - 1)):
        raise SystemExit('duplicate merge pair')
    out += struct.pack('<i', len(rows))
    out += b'\0' * (-len(out) % 8)
    out += struct.pack('<%dq' % len(rows), *[r[0] for r in rows])
    out += struct.pack('<%di' % len(rows), *[r[1] for r in rows])
    out += struct.pack('<%di' % len(rows), *[r[2] for r in rows])
    open(dst, 'wb').write(out)


if __name__ == '__main__':
    convert(sys.argv[1], sys.argv[2])
