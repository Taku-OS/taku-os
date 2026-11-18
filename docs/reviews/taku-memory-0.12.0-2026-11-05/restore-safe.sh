# 恢复备份（$1）到记忆调试包：解包后再停一次进程，并确认设置文件没被正在运行的进程写回成空的
cd /data/local/tmp/recall; B=${1:-backup-0.5-22day.tgz}
D=/data/data/org.agentos.memory; U=$(stat -c %u $D)
for k in 1 2 3 4 5; do
 am force-stop org.agentos.memory
 rm -rf $D/databases $D/shared_prefs; tar xzf $B -C $D
 chown -R $U:$U $D/databases $D/shared_prefs; restorecon -R $D >/dev/null 2>&1
 am force-stop org.agentos.memory; sleep 2; am force-stop org.agentos.memory
 grep -q 'name="userName"' $D/shared_prefs/memory.xml && break
 echo "retry $k"
done
C="content call --user 0 --uri content://org.agentos.memory"
$C --method status --arg '{}' >/dev/null 2>&1; sleep 3
grep -q 'name="userName"' $D/shared_prefs/memory.xml && echo "@@PREFS ok" || echo "@@PREFS LOST"
cmd jobscheduler run -f org.agentos.memory 4101 >/dev/null 2>&1
n=0; while [ $n -lt 40 ]; do v=$($C --method status --arg '{}' | tr ',' '\n' | grep -E '"vectors"|"count"' | tr '\n' ' '); echo "$v" | grep -q '"vectors":1537' && break; sleep 5; n=$((n+1)); done
echo "@@RESTORED $v"
