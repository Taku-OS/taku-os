package org.agentos.agent.home.assistant

import org.agentos.agent.home.menuscan.MenuVoiceHandoff
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kotlin port of the Agent UI session-stream reducer (`src/model/sessionStream.ts`,
 * contract in `system/assistant/SESSION_STREAMING.md`): applies `taku.session-events/1`
 * lines to one thread. Single-threaded; owned by [AssistantSession]'s worker.
 */
class SessionStreamState(val threadId: String) {
  class Turn(val id: String, var data: JSONObject) {
    val items = LinkedHashMap<String, JSONObject>()
  }

  var epoch = ""; private set
  var seq = -1L; private set
  var needsResync = false; private set
  var thread = JSONObject(); private set
  val turns = LinkedHashMap<String, Turn>()

  /** Cursor for reconnecting, or null when a fresh snapshot is required. */
  fun cursor(): Pair<String, Long>? = if (epoch.isNotEmpty() && !needsResync) epoch to seq else null

  fun forceResync() { needsResync = true }

  /** Applies one event; returns true when the visible thread changed. */
  fun apply(event: JSONObject): Boolean {
    if (event.optString("schema") != SCHEMA || event.optString("threadId") != threadId) return false
    val method = event.optString("method")
    val params = event.optJSONObject("params") ?: JSONObject()
    val eventEpoch = event.optString("brokerEpoch")
    val eventSeq = if (event.has("seq")) event.optLong("seq", -1) else -1
    if (eventEpoch.isEmpty() || eventSeq < 0) { needsResync = true; return false }
    when (method) {
      "session/heartbeat" -> return false
      "session/resync" -> { needsResync = true; return false }
      "session/snapshot" -> {
        val t = params.optJSONObject("thread") ?: JSONObject()
        if (t.optString("id") != threadId) { needsResync = true; return false }
        if (eventEpoch == epoch && eventSeq < seq && !needsResync) return false
        turns.clear()
        val list = t.optJSONArray("turns") ?: JSONArray()
        for (i in 0 until list.length()) {
          val raw = list.optJSONObject(i) ?: continue
          val id = raw.optString("id").ifEmpty { "turn-$i" }
          val turn = Turn(id, raw)
          val items = raw.optJSONArray("items") ?: JSONArray()
          for (j in 0 until items.length()) items.optJSONObject(j)?.let { item -> item.optString("id").takeIf { it.isNotEmpty() }?.let { turn.items[it] = item } }
          turns[id] = turn
        }
        thread = t; epoch = eventEpoch; seq = eventSeq; needsResync = false
        return true
      }
    }
    if (needsResync) return false
    if (method == "session/ready") {
      if (epoch != eventEpoch || seq != eventSeq) needsResync = true
      return false
    }
    if (epoch != eventEpoch) { needsResync = true; return false }
    if (eventSeq <= seq) return false
    if (eventSeq != seq + 1) { needsResync = true; return false }
    seq = eventSeq
    when (method) {
      "thread/name/updated" -> { thread.put("name", params.opt("threadName") ?: params.opt("name")); return true }
      "thread/status/changed" -> { thread.put("status", params.opt("status")); return true }
    }
    val sourceTurn = params.optJSONObject("turn") ?: JSONObject()
    val turnId = params.optString("turnId").ifEmpty { sourceTurn.optString("id") }
    if (turnId.isEmpty()) return false
    val turn = turns.getOrPut(turnId) { Turn(turnId, JSONObject().put("id", turnId).put("status", "inProgress")) }
    val source = params.optJSONObject("item") ?: JSONObject()
    val itemId = source.optString("id").ifEmpty { params.optString("itemId") }
    when {
      method == "item/agentMessage/delta" && itemId.isNotEmpty() -> {
        val prior = turn.items[itemId]
        if (prior?.optBoolean("_streamCompleted") == true) return false
        val next = prior?.let { JSONObject(it.toString()) } ?: JSONObject()
        next.put("id", itemId).put("type", "agentMessage").put("text", (prior?.optString("text") ?: "") + params.optString("delta")).put("_streamCompleted", false)
        turn.items[itemId] = next
      }
      (method == "item/started" || method == "item/completed") && itemId.isNotEmpty() -> {
        val completed = method == "item/completed"
        val prior = turn.items[itemId]
        if (!completed && prior?.optBoolean("_streamCompleted") == true) return false
        val next = merge(prior, source).put("id", itemId)
        if (!completed && prior?.has("text") == true) next.put("text", prior.optString("text"))
        next.put("_streamCompleted", completed)
        turn.items[itemId] = next
      }
      method == "turn/started" || method == "turn/completed" -> {
        val completed = method == "turn/completed"
        turn.data = merge(turn.data, sourceTurn).put("status", sourceTurn.optString("status").ifEmpty { if (completed) "completed" else "inProgress" })
        val items = sourceTurn.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
          val item = items.optJSONObject(i) ?: continue
          val id = item.optString("id")
          if (id.isEmpty()) continue
          val prior = turn.items[id]
          val next = merge(prior, item)
          if (!completed && prior?.has("text") == true) next.put("text", prior.optString("text"))
          next.put("_streamCompleted", completed || prior?.optBoolean("_streamCompleted") == true)
          turn.items[id] = next
        }
        if (completed) turn.items.values.forEach { it.put("_streamCompleted", true) }
      }
      else -> return false
    }
    return true
  }

  private fun merge(a: JSONObject?, b: JSONObject): JSONObject {
    val out = a?.let { JSONObject(it.toString()) } ?: JSONObject()
    b.keys().forEach { k -> out.put(k, b.get(k)) }
    return out
  }

  companion object { const val SCHEMA = "taku.session-events/1" }
}

