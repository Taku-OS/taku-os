package org.agentos.agent.home.panels

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The desktop's actual navigation state, projected from the live pager: which Panels the
 * directory holds, which Panel roots are mounted, and which Panel the pager settled on.
 * It is a UI read (see [PanelNavigationProvider]) and never a second business directory.
 */
object PanelNavigation {
  /** Action that opens Home on one Panel; the only navigation route into a Panel page. */
  const val ACTION_OPEN_PANEL = "org.agentos.agent.action.OPEN_PANEL"
  const val EXTRA_PANEL_ID = "panelId"

  data class Snapshot(
    val available: List<String> = emptyList(),
    val visible: List<String> = emptyList(),
    val selectedPanelId: String? = null,
  ) {
    fun toJson(): JSONObject = JSONObject()
      .put("available", JSONArray(available))
      .put("visible", JSONArray(visible))
      .put("selectedPanelId", selectedPanelId ?: JSONObject.NULL)
  }

  private val current = MutableStateFlow(Snapshot())
  val state: StateFlow<Snapshot> = current.asStateFlow()
  private var projected = Snapshot()
  private var foreground = false

  fun setForeground(visible: Boolean) {
    foreground = visible
    publish(projected)
  }

  /** Publishes the state the pager is on now; an unchanged snapshot does not notify. */
  fun publish(snapshot: Snapshot) {
    projected = snapshot
    val visible = if (foreground) snapshot else snapshot.copy(visible = emptyList())
    if (current.value != visible) current.value = visible
  }
}
