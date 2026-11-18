package org.agentos.agent.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConversationHostTest {
  private class Host(val conversation: Boolean, var destroyed: Boolean = false, var finishing: Boolean = false) {
    val extras = mutableMapOf("threadId" to "old", "panelId" to "panel", "panelLaunchId" to "launch")
  }
  private fun host(cached: Host?, current: Host?) = conversationHost(cached, current) {
    it.conversation && !it.destroyed && !it.finishing
  }

  @Test fun backgroundChatRouteChangesLeaveHomeIntentUntouched() {
    val chat = Host(true)
    val home = Host(false)
    val originalHome = home.extras.toMap()
    host(chat, home)!!.extras.apply { put("threadId", "selected"); remove("panelId") }
    assertEquals("selected", chat.extras["threadId"])
    assertNull(chat.extras["panelId"])
    assertEquals(originalHome, home.extras)
  }

  @Test fun coldChatBeforeReactHostResumeUsesTheCreatedChat() {
    val chat = Host(true)
    assertSame(chat, host(chat, Host(false)))
  }

  @Test fun destroyedCachedChatFallsBackToItsLiveReplacement() {
    val old = Host(true, destroyed = true)
    val replacement = Host(true)
    assertSame(replacement, host(old, replacement))
  }

  @Test fun finishingConversationCannotReceiveLateWrites() {
    assertNull(host(Host(true, finishing = true), Host(false)))
  }

  @Test fun homeIsNeverUsedEvenWhenItCarriesConversationExtras() {
    assertNull(host(null, Host(false)))
    assertNull(host(Host(false), Host(false)))
    assertNull(host(Host(true, destroyed = true), Host(false)))
  }

  @Test fun alreadyResumedChatWorksWhenLifecycleObserverAttachedLate() {
    val chat = Host(true)
    assertSame(chat, host(null, chat))
    assertNull(host(null, null))
  }
}
