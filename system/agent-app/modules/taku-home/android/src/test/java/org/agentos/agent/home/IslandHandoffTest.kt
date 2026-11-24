package org.agentos.agent.home

import org.agentos.agent.home.assistant.*
import org.junit.Assert.*
import org.junit.Test

class IslandHandoffTest {
  private fun home(threadId: String? = null, islandRequested: Boolean = false, cardExpanded: Boolean = false,
                   working: Boolean = false, needsUser: Boolean = false, entryOpen: Boolean = false,
                   microphoneBusy: Boolean = false, liveTalk: Boolean = false) =
    IslandHandoff.Home(threadId, islandRequested, cardExpanded, working, needsUser, entryOpen, microphoneBusy, liveTalk)

  private fun busy(home: IslandHandoff.Home, thread: String = "chat") {
    try { IslandHandoff.admit(home, thread); fail("Home was replaced while busy: $home") }
    catch (e: AssistantException) { assertEquals("HOME_BUSY", e.code) }
  }

  private fun turn(id: String, active: Boolean, failed: Boolean = false) =
    TurnView(id, "Plan a weekend", active, failed, null, if (active) null else "Here is the plan", emptyList(), emptyList())

  @Test fun anIdleOrSameThreadHomeAcceptsTheHandoff() {
    assertEquals(IslandHandoff.Admission.HANDED_OFF, IslandHandoff.admit(home(), "chat"))
    assertEquals(IslandHandoff.Admission.HANDED_OFF, IslandHandoff.admit(home(threadId = "other"), "chat"))
    // Opened from the island card earlier: same thread, island hidden.
    assertEquals(IslandHandoff.Admission.HANDED_OFF, IslandHandoff.admit(home(threadId = "chat", working = true), "chat"))
  }

  @Test fun collapseAlreadyShowsTheThread() {
    assertEquals(IslandHandoff.Admission.ALREADY_SHOWN,
      IslandHandoff.admit(home(threadId = "chat", islandRequested = true, cardExpanded = true, working = true), "chat"))
  }

  @Test fun whateverHomeIsDoingWins() {
    busy(home(threadId = "other", working = true))
    busy(home(threadId = "other", needsUser = true))
    busy(home(threadId = "other", islandRequested = true, cardExpanded = true))
    busy(home(entryOpen = true))
    busy(home(microphoneBusy = true))
    busy(home(threadId = "chat", liveTalk = true))
  }

  @Test fun onlyConversationIdsAreAccepted() {
    for (bad in listOf("", " ", "a b", "x".repeat(129), "../thread")) {
      try { IslandHandoff.admit(home(), bad); fail("accepted $bad") }
      catch (e: AssistantException) { assertEquals("INVALID_ARGUMENT", e.code) }
    }
    assertTrue(IslandHandoff.validThread("019a2b3c-thread_1:2.x"))
  }

  @Test fun theAwaitedTurnRevealsOnlyOnceItFinishes() {
    val handoff = Handoff("chat", turnId = "t2")
    assertEquals(IslandHandoff.Outcome.WAIT, IslandHandoff.step(handoff, "chat", null, null, null, null).outcome)
    assertEquals(IslandHandoff.Outcome.WAIT, IslandHandoff.step(handoff, "chat", turn("t2", true), "running", "9", null).outcome)
    // The previous reply the stream shows first is not this turn's result.
    assertEquals(IslandHandoff.Outcome.WAIT, IslandHandoff.step(handoff, "chat", turn("t1", false), "running", "9", null).outcome)
    assertEquals(IslandHandoff.Outcome.REVEAL, IslandHandoff.step(handoff, "chat", turn("t2", false), "completed", "9", null).outcome)
  }

  @Test fun aQueuedTaskWaitsForTheFirstTurnThatStarts() {
    val queued = Handoff("chat", taskId = "9")
    val idle = IslandHandoff.step(queued, "chat", turn("t1", false), "queued", "9", null)
    assertEquals(IslandHandoff.Outcome.WAIT, idle.outcome)
    assertNull(idle.handoff?.turnId)
    val armed = IslandHandoff.step(queued, "chat", turn("t2", true), "running", "9", null)
    assertEquals("t2", armed.handoff?.turnId)
    assertEquals(IslandHandoff.Outcome.REVEAL, IslandHandoff.step(armed.handoff!!, "chat", turn("t2", false), "completed", "9", null).outcome)
  }

  // Retest of f6a0913f: a send left before its turn was known must not miss a turn that finishes
  // before Home first sees it running.
  @Test fun theFirstTurnAfterTheKnownOnesIsAwaitedEvenIfAlreadyFinished() {
    val left = Handoff("chat", knownTurnIds = setOf("t1"))
    assertEquals(IslandHandoff.Outcome.WAIT, IslandHandoff.step(left, "chat", turn("t1", false), "completed", null, null).outcome)
    assertEquals(IslandHandoff.Outcome.REVEAL, IslandHandoff.step(left, "chat", turn("t2", false), "completed", "9", null).outcome)
    val running = IslandHandoff.step(left, "chat", turn("t2", true), "running", "9", null)
    assertEquals("t2", running.handoff?.turnId)
    // A new conversation had no earlier turns at all.
    assertEquals(IslandHandoff.Outcome.REVEAL,
      IslandHandoff.step(Handoff("chat", knownTurnIds = emptySet()), "chat", turn("t1", false), "completed", null, null).outcome)
  }

  @Test fun knownTurnsAreValidatedAndBounded() {
    assertNull(IslandHandoff.knownTurns(null))
    assertEquals(setOf("t1", "t2"), IslandHandoff.knownTurns(listOf("t1", "t2", "t1")))
    for (bad in listOf(listOf<Any?>(1), listOf<Any?>("a b"), List(IslandHandoff.MAX_KNOWN_TURNS + 1) { "t$it" })) {
      try { IslandHandoff.knownTurns(bad); fail("accepted $bad") }
      catch (e: AssistantException) { assertEquals("INVALID_ARGUMENT", e.code) }
    }
  }

  @Test fun aQuestionOrANewFailureRevealsButAnOldErrorDoesNot() {
    val handoff = Handoff("chat", turnId = "t2", errorAtStart = "TURN_FAILED")
    assertEquals(IslandHandoff.Outcome.REVEAL, IslandHandoff.step(handoff, "chat", turn("t2", true), "needs_user", "9", "TURN_FAILED").outcome)
    assertEquals(IslandHandoff.Outcome.WAIT, IslandHandoff.step(handoff, "chat", turn("t2", true), "running", "9", "TURN_FAILED").outcome)
    assertEquals(IslandHandoff.Outcome.REVEAL, IslandHandoff.step(handoff, "chat", turn("t2", true), "failed", "9", "TASK_FAILED").outcome)
    assertEquals(IslandHandoff.Outcome.REVEAL, IslandHandoff.step(Handoff("chat", turnId = "t2"), "chat", turn("t2", false, failed = true), "running", "9", "TURN_FAILED").outcome)
  }

  @Test fun aCancelledTaskOrAnotherThreadOpensNothing() {
    val handoff = Handoff("chat", taskId = "9")
    assertEquals(IslandHandoff.Outcome.DROP, IslandHandoff.step(handoff, "chat", null, "cancelled", "9", null).outcome)
    assertEquals(IslandHandoff.Outcome.WAIT, IslandHandoff.step(handoff, "chat", null, "cancelled", "8", null).outcome)
    assertEquals(IslandHandoff.Outcome.DROP, IslandHandoff.step(handoff, "other", null, "running", "9", null).outcome)
  }
}
