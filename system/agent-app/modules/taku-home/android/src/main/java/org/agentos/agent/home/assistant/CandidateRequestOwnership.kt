package org.agentos.agent.home.assistant

internal fun AssistantState.ownsRequest(generation: Long, thread: String? = threadId): Boolean =
  requestGeneration == generation && threadId == thread

internal val AssistantState.candidatePresentationIdentity: String?
  get() = options?.takeIf { optionsVisible }?.let {
    "$requestGeneration/$threadId/${it.sourceIdentity}"
  }

/** Keep the Island available until the focused overlay actually renders this result. */
internal fun candidateOwnsResultPresentation(state: AssistantState, overlayIdentity: String?): Boolean =
  overlayIdentity != null && overlayIdentity == state.candidatePresentationIdentity

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
