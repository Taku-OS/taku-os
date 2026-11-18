package org.agentos.agent.home

import org.agentos.agent.home.assistant.*
import org.junit.Assert.*
import org.junit.Test

class AppBuildPresentationTest {
  private fun running() = AssistantState(threadId = "thread", task = "running", turn =
    TurnView("turn", "Create an app", true, false, null, null, emptyList(), emptyList()))

  @Test fun onlyAnExplicitAppWorkflowShowsBuilding() {
    assertFalse(AssistantState(entry = EntryMode.Voice).buildingApp)
    assertFalse(AssistantState(error = "VOICE_UNAVAILABLE").buildingApp)
    assertFalse(running().buildingApp) // Even a prompt mentioning an app is not a build signal.
    assertFalse(AssistantState(sending = true).buildingApp)
    assertTrue(running().withAppBuild("thread", "turn", true).buildingApp)
  }
  @Test fun terminalErrorApprovalAndDifferentTurnsImmediatelyHideBuilding() {
    val state = running().withAppBuild("thread", "turn", true)
    assertFalse(state.copy(error = "TASK_FAILED").buildingApp)
    assertFalse(state.copy(task = "needs_user").buildingApp)
    assertFalse(state.copy(task = "failed").buildingApp)
    assertFalse(state.copy(abandoned = true).buildingApp)
    assertFalse(state.copy(turn = state.turn!!.copy(active = false)).buildingApp)
    assertFalse(state.copy(turn = state.turn!!.copy(turnId = "next")).buildingApp)
    assertFalse(state.withAppBuild("thread", "turn", false).buildingApp)
  }
  @Test fun staleAndIdleWorkflowsCannotShowBuilding() {
    for ((thread, turn) in listOf("other" to "turn", "thread" to "old")) {
      try { running().withAppBuild(thread, turn, true); fail("stale turn accepted") }
      catch (e: AssistantException) { assertEquals("STALE_TURN", e.code) }
    }
    try { running().copy(task = "completed").withAppBuild("thread", "turn", true); fail("completed task accepted") }
    catch (e: AssistantException) { assertEquals("NOT_WORKING", e.code) }
  }
}
