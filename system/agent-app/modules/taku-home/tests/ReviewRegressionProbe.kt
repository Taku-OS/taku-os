package org.agentos.agent.home.tests

import android.app.Activity
import android.app.Instrumentation
import android.appwidget.AppWidgetHostView
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.agentos.agent.home.apps.HomeLayoutRepository
import org.agentos.agent.home.apps.Slot
import org.agentos.agent.home.assistant.*
import org.agentos.agent.home.widgets.HomeWidgetHost
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Isolated-emulator regression checks. No microphone, model calls or submitted messages. */
class ReviewRegressionProbe : Instrumentation() {
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
  private fun main(block: () -> Unit) = runOnMainSync(block)
  private fun settle() { waitForIdleSync(); SystemClock.sleep(350); waitForIdleSync() }

  private class Recording : VoiceInput {
    override val state = MutableStateFlow(VoiceState())
    override fun start(target: VoiceTarget) { state.value = VoiceState(operationId = 1, target = target, phase = VoicePhase.Capturing) }
    override fun finish() { state.value = state.value.copy(phase = VoicePhase.Finalizing) }
    override fun cancel() { state.value = VoiceState() }
    fun fail() { state.value = state.value.copy(phase = VoicePhase.Closed, outcome = VoiceOutcome.Failed("NETWORK_FAILED")) }
  }

