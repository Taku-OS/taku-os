package org.agentos.agent.home.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptionSharePollValidationTest {
  @Test fun groupMustBeSelectedInWhatsApp() {
    val error = runCatching { share().copy(pollRecipient = null).validatePoll() }.exceptionOrNull() as AssistantException
    assertEquals("INVALID_POLL_TARGET", error.code)
  }

  @Test fun duplicateDisplayLabelsCannotCreatePoll() {
    val error = runCatching { share("FIRST").validatePoll() }.exceptionOrNull() as AssistantException
    assertEquals("INVALID_POLL_OPTIONS", error.code)
  }

  @Test fun canonicalYelpCandidatesNeedNoStreetAddressToCreatePoll() {
    share().validatePoll()
  }

  @Test fun aNativeAnchorIsRequiredToReportDelivery() {
    val result = JSONObject().put("intentId", "intent").put("groupName", "Group")
      .put("status", "sent").put("sent", true)
    val envelope = JSONObject().put("ok", true).put("result", result)
    assertNull(PollSendReceipt.from(envelope, "intent", "Group"))
    result.put("anchor", "message-id")
    assertTrue(PollSendReceipt.from(envelope, "intent", "Group")!!.sent)
    assertNull(PollSendReceipt.from(envelope, "another-intent", "Group"))
    result.put("status", "unknown").put("sent", false)
    assertFalse(PollSendReceipt.from(envelope, "intent", "Group")!!.notSent)
  }

  @Test fun previewShowsTheExactQuestionThatWillBeSent() {
    val share = share().copy(id = "01234567-89ab-4123-8456-123456789abc", pollQuestion = " Where should we eat? ")
    assertEquals("Where should we eat?〔Taku·0123456789AB〕", share.wireQuestion)
    assertEquals(share.wireQuestion, share.json().getString("wireQuestion"))
  }

  private fun share(second: String = "Second") = OptionShare(
    generation = 1, threadId = "thread", pollRecipient = PollRecipient.selected("12345@g.us", "Group"),
    options = ReplyOptions("reply", "candidates", "turn", 1L, "a".repeat(64), "Restaurants", "",
      listOf(candidate("first", "First"), candidate("second", second))),
  )

  private fun candidate(id: String, label: String) =
    ReplyOption(id, label, "", "", listOf(OptionFact("rating", "4.0", "Yelp", "", "")))
}
