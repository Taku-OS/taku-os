package org.agentos.agent.home.tests

import android.app.Instrumentation
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import org.agentos.agent.home.assistant.*
import org.json.JSONObject
import java.io.File

/** Isolated UI fixture, never submitted to the model or persisted as a conversation. */
class NativeIslandProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun presentation(): JSONObject = JSONObject(targetContext.contentResolver.call(
        Uri.parse("content://com.android.systemui.island"), "state", null, null)!!.getString("json")!!).getJSONObject("presentation")
    private fun awaitCard(text: String, options: Int): JSONObject {
        val end = SystemClock.uptimeMillis() + 8000
        do {
            val p = presentation()
            val card = p.optJSONObject("card")
            if (card != null) {
                check(card.getString("renderer") == "systemui-native") { "A non-native card was displayed" }
                check(!card.optBoolean("richReady")) { "Remote surface unexpectedly used" }
                val body = card.optJSONObject("assistant")
                if (card.optBoolean("nativeReady") && card.optInt("height") > 0 && body?.optString("text") == text && body.optInt("suggestionCount") == options) {
                    check(!p.optBoolean("compactVisible")); return card
                }
            }
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < end)
        error("Native card did not reach expected content: ${presentation()}")
    }
    private fun gesture(card: JSONObject, xFraction: Float, yDp: Float, dxDp: Float, dyDp: Float) {
        val density = targetContext.resources.displayMetrics.density
        val width = targetContext.resources.displayMetrics.widthPixels
        val x = (width - card.getInt("width")) / 2f + card.getInt("width") * xFraction
        val y = card.getInt("top") + yDp * density
        val down = SystemClock.uptimeMillis()
        for (i in 0..12) {
            val action = if (i == 0) MotionEvent.ACTION_DOWN else if (i == 12) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x + dxDp * density * i / 12, y + dyDp * density * i / 12, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)); event.recycle(); SystemClock.sleep(20)
        }
        SystemClock.sleep(250)
    }
    private fun reopen(card: JSONObject) {
        val result = JSONObject(targetContext.contentResolver.call(Uri.parse("content://com.android.systemui.island"),
            "expand", card.getString("key"), null)!!.getString("json")!!)
        check(result.optBoolean("ok")) { result.toString() }
    }
    private fun assertCollapsed() {
        val end = SystemClock.uptimeMillis() + 3000
        while (!presentation().isNull("card") && SystemClock.uptimeMillis() < end) SystemClock.sleep(40)
        val p = presentation()
        check(p.isNull("card") && !p.optBoolean("compactVisible") && p.optString("reason") == "swipe_up") { p.toString() }
        val observerDeadline = SystemClock.uptimeMillis() + 3000
        while (org.agentos.agent.home.island.SystemIslandBridge.presentation(targetContext) != "hidden" &&
            SystemClock.uptimeMillis() < observerDeadline) SystemClock.sleep(40)
        check(org.agentos.agent.home.island.SystemIslandBridge.presentation(targetContext) == "hidden") {
            "GUI dismissal did not notify the Agent's SystemUI observer"
        }
    }
    private fun screenshot(name: String) {
        // Provider state changes on the UI thread before the next compositor frame.
        SystemClock.sleep(200)
        val folder = File(targetContext.filesDir, "native-island-acceptance").apply { mkdirs() }
        uiAutomation.takeScreenshot().let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    override fun onStart() {
        val report = JSONObject().put("passed", false)
        val notifications = targetContext.getSystemService(NotificationManager::class.java)
        val progressId = 606001
        var original: AssistantState? = null
        var flow: MutableStateFlow<AssistantState>? = null
        try {
            // Home can already be resumed; startActivitySync then waits for an Activity creation
            // that never happens. Let the state/readback polling below own readiness instead.
            runOnMainSync { targetContext.startActivity(Intent().setClassName(targetContext, "org.agentos.agent.home.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            SystemClock.sleep(700)
            val session = AssistantSession.get(targetContext)
            @Suppress("UNCHECKED_CAST")
            val state = AssistantSession::class.java.getDeclaredField("flow").apply { isAccessible = true }.get(session) as MutableStateFlow<AssistantState>
            flow = state; original = state.value
            check(!original.working && original.entry == EntryMode.None) { "Requires idle session; draft is preserved" }
            // Preserve the restored Expanded state: a new explicit request must reopen it.
            val listening = AssistantState(activityId = "native-ui-fixture", island = IslandMode.Expanded,
                entry = EntryMode.Voice, expansionRequest = state.value.expansionRequest + 1)
            runOnMainSync { state.value = listening }
            val first = awaitCard("Taku is listening...", 0)
            repeat(6) { awaitCard("Taku is listening...", 0); SystemClock.sleep(80) }
            screenshot("listening")
            report.put("listeningHeight", first.getInt("height")).put("noCannedOptions", true).put("nativeFromFirstCard", true)
            val reply = "Here is the response from the current Agent turn."
            val turn = TurnView("fixture-turn", "fixture input", false, false, "A summary", reply, emptyList(), emptyList())
            runOnMainSync { state.value = listening.copy(entry = EntryMode.None, turn = turn) }
            var card = awaitCard(reply, 0); screenshot("reply")
            gesture(card, .5f, 70f, 0f, -42f); assertCollapsed()
            check(state.value.turn == turn && !state.value.working) { "Dismiss changed the Agent turn" }
            runOnMainSync { state.value = state.value.copy(expansionRequest = state.value.expansionRequest + 1) }
            card = awaitCard(reply, 0)
            report.put("newInteractionReopensDismissedCard", true)
            gesture(card, .025f, 60f, 0f, -35f); assertCollapsed()
            reopen(card); awaitCard(reply, 0)
            report.put("replyAndPaddingSwipeDismiss", true).put("dismissPreservesTurn", true)
            report.put("swipeNotifiesAgentObserver", true)
            val choices = listOf(Suggestion("detail", "Show all of the detailed response", "Show details"), Suggestion("short", "Make the complete answer much shorter", "Make it shorter"))
            runOnMainSync { state.value = state.value.copy(turn = turn.copy(suggestions = choices)) }
            card = awaitCard(reply, 2); screenshot("reply-options")
            val chipY = card.getInt("height") / targetContext.resources.displayMetrics.density - 34f
            gesture(card, .75f, chipY, -80f, 0f)
            awaitCard(reply, 2); check(!state.value.working)
            gesture(card, .5f, chipY, 0f, -42f); assertCollapsed()
            reopen(card); awaitCard(reply, 2)
            report.put("chipsKeepHorizontalScroll", true).put("chipsSwipeUpDismiss", true)
            runOnMainSync { state.value = state.value.copy(turn = turn) }
            awaitCard(reply, 0)
            val longReply = List(24) { "This is a line from the Agent response, shown by the native card." }.joinToString("\n")
            runOnMainSync { state.value = state.value.copy(turn = turn.copy(reply = longReply)) }
            card = awaitCard(longReply.take(1200), 0); screenshot("long-reply")
            gesture(card, .5f, card.getInt("height") / targetContext.resources.displayMetrics.density - 32f, 0f, -90f)
            awaitCard(longReply.take(1200), 0) // This gesture scrolls the long body instead of dismissing it.
            gesture(card, .35f, 35f, 0f, -30f); assertCollapsed()
            report.put("longReplyRemainsScrollable", true).put("headerSwipeDismiss", true)
            // A completed assistant remains in the store. It must not hide unrelated ongoing work.
            notifications.createNotificationChannel(NotificationChannel("island-coexistence-test", "Island coexistence test", NotificationManager.IMPORTANCE_LOW))
            val payload = JSONObject().put("version", 2)
                .put("compact", JSONObject().put("b", JSONObject().put("type", "text").put("text", "Test progress")))
                .put("expanded", JSONObject().put("template", "basic").put("title", "Test progress").put("body", "Temporary regression fixture"))
            notifications.notify(progressId, Notification.Builder(targetContext, "island-coexistence-test")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Test progress").setOngoing(true)
                .addExtras(Bundle().apply { putString("taku.island.v2", payload.toString()) }).build())
            fun awaitProgress() {
                val end = SystemClock.uptimeMillis() + 5000
                while (!presentation().optBoolean("compactVisible") && SystemClock.uptimeMillis() < end) SystemClock.sleep(40)
                val p = presentation()
                check(p.isNull("card") && p.optBoolean("compactVisible") && p.optString("selected") != card.getString("key")) { p.toString() }
            }
            awaitProgress(); screenshot("progress-with-hidden-assistant")
            reopen(card); awaitCard(longReply.take(1200), 0)
            targetContext.contentResolver.call(Uri.parse("content://com.android.systemui.island"), "collapse", null, null)
            awaitProgress()
            report.put("hiddenAssistantDoesNotMaskProgress", true).put("explicitAssistantExpansionPreserved", true)
            // Exercise a real new-gesture edge from a retained Expanded reply, without resetting
            // AssistantSession (the old probe reset concealed this production regression).
            notifications.cancel(progressId)
            val originalVoice = session.voice
            try {
                runOnMainSync {
                    session.voice = object : VoiceInput {
                        override val state = MutableStateFlow(VoiceState())
                        override fun start(target: VoiceTarget) { this.state.value = VoiceState(operationId = 1L, target = target, phase = VoicePhase.Capturing) }
                        override fun finish() { this.state.value = this.state.value.copy(phase = VoicePhase.Finalizing) }
                        override fun cancel() { this.state.value = VoiceState() }
                    }
                    session.beginDesktopHold()
                    session.releaseHold()
                }
                card = awaitCard("Taku is listening...", 0)
                report.put("realGestureReopensRetainedExpandedReply", true)
                // Home intentionally requests portrait. Rotate an unconstrained fixture Activity,
                // not the fixed-orientation launcher; leave the latched voice session alive.
                runOnMainSync { targetContext.startActivity(Intent().setClassName("org.agentos.island.fixture", "org.agentos.island.fixture.Fixture")
                    .putExtra("editorOnly", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                SystemClock.sleep(600)
                check(uiAutomation.setRotation(1))
                SystemClock.sleep(800)
                uiAutomation.takeScreenshot().let { bitmap ->
                    try { check(bitmap.width > bitmap.height) { "Rotation test did not enter landscape" } }
                    finally { bitmap.recycle() }
                }
                val until = SystemClock.uptimeMillis() + 5000
                while (!presentation().isNull("card") && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
                check(presentation().isNull("card")) { presentation().toString() }
                report.put("landscapeClosesNonCompactCard", true)
                check(uiAutomation.setRotation(0)); SystemClock.sleep(800)
                runOnMainSync { session.expand(false) }
                awaitCard("Taku is listening...", 0)
                report.put("portraitExplicitExpandRestoresCard", true)
            } finally {
                uiAutomation.setRotation(-2)
                runOnMainSync { session.cancelVoice(); session.voice = originalVoice }
            }
            report.put("actualReplyProjection", true).put("dynamicOptionsAppearAndDisappear", true).put("passed", true)
        } catch (e: Throwable) { report.put("error", e.toString()) }
        finally {
            notifications.cancel(progressId)
            notifications.deleteNotificationChannel("island-coexistence-test")
            if (original != null && flow != null) runOnMainSync { flow!!.value = original!! }
            SystemClock.sleep(900)
            finish(if (report.optBoolean("passed")) -1 else 0, Bundle().apply { putString("report", report.toString()) })
        }
    }
}
