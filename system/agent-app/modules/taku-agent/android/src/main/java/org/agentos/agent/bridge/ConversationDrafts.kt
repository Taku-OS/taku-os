package org.agentos.agent.bridge

/** Activity intents can arrive before Expo creates the module that consumes their text. */
object ConversationDrafts {
  const val EXTRA_DRAFT = "org.agentos.agent.extra.DRAFT"
  private var pending: MutableList<String>? = null

  @Synchronized fun offer(text: String?) {
    val draft = text?.take(16000)?.takeIf { it.isNotBlank() } ?: return
    val drafts = pending ?: ArrayList<String>().also { pending = it }
    drafts.add(draft)
  }

  @Synchronized fun hasPending(): Boolean = pending != null

  @Synchronized fun take(): String {
    val drafts = pending ?: return ""
    pending = null
    return if (drafts.size == 1) drafts[0] else drafts.joinToString("\n")
  }
}
