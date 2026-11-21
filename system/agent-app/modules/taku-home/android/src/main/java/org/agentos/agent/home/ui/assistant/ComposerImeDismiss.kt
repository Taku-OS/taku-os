package org.agentos.agent.home.ui.assistant

/** Requested IME visibility alone does not prove this input ever displayed a keyboard. */
internal class ComposerImeDismiss {
  private var shown = false

  fun update(open: Boolean, visible: Boolean, bottom: Int, listening: Boolean): Boolean {
    if (!open) { shown = false; return false }
    if (visible && bottom > 0) shown = true
    if (!visible && bottom == 0) {
      val dismiss = shown && !listening
      shown = false
      return dismiss
    }
    return false
  }
}