enum class StepKind { Search, Browse, Act }

/** One visible activity of the agent (1-4: "Searching tickets…", "Reading expedia.com"). */
data class Step(val id: String, val kind: StepKind, val text: String, val running: Boolean)

/** A reply the agent offered for the user to send back (from its chat presentation). */
data class Suggestion(val id: String, val label: String, val reply: String)

/** What the Dynamic Island shows for one turn. Nothing here is invented by the client. */
data class TurnView(
  val turnId: String,
  val userText: String?,
  val active: Boolean,
  val failed: Boolean,
  /** Latest agent text: progress commentary while working, the answer once done. */
  val headline: String?,
  /** The final answer, once known. */
  val reply: String?,
  val steps: List<Step>,
  val suggestions: List<Suggestion>,
)

object ThreadProjection {
  private val toolTypes = setOf("commandExecution", "fileChange", "mcpToolCall", "dynamicToolCall", "webSearch", "collabAgentToolCall", "collabToolCall")
  private val terminal = setOf("completed", "failed", "errored", "cancelled", "canceled", "interrupted")

  /** The newest turn not present in [baseline] (turns that existed before this home session). */
  fun latest(state: SessionStreamState, baseline: Set<String>): TurnView? {
    val turn = state.turns.values.lastOrNull { it.id !in baseline } ?: return null
    return view(turn, state.thread)
  }

  fun view(turn: SessionStreamState.Turn, thread: JSONObject): TurnView {
    val status = turn.data.optString("status")
    val active = status !in terminal
    val items = turn.items.values.toList()
    val lastTool = items.indexOfLast { it.optString("type") in toolTypes }
    var userText: String? = null
    var commentary: String? = null
    var answer: String? = null
    val steps = ArrayList<Step>()
    items.forEachIndexed { i, item ->
      when (val type = item.optString("type")) {
        "userMessage" -> userText = userMessageText(item) ?: userText
        "agentMessage" -> {
          val text = item.optString("text").trim()
          if (text.isEmpty()) return@forEachIndexed
          val phase = item.optString("phase")
          if (phase == "commentary" || (phase.isEmpty() && i < lastTool)) commentary = text else answer = text
        }
        in toolTypes -> step(item, type)?.let(steps::add)
      }
    }
    val failed = status == "failed" || status == "errored"
    val reply = if (!active) answer ?: commentary else null
    return TurnView(
      turnId = turn.id, userText = userText, active = active, failed = failed,
      headline = (if (active) answer ?: commentary else reply)?.let(::plainText),
      reply = reply?.let(::plainText),
      steps = steps,
      suggestions = if (active) emptyList() else suggestions(thread, turn.id),
    )
  }

  private fun userMessageText(item: JSONObject): String? {
    val content = item.optJSONArray("content") ?: return MenuVoiceHandoff.spokenText(item.optString("text")).ifEmpty { null }
    val parts = (0 until content.length()).mapNotNull { content.optJSONObject(it) }.filter { it.optString("type") == "text" || it.optString("type") == "inputText" }
    return parts.joinToString("\n") { MenuVoiceHandoff.spokenText(it.optString("text")) }.trim().ifEmpty { null }
  }

