package org.agentos.agent.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PanelLaunchTest {
  private val threadA = LaunchRoute.Path("thread-a", "panel-a", "thread-a", "launch-a")
  private val threadB = LaunchRoute.Path("thread-b", "panel-b", "thread-b", "launch-b")
  private val targetA = PanelTarget("panel-a", "thread-a", "launch-a", "Trip plan")
  private val targetB = PanelTarget("panel-b", "thread-b", "launch-b", "Packing list")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun launch(resolve: suspend (LaunchRoute.Path) -> LaunchAnswer): PanelLaunch = PanelLaunch(scope, resolve)

  private fun launch(answers: Map<String, CompletableDeferred<LaunchAnswer>>) =
    launch { path -> answers.getValue(path.panelId!!).await() }

  @Test fun aRouteWithNoPanelAnswersWithoutARead() = runBlocking {
    var reads = 0
    val panelLaunch = launch { reads++; LaunchAnswer.NoPanel }
    panelLaunch.observe(LaunchRoute.Path("thread-c", null, null))
    assertEquals(LaunchAnswer.NoPanel, panelLaunch.answer(1_000))
    assertEquals(0, reads)
    scope.cancel()
  }

  @Test fun anAwaitForASupersededRouteAnswersTheRouteThatReplacedIt() = runBlocking {
    val gates = mapOf("panel-a" to CompletableDeferred<LaunchAnswer>(), "panel-b" to CompletableDeferred<LaunchAnswer>())
    val panelLaunch = launch(gates)
    panelLaunch.observe(threadA)
    val waiting = async { panelLaunch.answer(2_000) }
    delay(20)
    panelLaunch.observe(threadB)
    gates.getValue("panel-b").complete(LaunchAnswer.Accepted(targetB))
    assertEquals(LaunchAnswer.Accepted(targetB), waiting.await())
    gates.getValue("panel-a").complete(LaunchAnswer.Accepted(targetA))
    delay(20)
    assertEquals(threadB, panelLaunch.path())
    assertEquals(LaunchAnswer.Accepted(targetB), panelLaunch.answer(1_000))
    scope.cancel()
  }

  @Test fun aRefusedRouteKeepsItsNativeCode() = runBlocking {
    val refused = CompletableDeferred<LaunchAnswer>()
    val panelLaunch = launch(mapOf("panel-a" to refused))
    panelLaunch.observe(threadA)
    refused.complete(LaunchAnswer.Rejected("SOURCE_THREAD_NOT_FOUND"))
    assertEquals(LaunchAnswer.Rejected("SOURCE_THREAD_NOT_FOUND"), panelLaunch.answer(1_000))
    scope.cancel()
  }

  @Test fun aRouteThatNeverAnswersRejectsInsteadOfPassingForOrdinaryChat() = runBlocking {
    val silent = CompletableDeferred<LaunchAnswer>()
    val panelLaunch = launch(mapOf("panel-a" to silent))
    panelLaunch.observe(threadA)
    val answer = panelLaunch.answer(50)
    assertFalse(answer == LaunchAnswer.NoPanel)
    assertEquals(LaunchAnswer.Rejected(PanelLaunch.SOURCE_UNAVAILABLE), answer)
    scope.cancel()
  }

  @Test fun aSeededRouteAnswersWithoutReadingThePanel() = runBlocking {
    var reads = 0
    val panelLaunch = launch { reads++; LaunchAnswer.Rejected("UNEXPECTED_READ") }
    panelLaunch.seed(threadA, LaunchAnswer.Accepted(targetA))
    assertEquals(LaunchAnswer.Accepted(targetA), panelLaunch.answer(1_000))
    assertEquals(0, reads)
    scope.cancel()
  }

  @Test fun anUnobservedRouteIsAnOrdinaryConversation() = runBlocking {
    val panelLaunch = launch { LaunchAnswer.Rejected("UNEXPECTED_READ") }
    assertNull(panelLaunch.path())
    assertFalse(panelLaunch.answer(1_000) is LaunchAnswer.Rejected)
    scope.cancel()
  }

  @Test fun reopeningTheSamePanelUsesItsCurrentTitle() = runBlocking {
    var title = "Trip plan"
    val panelLaunch = launch { LaunchAnswer.Accepted(targetA.copy(title = title)) }
    panelLaunch.observe(threadA)
    assertEquals("Trip plan", (panelLaunch.answer(1_000) as LaunchAnswer.Accepted).target.title)
    title = "Renamed trip plan"
    panelLaunch.reopen(threadA)
    assertEquals("Renamed trip plan", (panelLaunch.answer(1_000) as LaunchAnswer.Accepted).target.title)
    scope.cancel()
  }

  @Test fun aNewValidatedTargetSupersedesTheCachedTitleForTheSamePath() = runBlocking {
    val panelLaunch = launch { LaunchAnswer.Rejected("UNEXPECTED_READ") }
    val previous = panelLaunch.seed(threadA, LaunchAnswer.Accepted(targetA))
    val renamed = targetA.copy(title = "Renamed trip plan")
    panelLaunch.seed(threadA, LaunchAnswer.Accepted(renamed))
    assertFalse(panelLaunch.current(previous))
    assertEquals(LaunchAnswer.Accepted(renamed), panelLaunch.answer(1_000))
    scope.cancel()
  }

  @Test fun clearingTheRouteWakesAnUnresolvedLaunchWithoutRestoringIt() = runBlocking {
    val late = CompletableDeferred<LaunchAnswer>()
    val panelLaunch = launch { late.await() }
    val previous = panelLaunch.observe(threadA)
    val waiting = async { panelLaunch.answer(1_000) }
    panelLaunch.clear()
    assertEquals(LaunchAnswer.NoPanel, waiting.await())
    late.complete(LaunchAnswer.Accepted(targetA))
    assertNull(panelLaunch.path())
    assertFalse(panelLaunch.current(previous))
    scope.cancel()
  }

  @Test fun reopeningAfterATransientRejectionCanAcceptTheRecoveredSource() = runBlocking {
    var available = false
    val panelLaunch = launch {
      if (available) LaunchAnswer.Accepted(targetA) else LaunchAnswer.Rejected(PanelLaunch.SOURCE_UNAVAILABLE)
    }
    panelLaunch.observe(threadA)
    assertEquals(LaunchAnswer.Rejected(PanelLaunch.SOURCE_UNAVAILABLE), panelLaunch.answer(1_000))
    available = true
    panelLaunch.reopen(threadA)
    assertEquals(LaunchAnswer.Accepted(targetA), panelLaunch.answer(1_000))
    scope.cancel()
  }

  @Test fun theSameRestoredIntentStillResolvesThePanelInANewModule() = runBlocking {
    val first = launch { LaunchAnswer.Accepted(targetA) }
    first.observe(threadA)
    assertEquals(LaunchAnswer.Accepted(targetA), first.answer(1_000))
    val restored = launch { LaunchAnswer.Accepted(targetA) }
    restored.observe(threadA)
    assertEquals(LaunchAnswer.Accepted(targetA), restored.answer(1_000))
    scope.cancel()
  }

  @Test fun ordinaryNavigationToTheSameThreadSupersedesAnInFlightPanelRead() = runBlocking {
    val response = CompletableDeferred<LaunchAnswer>()
    val panelLaunch = launch { response.await() }
    val previous = panelLaunch.observe(threadA)
    val ordinary = LaunchRoute.Path(threadA.threadId, null, null)
    panelLaunch.reopen(ordinary)
    response.complete(LaunchAnswer.Accepted(targetA))
    assertFalse(panelLaunch.current(previous))
    assertEquals(ordinary, panelLaunch.path())
    assertEquals(LaunchAnswer.NoPanel, panelLaunch.answer(1_000))
    scope.cancel()
  }
}
