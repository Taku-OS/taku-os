package org.agentos.agent.bridge

import android.content.Intent
import android.os.Binder

object ChatCameraLaunch {
  private const val EXTRA = "org.agentos.agent.extra.OPEN_CHAT_CAMERA"
  private const val DRAFT = "$EXTRA.draft"
  private const val DRAFT_TICKET = "$DRAFT.ticket"

  // MainActivity is exported. Only a ticket created inside this process can open its viewfinder.
  private fun ticket(intent: Intent, threadId: String, key: String): Binder? {
    if (threadId.isBlank() || threadId != intent.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID)) return null
    val binder = intent.extras?.getBinder(key) as? Binder ?: return null
    return binder.takeIf { it.interfaceDescriptor == "$key:$threadId" }
  }

  @Synchronized internal fun isPending(intent: Intent): Boolean =
    ticket(intent, intent.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID).orEmpty(), EXTRA) != null

  @Synchronized internal fun consume(intent: Intent?, threadId: String): Boolean {
    return intent != null && consumeTicket(intent, threadId, EXTRA)
  }

  @Synchronized internal fun consumeDraft(intent: Intent?, threadId: String): String {
    if (intent == null || !consumeTicket(intent, threadId, DRAFT_TICKET)) return ""
    val draft = intent.getStringExtra(DRAFT).orEmpty()
    intent.removeExtra(DRAFT)
    return draft
  }

  private fun consumeTicket(intent: Intent, threadId: String, key: String): Boolean {
    val ticket = ticket(intent, threadId, key) ?: return false
    ticket.attachInterface(null, "")
    intent.removeExtra(key)
    return true
  }

  @Synchronized fun invalidatePrevious(previous: Intent?, next: Intent) {
    if (previous == null || previous === next) return
    val threadId = previous.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID).orEmpty()
    consumeTicket(previous, threadId, EXTRA)
    if (consumeTicket(previous, threadId, DRAFT_TICKET)) previous.removeExtra(DRAFT)
  }
}
