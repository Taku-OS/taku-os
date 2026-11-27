package org.agentos.agent.home.assistant

/** Data returned by WhatsApp's own chat chooser, never a title inferred by the model. */
data class PollRecipient(val jid: String, val name: String) {
  companion object {
    fun selected(jid: String?, name: String?): PollRecipient {
      if (jid == null || !jid.matches(Regex("[0-9]{5,24}(-[0-9]{1,24})?@g\\.us")))
        throw AssistantException("POLL_RECIPIENT_NOT_GROUP", "Choose a WhatsApp group. The poll has not been sent.")
      val title = name?.trim().orEmpty()
      if (title.isBlank() || title.length > 160 || name?.any(::invalidPollCharacter) == true)
        throw AssistantException("POLL_RECIPIENT_UNAVAILABLE", "The selected group could not be read. Choose it again. The poll has not been sent.")
      // Match the existing native AX/journal title normalization.
      return PollRecipient(jid, title)
    }
  }
}
