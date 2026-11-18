package org.agentos.agent.home.tests

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import org.agentos.agent.home.assistant.*
import org.json.JSONObject

/** Runs only in a disposable AVD: no microphone, cloud requests, or submitted messages. */
class VoiceSessionProbe : Instrumentation() {
  override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

  private class Recording : VoiceInput {
    override val state = MutableStateFlow(VoiceState())
    var starts = 0L
    var startFailure: Exception? = null
    override fun start(target: VoiceTarget) {
      startFailure?.let { throw it }
      state.value = VoiceState(operationId = ++starts, target = target, phase = VoicePhase.Preparing)
    }
    fun capture() { state.value = state.value.copy(phase = VoicePhase.Capturing, preview = "partial") }
    override fun finish() {
      if (state.value.phase == VoicePhase.Capturing) state.value = state.value.copy(phase = VoicePhase.Finalizing)
    }
    fun success(text: String) { state.value = state.value.copy(phase = VoicePhase.Closed, outcome = VoiceOutcome.Success(text)) }
    fun fail() { state.value = state.value.copy(phase = VoicePhase.Closed, outcome = VoiceOutcome.Failed("NETWORK_FAILED")) }
    override fun cancel() { state.value = VoiceState() }
  }

  override fun onStart() {
    val passed = mutableListOf<String>()
    var failure: Throwable? = null
    runOnMainSync {
      val session = AssistantSession.get(targetContext)
      val original = session.voice
      val recording = Recording()
      fun expect(value: Boolean, why: String) { check(value) { why + ": " + session.toJson() } }
      fun hold() {
        // Gesture starts use monotonically increasing device time, including within one frame.
        SystemClock.sleep(2)
        session.beginDesktopHold()
        expect(session.gesture.phase == VoiceGesture.Phase.Holding, "gesture activated")
        expect(session.toJson().getJSONObject("voice").getJSONObject("delivery").getString("target") == "AwaitGesture", "recording has delivery ownership")
      }
      fun case(name: String, test: () -> Unit) {
        session.reset(); test(); passed += name
        expect(!session.state.value.working && session.state.value.threadId == null, "test must never send a request")
      }
      try {
        session.reset(); session.voice = recording
        case("release after activation latches while capture is preparing") {
          hold(); session.releaseHold()
          expect(recording.state.value.phase == VoicePhase.Preparing, "same capture continues preparing")
          expect(session.state.value.entry == EntryMode.Voice && session.gesture.phase == VoiceGesture.Phase.Listening, "page stays latched")
          session.cancelVoice()
          expect(recording.state.value.phase == VoicePhase.Idle && session.state.value.entry == EntryMode.None, "explicit cancel revokes preparation")
        }
        case("Live Talk pause follows user intent through finalization") {
          session.startLiveTalk("")
          recording.capture()
          session.toggleLiveMic()
          expect(session.state.value.voicePaused && recording.state.value.phase == VoicePhase.Finalizing, "pause finalizes the existing capture")
          session.toggleLiveMic()
          expect(!session.state.value.voicePaused && recording.state.value.phase == VoicePhase.Finalizing, "resume waits for the in-flight final result")
          recording.success("")
          expect(session.state.value.voiceContinuous, "empty result keeps Live Talk enabled")
          val starts = recording.starts
          session.toggleLiveMic()
          expect(session.state.value.voicePaused && recording.starts == starts, "mute after capture closes must not start a recorder")
          session.resume("")
          expect(!session.state.value.voiceContinuous && !session.state.value.voicePaused, "conversation boundary clears the pause intent")
          session.stopLiveTalk()
          expect(!session.state.value.voiceContinuous && session.state.value.entry == EntryMode.None, "explicit stop releases voice")
        }
        case("release during automatic finalization still chooses Submit") {
          hold(); recording.capture(); recording.finish()
          session.releaseHold()
          expect(session.gesture.phase == VoiceGesture.Phase.Listening, "release latches listening")
          expect(session.toJson().getJSONObject("voice").getJSONObject("delivery").getString("target") == "Submit", "finalizing result belongs to release")
          recording.success("")
          expect(session.state.value.entry == EntryMode.Voice, "empty final keeps the listening page")
        }
        case("Text receives the complete tail once and preserves edits made while waiting") {
          hold(); recording.capture(); session.setDraft("original")
          session.voiceToText()
          expect(session.state.value.entry == EntryMode.Text, "Text opens immediately")
          session.setDraft("edited")
          session.finishVoice() // A stale Send must not hide Text or change its destination.
          expect(session.state.value.entry == EntryMode.Text, "late finish leaves Text open")
          recording.success("complete tail")
          expect(session.state.value.draft == "edited complete tail", "complete result appended once")
          session.stopDictation()
          expect(session.state.value.draft == "edited complete tail", "duplicate final not appended")
        }
        case("successful recognition before release waits for an explicit destination") {
          hold(); recording.capture(); recording.success("finished early")
          expect(session.state.value.entry == EntryMode.Voice && session.state.value.draft.isEmpty(), "no automatic delivery")
          session.voiceToText()
          expect(session.state.value.draft == "finished early", "closed result can still become draft")
        }
        case("cancel revokes an in-flight result and cannot affect the next recording") {
          hold(); recording.capture(); session.voiceToText()
          val late = recording.state.value.copy(phase = VoicePhase.Closed, outcome = VoiceOutcome.Success("too late"))
          session.cancelVoice(); recording.state.value = late
          expect(session.state.value.draft.isEmpty(), "cancelled result not delivered")
          hold(); recording.capture(); session.voiceToText(); recording.success("new words")
          expect(session.state.value.draft == "new words", "new generation owns its result")
        }
        case("failed recognition ends the gesture and retains the reason without sending partial text") {
          hold(); recording.capture(); recording.fail()
          expect(session.state.value.error != null, "failure is visible")
          expect(session.state.value.entry == EntryMode.Voice && session.state.value.draft.isEmpty(), "partial not delivered and page stays open")
          expect(session.toJson().getJSONObject("voice").getString("error") == "NETWORK_FAILED", "actual error retained")
        }
        case("hidden listening surface releases capture, rotation and handoff retain it") {
          hold(); recording.capture(); session.releaseHold()
          val id = session.gesture.id
          session.onVoiceSurfaceStopped(id, true)
          expect(recording.state.value.listening, "rotation retains the recording")
          session.onVoiceSurfaceStopped("stale-gesture", false)
          expect(recording.state.value.listening, "stale activity cannot cancel a new gesture")
          recording.finish()
          session.onVoiceSurfaceStopped(id, false)
          expect(recording.state.value.phase == VoicePhase.Idle && session.gesture.phase == VoiceGesture.Phase.Cancelled,
            "backgrounding revokes both finalization and the listening gesture")
          hold(); session.releaseHold()
          expect(session.gesture.phase == VoiceGesture.Phase.Listening, "next gesture is not VOICE_BUSY")
          session.cancelVoice()
          session.startLiveTalk("")
          session.onVoiceSurfaceStopped(session.gesture.id, false)
          expect(session.state.value.voiceContinuous && recording.state.value.listening, "Continuous handoff keeps capture")
          session.stopLiveTalk()
        }
        case("automatic restart failures are contained and revoke recording ownership") {
          val restart = AssistantSession::class.java.getDeclaredMethod("restartVoice").apply { isAccessible = true }
          hold(); session.releaseHold(); recording.capture(); recording.success("")
          recording.startFailure = AssistantException("DEVICE_LOCKED", "locked between poll and start")
          restart.invoke(session)
          expect(session.state.value.entry == EntryMode.None && !session.state.value.voiceContinuous,
            "lock race closes voice without throwing")
          recording.startFailure = null
          hold(); session.releaseHold(); recording.capture(); recording.success("")
          recording.startFailure = IllegalStateException("recorder unavailable")
          restart.invoke(session)
          expect(session.state.value.voiceFailure == "VOICE_UNAVAILABLE" && !recording.state.value.listening,
            "backend failure remains observable and cannot auto-retry")
          expect(session.gesture.phase == VoiceGesture.Phase.Cancelled, "failed restart releases gesture")
          recording.startFailure = null
        }
        case("system release before overlay activation never begins a recording") {
          SystemClock.sleep(2)
          val starts = recording.starts
          val id = "system-cold-start"
          session.prepareHold(id, "system", SystemClock.uptimeMillis())
          session.gestureEvent(JSONObject().put("action", "end").put("id", id).put("sequence", 1).put("time", SystemClock.uptimeMillis()))
          session.activateHold(id)
          expect(recording.starts == starts && session.state.value.entry == EntryMode.None, "late overlay cannot start voice")
        }
      } catch (error: Throwable) { failure = error }
      finally { session.reset(); session.voice = original }
    }
    val result = JSONObject().put("passed", failure == null).put("cases", org.json.JSONArray(passed))
    failure?.let { result.put("error", it.toString()) }
    finish(if (failure == null) -1 else 0, Bundle().apply { putString("report", result.toString()) })
  }
}
