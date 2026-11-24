package org.agentos.agent.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import java.util.UUID

object PanelLaunchIntents {
  const val EXTRA_LAUNCH_ID = "panelLaunchId"
  private const val SAVED_ROUTE = "org.agentos.agent.conversationRoute"

  @Suppress("DEPRECATION")
  fun restore(activity: Activity, state: Bundle?) {
    state?.getParcelable<Intent>(SAVED_ROUTE)?.let { activity.intent = it }
    prepare(activity.intent)
  }

  fun save(activity: Activity, state: Bundle) {
    state.putParcelable(SAVED_ROUTE, Intent(activity.intent))
  }

  /** Only a new external navigation without an identity gets a new one. */
  fun prepare(intent: Intent) {
    if (!intent.getStringExtra(TakuAgentModule.EXTRA_THREAD_ID).isNullOrBlank() &&
      !intent.getStringExtra(TakuAgentModule.EXTRA_PANEL_ID).isNullOrBlank() &&
      intent.getStringExtra(EXTRA_LAUNCH_ID).isNullOrBlank()) {
      intent.putExtra(EXTRA_LAUNCH_ID, UUID.randomUUID().toString())
    }
  }

  fun clearPanel(intent: Intent) {
    intent.removeExtra(TakuAgentModule.EXTRA_PANEL_ID)
    intent.removeExtra(EXTRA_LAUNCH_ID)
    intent.removeExtra(TakuAgentModule.EXTRA_ORIGIN_THREAD_ID)
  }
}
