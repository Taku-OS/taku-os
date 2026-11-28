package org.agentos.agent.home.island

import org.json.JSONArray
import org.json.JSONObject

/**
 * The island capsule that hosts Live Talk while its chat is off screen. It is a separate
 * notification ([TAG]) from the assistant card, so a text task and a live conversation never
 * replace each other. Pure JSON so host tests check it against SystemUI's `IslandPayload`.
 */
internal object LiveIslandCard {
  const val TAG = "taku-island:live"
  const val OPEN = "live_open"
  const val MUTE = "live_mute"
  const val STOP = "live_stop"

  data class View(
    val lifecycle: String,
    val muted: Boolean,
    val speech: Boolean,
    val playback: String,
    val input: String?,
    val output: String?,
    val task: String?,
    val standby: String = "active",
    /** Shared video: `none`, `screen` or `camera`; [videoMasked] means frames are withheld. */
    val videoSource: String = "none",
    val videoActive: Boolean = false,
    val videoMasked: Boolean = false,
  ) {
    /** The screen or camera is being sent right now; the capsule must always say so (D1). */
    val sharing get() = videoActive && !videoMasked && videoSource != "none"
  }

  /** Short capsule text (SystemUI allows 1–16 characters). */
  fun status(view: View): String = when {
    view.lifecycle == "connecting" -> "Connecting"
    view.lifecycle == "disconnected" || view.lifecycle == "failed" -> "Disconnected"
    view.sharing -> if (view.videoSource == "camera") "Camera" else "Sharing"
    view.muted -> "Muted"
    view.standby != "active" -> "Standby"
    view.playback == "speaking" -> "Speaking"
    view.speech -> "Listening"
    else -> "Live"
  }

  fun title(view: View): String = when {
    view.lifecycle == "disconnected" || view.lifecycle == "failed" -> "Taku Live disconnected"
    view.sharing -> if (view.videoSource == "camera") "Taku Live · camera on" else "Taku Live · sharing screen"
    view.muted -> "Taku Live · microphone paused"
    else -> "Taku Live"
  }

  /** The latest spoken caption, else the delegated task state, else a hint. */
  fun body(view: View): String = when {
    view.lifecycle == "disconnected" || view.lifecycle == "failed" -> "Open the conversation to reconnect."
    view.sharing && view.input.isNullOrBlank() && view.output.isNullOrBlank() ->
      if (view.videoSource == "camera") "Taku sees your camera. Open the conversation to stop." else "Taku sees your screen. Open the conversation to stop."
    !view.output.isNullOrBlank() && view.playback == "speaking" -> view.output
    !view.input.isNullOrBlank() -> view.input
    !view.output.isNullOrBlank() -> view.output
    view.task != null -> "Task ${view.task}"
    view.muted -> "Resume the microphone to keep talking."
    view.standby == "cold_standby" -> "Standing by. Speak to continue."
    else -> "Keep talking while you use other apps."
  }

  /** Action ids in display order; resume is offered only when the session can still talk. */
  fun actions(view: View): List<String> =
    if (view.lifecycle == "active") listOf(OPEN, MUTE, STOP) else listOf(OPEN, STOP)

  fun muteLabel(view: View) = if (view.muted) "Resume mic" else "Pause mic"

  fun payload(view: View): JSONObject {
    val listening = view.lifecycle == "active" && !view.muted
    val body = bounded(body(view), 600)
    return JSONObject().put("version", 2).put("timeoutS", 28800)
      .put("compact", JSONObject()
        .put("a", if (listening) JSONObject().put("type", "animation").put("name", "ai-listening")
          else JSONObject().put("type", "icon").put("pic", "taku"))
        .put("b", JSONObject().put("type", "text").put("text", status(view))))
      .put("expanded", JSONObject().put("template", "basic").put("title", title(view))
        .put("body", body).put("actions", JSONArray(actions(view)))
        .put("primaryAction", OPEN).put("tapAction", OPEN))
  }

  private fun bounded(value: String, count: Int) =
    value.substring(0, value.offsetByCodePoints(0, minOf(count, value.codePointCount(0, value.length))))
}
