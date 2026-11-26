# 预设音频与署名

此目录是手机离线声音库。`catalog.json` 包含每条内容、用途、作者、来源页面、许可链接、文件 SHA256、下载原文件 SHA256 和处理说明；页面“音频来源与许可”可查看署名。

| 文件 | 内容 | 作者 | 授权 |
|---|---|---|---|
| hello.wav | 普通话“你好” | Sjors Provoost | CC BY-SA 3.0 |
| thanks.wav | 普通话“谢谢” | Wei Gao、Vion Nicolas / Shtooka Project | CC BY 2.0 FR |
| goodbye.wav | 普通话“再见” | Wyang | CC BY-SA 3.0 |
| dog-bark.wav | 近距离犬吠，截取原音频前 5 秒 | felix.blume | CC0 1.0 |
| confirm.wav | confirmation_001，完成提示 | Kenney | CC0 1.0 |
| bell.wav | bong_001，短铃声 | Kenney | CC0 1.0 |

“你好”与“再见”的本目录版本继续按 CC BY-SA 3.0 提供，“谢谢”继续按 CC BY 2.0 FR 提供，其余为 CC0。授权适用于相应音频，不能将本目录所有文件标为项目代码的许可证。所有声音都转成 48kHz 单声道 PCM WAV、统一峰值、首尾各补 0.2 秒静音；除犬吠截取外没有修改台词内容。以上首批六条是下载的原录音与音效。下方英语集合是新增的原创合成语音。

下载来源（2026-10-30）：

- 你好：https://commons.wikimedia.org/wiki/File:Zh_n%C7%90_h%C7%8Eo.ogg
- 谢谢：https://commons.wikimedia.org/wiki/File:Zh-xi%C3%A8xie.ogg
- 再见：https://commons.wikimedia.org/wiki/File:Zh-cmn-%E5%86%8D%E8%A6%8B.opus
- 狗叫：https://freesound.org/people/felix.blume/sounds/199261/ ，使用作者公开的 HQ MP3 预听文件。
- 两种提示音：https://opengameart.org/content/interface-sounds ，来自作者 Kenney 上传的原始音效包。包内许可保留为 KENNEY-LICENSE.txt。

许可文本入口：
- https://creativecommons.org/licenses/by-sa/3.0/
- https://creativecommons.org/licenses/by/2.0/fr/
- https://creativecommons.org/publicdomain/zero/1.0/

客户端只能发送预设 ID；服务端验证白名单、目录边界和文件哈希。选择预设不覆盖此前用户导入的录音，选择记录独立保存在手机私有目录。音量在拖动结束后写入设备，并再次读取确认；停止操作会作废尚未完成的播放流程。

## 英语男声集合（2026-10-30）

新增 20 条原创英语短句，由同一次完整录音切分，保持声音一致。风格目标为非裔美式英语、南加州口音、自然松弛的西海岸嘻哈表达；这是生成指令和风格目标，不代表经过语言学口音鉴定。没有使用真实人物录音或指定歌手作为参考。页面明确标注 AI 合成，并展示英文台词和中文释义。

| 预设 | 英文台词 | 中文释义 |
|---|---|---|
| en-whats-good | Yo, what's good? I'm Taku. | 嘿，最近怎么样？我是 Taku。 |
| en-lets-roll | All right, let's roll. | 好，咱们出发。 |
| en-got-you | Say less. I got you. | 懂了，交给我。 |
| en-good-to-see-you | Hey, good to see you, fam. | 嘿，兄弟，见到你真好。 |
| en-ready | I'm ready when you are. | 我准备好了，随时听你的。 |
| en-on-my-way | I'm on my way. Catch you in a sec. | 我这就来，马上见。 |
| en-coming-through | Coming through. Appreciate you. | 借过一下，多谢啦。 |
| en-give-me-room | Hey, give me a little room, please. | 嘿，麻烦给我让一点空间。 |
| en-hold-up | Hold up. Give me a second. | 稍等，给我一点时间。 |
| en-all-good | It's all good. No rush. | 没事儿，不着急。 |
| en-nice-work | Okay, I see you! Nice work. | 可以啊，有两下子！干得漂亮。 |
| en-thats-the-vibe | Yeah, that's the vibe. | 对，就是这个感觉。 |
| en-keep-it-moving | Let's keep it moving. | 咱们继续往前走。 |
| en-roll-with-me | Roll with me. We got this. | 跟我一起，咱们能搞定。 |
| en-good-looking-out | Good looking out. I appreciate you. | 谢了，多亏你照应。 |
| en-my-bad | My bad. Let me try that again. | 我的问题，让我再试一次。 |
| en-catch-you-later | All right, catch you later. Stay cool. | 好啦，回头见，保持酷劲儿。 |
| en-recharge | I'm gonna recharge. Catch you in a bit. | 我去充个电，一会儿见。 |
| en-lets-go | Let's go! That's what I'm talking about. | 好耶！要的就是这个。 |
| en-taku-intro | Four paws, fresh moves, and a whole lot of heart. I'm Taku. Let's make today a good one. | 四只脚，酷动作，还有满满的热情。我是 Taku，今天一起玩出点精彩。 |

生成服务：[Qwen 官方 Qwen3-TTS Space](https://huggingface.co/spaces/Qwen/Qwen3-TTS)。使用 [VoiceDesign](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign) 生成同一段约 58 秒的完整台词录音，再按自动转写的词时间戳与句间静音切分。模型使用 Apache-2.0；该标注说明模型许可证，不把生成音频或第三方旧音频统一改为 Apache-2.0。

`english-voice-recipe.json` 保存完整台词和音色描述，`../prepare-english-presets.py` 是可选切分工具，播放不依赖该工具或在线生成服务。`catalog.json` 保存每条最终文件与完整原始录音的 SHA256，以及该条在原始录音中的起止时间。音频统一为 48kHz、16-bit、单声道 WAV，峰值约 -6.2dBFS，首尾各补 0.2 秒静音。

这些预设由用户选择后手动播放；例如“On my way”是台词，不会触发行走，也不表示系统已执行对应动作。
