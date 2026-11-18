# m12d：0.12.0 图谱上卡片用 / 不用字段，再在 0.11.0 图谱上跑一遍，最后恢复 0.12.0 图谱
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; L=eval12d.log; : > $L
$C --method configure --arg '{"agendaFields":true}' >/dev/null; sh ncard.sh >/dev/null 2>&1; rm -rf ncard-s6on; mv ncard ncard-s6on
$C --method configure --arg '{"agendaFields":false}' >/dev/null; sh ncard.sh >/dev/null 2>&1; rm -rf ncard-s6off; mv ncard ncard-s6off
$C --method configure --arg '{"agendaFields":true}' >/dev/null; echo "s6 $(date +%T)" >> $L
sh restore_safe.sh backup-step2-22day.tgz >> $L 2>&1
$C --method configure --arg '{"agendaFields":true}' >/dev/null; sh ncard.sh >/dev/null 2>&1; rm -rf ncard-s2v12d; mv ncard ncard-s2v12d
sh restore_safe.sh backup-step3-22day.tgz >> $L 2>&1
tar cf ncard-s6.tar ncard-s6on ncard-s6off ncard-s2v12d; echo "@@END $(date +%T)" >> $L
