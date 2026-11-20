"""Keyword SQL before (no covering index, LIKE) and after (covering index, instr for caseless terms) on device-order copies."""
import sqlite3, sys, time, statistics, shutil, os
Q = {'natural-language (no hit)': '明天要交什么材料', 'name (hits)': '婷婷'}
def esc(t): return '%' + t.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'
def caseless(t): return t == t.upper() and t == t.lower()
def run(d, q, now, new):
    ts = [t.lower() for t in q.split()]; where = 'first_seen<=?'; args = [now]
    for t in ts:
        if new and caseless(t): where += " AND instr(title||' '||head||char(10)||fresh,?)>0"; args.append(t)
        else: where += " AND (title||' '||head||char(10)||fresh) LIKE ? ESCAPE '\\'"; args.append(esc(t))
    return [r[0] for r in d.execute('SELECT id FROM memories WHERE ' + where + ' ORDER BY first_seen DESC,id DESC LIMIT 30', args)]
def med(d, q, now, new):
    run(d, q, now, new); ts = []
    for _ in range(7):
        a = time.perf_counter(); r = run(d, q, now, new); ts.append((time.perf_counter() - a) * 1000)
    return statistics.median(ts), r
for n in sys.argv[2:]:
    p = '%s/r%s.db' % (sys.argv[1], n); q2 = p + '.old.db'; shutil.copy(p, q2)
    old = sqlite3.connect(q2); old.execute('DROP INDEX IF EXISTS memory_kw'); old.commit()
    new = sqlite3.connect(p); new.execute('CREATE INDEX IF NOT EXISTS memory_kw ON memories(first_seen,id,title,head,fresh)'); new.commit()
    now = new.execute('SELECT max(first_seen) FROM memories').fetchone()[0] + 1; out = []
    for label, q in Q.items():
        a, ra = med(old, q, now, False); b, rb = med(new, q, now, True); assert ra == rb
        out.append('%s %.1f -> %.1f ms' % (label, a, b))
    print(n, 'memories | keyword SQL:', ' | '.join(out), '(same rows)'); os.remove(q2)
