package org.agentos.agent.home.island

import android.content.Context

internal const val ISLAND_SESSION_PREFERENCES = "system_island_session"
internal const val ISLAND_TIMEOUT_SECONDS = 28_800

internal data class PersistedPanelIsland(
  val threadId: String,
  val panelId: String,
  val activityId: String,
  val createdAt: Long,
) {
  companion object {
    fun read(context: Context): PersistedPanelIsland? {
      val prefs = context.getSharedPreferences(ISLAND_SESSION_PREFERENCES, Context.MODE_PRIVATE)
      val createdAt = prefs.getLong("created", 0)
      if (System.currentTimeMillis() - createdAt !in 0L..ISLAND_TIMEOUT_SECONDS * 1_000L) return null
      val threadId = prefs.getString("thread", null)?.takeIf { it.isNotBlank() && it != "entry" } ?: return null
      val panelId = prefs.getString("panelId", null)?.takeIf { it.isNotBlank() } ?: return null
      val activityId = prefs.getString("activity", null)?.takeIf { it.isNotBlank() } ?: return null
      return PersistedPanelIsland(threadId, panelId, activityId, createdAt)
    }

    fun forget(context: Context) {
      val prefs = context.getSharedPreferences(ISLAND_SESSION_PREFERENCES, Context.MODE_PRIVATE)
      // A process death immediately after dismissal must not make the old route recoverable.
      if (prefs.contains("panelId")) prefs.edit().clear().commit()
    }
  }
}
