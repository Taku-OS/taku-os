# 0.14.0 首页 AI 设计卡片：装首页卡片流替身（TakuHomeFeedStub.apk）后，经真实路径（真的问模型、发出提醒）走描述这条路
# 替身的 feed/generate 不调模型，按描述里的 actions 排一张卡；feed/stub 切换成篡改按钮（tamper）或拒绝（refuse），看记忆是否收回并改用固定样式
# 只在专用测试 AVD 上运行；测完卸载替身：adb uninstall org.agentos.memorytests.homefeed
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; F="content call --user 0 --uri content://org.agentos.agent.home-feed"; O=describe-e2e.txt; : > $O
# 推卡在后台线程里做：等替身的调用记录不再增加
calls(){ $F --method feed/list | grep -o '"method"' | wc -l; }
settle(){ prev=-1; for i in $(seq 1 90); do n=$(calls); if [ "$n" -gt "$1" ] && [ "$n" = "$prev" ]; then break; fi; prev=$n; sleep 3; done; echo "@@SETTLED $n" >> $O; }
run(){ before=$(calls); echo "@@$1" >> $O; $C --method proactive --arg "$2" >> $O 2>&1; settle $before; echo "@@LIST $1" >> $O; $F --method feed/list >> $O 2>&1; }
echo "@@RESET" >> $O; $F --method feed/reset >> $O 2>&1
echo "@@ON" >> $O; $C --method configure --arg '{"feedCards":true,"feedDescribe":true}' >> $O 2>&1
$F --method feed/stub --arg '{"generate":"ok"}' >> $O 2>&1
run "EVENING1 ok" '{"kind":"evening","now":1795015800000,"run":true,"deliver":true}'
run "MESSAGE ok" '{"kind":"message","now":1795003200000,"title":"小雨","text":"明晚一起吃个饭吧？","package":"com.tencent.mm","run":true,"deliver":true}'
$F --method feed/stub --arg '{"generate":"tamper"}' >> $O 2>&1
run "MESSAGE2 tamper" '{"kind":"message","now":1794573600000,"title":"小雨","text":"阿杰生日你准备送啥？我想送点他真想要的，你知道他最近想要啥吗？","package":"com.tencent.mm","run":true,"deliver":true}'
$F --method feed/stub --arg '{"generate":"refuse"}' >> $O 2>&1
run "EVENING2 refuse" '{"kind":"evening","now":1795102200000,"run":true,"deliver":true}'
echo "@@PROMPTS" >> $O; $F --method feed/stub-prompts >> $O 2>&1
echo "@@OFF" >> $O; $C --method configure --arg '{"feedCards":false,"feedDescribe":false}' >> $O 2>&1
echo "@@END $(date)" >> $O; echo done > describe-e2e.done
