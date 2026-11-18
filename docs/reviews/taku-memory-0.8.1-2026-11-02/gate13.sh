cd /data/local/tmp/recall; O=gate13.txt; : > $O
C="content call --user 0 --uri content://org.agentos.memory"
q(){ sqlite3 /data/data/org.agentos.memory/databases/memories.db "$1" 2>&1 | tr '\n' ' '; }
waitorg(){ n=0; sleep 5; while $C --method status --arg '{}' | grep -q '"organizing":true'; do sleep 5; n=$((n+1)); [ $n -gt 100 ] && break; done; }
echo "@@BEFORE $(settings get global taku_ai_app_memory) $(settings get global taku_ai_enabled) snap=$(q "SELECT count(*) FROM boards; SELECT count(*) FROM profiles2; SELECT count(DISTINCT nid) FROM nodes;")" >> $O
echo "@@STATUS_ERR $($C --method status --arg '{}' | grep -o '"lastError":"[^"]*"\|"errors":[0-9]*\|"organizerState":"[^"]*"' | tr '\n' ' ')" >> $O
logcat -c
settings put global taku_ai_app_memory 0; sleep 3
su 2000 cmd notification post -S bigtext -t 大鹏 dapeng "周日上午 9 点世纪公园跑步，别迟到" >/dev/null 2>&1; sleep 5
$C --method maintain --arg '{}' >/dev/null; waitorg
echo "@@GATED snap=$(q "SELECT count(*) FROM boards; SELECT count(*) FROM profiles2; SELECT count(DISTINCT nid) FROM nodes;") log=$(logcat -d -s TakuMemory | grep -i '整理\|organiz\|denied\|memory' | tail -3 | cut -c1-200 | tr '\n' ' ')" >> $O
echo "@@STATUS_GATED $($C --method status --arg '{}' | grep -o '"lastError":"[^"]*"\|"organizerState":"[^"]*"\|"lastOrganize":{[^}]*}' | tr '\n' ' ')" >> $O
settings put global taku_ai_app_memory 1; sleep 3
$C --method maintain --arg '{}' >/dev/null; waitorg
echo "@@RESTORED $(settings get global taku_ai_app_memory) snap=$(q "SELECT count(*) FROM boards; SELECT count(*) FROM profiles2; SELECT count(DISTINCT nid) FROM nodes;")" >> $O
echo "@@STATUS_RESTORED $($C --method status --arg '{}' | grep -o '"lastError":"[^"]*"\|"organizerState":"[^"]*"\|"lastOrganize":{[^}]*}' | tr '\n' ' ')" >> $O
echo @@END >> $O