  override fun onStart() {
    val passed = mutableListOf<String>()
    var failure: Throwable? = null
    var home: Activity? = null
    try {
      // Android may have already auto-started its HOME task when instrumentation replaces
      // the process. startActivitySync cannot observe an onNewIntent-only launch.
      home = startActivitySync(Intent().setClassName(targetContext, "org.agentos.agent.home.HomeActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
      settle()
      check(!android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com"))
      check(!android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("192.168.1.20"))
      passed += "installed Agent manifest denies public and LAN cleartext traffic"
      val host = HomeWidgetHost.get(targetContext)
      val layout = HomeLayoutRepository.get(targetContext)
      val baseline = layout.layout.value.pages
      var temporary: Int? = null
      val pending = targetContext.getSharedPreferences("widget-pending", 0)
      try {
        main {
          val result = host.add("org.taku.widgets/.DateWidget", baseline.size, null, 1, 1)
          val id = result.getInt("appWidgetId"); temporary = id
          check(result.getString("status") == "added")
          // Recreate exactly the durable state left by death after layout commit but before
          // pending cleanup. Exercise each public recovery path separately.
          fun interrupted() {
            check(pending.edit().putString(id.toString(), JSONObject().put("appWidgetId", id)
              .put("provider", "org.taku.widgets/.DateWidget").put("page", baseline.size)
              .put("index", JSONObject.NULL).put("spanX", 1).put("spanY", 1).put("stage", "configure").toString()).commit())
          }
          interrupted(); check(host.finish(id).getString("status") == "added")
          interrupted(); check(!host.cancel(id).getBoolean("cancelled")); check(host.info(id) != null)
          interrupted(); check(host.resume(id).getString("status") == "added")
          interrupted(); check(host.instancesJson().getJSONArray("pending").length() == 0)
          check(layout.layout.value.pages.flatten().filterIsInstance<Slot.Widget>().count { it.appWidgetId == id } == 1)
          check(host.info(id) != null)
          passed += "interrupted placement: finish/resume/list/cancel preserve one live binding"
        }
      } finally {
        main { temporary?.let { pending.edit().remove(it.toString()).commit(); host.remove(it) } }
      }
      check(layout.layout.value.pages == baseline) { "original layout must be preserved" }
      settle()

      // The source HostView must remain the listener while a cross-page placeholder is shown.
      val id = baseline.first().filterIsInstance<Slot.Widget>().first().appWidgetId
      @Suppress("UNCHECKED_CAST")
      fun currentView(): AppWidgetHostView? {
        val field = HomeWidgetHost::class.java.getDeclaredField("views").apply { isAccessible = true }
        return (field.get(host) as Map<Int, WeakReference<AppWidgetHostView>>)[id]?.get()
      }
      lateinit var original: AppWidgetHostView
      var x = 0f; var y = 0f; var width = 0f
      main {
        original = requireNotNull(currentView())
        val at = IntArray(2); original.getLocationOnScreen(at)
        x = at[0] + original.width / 2f; y = at[1] + original.height / 2f
        width = home.window.decorView.width.toFloat()
      }
      val down = SystemClock.uptimeMillis()
      fun pointer(action: Int, px: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, px, y, 0)
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try { sendPointerSync(event) } finally { event.recycle() }
      }
      try {
        pointer(MotionEvent.ACTION_DOWN, x); SystemClock.sleep(650)
        pointer(MotionEvent.ACTION_MOVE, x + 40); SystemClock.sleep(100)
        pointer(MotionEvent.ACTION_MOVE, width - 8); SystemClock.sleep(1400)
        main {
          val at = IntArray(2); original.getLocationOnScreen(at)
          check(at[0] < 0) { "drag must actually cross into the next page, x=${at[0]}" }
          check(currentView() === original) { "preview replaced the live widget listener" }
        }
        pointer(MotionEvent.ACTION_MOVE, 8f); SystemClock.sleep(1400)
      } finally { pointer(MotionEvent.ACTION_CANCEL, x) }
      settle()
      main { check(currentView() === original); check(original.width > 0) }
      check(layout.layout.value.pages == baseline)
      passed += "cross-page widget drag and cancellation retain the source HostView and layout"

      val session = AssistantSession.get(targetContext)
      val recording = Recording()
      val originalVoice = session.voice
      var overlay: Activity? = null
      try {
        main {
          check(!session.state.value.working && session.state.value.threadId == null && session.state.value.draft.isBlank()) { "voice probe needs an idle, empty Home session" }
          session.reset(); session.voice = recording
          session.prepareHold("review-overlay-hold", "system", SystemClock.uptimeMillis())
        }
        // Put a different app underneath the input-only overlay. Results belong to SystemUI.
        targetContext.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(300)
        overlay = startActivitySync(Intent().setClassName(targetContext, "org.agentos.agent.home.assistant.AssistantOverlayActivity")
          .putExtra("mode", "voice").putExtra("gestureId", "review-overlay-hold").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        main {
          callActivityOnNewIntent(overlay, Intent().putExtra("mode", "text"))
          callActivityOnNewIntent(overlay, Intent().putExtra("mode", "voice").putExtra("gestureId", "unrelated"))
          session.open(EntryMode.Text)
          check(recording.state.value.phase == VoicePhase.Capturing && session.state.value.entry == EntryMode.Voice)
          check(!overlay.isFinishing) { "repeated or invalid entry must not interrupt recording" }
        }
        main { session.finishVoice(); check(recording.state.value.phase == VoicePhase.Finalizing) }
        val reopened = targetContext.contentResolver.call(android.net.Uri.parse("content://org.agentos.agent.home-assistant"),
          "assistant/open", "{\"mode\":\"text\"}", null)
        check(JSONObject(reopened!!.getString("result")!!).getBoolean("ok"))
        main { check(recording.state.value.phase == VoicePhase.Finalizing && session.state.value.entry == EntryMode.Voice) }
        settle(); check(!overlay.isFinishing && !overlay.isDestroyed) { "the listening page remains until explicit dismissal" }
        main { recording.fail() }
        settle(); check(session.state.value.error != null && session.state.value.overlayVisible) { "late failure is shown without navigating away" }
        main { session.dismiss() }
        settle(); check(overlay.isFinishing || overlay.isDestroyed) { "dismissal must return to the underlying app" }
        passed += "cross-app voice keeps its listening page through finalization/error and exits on explicit dismissal"
        passed += "text reopen and invalid overlay intents preserve capture/finalization through GUI and provider"
      } finally {
        main { overlay?.finish(); session.reset(); session.voice = originalVoice }
      }
    } catch (error: Throwable) { failure = error }
    finally { main { home?.finish() } }
    val report = JSONObject().put("passed", failure == null).put("cases", JSONArray(passed))
      .put("failure", failure?.stackTraceToString() ?: JSONObject.NULL)
    finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, Bundle().apply { putString("report", report.toString()) })
  }
}
