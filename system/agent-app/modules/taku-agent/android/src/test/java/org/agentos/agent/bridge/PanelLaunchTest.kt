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
  private val targetA = PanelTarget("panel-a", 3, "thread-a", "launch-a")
  private val targetB = PanelTarget("panel-b", 0, "thread-b", "launch-b")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun launch(resolve: suspend (LaunchRoute.Path) -> LaunchAnswer): PanelLaunch {
    val consumed = mutableSetOf<String>()
    return PanelLaunch(scope, consumed::contains, { consumed.add(it) }, resolve)
  }

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

  @Test fun reopeningTheSamePanelUsesItsCurrentSourceVersion() = runBlocking {
    var version = 0L
    val panelLaunch = launch { LaunchAnswer.Accepted(PanelTarget("panel-a", version, "thread-a", "launch-a")) }
    panelLaunch.observe(threadA)
    assertEquals(0L, (panelLaunch.answer(1_000) as LaunchAnswer.Accepted).target.sourceVersion)
    version = 1L
    panelLaunch.reopen(threadA)
    assertEquals(1L, (panelLaunch.answer(1_000) as LaunchAnswer.Accepted).target.sourceVersion)
    scope.cancel()
  }

  @Test fun aNewValidatedTargetSupersedesTheCachedVersionForTheSamePath() = runBlocking {
    val panelLaunch = launch { LaunchAnswer.Rejected("UNEXPECTED_READ") }
    val previous = panelLaunch.seed(threadA, LaunchAnswer.Accepted(PanelTarget("panel-a", 0, "thread-a", "launch-a")))
    panelLaunch.seed(threadA, LaunchAnswer.Accepted(PanelTarget("panel-a", 1, "thread-a", "launch-a")))
    assertFalse(panelLaunch.current(previous))
    assertEquals(1L, (panelLaunch.answer(1_000) as LaunchAnswer.Accepted).target.sourceVersion)
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

  @Test fun acceptedConsumptionSurvivesANewControllerAndAnOldRestoredIntent() = runBlocking {
    val consumed = mutableSetOf<String>()
    fun restored() = PanelLaunch(scope, consumed::contains, { consumed.add(it) }) {
      LaunchAnswer.Accepted(targetA)
    }
    val first = restored()
    val original = first.observe(threadA)
    assertEquals(LaunchAnswer.Accepted(targetA), first.answer(1_000))
    first.consume("launch-a")
    assertFalse(first.current(original))
    assertEquals(LaunchAnswer.NoPanel, first.answer(1_000))
    val afterProcessDeath = restored()
    afterProcessDeath.observe(threadA)
    assertEquals(LaunchAnswer.NoPanel, afterProcessDeath.answer(1_000))
    afterProcessDeath.reopen(threadA)
    assertEquals(LaunchAnswer.NoPanel, afterProcessDeath.answer(1_000))
    assertEquals("thread-a", afterProcessDeath.path()?.threadId)
    scope.cancel()
  }

  @Test fun anExplicitSamePanelReopenGetsAFreshContinuationAfterConsumption() = runBlocking {
    val consumed = mutableSetOf<String>()
    val panelLaunch = PanelLaunch(scope, consumed::contains, { consumed.add(it) }) { path ->
      LaunchAnswer.Accepted(targetA.copy(launchId = path.launchId!!))
    }
    panelLaunch.observe(threadA)
    panelLaunch.consume("launch-a")
    panelLaunch.reopen(threadA.copy(launchId = "launch-a-again"))
    assertEquals(LaunchAnswer.Accepted(targetA.copy(launchId = "launch-a-again")), panelLaunch.answer(1_000))
    scope.cancel()
  }

  @Test fun anOldReceiptCannotConsumeANewerLaunchEvenForTheSamePanelAndVersion() = runBlocking {
    val consumed = mutableSetOf<String>()
    val panelLaunch = PanelLaunch(scope, consumed::contains, { consumed.add(it) }) { path ->
      LaunchAnswer.Accepted(targetA.copy(launchId = path.launchId!!))
    }
    panelLaunch.observe(threadA)
    panelLaunch.reopen(threadA.copy(launchId = "launch-new"))
    panelLaunch.consume("launch-a")
    assertEquals(LaunchAnswer.Accepted(targetA.copy(launchId = "launch-new")), panelLaunch.answer(1_000))
    assertEquals(setOf("launch-a"), consumed)
    scope.cancel()
  }

  @Test fun consumptionStorageFailureKeepsTheContinuationAvailableForReceiptReconciliation() = runBlocking {
    val panelLaunch = PanelLaunch(scope, consumed = { false }, persistConsumption = { throw IllegalStateException("disk full") }) {
      LaunchAnswer.Accepted(targetA)
    }
    panelLaunch.observe(threadA)
    try {
      panelLaunch.consume("launch-a")
      org.junit.Assert.fail("Consumption must not succeed without its durable marker")
    } catch (_: IllegalStateException) {
      assertEquals(LaunchAnswer.Accepted(targetA), panelLaunch.answer(1_000))
    }
    scope.cancel()
  }

  @Test fun anAnswerAlreadyInFlightCannotRestoreAConsumedLaunch() = runBlocking {
    val response = CompletableDeferred<LaunchAnswer>()
    val consumed = mutableSetOf<String>()
    val panelLaunch = PanelLaunch(scope, consumed::contains, { consumed.add(it) }) { response.await() }
    val original = panelLaunch.observe(threadA)
    panelLaunch.consume("launch-a")
    response.complete(LaunchAnswer.Accepted(targetA))
    assertFalse(panelLaunch.current(original))
    assertEquals(LaunchAnswer.NoPanel, panelLaunch.answer(1_000))
    scope.cancel()
  }
}
