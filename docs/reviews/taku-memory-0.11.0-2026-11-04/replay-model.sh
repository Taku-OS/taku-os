# 0.11.0 终版 Broker：消息时刻三遍、睡前提醒两遍（用整理字段）、一遍不用整理字段
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; mkdir -p s2c
sh events3.sh; cp pro_events_r1.txt pro_events_r2.txt pro_events_r3.txt s2c/
sh evenings2.sh; cp pro_evenings_s2_r1.txt pro_evenings_s2_r2.txt s2c/
$C --method configure --arg '{"agendaFields":false}' >/dev/null; sh replay_evenings.sh; cp pro_evenings.txt s2c/pro_evenings_off.txt; $C --method configure --arg '{"agendaFields":true}' >/dev/null
tar cf s2c.tar s2c; echo "@@END $(date)" > runall11.done
