# 5 个会开口的消息时刻：经 Broker 真的问模型，附上按这次结果会推的卡片（card: true，不发出）
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; O=message-cards.txt; : > $O
echo "@@CARD message" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1793791800000, "title": "小雨", "text": "诶你周末说的那家日料叫啥来着？我周五想带我妈去", "run": true, "card": true, "package": "com.tencent.mm"}' >> $O 2>&1
echo "@@CARD message" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794198000000, "title": "房东徐阿姨", "text": "小林，热水器师傅周三（11 号）下午 2 点到 4 点上门修，家里得有人，你看行不？不行我跟他改", "run": true, "card": true, "package": "com.tencent.mm"}' >> $O 2>&1
echo "@@CARD message" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794535500000, "title": "公司全员群", "text": "@所有人 门禁系统升级，今天 18:00 前到前台更新门禁卡照片，不更新的下周一刷不开门哦", "run": true, "card": true, "package": "com.tencent.mm"}' >> $O 2>&1
echo "@@CARD message" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794573600000, "title": "小雨", "text": "阿杰生日你准备送啥？我想送点他真想要的，你知道他最近想要啥吗？", "run": true, "card": true, "package": "com.tencent.mm"}' >> $O 2>&1
echo "@@CARD message" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794644400000, "title": "小姨", "text": "晚上想吃本帮菜！你找个馆子哈，我请客", "run": true, "card": true, "package": "com.tencent.mm"}' >> $O 2>&1
echo "@@CARD message" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1794852600000, "title": "云程航空", "text": "您的航班 YC1203（上海虹桥—北京首都 07:30）因天气原因取消，可免费改签或全额退票。", "run": true, "card": true, "package": "com.tencent.mm"}' >> $O 2>&1
echo "@@CARD message" >> $O; $C --method proactive --arg '{"kind": "message", "now": 1795003200000, "title": "小雨", "text": "明晚一起吃个饭吧？", "run": true, "card": true, "package": "com.tencent.mm"}' >> $O 2>&1
echo "@@END $(date +%T)" >> $O
