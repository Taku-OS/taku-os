package org.agentos.agent.home.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.agentos.agent.home.assistant.*
import org.json.JSONArray
import org.json.JSONObject

/** Disposable AVD only. Real Activity/screen lifecycle, fake audio, no submitted messages. */
class VoiceBackgroundProbe : Instrumentation() {
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
  private class Recording : VoiceInput {
    override val state = MutableStateFlow(VoiceState())
    var starts = 0L
    var startFailure: Exception? = null
    override fun start(target: VoiceTarget) {
      startFailure?.let { throw it }
      state.value = VoiceState(operationId = ++starts, target = target, phase = VoicePhase.Capturing)
    }
    override fun finish() { state.value = state.value.copy(phase = VoicePhase.Finalizing) }
    override fun cancel() { state.value = VoiceState() }
    fun empty() { state.value = state.value.copy(phase = VoicePhase.Closed, outcome = VoiceOutcome.Success("")) }
  }
  private fun shell(command: String) {
    uiAutomation.executeShellCommand(command).use { fd -> java.io.FileInputStream(fd.fileDescriptor).use { it.readBytes() } }
  }
  private fun settle() { SystemClock.sleep(1200); waitForIdleSync() }
  override fun onStart() {
    val cases = mutableListOf<String>()
    var failure: Throwable? = null
    lateinit var session: AssistantSession
    lateinit var original: VoiceInput
    val recording = Recording()
    var initialized = false
    var overlay: Activity? = null
    fun main(block: () -> Unit) = runOnMainSync(block)
    fun assertStopped() = main {
      check(recording.state.value.phase == VoicePhase.Idle)
      check(!session.state.value.voiceContinuous && session.state.value.entry == EntryMode.None)
      check(session.gesture.phase == VoiceGesture.Phase.Cancelled)
      val result = targetContext.contentResolver.call(android.net.Uri.parse("content://org.agentos.agent.home-assistant"), "assistant/get", null, null)
      val response = JSONObject(requireNotNull(result?.getString("result")))
      check(response.getBoolean("ok"))
      val state = response.getJSONObject("result")
      check(!state.getBoolean("voiceContinuous"))
      check(state.getString("entry") == "none") { "Agent and GUI must agree after lifecycle stop" }
    }
    try {
      shell("input keyevent 224"); shell("wm dismiss-keyguard")
      startActivitySync(Intent().setClassName(targetContext, "org.agentos.agent.home.HomeActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
      settle()
      main { session = AssistantSession.get(targetContext); original = session.voice; session.reset(); session.voice = recording; initialized = true }
      fun desktopHold() { main { session.beginDesktopHold(); session.releaseHold(); check(session.gesture.phase == VoiceGesture.Phase.Listening) } }
      desktopHold()
      targetContext.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      settle(); assertStopped()
      cases += "Home Listening to another app releases capture and gesture ownership"

      main {
        session.prepareHold("background-system-gesture", "system", SystemClock.uptimeMillis())
      }
      overlay = startActivitySync(Intent().setClassName(targetContext, "org.agentos.agent.home.assistant.AssistantOverlayActivity")
        .putExtra("mode", "voice").putExtra("gestureId", "background-system-gesture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      main { session.releaseHold(); check(session.gesture.phase == VoiceGesture.Phase.Listening) }
      targetContext.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      settle(); assertStopped()
      cases += "cross-app Listening overlay stops when another app covers it"

      targetContext.startActivity(Intent().setClassName(targetContext, "org.agentos.agent.home.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
      settle(); desktopHold()
      val starts = recording.starts
      shell("input keyevent 223"); settle()
      check(!targetContext.getSystemService(PowerManager::class.java).isInteractive)
      assertStopped()
      check(recording.starts == starts) { "screen-off must not reopen capture" }
      cases += "real screen-off cancels latched listening without crash or restart"
      shell("input keyevent 224"); shell("wm dismiss-keyguard"); settle()
      desktopHold(); main { session.cancelVoice() }
      cases += "wake permits a new gesture instead of VOICE_BUSY"

      desktopHold()
      main { session.keepTalking(); check(session.state.value.voiceContinuous) }
      shell("input keyevent 223"); settle(); assertStopped()
      cases += "screen-off also stops the Live Talk handoff"
      shell("input keyevent 224"); shell("wm dismiss-keyguard"); settle()

      desktopHold()
      main { recording.startFailure = IllegalStateException("failed auto restart"); recording.empty() }
      settle()
      main {
        check(session.state.value.voiceFailure == "VOICE_UNAVAILABLE")
        check(session.gesture.phase == VoiceGesture.Phase.Cancelled)
        check(!recording.state.value.listening)
        check(!session.state.value.working && session.state.value.threadId == null)
      }
      cases += "actual 500ms auto-restart loop contains backend failure and remains alive"
    } catch (error: Throwable) { failure = error }
    finally {
      shell("input keyevent 224"); shell("wm dismiss-keyguard")
      main {
        if (initialized) { session.reset(); session.voice = original }
        overlay?.finish()
      }
      targetContext.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    val report = JSONObject().put("passed", failure == null).put("cases", JSONArray(cases))
    failure?.let { report.put("error", it.toString()) }
    finish(if (failure == null) -1 else 0, Bundle().apply { putString("report", report.toString()) })
  }
}
