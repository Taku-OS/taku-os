package org.agentos.agent.home.assistant

import org.json.JSONObject
import org.agentos.agent.home.island.PollSendStatusDemand
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

  @Test fun pollFormRejectsTextTheNativeJournalCannotRegister() {
    for (label in listOf("Second ", " Second", "Second\u2028Branch", "Second\u2029Branch", "Second\u0000Branch")) {
      val error = runCatching { share(label).validatePoll() }.exceptionOrNull() as AssistantException
      assertEquals("INVALID_POLL_OPTIONS", error.code)
    }
    for (separator in listOf('\u2028', '\u2029')) {
      val error = runCatching { share().copy(pollQuestion = "Where${separator}today?").validatePoll() }.exceptionOrNull() as AssistantException
      assertEquals("INVALID_POLL_QUESTION", error.code)
      val recipientError = runCatching { PollRecipient.selected("12345@g.us", "Group${separator}name") }.exceptionOrNull() as AssistantException
      assertEquals("POLL_RECIPIENT_UNAVAILABLE", recipientError.code)
    }
  }

  @Test fun onlyAnActiveVisiblePollKeepsReadingItsJournal() {
    val share = share()
    val poll = PollPreparationSource("source", share.options, share.pollTarget, share.pollQuestion, share.id)
    val idle = AssistantState(threadId = "execution", island = IslandMode.Expanded, pollPreparation = true, pollSource = poll)
    val idleDemand = PollSendStatusDemand.from(idle)!!
    assertTrue(idleDemand.shouldRead(ready = true, receipt = null, attempts = 0))
    assertFalse(idleDemand.shouldRead(ready = true, receipt = null, attempts = 3))
    for (status in listOf("registered", "bound", "unknown"))
      assertFalse(idleDemand.shouldRead(ready = true, receipt = PollSendReceipt(status, null), attempts = 1))
    val active = PollSendStatusDemand.from(idle.copy(sending = true))!!
    assertTrue(active.shouldRead(ready = true, receipt = PollSendReceipt("unknown", null), attempts = 4))
    assertFalse(active.shouldRead(ready = false, receipt = null, attempts = 0))
    assertFalse(active.shouldRead(ready = true, receipt = PollSendReceipt("sent", null), attempts = 1))
    assertNull(PollSendStatusDemand.from(idle.copy(island = IslandMode.Hidden)))
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

  @Test fun onlyOlderConfirmedPollsMayBePruned() {
    val share = share()
    val source = PollPreparationSource("source", share.options, share.pollTarget, share.pollQuestion, share.id)
    val confirmed = (1..36).associate { n ->
      val thread = "confirmed-$n"
      thread to PollSendResumeStore.record(thread, source).put("confirmedAt", n).toString()
    }
    val unresolved = (1..40).associate { n ->
      val thread = "unresolved-$n"
      thread to PollSendResumeStore.record(thread, source).toString()
    }
    val mismatched = "mismatched" to PollSendResumeStore.record("wrong", source).put("confirmedAt", 40).toString()
    assertEquals(listOf("confirmed-4", "confirmed-3", "confirmed-2", "confirmed-1"),
      PollSendResumeStore.confirmedRefsToPrune(confirmed + unresolved + mismatched))
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
