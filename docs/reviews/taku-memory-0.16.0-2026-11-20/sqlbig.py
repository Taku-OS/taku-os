"""Keyword SQL on the million-row store: before (memory_seen index, LIKE) and after (covering memory_kw, instr for caseless
terms), on the same file: the before form is forced with INDEXED BY memory_seen."""
import sqlite3, sys, time, statistics, os
p = sys.argv[1]; d = sqlite3.connect(p)
Q = {'natural-language (no hit)': '明天要交什么材料', 'phrase (no hit)': '不存在的词', 'name (hits)': '婷婷', 'english (hits)': 'slides friday'}
def esc(t): return '%' + t.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'
def caseless(t): return t == t.upper() and t == t.lower()
def run(q, now, new):
    ts = [t.lower() for t in q.split()]; where = 'first_seen<=?'; args = [now]
    for t in ts:
        if new and caseless(t): where += " AND instr(title||' '||head||char(10)||fresh,?)>0"; args.append(t)
        else: where += " AND (title||' '||head||char(10)||fresh) LIKE ? ESCAPE '\\'"; args.append(esc(t))
    table = 'memories' if new else 'memories INDEXED BY memory_seen'
    return [r[0] for r in d.execute('SELECT id FROM ' + table + ' WHERE ' + where + ' ORDER BY first_seen DESC,id DESC LIMIT 30', args)]
def med(q, now, new, reps):
    run(q, now, new); ts = []
    for _ in range(reps):
        a = time.perf_counter(); r = run(q, now, new); ts.append((time.perf_counter() - a) * 1000)
    return statistics.median(ts), r
now = d.execute('SELECT max(first_seen) FROM memories').fetchone()[0] + 1
size = lambda: d.execute('PRAGMA page_count').fetchone()[0] * d.execute('PRAGMA page_size').fetchone()[0] / 1e9
print('rows', d.execute('SELECT count(*) FROM memories').fetchone()[0], 'database %.2f GB' % size(), flush=True)
before = {k: med(q, now, False, 3) for k, q in Q.items()}
a = time.perf_counter(); d.execute('CREATE INDEX IF NOT EXISTS memory_kw ON memories(first_seen,id,title,head,fresh)'); d.commit(); build = time.perf_counter() - a
print('built memory_kw in %.0f s; database %.2f GB' % (build, size()), flush=True)
for k, q in Q.items():
    b, rb = med(q, now, True, 3); assert rb == before[k][1], k
    print('  %s: %.1f -> %.1f ms (same rows)' % (k, before[k][0], b), flush=True)
