package org.agentos.agent.bridge

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.io.IOException
import java.util.UUID

/** The saved route belongs to this Activity instance; consumption outlives its saved state. */
object PanelLaunchIntents {
  const val EXTRA_LAUNCH_ID = "panelLaunchId"
  private const val SAVED_ROUTE = "org.agentos.agent.conversationRoute"
  private const val CONSUMED = "panel-launch-consumed"

  @Suppress("DEPRECATION")
  fun restore(activity: Activity, state: Bundle?) {
    state?.getParcelable<Intent>(SAVED_ROUTE)?.let { activity.intent = it }
    prepare(activity.intent)
    discardConsumed(activity, activity.intent)
  }

  fun save(activity: Activity, state: Bundle) {
    state.putParcelable(SAVED_ROUTE, Intent(activity.intent))
  }

  /** Only a new external navigation without an identity gets a new one. */
  fun prepare(intent: Intent) {
    if (!intent.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID).isNullOrBlank() &&
      intent.getStringExtra(EXTRA_LAUNCH_ID).isNullOrBlank()) {
      intent.putExtra(EXTRA_LAUNCH_ID, UUID.randomUUID().toString())
    }
  }

  fun consumed(context: Context, launchId: String): Boolean =
    context.getSharedPreferences(CONSUMED, Context.MODE_PRIVATE).getBoolean(launchId, false)

  fun consume(context: Context, launchId: String) {
    if (!context.getSharedPreferences(CONSUMED, Context.MODE_PRIVATE).edit().putBoolean(launchId, true).commit()) {
      throw IOException("Panel launch consumption could not be saved")
    }
  }

  fun discardConsumed(context: Context, intent: Intent) {
    val launchId = intent.getStringExtra(EXTRA_LAUNCH_ID) ?: return
    if (consumed(context, launchId)) clearPanel(intent)
  }

  fun clearPanel(intent: Intent) {
    intent.removeExtra(TakuAgentModule.EXTRA_PANEL_ID)
    intent.removeExtra(TakuAgentModule.EXTRA_SOURCE_VERSION)
    intent.removeExtra(TakuAgentModule.EXTRA_ORIGIN_THREAD_ID)
  }
}
