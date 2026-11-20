package org.agentos.agent.home.tests

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.agentos.agent.home.assistant.*
import org.json.JSONObject
import java.io.File

/** Live acceptance: measured capture callbacks and an actual FAL transcript, with the real gateway.
 * No finish/Keep talking tap triggers submission, and no Agent reply is mocked. */
class VoiceReplyProbe : Instrumentation() {
    override fun onCreate(args: Bundle?) { super.onCreate(args); start() }
    private class Captured(override val state: MutableStateFlow<VoiceState>) : VoiceInput {
        private var id = 0L
        var finishes = 0
        override fun start(target: VoiceTarget) {
            state.value = VoiceState(operationId = ++id, target = target, phase = VoicePhase.Capturing)
        }
        fun sample(level: Float, preview: String = state.value.preview) {
            state.value = state.value.copy(levels = (state.value.levels + level).takeLast(VoiceState.LEVEL_HISTORY), preview = preview)
        }
        override fun finish() {
            if (state.value.phase != VoicePhase.Capturing) return
            finishes++
            state.value = state.value.copy(phase = VoicePhase.Finalizing)
        }
        fun complete(text: String) {
            state.value = state.value.copy(phase = VoicePhase.Closed, outcome = VoiceOutcome.Success(text))
        }
        override fun cancel() { state.value = VoiceState() }
    }
    private fun api(method: String, authority: String = "org.agentos.agent.home-assistant", arg: String? = null): JSONObject =
        JSONObject(targetContext.contentResolver.call(Uri.parse("content://$authority"), method, arg, null)!!.getString("result")!!).also {
            check(it.getBoolean("ok")) { it.toString() }
        }.getJSONObject("result")
    private fun await(timeout: Long = 8000, test: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (!test()) { check(SystemClock.uptimeMillis() < end) { "Timed out waiting for state" }; SystemClock.sleep(50) }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(200)
        val folder = File(targetContext.filesDir, "voice-reply-acceptance").apply { mkdirs() }
        uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    override fun onStart() {
        val report = JSONObject().put("passed", false).put("captureReplayed", true).put("realGateway", true)
        val session = AssistantSession.get(targetContext)
        val original = session.voice
        try {
            check(!session.state.value.working && session.state.value.entry == EntryMode.None)
            val liveSettings = api("settings", "org.agentos.audio.open-access", """{"action":"read"}""").getJSONObject("live")
            val expectedProvider = liveSettings.getString("profileId")
            val (expectedProviderName, otherProviderName) = when (expectedProvider) {
                "fal-grok" -> "Grok Voice via fal" to "Google Gemini Live"
                "gemini-live" -> "Google Gemini Live" to "Grok Voice via fal"
                else -> error("Unknown Live Talk profile: $expectedProvider")
            }
            check(liveSettings.getBoolean("configured")) { "Configure a key for $expectedProvider in Taku Audio" }
            val text = File("/data/local/tmp/taku-voice-check-text.txt").readText().trim()
            check(text.contains("测试") && text.length < 100)
            // Replay into the Engine's existing state stream too: the native publisher is already
            // observing that stream. Replacing only session.voice would leave its UI subscription stale.
            @Suppress("UNCHECKED_CAST")
            val source = EngineVoiceInput::class.java.getDeclaredField("flow").apply { isAccessible = true }
                .get(original) as MutableStateFlow<VoiceState>
            val captured = Captured(source)
            runOnMainSync {
                targetContext.startActivity(Intent().setClassName(targetContext, "org.agentos.agent.home.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            SystemClock.sleep(1000) // Home's resume effect precedes the opening gesture.
            runOnMainSync { session.reset(); session.voice = captured }
            // Agent API and GUI both arm the same latched operation. Pure silence never finishes.
            api("assistant/voice-start")
            check(session.gesture.phase == VoiceGesture.Phase.Listening)
            runOnMainSync { captured.sample(0f) }
            SystemClock.sleep(2200)
            check(captured.finishes == 0 && !session.state.value.working)
            report.put("silentCaptureNotSubmitted", true)
            // Cancel wins even if a late successful final callback arrives after speech.
            runOnMainSync { captured.sample(.7f, text) }
            SystemClock.sleep(100)
            val late = captured.state.value.copy(phase = VoicePhase.Closed, outcome = VoiceOutcome.Success(text))
            api("assistant/voice-cancel")
            runOnMainSync { captured.state.value = late }
            SystemClock.sleep(1800)
            check(!session.state.value.working && session.state.value.turn == null && session.state.value.entry == EntryMode.None)
            report.put("cancelledLateResultNotSubmitted", true)
            // A successful but empty backend-limit result also stays local.
            api("assistant/voice-start")
            val emptyOperation = captured.state.value.operationId
            runOnMainSync { captured.complete("") }
            await { captured.state.value.ready && captured.state.value.operationId != emptyOperation }
            check(!session.state.value.working && session.state.value.entry == EntryMode.Voice)
            check(session.state.value.threadId == null && session.state.value.error == null)
            report.put("emptyResultResumesCapture", true)
            api("assistant/voice-cancel")
            SystemClock.sleep(700)
            check(captured.state.value.phase == VoicePhase.Idle && session.state.value.entry == EntryMode.None)
            report.put("cancelPreventsRestart", true)
            // Actual GUI gesture path: only release, then speech levels followed by silence.
            runOnMainSync { session.beginDesktopHold(); session.releaseHold(); captured.sample(.7f, text) }
            SystemClock.sleep(250)
            runOnMainSync { captured.sample(0f) }
            screenshot("chinese-transcript")
            report.put("stage", "automatic-finalization")
            await { captured.state.value.phase == VoicePhase.Finalizing }
            check(captured.finishes == 1 && !session.state.value.working) { "A preview was submitted before finalization" }
            check(session.state.value.entry == EntryMode.Voice)
            val cardActivity = session.state.value.activityId
            report.put("stage", "agent-reply")
            runOnMainSync { captured.complete(text) } // Delayed successful cloud final callback.
            await(90000) {
                val state = session.state.value
                check(state.error == null) { state.error.orEmpty() }
                check(state.entry == EntryMode.Voice) { "Automatic submission dismissed the hold page" }
                state.turn?.reply?.isNotBlank() == true && !state.working
            }
            val replied = session.state.value
            check(replied.activityId == cardActivity) { "Voice card identity changed during send" }
            report.put("stage", "native-card").put("actualReply", replied.turn!!.reply)
            val readback = api("assistant/get")
            check(readback.getString("entry") == "voice")
            check(readback.getJSONObject("voice").getJSONObject("delivery").getString("state") == "Consumed")
            // Verify the user's visible result is the same actual reply, rendered by SystemUI.
            await {
                val island = JSONObject(targetContext.contentResolver.call(Uri.parse("content://com.android.systemui.island"), "state", null, null)!!.getString("json")!!)
                val card = island.optJSONObject("presentation")?.optJSONObject("card")
                card?.optString("renderer") == "systemui-native" && card.optJSONObject("assistant")?.optString("text") == replied.turn!!.reply
            }
            screenshot("automatic-agent-reply")
            // Replayed callbacks / repeated finish cannot create another turn or hide the page.
            runOnMainSync { captured.state.value = captured.state.value.copy(preview = text); session.finishVoice() }
            SystemClock.sleep(2000)
            check(!session.state.value.working && session.state.value.turn?.turnId == replied.turn?.turnId)
            check(session.state.value.entry == EntryMode.Voice && captured.finishes == 1)
            report.put("threadId", replied.threadId).put("transcript", text).put("reply", replied.turn!!.reply)
                .put("automaticSendWithoutTap", true).put("retainedHoldPage", true).put("nativeReply", true).put("sentOnce", true)
            // Keep talking remains usable after automatic send, in this same conversation.
            report.put("stage", "keep-talking")
            val handoff = api("assistant/voice-continue")
            check(handoff.getJSONObject("liveTalk").get("provider") == JSONObject.NULL)
            val handoffDisclosure = handoff.getString("liveTalkDisclosure")
            check(expectedProviderName in handoffDisclosure && otherProviderName in handoffDisclosure) { handoffDisclosure }
            await { session.state.value.entry == EntryMode.None && session.state.value.voiceContinuous }
            check(session.state.value.threadId == replied.threadId)
            await(30_000) {
                val live = api("assistant/get").getJSONObject("liveTalk")
                check(live.isNull("error")) { live.toString() }
                live.getString("lifecycle") == "active"
            }
            val active = api("assistant/get")
            val live = active.getJSONObject("liveTalk")
            check(live.getString("threadId") == replied.threadId)
            check(live.getString("provider") == expectedProvider && live.getString("sessionId").isNotBlank()) { live.toString() }
            val disclosure = active.getString("liveTalkDisclosure")
            check(expectedProviderName in disclosure && otherProviderName !in disclosure) { disclosure }
            report.put("provider", expectedProvider).put("keepTalkingUsesSameThread", true).put("passed", true)
        } catch (e: Throwable) {
            report.put("error", e.toString()).put("entry", session.state.value.entry.name)
                .put("voicePhase", session.voice.state.value.phase.name)
            runCatching {
                report.put("island", JSONObject(targetContext.contentResolver.call(Uri.parse("content://com.android.systemui.island"), "state", null, null)!!.getString("json")!!).optJSONObject("presentation"))
            }
        }
        finally {
            runOnMainSync { session.stopLiveTalk(); session.voice = original }
            finish(if (report.optBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
        }
    }
}
