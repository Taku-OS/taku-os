package org.agentos.agent.bridge

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Which hosts of one shared surface are started. Android calls an Activity visible while it is
 * STARTED; a translucent Activity above it does not end that, while a full-screen one does. So
 * this is the signal that separates a surface the user can see from one still mounted behind
 * another activity. Identity matters: a replacement instance is a different host.
 */
internal class StartedHosts<T : Any>(private val onChange: (Boolean) -> Unit) {
  private val hosts = ArrayList<T>()

  @Volatile
  var visible = false
    private set

  @Synchronized fun start(host: T) {
    hosts.removeAll { it === host }
    hosts.add(host)
    project()
  }

  @Synchronized fun stop(host: T) {
    hosts.removeAll { it === host }
    project()
  }

  /** Seeds one already started host, for an observer that attaches after the host started. */
  @Synchronized fun seed(host: T) {
    if (hosts.none { it === host }) hosts.add(host)
    project()
  }

  private fun project() {
    val next = hosts.isNotEmpty()
    if (next == visible) return
    visible = next
    onChange(next)
  }
}

/**
 * Tracks the app activities that host the conversation, so the shared React runtime can tell a
 * visible chat from a chat still mounted behind the desktop. Attach and detach on the UI thread.
 */
internal class ConversationVisibility(
  private val hostsConversation: (Activity) -> Boolean,
  onChange: (Boolean) -> Unit,
) : Application.ActivityLifecycleCallbacks {
  private val hosts = StartedHosts<Activity>(onChange)

  val visible: Boolean get() = hosts.visible

  fun attach(application: Application, current: Activity?) {
    application.registerActivityLifecycleCallbacks(this)
    seed(current)
  }

  fun seed(current: Activity?) {
    if (current != null && hostsConversation(current)) hosts.seed(current)
  }

  fun detach(application: Application) {
    application.unregisterActivityLifecycleCallbacks(this)
  }

  override fun onActivityStarted(activity: Activity) {
    if (hostsConversation(activity)) hosts.start(activity)
  }

  override fun onActivityStopped(activity: Activity) {
    if (hostsConversation(activity)) hosts.stop(activity)
  }

  override fun onActivityDestroyed(activity: Activity) {
    if (hostsConversation(activity)) hosts.stop(activity)
  }

  override fun onActivityResumed(activity: Activity) {
    // A resumed activity is started too; this also covers a tracker that attached mid-lifecycle.
    if (hostsConversation(activity)) hosts.start(activity)
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
  override fun onActivityPaused(activity: Activity) {}
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
}
