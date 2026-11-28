package org.agentos.agent.home

import com.android.systemui.taku.island.IslandPayload
import org.agentos.agent.home.island.LiveIslandCard
import org.json.JSONObject

private fun check(condition: Boolean, label: String) { if (!condition) throw AssertionError(label) }

/** The Live capsule must pass SystemUI's own island payload validator in every Live Talk state. */
fun main() {
  val taku = IslandPayload.Publisher("org.agentos.agent", true)
  val base = LiveIslandCard.View("active", muted = false, speech = false, playback = "stopped", input = null, output = null, task = null)
  val states = listOf(
    base,
    base.copy(speech = true, input = "What's the weather in Taipei?"),
    base.copy(playback = "speaking", output = "It is sunny, 24 degrees. ".repeat(80)),
    base.copy(muted = true),
    base.copy(task = "running"),
    base.copy(lifecycle = "connecting"),
    base.copy(lifecycle = "disconnected"),
    base.copy(input = "台北今天天氣如何？🌤".repeat(100)),
  )
  for (view in states) {
    val payload = LiveIslandCard.payload(view)
    val parsed = IslandPayload.parse(payload.toString(), taku)
    check(parsed.isValid, "valid payload for $view: ${parsed.error}")
    val status = payload.getJSONObject("compact").getJSONObject("b").getString("text")
    check(status.length in 1..16, "capsule text bound")
    val expanded = payload.getJSONObject("expanded")
    check(expanded.getString("template") == "basic", "Live never claims the reserved assistant card")
    check(expanded.getString("primaryAction") == LiveIslandCard.OPEN, "tap opens the conversation")
    val actions = expanded.getJSONArray("actions")
    check((0 until actions.length()).map(actions::getString) == LiveIslandCard.actions(view), "actions match")
  }
  check(LiveIslandCard.actions(base).contains(LiveIslandCard.MUTE), "an active session can pause the mic")
  check(!LiveIslandCard.actions(base.copy(lifecycle = "disconnected")).contains(LiveIslandCard.MUTE), "no mic toggle without a connection")
  check(LiveIslandCard.status(base.copy(muted = true)) == "Muted" && LiveIslandCard.muteLabel(base.copy(muted = true)) == "Resume mic", "muted state")
  val listening = LiveIslandCard.payload(base).getJSONObject("compact").getJSONObject("a")
  check(listening.getString("type") == "animation" && listening.getString("name") == "ai-listening", "listening animation")
  check(LiveIslandCard.payload(base.copy(muted = true)).getJSONObject("compact").getJSONObject("a").getString("type") == "icon", "muted shows the Taku icon")
  check(!IslandPayload.parse(LiveIslandCard.payload(base).toString(), IslandPayload.Publisher("com.example", false)).isValid,
    "AI animations stay reserved for Taku Agent")
  check(LiveIslandCard.TAG != "taku-island" && LiveIslandCard.TAG.startsWith("taku-island"), "separate notification tag")
  JSONObject() // org.json is on the test classpath
  println("PASS LiveIslandCardTest: ${states.size} Live states pass IslandPayload, separate tag, actions and capsule text bounds")
}
