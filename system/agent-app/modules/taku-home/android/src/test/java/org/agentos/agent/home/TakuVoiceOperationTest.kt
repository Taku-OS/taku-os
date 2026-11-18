package org.agentos.agent.home

import kotlinx.coroutines.flow.MutableStateFlow
import org.agentos.agent.home.assistant.VoiceInput
import org.agentos.agent.home.assistant.VoicePhase
import org.agentos.agent.home.assistant.VoiceState
import org.agentos.agent.home.assistant.VoiceTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class TakuVoiceOperationTest {
  private class Recording : VoiceInput {
    override val state = MutableStateFlow(VoiceState())
    private var nextOperation = 0L

    override fun start(target: VoiceTarget) {
      state.value = VoiceState(operationId = ++nextOperation, target = target, phase = VoicePhase.Preparing)
    }

    fun capture() {
      state.value = state.value.copy(phase = VoicePhase.Capturing)
    }

    override fun finish() {
      when (state.value.phase) {
        VoicePhase.Preparing -> cancel()
        VoicePhase.Capturing -> state.value = state.value.copy(phase = VoicePhase.Finalizing)
        else -> Unit
      }
    }

    override fun cancel() {
      state.value = VoiceState()
    }
  }

  @Test fun lateCancellationFromAnEarlierStartDoesNotCancelTheNewRecording() {
    val recording = Recording()
    recording.start(VoiceTarget.Dictation)
    val first = requireNotNull(recording.state.value.operationId)
    recording.cancel(first)
    recording.start(VoiceTarget.Dictation)
    recording.capture()
    val second = requireNotNull(recording.state.value.operationId)

    recording.cancel(first)
    assertEquals(second, recording.state.value.operationId)
    assertEquals(VoicePhase.Capturing, recording.state.value.phase)

    recording.cancel(second)
    assertEquals(VoicePhase.Idle, recording.state.value.phase)
  }

  @Test fun anOldStopCannotFinishTheNewRecording() {
    val recording = Recording()
    recording.start(VoiceTarget.Dictation)
    val first = requireNotNull(recording.state.value.operationId)
    recording.start(VoiceTarget.Dictation)
    recording.capture()
    val second = requireNotNull(recording.state.value.operationId)

    recording.finish(first)
    assertEquals(VoicePhase.Capturing, recording.state.value.phase)
    recording.finish(second)
    assertEquals(VoicePhase.Finalizing, recording.state.value.phase)
  }

  @Test fun cleanupForAnOlderScreenCannotCancelPreparationForTheNewScreen() {
    val recording = Recording()
    recording.start(VoiceTarget.Dictation)
    val old = requireNotNull(recording.state.value.operationId)
    recording.start(VoiceTarget.Dictation)
    val current = requireNotNull(recording.state.value.operationId)

    recording.cancel(old)
    assertEquals(current, recording.state.value.operationId)
    assertEquals(VoicePhase.Preparing, recording.state.value.phase)
    recording.finish(current)
    assertEquals(VoicePhase.Idle, recording.state.value.phase)
  }
}
