"""Synthetic memory store for timing recall: evidence rows (title/head/fresh/body like captured chats and pages),
their 512-d vectors, and graph nodes with versions, related links and vectors. Writes an SQLite file with the memory
schema subset Recall reads, plus nodes.jsonl / vectors.f32 for the JVM benchmark."""
import json, random, sqlite3, sys, os, struct
import numpy as np
N, M, out = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
random.seed(7); rng = np.random.default_rng(7)
people = ['婷婷','小雨','阿杰','妈妈','表哥','王老师','李经理','Tina','Jack','房东','快递员','同事小周']
apps = ['微信','WhatsApp','钉钉','邮件','日历','淘宝','美团','备忘录','Slack','短信']
phr = ['明晚 19:00 在西村日料吃饭','记得带伞还她','周三前把报表口径对一遍','包裹已到小区驿站，取件码 6-1-3305','私教体验课今晚确认',
 '打车报销 236 元','燃气年检 4 号上门','周六上午约了牙医','下周一前交报告','季度复盘会议改到周五下午','妈妈体检后中午打电话',
 'Let us meet at 3pm tomorrow','please send the slides before Friday','航班 MU5101 明早 8:05 起飞','房租 3200 元月底前转','孩子家长会周四晚上',
 '新买的耳机已发货','信用卡账单 12 号到期','电影票两张已出票','健身房会员下月到期']
def text(n):
    return '\n'.join(random.choice(people)+'：'+random.choice(phr)+random.choice(['。','，好的','？','，收到','']) for _ in range(n))
if os.path.exists(out): os.remove(out)
d = sqlite3.connect(out)
d.executescript("""CREATE TABLE memories(id TEXT PRIMARY KEY,source TEXT,title TEXT,head TEXT,fresh TEXT,body TEXT,first_seen INTEGER,last_seen INTEGER,revision INTEGER DEFAULT 1,category TEXT DEFAULT '未分类');
CREATE INDEX memory_seen ON memories(first_seen);CREATE INDEX memory_recent ON memories(last_seen DESC);
CREATE TABLE vectors(id TEXT PRIMARY KEY,v BLOB NOT NULL);
CREATE TABLE nodes(nid TEXT NOT NULL,ver INTEGER NOT NULL,t INTEGER NOT NULL,data TEXT NOT NULL,v BLOB,PRIMARY KEY(nid,ver));
CREATE TABLE forgotten(digest TEXT PRIMARY KEY,until_at INTEGER NOT NULL);CREATE TABLE boards(t INTEGER PRIMARY KEY,data TEXT);CREATE TABLE profiles2(t INTEGER PRIMARY KEY,data TEXT);""")
t0 = 1790000000000; vf = open(out + '.vectors.f32', 'wb'); ids = []
for i in range(N):
    body = text(random.randint(3, 25)); head = body.split('\n')[0]
    fresh = '\n'.join(body.split('\n')[1:random.randint(2, 8)])
    mid = 'm%07d' % i; seen = t0 + i * 60000 * 7; ids.append(mid)
    v = rng.standard_normal(512).astype(np.float32); v /= np.linalg.norm(v)
    d.execute('INSERT INTO memories(id,source,title,head,fresh,body,first_seen,last_seen) VALUES(?,?,?,?,?,?,?,?)', (mid, 'com.tencent.mm', random.choice(people + apps), head, fresh, body, seen, seen))
    d.execute('INSERT INTO vectors VALUES(?,?)', (mid, v.tobytes())); vf.write(struct.pack('<q', seen) + v.tobytes())
vf.close()
nj = open(out + '.nodes.jsonl', 'w', encoding='utf-8')
for j in range(M):
    for ver in range(random.randint(1, 3)):
        t = t0 + j * 60000 * 30 + ver * 3600000
        data = {'type': random.choice(['event','task','item','preference','person']), 'title': random.choice(phr)[:16], 'summary': random.choice(phr) + '，' + random.choice(phr),
                'when': '%d-%d %02d:00' % (random.randint(1,12), random.randint(1,28), random.randint(8,21)) if random.random() < .6 else '', 'amount': random.choice(['', '236 元', '3200 元']),
                'people': random.sample(people, random.randint(0, 3)), 'related': ['n%d' % random.randint(0, M - 1) for _ in range(random.randint(0, 3))],
                'evidence': ['e%d' % random.randint(1, N) for _ in range(random.randint(1, 4))], 'status': random.choice(['active','active','done']), 't': t, 'stake': random.choice(['', '逾期作废'])}
        v = rng.standard_normal(512).astype(np.float32); v /= np.linalg.norm(v)
        d.execute('INSERT INTO nodes VALUES(?,?,?,?,?)', ('n%d' % j, ver, t, json.dumps(data, ensure_ascii=False), v.tobytes()))
        nj.write(json.dumps({'nid': 'n%d' % j, 'data': data, 'v': v.tolist()}, ensure_ascii=False) + '\n')
nj.close(); d.commit(); d.close()
print('wrote', out, N, 'memories', M, 'nodes')
