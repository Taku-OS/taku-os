package org.agentos.agent.bridge

/**
 * Which conversation route is current, and whether a resolution started for it still applies.
 *
 * A conversation intent names a thread and, for a Panel, the Panel that route was opened for. The
 * Panel target is read through the Assistant's public `panels/get`, so it resolves asynchronously
 * while a second intent can already supersede the first. Every new path starts a generation, and
 * only the generation that is still current may publish its target or deliver an event, so a late
 * resolution can never reopen the conversation the user left.
 */
internal class LaunchRoute {
  data class Path(val threadId: String, val panelId: String?, val originThreadId: String?, val launchId: String? = null) {
    fun withoutPanel(): Path = copy(panelId = null, originThreadId = null)
  }

  /** The generation [path] was observed with, and whether its one resolution still has to run. */
  data class Observation(val generation: Long, val fresh: Boolean)

  private var path: Path? = null
  private var generation = 0L

  /** Records [next]; a repeated path keeps its generation, so its resolution is not redone. */
  @Synchronized fun observe(next: Path, refresh: Boolean = false): Observation {
    if (!refresh && next == path) return Observation(generation, fresh = false)
    path = next
    generation++
    return Observation(generation, fresh = true)
  }

  /** True while [ofGeneration] is the route whose target and event may still be published. */
  @Synchronized fun current(ofGeneration: Long): Boolean = path != null && ofGeneration == generation

  @Synchronized fun clear() {
    path = null
    generation++
  }

  @Synchronized fun path(): Path? = path
}
