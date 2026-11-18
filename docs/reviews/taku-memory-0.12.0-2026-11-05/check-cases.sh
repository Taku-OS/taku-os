# 0.12.0 复核：回放里说错的 3 条和几条有用的，经真实路径各跑两次
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; O=check_cases.txt; : > $O
for k in 1 2; do
echo "@@CASE 11-04 11:00 useful" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1793761200000, "title": "日历", "text": "陈姐邀请你参加：1:1（陈姐），11月6日 16:00，陈姐办公室", "run": true}' >> $O 2>&1
echo "@@CASE 11-11 16:00 wrong" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794384000000, "title": "日历", "text": "日程变更：需求评审（推荐算法） 改到 11月13日 10:00", "run": true}' >> $O 2>&1
echo "@@CASE 11-16 21:20 wrong" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794835200000, "title": "约吧", "text": "预约成功：全季酒店（北京建国路店） 11月17日 周二 14:00", "run": true}' >> $O 2>&1
echo "@@CASE 11-17 02:10 useful" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794852600000, "title": "云程航空", "text": "您的航班 YC1203（上海虹桥—北京首都 07:30）因天气原因取消，可免费改签或全额退票。", "run": true}' >> $O 2>&1
echo "@@CASE 11-18 18:30 wrong" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794997800000, "title": "物业", "text": "【漕溪北路 88 弄物业】11月19日（周四）9:00–15:00 小区二次供水检修，届时停水，请提前储水。", "run": true}' >> $O 2>&1
echo "@@CASE 11-20 22:18 useful" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1795184280000, "title": "好物集", "text": "下单成功：暖暖 保温杯 350ml 星空蓝，预计 11/22 送达", "run": true}' >> $O 2>&1
done; echo @@END >> $O
