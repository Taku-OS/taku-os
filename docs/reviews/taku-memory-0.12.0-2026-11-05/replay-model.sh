# 0.12.0 Broker：消息时刻三遍、睡前提醒两遍（用整理字段）、一遍不用整理字段
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; mkdir -p s3c
sh events3.sh; cp pro_events_r1.txt pro_events_r2.txt pro_events_r3.txt s3c/
sh evenings2.sh; cp pro_evenings_s2_r1.txt pro_evenings_s2_r2.txt s3c/
$C --method configure --arg '{"agendaFields":false}' >/dev/null; sh replay_evenings.sh; cp pro_evenings.txt s3c/pro_evenings_off.txt; $C --method configure --arg '{"agendaFields":true}' >/dev/null
tar cf s3c.tar s3c; echo "@@END $(date)" > runall11.done
