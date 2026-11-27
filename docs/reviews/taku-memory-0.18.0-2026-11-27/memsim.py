# Python mirror of Taku Memory 0.18 search over Taku conversation records (Recall.search with Scope, evidence only: no
# graph nodes, as no organizer ran). Word lists and patterns are read from the Java sources so they cannot drift.
# One LongMemEval question = one phone whose memory holds that question's haystack as Taku conversations: each user turn
# is a record with speaker "user", each assistant turn one with speaker "assistant", first seen at the session's time
# plus one second per turn. MemoryStore.chat drops turns under 8 characters and turns with passwords or one-time codes,
# and masks card-like numbers; the same happens here.
import json, os, re, sys, uuid, datetime, unicodedata, numpy as np

ROOT = os.environ.get('TAKU_REPO') or os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '../../..'))
REPO = ROOT + '/system/memory/src/org/agentos/memory/'
recall = open(REPO + 'Recall.java', encoding='utf-8').read()
lang = open(REPO + 'Lang.java', encoding='utf-8').read()
privacy = open(REPO + 'Privacy.java', encoding='utf-8').read()
store = open(REPO + 'MemoryStore.java', encoding='utf-8').read()

def jstr(s):  # a Java string literal's value (only the escapes these sources use)
    return re.sub(r'\\(.)', lambda m: {'n': '\n', 't': '\t'}.get(m.group(1), m.group(1)), s)
def jset(src, name):
    m = re.search(name + r'=(?:new HashSet<>\(Arrays\.asList|Set\.of)\((.*?)\)\)?;', src, re.S)
    return set(jstr(x) for x in re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1)))
def jpattern(src, decl):
    m = re.search(re.escape(decl) + r'\s*(?:=\s*)?(?:java\.util\.regex\.)?Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', src)
    return jstr(m.group(1))

EN_STOP = jset(recall, 'EN_STOP'); ENGLISH = jset(lang, 'ENGLISH'); EN_FILLER = jset(recall, 'EN_FILLER')
m = re.search(r'"en",java\.util\.regex\.Pattern\.compile\("((?:[^"\\]|\\.)*)"\)\);', recall); ASKS_EN = re.compile(jstr(m.group(1)), re.A)
m = re.search(r'"zh",java\.util\.regex\.Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', recall); ASKS_ZH = re.compile(jstr(m.group(1)))
SECRET = re.compile(jpattern(privacy, 'static final java.util.regex.Pattern SECRET'), re.A)
CHAT_SECRET = re.compile(jpattern(store, 'static final java.util.regex.Pattern CHAT_SECRET'), re.A)
CARD = re.compile(jstr(re.search(r'CARD_NUMBER=Pattern\.compile\("((?:[^"\\]|\\.)*)"\)', privacy).group(1)), re.A)
LIST = int(re.search(r'static final int LIST=(\d+)', recall).group(1))
WD = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']

def asks_taku(q): return bool(ASKS_EN.search(q) or ASKS_ZH.search(q))
def jtrim(s):
    a, b = 0, len(s)
    while a < b and s[a] <= ' ': a += 1
    while b > a and s[b - 1] <= ' ': b -= 1
    return s[a:b]
def bare(t):
    ps = lambda c: unicodedata.category(c)[0] in 'PS'
    a, b = 0, len(t)
    while a < b and ps(t[a]): a += 1
    while b > a and ps(t[b - 1]): b -= 1
    return t[a:b]
def sentence_words(query):
    terms = re.split(r'[ \t\n\x0b\f\r]+', jtrim(query)); han = func = False
    for t in terms:
        if re.search(r'[一-鿿]{4,}', t): han = True
        w = bare(t).lower()
        if w in EN_STOP or w in ENGLISH: func = True
    if not han and not (len(terms) >= 4 and func): return None
    out = []
    for t in terms:
        b = bare(t)
        if not b: continue
        if any(unicodedata.name(c, '').startswith('CJK UNIFIED') for c in b): raise ValueError('Chinese queries need ICU segmentation; not mirrored')
        w = b.lower()
        if w not in EN_STOP and w not in ENGLISH and w not in EN_FILLER and (len(w) >= 3 or any(c.isdigit() for c in w)) and w not in out: out.append(w)
    return None if len(out) < 2 else out[:8]
def caseless(t): return all(c.lower() == c and c.upper() == c and c.title() == c for c in t)
def ascii_lower(s): return s.translate(str.maketrans('ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz'))
def matches(text, low, terms):  # instr() for caseless terms, SQLite LIKE (ASCII case folding) otherwise
    for t in terms:
        if caseless(t):
            if t not in text: return False
        elif ascii_lower(t) not in low: return False
    return True

def stored(role, content):
    """The record MemoryStore.chat keeps for a turn, or None when it drops it."""
    text = re.sub(r'[ \t]+', ' ', content).strip()[:8000]
    if len(text) < 8 or SECRET.search(text) or CHAT_SECRET.search(text): return None
    return CARD.sub('(number omitted)', text)
