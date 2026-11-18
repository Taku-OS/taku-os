package org.agentos.agent.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What the Panel record answered for one conversation route. */
internal sealed interface LaunchAnswer {
  /** The route names no Panel: an ordinary conversation. */
  data object NoPanel : LaunchAnswer

  /** The record accepts the route as a continuation target. */
  data class Accepted(val target: PanelTarget) : LaunchAnswer

  /** The record refused the route, or never answered; [code] is the native reason. */
  data class Rejected(val code: String) : LaunchAnswer

  /** Never published: it wakes a waiter whose route a newer intent replaced. */
  data object Superseded : LaunchAnswer
}

/**
 * The Panel half of a conversation route.
 *
 * One `panels/get` runs per route. A route superseded by a newer intent answers nobody: its late
 * answer can neither become the target nor move the chat back. A caller waiting on a route that
 * gets superseded follows the route that replaced it, because that is the conversation actually
 * open, and is never told "no Panel" for a route it did not ask about.
 */
internal class PanelLaunch(
  private val scope: CoroutineScope,
  private val consumed: (String) -> Boolean,
  private val persistConsumption: (String) -> Unit,
  private val resolve: suspend (LaunchRoute.Path) -> LaunchAnswer,
) {
  class Resolution internal constructor(val path: LaunchRoute.Path, val generation: Long) {
    internal val answer = CompletableDeferred<LaunchAnswer>()
    internal var started = false
  }

  private val route = LaunchRoute()
  private val lock = Any()
  private var resolution: Resolution? = null

  fun path(): LaunchRoute.Path? = route.path()

  /** True while [pending] is still the route whose answer may be published. */
  fun current(pending: Resolution): Boolean = route.current(pending.generation)

  fun current(generation: Long): Boolean = route.current(generation)

  fun clear() = synchronized(lock) {
    route.clear()
    resolution?.answer?.complete(LaunchAnswer.Superseded)
    resolution = null
  }

  /** Persist before releasing the pending receipt, including when a newer route is already open. */
  fun consume(launchId: String) = synchronized(lock) {
    persistConsumption(launchId)
    val path = route.path()
    if (path?.launchId == launchId && path.panelId != null) install(path.withoutPanel(), LaunchAnswer.NoPanel)
  }

  /** Records [path] and resolves it once; a repeated path keeps the answer it already has. */
  fun observe(path: LaunchRoute.Path): Resolution = install(path, null)

  fun reopen(path: LaunchRoute.Path): Resolution = install(path, null, refresh = true)

  /** Records [path] with an answer already read, for a conversation opened from inside the app. */
  fun seed(path: LaunchRoute.Path, answer: LaunchAnswer): Resolution = install(path, answer, refresh = true)

  private fun install(requested: LaunchRoute.Path, answer: LaunchAnswer?, refresh: Boolean = false): Resolution {
    val pending = synchronized(lock) {
      val path = if (requested.launchId?.let(consumed) == true) requested.withoutPanel() else requested
      val observation = route.observe(path, refresh)
      val known = resolution
      if (known != null && known.generation >= observation.generation) return@synchronized known
      // Wake anyone waiting on the replaced route so it can follow the one that took over.
      known?.let { if (!it.answer.isCompleted) it.answer.complete(LaunchAnswer.Superseded) }
      Resolution(path, observation.generation).also { resolution = it }
    }
    if (answer != null && pending.path == requested) pending.answer.complete(answer) else start(pending)
    return pending
  }

  /**
   * The answer for whichever route is current, waiting as long as [timeoutMs]. A route that
   * changes while the read is in flight makes this follow the newer one; a route that never
   * answers rejects rather than passing for an ordinary conversation.
   */
  suspend fun answer(timeoutMs: Long): LaunchAnswer {
    val settled = withTimeoutOrNull(timeoutMs) {
      while (true) {
        val pending = synchronized(lock) { resolution } ?: return@withTimeoutOrNull LaunchAnswer.NoPanel
        val answer = pending.answer.await()
        // Superseded, or superseded as the answer landed: ask the route that replaced it.
        if (answer != LaunchAnswer.Superseded && current(pending)) return@withTimeoutOrNull answer
      }
      @Suppress("UNREACHABLE_CODE")
      LaunchAnswer.NoPanel
    }
    return settled ?: LaunchAnswer.Rejected(SOURCE_UNAVAILABLE)
  }

  private fun start(pending: Resolution) {
    if (pending.path.panelId == null) {
      pending.answer.complete(LaunchAnswer.NoPanel)
      return
    }
    synchronized(lock) {
      if (pending.started || pending.answer.isCompleted) return
      pending.started = true
    }
    scope.launch {
      // A route replaced before the read starts is not worth reading.
      if (!current(pending)) {
        pending.answer.complete(LaunchAnswer.Superseded)
        return@launch
      }
      val answer = try {
        resolve(pending.path)
      } catch (error: Exception) {
        LaunchAnswer.Rejected(SOURCE_UNAVAILABLE)
      }
      if (current(pending)) pending.answer.complete(answer)
      else pending.answer.complete(LaunchAnswer.Superseded)
    }
  }

  companion object {
    /** The route could not be read in time: no evidence either way, so never a plain conversation. */
    const val SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE"
  }
}
