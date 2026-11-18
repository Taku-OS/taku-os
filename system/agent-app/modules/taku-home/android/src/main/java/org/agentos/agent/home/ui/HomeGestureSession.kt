package org.agentos.agent.home.ui

import org.json.JSONObject

/** Main-thread, live diagnostic projection. No persisted coordinates or separate control state. */
internal object HomeGestureSession {
  var read: (() -> JSONObject)? = null
  fun snapshot(): JSONObject = read?.invoke() ?: JSONObject().put("available", false)
}
