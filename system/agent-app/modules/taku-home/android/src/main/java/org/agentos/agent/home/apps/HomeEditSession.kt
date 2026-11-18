package org.agentos.agent.home.apps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Transient editing mode shared by the launcher GUI and its ContentProvider. */
object HomeEditSession {
  private val mutable = MutableStateFlow(false)
  val state = mutable.asStateFlow()
  fun set(enabled: Boolean): JSONObject {
    mutable.value = enabled
    return JSONObject().put("editing", enabled)
  }
  fun read(): JSONObject = JSONObject().put("editing", state.value)
}
