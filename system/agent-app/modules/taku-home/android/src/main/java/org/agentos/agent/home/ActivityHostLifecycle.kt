package org.agentos.agent.home

/**
 * Serializes a shared host's single current activity while Android may have several resumed
 * activities. Identity matters: a replacement instance of the same Activity is a different owner.
 * Call on the UI thread. A late pause/destroy may retire its own activity, never the new owner.
 */
internal class ActivityHostLifecycle<A : Any>(
  private val resumeHost: (A) -> Unit,
  private val pauseHost: (A) -> Unit,
  private val destroyHost: (A) -> Unit,
) {
  private val resumed = ArrayList<A>()
  private var current: A? = null

  fun resume(activity: A) {
    resumed.removeAll { it === activity }
    resumed.add(activity)
    val previous = current
    if (previous != null && previous !== activity) pauseHost(previous)
    current = activity
    resumeHost(activity)
  }

  fun pause(activity: A) {
    resumed.removeAll { it === activity }
    if (current !== activity) return
    pauseHost(activity)
    current = resumed.lastOrNull()
    current?.let(resumeHost)
  }

  fun destroy(activity: A) {
    pause(activity)
    // Destroying an old page must not tear down another page's resumed React context.
    if (current == null) destroyHost(activity)
  }

  fun clear() {
    resumed.clear()
    current = null
  }
}
