package org.agentos.agent.home.assistant

internal fun AssistantState.ownsRequest(generation: Long, thread: String? = threadId): Boolean =
  requestGeneration == generation && threadId == thread

/** Candidate presentation must never suppress a new recording or a running task. */
internal fun candidateOwnsResultPresentation(state: AssistantState): Boolean =
  state.entry == EntryMode.None &&
    !state.working && !state.needsUser && !state.abandoned && state.error == null &&
    (state.optionsVisible || state.share != null)

/** Back is consumed while the picker or journal registration is in progress. */
internal fun candidateBack(session: AssistantSession) {
  val state = session.state.value
  val share = state.share
  if (share?.busy == true) return
  if (share != null) session.cancelOptionShare(share.options.replyId)
  else if (state.optionsVisible) state.options?.let { session.dismissOptions(it.replyId) }
}

internal fun AssistantState.failCandidateShare(id: String, message: String): AssistantState {
  val current = share?.takeIf { it.id == id } ?: return this
  return copy(optionShare = current.copy(busy = false, pollPickerId = null, error = message))
}
