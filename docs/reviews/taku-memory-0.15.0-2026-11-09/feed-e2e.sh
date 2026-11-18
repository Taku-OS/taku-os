# 0.15.0 首页卡片的生命周期和反馈：装首页卡片流替身（TakuHomeFeedStub.apk）后，经真实路径发提醒、在替身上模拟用户操作，
# 看记忆是否读回反应、收回被删掉和到期的卡、关掉开关时全部收回，以及 feed/generate 没回话时能否在首页上找回那张卡。
# deliverNudge / feedTidy 是调试构建、root 才有的钩子；不问模型。只在专用测试 AVD 上运行；测完卸载替身。
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; F="content call --user 0 --uri content://org.agentos.agent.home-feed"; O=feed-e2e.txt; : > $O
rev(){ $F --method feed/list | grep -o '"revision":[0-9]*' | head -1 | cut -d: -f2; }
settle(){ prev=-1; for i in $(seq 1 60); do n=$(rev); if [ "$n" -gt "$1" ] && [ "$n" = "$prev" ]; then break; fi; prev=$n; sleep 2; done; echo "@@SETTLED rev=$n" >> $O; }
nid(){ grep -o '"result":{"id":[0-9]*' | head -1 | cut -d: -f3; }
deliver(){ before=$(rev); echo "@@$1" >> $O; a=$(echo "$2" | sed "s/^{/{\"now\":$(( $(date +%s) * 1000 )),/"); $C --method deliverNudge --arg "$a" > d.tmp 2>&1; cat d.tmp >> $O; LAST=$(nid < d.tmp); echo "@@NUDGE $1 $LAST" >> $O; if [ "$3" = wait ]; then settle $before; else sleep 8; fi; }
list(){ echo "@@LIST $1" >> $O; $F --method feed/list >> $O 2>&1; }
nudge(){ echo "@@NUDGEROW $1 $2" >> $O; $C --method nudge --arg "{\"id\":$2}" >> $O 2>&1; }
tidy(){ echo "@@TIDY $1" >> $O; $C --method feedTidy --arg "$2" >> $O 2>&1; }
gui(){ echo "@@GUI $1 $2" >> $O; $F --method "$1" --arg "$2" >> $O 2>&1; }
NOW=$(( $(date +%s) * 1000 ))
echo "@@RESET" >> $O; $F --method feed/reset >> $O 2>&1; $F --method feed/stub --arg '{"generate":"ok"}' >> $O 2>&1
echo "@@ON" >> $O; $C --method configure --arg '{"nudges":true,"feedCards":true,"feedDescribe":false}' >> $O 2>&1
# 1. 三张固定样式的卡：撞时间的消息、睡前提醒、没有来源应用的消息
deliver "CLASH" '{"kind":"message","title":"小雨","text":"明晚一起吃个饭吧？","say":"明晚 19:00 已答应婷婷在西村日料吃饭，还要带伞还她；可以问小雨改天吗？","conflict":true,"package":"com.tencent.mm"}' wait; A=$LAST
deliver "BRIEF" '{"kind":"evening","title":"今晚确认体验课","items":["11月10日晚上前确认私教体验课，逾期位置让给别人。","11月11日 18:00 前提交打车报销 236 元。","周三前把共享盘/产品/留存_10月.xlsx 的口径对一遍。","给表哥回请柬的事。"]}' wait; B=$LAST
deliver "PARCEL" '{"kind":"message","title":"驿站","text":"您的包裹已到小区驿站，取件码 6-1-3305","say":"明晚 21:00 前取表哥寄的请柬。"}' wait; P=$LAST
list "three cards"
# 2. 模拟用户在屏幕上的操作（origin gui 是替身才有的参数）：撞时间那张交给 Taku，睡前提醒钉住，包裹那张点“隐藏”
gui feed/action "{\"id\":\"memory.nudge.$A\",\"actionId\":\"go\",\"origin\":\"gui\"}"
gui feed/pin "{\"id\":\"memory.brief.$B\",\"origin\":\"gui\"}"
gui feed/action "{\"id\":\"memory.nudge.$P\",\"actionId\":\"hide\",\"origin\":\"gui\"}"
tidy "read back" "{\"now\":$(( $(date +%s) * 1000 ))}"
nudge CLASH $A; nudge BRIEF $B; nudge PARCEL $P
list "after read back"
tidy "read again (nothing new)" "{\"now\":$(( $(date +%s) * 1000 ))}"
# Agent 读取提醒和反应要开着 Agent 记忆查询；这台测试机原来是关的就临时打开，读完关回去
Q=$(grep -o '"queryEnabled":[a-z]*' $O | head -1 | cut -d: -f2)
echo "@@NUDGES (queryEnabled was $Q)" >> $O; $C --method nudges --arg '{"days":1,"limit":5}' >> $O 2>&1
if [ "$Q" = false ]; then $C --method configure --arg '{"queryEnabled":true}' > /dev/null 2>&1; echo "@@NUDGES (queryEnabled on)" >> $O; $C --method nudges --arg '{"days":1,"limit":5}' >> $O 2>&1; $C --method configure --arg '{"queryEnabled":false}' > /dev/null 2>&1; fi
# 3. feed/generate 结果未知：lost 是卡发出了但没回话，silent 是没发也没回话，full 是首页已满
echo "@@DESCRIBE ON" >> $O; $C --method configure --arg '{"feedDescribe":true}' >> $O 2>&1
$F --method feed/stub --arg '{"generate":"lost"}' >> $O 2>&1
deliver "LOST" '{"kind":"message","title":"阿杰","text":"周六的球还打吗？","say":"周六上午你已经约了牙医，10:30 才结束。"}' wait; L=$LAST; nudge LOST $L
$F --method feed/stub --arg '{"generate":"silent"}' >> $O 2>&1
deliver "SILENT" '{"kind":"message","title":"阿杰","text":"那周日呢？","say":"周日下午没有安排。"}' wait; S=$LAST; nudge SILENT $S
$F --method feed/stub --arg '{"generate":"full"}' >> $O 2>&1
deliver "FULL" '{"kind":"message","title":"阿杰","text":"好，周日见","say":"周日下午没有安排。"}' nowait; U=$LAST; nudge FULL $U
list "after unknown outcomes"
# 4. 两天后：到期的卡全部收回（钉住的也收回）
tidy "two days later" "{\"now\":$(( NOW + 2*86400000 ))}"
list "after expiry"
# 5. 关掉首页卡片：记忆发出的卡全部收回
$F --method feed/stub --arg '{"generate":"ok"}' >> $O 2>&1; $C --method configure --arg '{"feedDescribe":false}' >> $O 2>&1
deliver "LAST" '{"kind":"message","title":"小雨","text":"那改到周五？","say":"周五晚上没有安排。","package":"com.tencent.mm"}' wait
list "before off"
before=$(rev); echo "@@OFF" >> $O; $C --method configure --arg '{"nudges":false,"feedCards":false,"feedDescribe":false}' >> $O 2>&1; settle $before
list "after off"
tidy "registry after off" '{"withdraw":true}'
echo "@@END $(date)" >> $O; echo done > feed-e2e.done
