# 在设备上执行：用当前整理器（带 due / stake / related）把 22 天合成时间线的记忆图谱从头重建一遍（只重建图谱，看板和用户卡沿用 0.5 的）
cd /data/local/tmp/recall; O=rebuild_graph.txt; : > $O
sh restore_safe.sh >> $O 2>&1
am force-stop org.agentos.memory
D=/data/data/org.agentos.memory/databases/memories.db
sqlite3 $D "DELETE FROM nodes; DELETE FROM gen_jobs; INSERT OR REPLACE INTO kv(k,v) VALUES('graphCursor','0'); INSERT OR REPLACE INTO kv(k,v) VALUES('nextNid','0'); INSERT OR REPLACE INTO kv(k,v) VALUES('graphRedo','[]');"
C="content call --user 0 --uri content://org.agentos.memory"
$C --method status --arg '{}' >/dev/null
E0=1793462400
for d in $(seq 1 22); do
 T=$((E0+(d-1)*86400+23*3600))000
 echo "@@DAY $d start $(date +%T) $($C --method maintain --arg "{\"now\":$T,\"graphOnly\":true}" | tr -d '\n' | cut -c1-80)" >> $O
 n=0; sleep 5; while $C --method status --arg '{}' | grep -q '"organizing":true'; do sleep 5; n=$((n+1)); [ $n -gt 600 ] && break; done
 echo "@@DAY $d done $(date +%T) nodes=$(sqlite3 $D 'SELECT count(DISTINCT nid)||\" vers=\"||count(*) FROM nodes;') due=$(sqlite3 $D "SELECT count(*) FROM nodes WHERE json_extract(data,'$.due')<>'';") stake=$(sqlite3 $D "SELECT count(*) FROM nodes WHERE json_extract(data,'$.stake')<>'';") state=$($C --method status --arg '{}' | grep -o '"organizerState":"[^"]*"')" >> $O
done
echo "@@END $(date)" >> $O