def local_time(ms):
    d = datetime.datetime.fromtimestamp(ms / 1000, datetime.timezone.utc)
    return f'{d.date()} {WD[d.weekday()]} {d.hour:02d}:{d.minute:02d}'
def day_ms(s, end):
    d = datetime.date.fromisoformat(s)  # raises on anything but YYYY-MM-DD, like LocalDate.parse
    t = datetime.datetime(d.year, d.month, d.day, tzinfo=datetime.timezone.utc) + (datetime.timedelta(days=1) if end else datetime.timedelta())
    return int(t.timestamp() * 1000) - (1 if end else 0)

class Phone:
    """One question's memory. Times are UTC throughout, standing in for the device's own zone."""
    def __init__(self, q, recs, vec):
        rows = []
        for sid, date, sess in zip(q['haystack_session_ids'], q['haystack_dates'], q['haystack_sessions']):
            base = int(datetime.datetime.strptime(date[:10] + date[-6:], '%Y/%m/%d %H:%M').replace(tzinfo=datetime.timezone.utc).timestamp() * 1000)
            for i, t in enumerate(sess):
                k = f'{sid}#{i}'; text = stored(t['role'], t['content'])
                if text is None: continue
                rows.append({'key': k, 'idx': recs[k], 'seen': base + 1000 * i, 'speaker': 'user' if t['role'] == 'user' else 'assistant',
                             'text': text, 'kw': 'Taku \n' + text, 'answer': bool(t.get('has_answer'))})
        rows.sort(key=lambda r: (r['seen'], r['key']))   # the vector index is in first-seen order
        for n, r in enumerate(rows):
            r['id'] = str(uuid.uuid5(uuid.NAMESPACE_URL, 'lme:' + r['key'])); r['ref'] = 'e' + str(n + 1); r['low'] = ascii_lower(r['kw'])
        self.rows = rows; self.by_id = {r['id']: r for r in rows}; self.by_ref = {r['ref']: r for r in rows}
        self.V = vec[np.array([r['idx'] for r in rows])] if rows else np.zeros((0, 384), np.float32)
        self.taku = np.array([r['speaker'] == 'assistant' for r in rows]); self.seen = np.array([r['seen'] for r in rows], dtype=np.int64)
        # The phone's clock: the end of the question's day. 76 haystacks hold sessions later on that same day than the
        # question's time stamp (none on a later day); LongMemEval treats every session as history.
        d = datetime.datetime.strptime(q['question_date'][:10], '%Y/%m/%d').replace(tzinfo=datetime.timezone.utc)
        self.now = int((d + datetime.timedelta(days=1)).timestamp() * 1000) - 1
    def item(self, r):
        body = r['text'][:max(0, 400 - len('Taku') - 1)]
        return {'kind': 'e', 'id': r['id'], 'ref': r['ref'], 'source': 'org.agentos.agent', 'title': 'Taku', 'body': body, 'first_seen': r['seen'], 'last_seen': r['seen'],
                'revision': 1, 'category': '未分类', 'seenAt': local_time(r['seen']), 'speaker': r['speaker']}
    def search(self, query, limit=10, qv=None, taku=None, frm=None, to=None):
        if len(query) > 500: raise ValueError('Search is limited to 500 characters')
        k = max(1, min(50, int(limit))); size = max(LIST, k)
        lo = day_ms(frm, False) if frm else 0; hi = day_ms(to, True) if to else 2 ** 62
        if lo > hi: raise ValueError('Search dates must be YYYY-MM-DD, with from no later than to')
        until = min(self.now, hi); t = asks_taku(query) if taku is None else bool(taku)
        ts = [x.lower() for x in re.split(r'[ \t\n\x0b\f\r]+', jtrim(query)) if x]
        w = sentence_words(query); ts = w if w is not None else ts
        def kw(want):
            hit = [r for r in self.rows if lo <= r['seen'] <= until and (r['speaker'] == 'assistant') == want and matches(r['kw'], r['low'], ts)]
            hit.sort(key=lambda r: (r['seen'], r['id']), reverse=True); return ['e:' + r['id'] for r in hit[:size]]
        def vec(want):
            if qv is None or not len(self.rows): return []
            ok = (self.seen >= lo) & (self.seen <= until) & (self.taku == want); idx = np.nonzero(ok)[0]
            sc = self.V[idx] @ qv; order = idx[np.argsort(-sc, kind='stable')[:size]]
            return ['e:' + self.rows[i]['id'] for i in order]
        lists = [kw(False), vec(False)] + ([kw(True), vec(True)] if t else [])
        score = {}
        for l in lists:
            for r, x in enumerate(l): score[x] = score.get(x, 0) + 1 / (60 + r)
        ranked = sorted(score, key=lambda x: -score[x])[:k]
        return {'items': [self.item(self.by_id[x[2:]]) for x in ranked]}, t
    def get(self, ident):
        r = self.by_id.get(ident) or self.by_ref.get(ident)
        if r is None: raise ValueError('Memory not found')
        o = self.item(r); o.pop('body'); o['text'] = r['text']; o['fullText'] = r['text']; return o
