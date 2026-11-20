"""Rebuild the synthetic memories table in the device's real column order (head/fresh/lines after body, as added by
ALTER TABLE) and time the keyword scan: as now, and with a covering index on (first_seen,id,title,head,fresh)."""
import sqlite3, sys, time, statistics, shutil, hashlib
src, dst = sys.argv[1], sys.argv[2]
shutil.copy(src, dst); d = sqlite3.connect(dst)
d.executescript("""CREATE TABLE m2(id TEXT PRIMARY KEY,source TEXT NOT NULL,page TEXT NOT NULL,title TEXT NOT NULL,body TEXT NOT NULL,digest TEXT UNIQUE NOT NULL,first_seen INTEGER NOT NULL,last_seen INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,category TEXT NOT NULL DEFAULT '未分类',analysis TEXT NOT NULL DEFAULT 'pending',model TEXT NOT NULL DEFAULT '',score REAL NOT NULL DEFAULT 0,decision TEXT NOT NULL DEFAULT 'none',confidence REAL NOT NULL DEFAULT 0,suggested_at INTEGER NOT NULL DEFAULT 0,task_id TEXT NOT NULL DEFAULT '',task_state TEXT NOT NULL DEFAULT '',head TEXT NOT NULL DEFAULT '',fresh TEXT NOT NULL DEFAULT '',lines TEXT NOT NULL DEFAULT '');""")
rows = d.execute('SELECT id,source,title,body,first_seen,last_seen,head,fresh FROM memories').fetchall()
for (i, so, ti, bo, fs, ls, he, fr) in rows:
    lines = ','.join(hashlib.sha1((i + str(k)).encode()).hexdigest()[:24] for k in range(bo.count('\n') + 1))
    d.execute('INSERT INTO m2(id,source,page,title,body,digest,first_seen,last_seen,head,fresh,lines) VALUES(?,?,?,?,?,?,?,?,?,?,?)', (i, so, 'p', ti, bo, i, fs, ls, he, fr, lines))
d.executescript('DROP TABLE memories; ALTER TABLE m2 RENAME TO memories; CREATE INDEX memory_seen ON memories(first_seen); CREATE INDEX memory_recent ON memories(last_seen DESC); VACUUM;')
Q = ['明天要交什么材料', '不存在的词', '婷婷', '驿站 取件码', 'slides friday']
def esc(t): return '%' + t.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'
def run(q, now):
    ts = [t.lower() for t in q.split()]; where = 'first_seen<=?'; args = [now]
    for t in ts: where += " AND (title||' '||head||char(10)||fresh) LIKE ? ESCAPE '\\'"; args.append(esc(t))
    return [r[0] for r in d.execute('SELECT id FROM memories WHERE ' + where + ' ORDER BY first_seen DESC,id DESC LIMIT 30', args)]
def bench(label):
    now = d.execute('SELECT max(first_seen) FROM memories').fetchone()[0] + 1; out = []; res = {}
    for q in Q:
        run(q, now); ts = []
        for _ in range(5):
            a = time.perf_counter(); r = run(q, now); ts.append((time.perf_counter() - a) * 1000)
        res[q] = r; out.append('%s %.1f' % (q, statistics.median(ts)))
    plan = [p[3] for p in d.execute("EXPLAIN QUERY PLAN SELECT id FROM memories WHERE first_seen<=? AND (title||' '||head||char(10)||fresh) LIKE ? ESCAPE '\\' ORDER BY first_seen DESC,id DESC LIMIT 30", (now, '%x%'))]
    print(' ', label, ' | '.join(out), plan); return res
size0 = d.execute('PRAGMA page_count').fetchone()[0] * d.execute('PRAGMA page_size').fetchone()[0]
a = bench('device column order:')
d.execute('CREATE INDEX memory_kw ON memories(first_seen,id,title,head,fresh)'); d.commit()
size1 = d.execute('PRAGMA page_count').fetchone()[0] * d.execute('PRAGMA page_size').fetchone()[0]
b = bench('covering index:     ')
assert a == b
print('  same rows; database %.1f MB -> %.1f MB' % (size0 / 1e6, size1 / 1e6))
