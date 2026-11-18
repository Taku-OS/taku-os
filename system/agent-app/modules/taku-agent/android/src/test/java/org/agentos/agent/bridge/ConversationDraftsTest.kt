package org.agentos.agent.bridge

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDraftsTest {
  @Before @After fun clearPendingDrafts() { ConversationDrafts.take() }

  @Test fun coldLaunchKeepsEveryRequestUntilTheComposerConsumesThem() {
    ConversationDrafts.offer("first request")
    ConversationDrafts.offer("second request")
    assertEquals("first request\nsecond request", ConversationDrafts.take())
    assertEquals("", ConversationDrafts.take())
  }

  @Test fun identicalTextFromTwoRequestsIsNotDeduplicated() {
    ConversationDrafts.offer("repeat this")
    ConversationDrafts.offer("repeat this")
    assertEquals("repeat this\nrepeat this", ConversationDrafts.take())
  }

  @Test fun laterHandoffsDoNotRestoreAlreadyConsumedText() {
    ConversationDrafts.offer("already applied")
    assertEquals("already applied", ConversationDrafts.take())
    ConversationDrafts.offer("next request")
    assertEquals("next request", ConversationDrafts.take())
    assertEquals("", ConversationDrafts.take())
  }
}