  private fun running(item: JSONObject) = item.optString("status").let { it == "inProgress" || it == "in_progress" } ||
    (!item.has("status") && !item.optBoolean("_streamCompleted", true))

  private fun step(item: JSONObject, type: String): Step? {
    val id = item.optString("id")
    val run = running(item)
    return when (type) {
      "webSearch" -> {
        val action = item.optJSONObject("action")
        val url = action?.optString("url").orEmpty()
        when {
          action?.optString("type") == "openPage" && url.isNotEmpty() -> Step(id, StepKind.Browse, "Reading ${host(url)}", run)
          else -> {
            val query = item.optString("query").ifEmpty { action?.optString("query").orEmpty() }
            Step(id, StepKind.Search, if (query.isEmpty()) "Searching the web" else "Searching $query", run)
          }
        }
      }
      "commandExecution" -> Step(id, StepKind.Act, "Running ${item.optString("command").lineSequence().firstOrNull().orEmpty().take(64)}".trim(), run)
      "fileChange" -> Step(id, StepKind.Act, "Editing files", run)
      "collabAgentToolCall", "collabToolCall" -> Step(id, StepKind.Act, "Working with another agent", run)
      else -> {
        val tool = item.optString("tool").substringAfterLast("__").substringAfterLast('/').substringAfterLast('.')
        val args = item.opt("arguments").let { if (it is JSONObject) it else (it as? String)?.let { s -> runCatching { JSONObject(s) }.getOrNull() } }
        val text = when (tool) {
          "open_app" -> "Opening ${args?.optString("app")?.ifEmpty { null } ?: args?.optString("package")?.ifEmpty { null } ?: "an app"}"
          "observe_background", "observe", "screenshot" -> "Looking at the screen"
          "tap" -> "Tapping on the screen"
          "type_text" -> "Typing"
          "apps" -> "Checking installed apps"
          "device_info" -> "Checking the phone"
          "" -> return null
          else -> "Using ${tool.replace('_', ' ')}"
        }
        val url = args?.optString("url").orEmpty()
        if (url.startsWith("http")) Step(id, StepKind.Browse, "Reading ${host(url)}", run) else Step(id, StepKind.Act, text, run)
      }
    }
  }

  private fun host(url: String) = url.substringAfter("://").substringBefore('/').removePrefix("www.")

  private fun suggestions(thread: JSONObject, turnId: String): List<Suggestion> {
    val record = thread.optJSONObject("chatEnhancements")?.optJSONObject(turnId) ?: return emptyList()
    if (record.optString("schema") != "taku.chat-enhance/1" || record.optString("status") != "completed") return emptyList()
    val actions = record.optJSONObject("request")?.optJSONArray("actions") ?: return emptyList()
    return (0 until actions.length()).mapNotNull { i ->
      val a = actions.optJSONObject(i) ?: return@mapNotNull null
      val label = a.optString("label").trim(); val reply = a.optString("reply").trim()
      // Actions with an actionRef execute through the native task broker, not as a reply.
      if (label.isEmpty() || reply.isEmpty() || a.has("actionRef")) null else Suggestion(a.optString("id").ifEmpty { "a$i" }, label, reply)
    }
  }

  /** Markdown reduced to readable plain text for the island (the full chat keeps formatting). */
  fun plainText(markdown: String): String {
    val out = StringBuilder()
    var fenced = false
    for (raw in markdown.lines()) {
      if (raw.trimStart().startsWith("```")) { fenced = !fenced; continue }
      var line = raw
      if (!fenced) {
        // Tables (issue #32 G1): a separator row goes, a cell row reads "a · b".
        val row = line.trim()
        if (row.startsWith("|") && row.endsWith("|") && row.length > 1) {
          val cells = row.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
          if (cells.all { it.matches(Regex(":?-{2,}:?")) }) continue
          line = cells.filter { it.isNotEmpty() }.joinToString(" · ")
        }
        line = line.replace(Regex("^\\s{0,3}>\\s?"), "")
        line = line.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "")
          .replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
          .replace(Regex("\\[([^\\]]+)]\\((?:[^)]+)\\)"), "$1")
          .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
          .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?!\\w)"), "$1")
          .replace(Regex("`([^`]+)`"), "$1")
      }
      out.append(line).append('\n')
    }
    return out.toString().trim().replace(Regex("\n{3,}"), "\n\n")
  }
}
