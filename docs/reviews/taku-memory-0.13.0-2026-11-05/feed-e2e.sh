# 0.13.0 首页卡片：装首页卡片流替身（TakuHomeFeedStub.apk）后，经真实路径（真的问模型、发出提醒）把提醒推成卡片
# 只在专用测试 AVD 上运行；替身占用首页的 authority，测完卸载：adb uninstall org.agentos.memorytests.homefeed
cd /data/local/tmp/recall; C="content call --user 0 --uri content://org.agentos.memory"; F="content call --user 0 --uri content://org.agentos.agent.home-feed"; O=feed-e2e.txt; : > $O
echo "@@RESET" >> $O; $F --method feed/reset >> $O 2>&1
echo "@@ON" >> $O; $C --method configure --arg '{"feedCards":true}' >> $O 2>&1
echo "@@EVENING1 1795015800000" >> $O; $C --method proactive --arg '{"kind":"evening","now":1795015800000,"run":true,"deliver":true}' >> $O 2>&1
echo "@@LIST1" >> $O; $F --method feed/list >> $O 2>&1
echo "@@MESSAGE 1795003200000" >> $O; $C --method proactive --arg '{"kind":"message","now":1795003200000,"title":"小雨","text":"明晚一起吃个饭吧？","package":"com.tencent.mm","run":true,"deliver":true}' >> $O 2>&1
echo "@@EVENING2 1795102200000" >> $O; $C --method proactive --arg '{"kind":"evening","now":1795102200000,"run":true,"deliver":true}' >> $O 2>&1
echo "@@LIST2" >> $O; $F --method feed/list >> $O 2>&1
echo "@@OFF" >> $O; $C --method configure --arg '{"feedCards":false}' >> $O 2>&1
echo "@@MESSAGE_OFF 1794573600000" >> $O; $C --method proactive --arg '{"kind":"message","now":1794573600000,"title":"小雨","text":"阿杰生日你准备送啥？我想送点他真想要的，你知道他最近想要啥吗？","package":"com.tencent.mm","run":true,"deliver":true}' >> $O 2>&1
echo "@@LIST3" >> $O; $F --method feed/list >> $O 2>&1
echo "@@END $(date)" >> $O; echo done > feed-e2e.done
