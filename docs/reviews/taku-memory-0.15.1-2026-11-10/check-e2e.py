"""Reads feed-e2e.txt (the device log of feed-e2e.sh) and checks each step; prints one line per check and writes feed-e2e.json.
Usage: python3 check-e2e.py feed-e2e.txt feed-e2e.json"""
import json, re, sys

def results(text):
    """Every {"ok": …} reply in a block, in order."""
    out, dec, i = [], json.JSONDecoder(), 0
    while True:
        j = text.find('{"ok"', i)
        if j < 0: return out
        try:
            obj, k = dec.raw_decode(text, j); out.append(obj); i = k
        except ValueError:
            i = j + 1

def blocks(text):
    parts = re.split(r'^@@', text, flags=re.M)
    return [(p.split('\n', 1)[0].strip(), p.split('\n', 1)[1] if '\n' in p else '') for p in parts if p.strip()]

def main(src, dst):
    text = open(src, encoding='utf-8').read()
    bl = blocks(text)
    def find(head, nth=0):
        hits = [b for h, b in bl if h.startswith(head)]
        return hits[nth] if len(hits) > nth else ''
    def one(head, nth=0):
        r = results(find(head, nth)); return r[0] if r else {}
    ids = {h.split()[1]: h.split()[2] for h, _ in bl if h.startswith('NUDGE ') and len(h.split()) > 2}
    checks = []
    def check(ok, what, detail=None):
        checks.append({'ok': bool(ok), 'check': what, 'detail': detail}); print(('ok   ' if ok else 'FAIL ') + what + ('' if detail is None else '  ' + json.dumps(detail, ensure_ascii=False)[:300]))
    A, B, P = ids.get('CLASH'), ids.get('BRIEF'), ids.get('PARCEL')
    three = one('LIST three cards').get('result', {})
    shown = {c['id']: c for c in three.get('cards', [])}
    E = ids.get('EMOJI')
    want = {f'memory.nudge.{A}': (1.3, 'chat'), f'memory.brief.{B}': (1.1, 'moon'), f'memory.nudge.{P}': (1.2, 'chat'), f'memory.nudge.{E}': (1.2, 'chat')}
    # 0.15.1：rank = 档位 + 时间 × 1e-14（现在约 0.018），档位之间差 0.1
    check(all(i in shown and 0 < shown[i]['rank'] - r < 0.05 and shown[i]['pin']['icon']['glyph'] == g for i, (r, g) in want.items()),
          'four fixed cards published with rank above the samples and the new glyphs', {i: (shown.get(i, {}).get('rank'), (shown.get(i, {}).get('pin') or {}).get('icon', {}).get('glyph')) for i in want})
    check([c['id'] for c in three.get('cards', [])][:4] == [f'memory.nudge.{A}', f'memory.nudge.{E}', f'memory.nudge.{P}', f'memory.brief.{B}'], 'feed order: clash, the newer message, the older message, brief')
    pin = (shown.get(f'memory.nudge.{E}') or {}).get('pin') or {}
    check(f'memory.nudge.{E}' in shown and bool(pin.get('label')) and bool(pin.get('detail')) and len(pin.get('label', '').encode('utf-16-le')) // 2 <= 24 and len(pin.get('detail', '').encode('utf-16-le')) // 2 <= 32, 'the emoji card was accepted and fits the pin limits', pin)
    tidy = one('TIDY read back').get('result', {})
    check(tidy.get('read') == 3 and tidy.get('swept') == 0, 'read back reactions of the three cards', tidy)
    reg = tidy.get('feedExpiring', {})
    check(f'memory.nudge.{P}' not in reg and f'memory.nudge.{A}' in reg and f'memory.brief.{B}' in reg, 'the card the user hid was retracted and dropped from the registry', sorted(reg))
    rows = {k: one('NUDGEROW ' + k).get('result', {}) for k in ('CLASH', 'BRIEF', 'PARCEL')}
    ops = {k: [(e['op'], e.get('origin'), e.get('effect')) for e in r.get('feedback', [])] for k, r in rows.items()}
    check(('action:go', 'gui', 'ask-taku') in ops['CLASH'], 'clash: the hand-off is recorded', ops['CLASH'])
    check(('pin', 'gui', None) in ops['BRIEF'], 'brief: the pin is recorded', ops['BRIEF'])
    check(('remove', 'gui', None) in ops['PARCEL'] and ('action:hide', 'gui', 'dismiss') in ops['PARCEL'], 'parcel: the hide button and the removal are recorded', ops['PARCEL'])
    after = one('LIST after read back').get('result', {})
    stored = after.get('stub', {}).get('stored', [])
    check(f'memory.nudge.{P}' not in stored and f'memory.brief.{B}' in [c['id'] for c in after.get('pins', [])], 'the hidden card is gone from storage; the pinned brief is in pins', {'stored': stored})
    again = one('TIDY read again').get('result', {})
    check(again.get('read') == 0, 'reading the same log again records nothing', again)
    oa_off = one('OPENACCESS nudges off')
    check(oa_off.get('ok') is False and '记忆查询已关闭' in oa_off.get('message', ''), 'open-access rejects reminder reads while queries are off', oa_off.get('message'))
    oa = one('OPENACCESS nudges on')
    check(oa.get('ok') is True and any(str(i['id']) == A for i in oa.get('result', {}).get('items', [])), 'agents can read reminders after explicit enable', oa.get('message'))
    nud_on = one('NUDGES query on')
    items = {str(i['id']): i for i in nud_on.get('result', {}).get('items', [])}
    check(nud_on.get('ok') is True and items.get(A, {}).get('reactions') == ['asked'] and items.get(B, {}).get('reactions') == ['pinned'] and items.get(P, {}).get('reactions') == ['removed'],
          'nudges lists the reactions per reminder', {k: items.get(v, {}).get('reactions') for k, v in (('CLASH', A), ('BRIEF', B), ('PARCEL', P))})
    nud_off = one('NUDGES query off')
    check(nud_off.get('ok') is False, 'signed nudges is refused while queries are off', nud_off.get('message'))
    lost = one('NUDGEROW LOST').get('result', {}); silent = one('NUDGEROW SILENT').get('result', {}); full = one('NUDGEROW FULL').get('result', {})
    check(lost.get('card', '').startswith('agent-generated:'), 'no answer, card made: memory finds and keeps it', lost.get('card'))
    check(silent.get('card') == f'memory.nudge.{ids.get("SILENT")}', 'no answer, no card: the fixed card goes out', silent.get('card'))
    check(full.get('card') == '', 'full feed: no card, only the notification', full.get('card'))
    unk = one('LIST after unknown outcomes').get('result', {})
    gen = [i for i in unk.get('stub', {}).get('stored', []) if i.startswith('agent-generated:')]
    check(gen == [lost.get('card')], 'exactly one generated card is on the feed, the one memory kept', gen)
    exp = one('TIDY two days later').get('result', {})
    check(exp.get('feedExpiring') == {} and exp.get('swept', 0) >= 5, 'two days later every card has been retracted, the pinned one too', exp)
    left = one('LIST after expiry').get('result', {})
    check(left.get('stub', {}).get('stored') == [] and left.get('pins') == [], 'nothing of memory is left on the feed', left.get('stub'))
    before = one('LIST before off').get('result', {}); off = one('LIST after off').get('result', {})
    check(len(before.get('stub', {}).get('stored', [])) == 1 and off.get('stub', {}).get('stored') == [], 'turning the feed cards off takes the last card down', {'before': before.get('stub'), 'after': off.get('stub')})
    reg_off = one('TIDY registry after off').get('result', {})
    check(reg_off.get('feedExpiring') == {} and reg_off.get('withdrawn') == 0, 'nothing is left to withdraw', reg_off)
    log = after.get('recentActions', [])
    check(all(set(('at', 'origin', 'op', 'cardId', 'detail')) <= set(e) for e in log), 'stand-in log entries have the home feed shape')
    json.dump({'ids': ids, 'checks': checks}, open(dst, 'w'), ensure_ascii=False, indent=1)
    bad = sum(not c['ok'] for c in checks); print(f'{len(checks) - bad}/{len(checks)} passed')
    if bad:
        raise SystemExit(1)

if __name__ == '__main__':
    main(*sys.argv[1:3])
