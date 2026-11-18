# 第二步对照：同一份重建后的图谱，用户卡用 / 不用整理写下的 due / stake / related
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"
$C --method configure --arg '{"agendaFields":true}' >/dev/null; sh ncard.sh >/dev/null 2>&1; rm -rf ncard-s2on; mv ncard ncard-s2on
$C --method configure --arg '{"agendaFields":false}' >/dev/null; sh ncard.sh >/dev/null 2>&1; rm -rf ncard-s2off; mv ncard ncard-s2off
$C --method configure --arg '{"agendaFields":true}' >/dev/null
tar cf ncard-s2.tar ncard-s2on ncard-s2off; echo "@@END $(date)" > ablate.done
