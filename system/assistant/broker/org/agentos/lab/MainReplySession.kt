package org.agentos.lab

import org.json.JSONArray
import org.json.JSONObject

/** Keeps raw SDK identity changes outside the public reply and its stable user anchor. */
internal class MainReplySession(private val threadId: String, private var contract: JSONObject?) {
    private class Source(val turnId: String, val ordinal: Int, val anchor: String, val stream: MainReplyStream) {
        val text = StringBuilder()
        var canonicalPrefix = false
        val aliases = HashSet<String>()
        var memberIds = emptyList<String>()
        var appendItemId: String? = null
        var observedLength = 0
        var ids = JSONArray()
        var idsApplied = false
        var revision = -1
        var reply: JSONObject? = null
    }
    private class TurnIndex(val raw: JSONObject, val view: JSONObject, val position: Int) {
        val rawItems = HashMap<String, JSONObject>()
        val items = LinkedHashMap<String, JSONObject>()
        val bindings = HashMap<String, Source>()
        val buffers = HashMap<String, StringBuilder>()
    }
    class Update(val turn: JSONObject?, val item: JSONObject?, val oldText: String?, val replies: List<JSONObject>)
    private val sources = LinkedHashMap<String, Source>()
    private val sourcesByTurn = HashMap<String, MutableList<Source>>()
    private val turns = HashMap<String, TurnIndex>()
    private val dirty = LinkedHashSet<Source>()
    private val modes = HashMap<String, Boolean>()
    private var nextMode = true

    fun prepare(enabled: Boolean) { nextMode = enabled }
    fun install(value: JSONObject) { contract = value }
    fun hasContract(): Boolean = contract != null
    fun started(turnId: String) {
        modes[turnId] = nextMode
        for ((identity, source) in sources) if (!identity.startsWith("$turnId/") && source.stream.hasSource()) {
            source.stream.fail("USER_ANCHOR_CHANGED")
            if (source.revision != source.stream.snapshotRevision()) dirty.add(source)
        }
    }

    fun interrupt(turnId: String, code: String) {
        for (source in sourcesByTurn[turnId] ?: emptyList()) if (source.stream.hasSource()) {
            source.stream.fail(code)
            if (source.revision != source.stream.snapshotRevision()) dirty.add(source)
        }
    }

    fun project(raw: JSONObject, previous: JSONObject?, canonical: Boolean): JSONObject {
        val view = MainReplyStream.copy(raw)
        view.remove("chatEnhancements")
        val turns = view.optJSONArray("turns") ?: return view
        val oldTurns = previous?.optJSONArray("turns") ?: JSONArray()
        this.turns.clear()
        for (index in 0 until turns.length()) {
            val turn = turns.getJSONObject(index)
            val turnId = turn.getString("id")
            val oldTurn = MainReplyStream.find(oldTurns, turnId)
            if (modes[turnId] == false) turn.remove("mainReplies")
            else if (contract != null) projectTurn(turn, oldTurn, canonical)
            else hideItems(turn)
            indexTurn(raw.getJSONArray("turns").getJSONObject(index), turn, index)
        }
        dirty.clear()
        return view
    }

    private fun indexTurn(raw: JSONObject, view: JSONObject, position: Int): TurnIndex {
        val indexed = TurnIndex(raw, view, position)
        val rawItems = raw.optJSONArray("items") ?: JSONArray().also { raw.put("items", it) }
        for (i in 0 until rawItems.length()) rawItems.getJSONObject(i).let { indexed.rawItems[it.getString("id")] = it }
        val items = view.optJSONArray("items") ?: JSONArray().also { view.put("items", it) }
        for (i in 0 until items.length()) items.getJSONObject(i).let { indexed.items[it.getString("id")] = it }
        val turnId = raw.getString("id")
        if (modes[turnId] != false) for (source in sourcesByTurn[turnId] ?: emptyList()) {
            for (id in source.aliases) indexed.bindings[id] = source
        }
        turns[turnId] = indexed
        return indexed
    }

    fun hidden(turnId: String, itemId: String): Boolean {
        val turn = turns[turnId] ?: return false
        val item = turn.items[itemId] ?: return false
        return item.has("replyId") || item.optString("type") == "agentMessage" && item.optString("text") != turn.rawItems[itemId]?.optString("text")
    }

    /** Live events retain historical objects; only explicit project() reconciles a whole thread. */
    fun update(raw: JSONObject, view: JSONObject, event: JSONObject): Update? {
        val method = event.getString("method")
        val params = event.getJSONObject("params")
        if (method == "thread/name/updated" || method == "thread/status/changed") {
            AgentSessionReducer.apply(raw, event)
            AgentSessionReducer.apply(view, event)
            return Update(null, null, null, emptyList())
        }
        val reasoning = method == AgentSessionReducer.REASONING_SUMMARY_DELTA
        if (method != "turn/started" && method != "turn/completed" && method != "item/started" && method != "item/completed" && method != "item/agentMessage/delta" && !reasoning) return null
        val turnId = params.optJSONObject("turn")?.getString("id") ?: params.getString("turnId")
        var indexed = turns[turnId]
        val itemId = params.optJSONObject("item")?.optString("id") ?: params.optString("itemId")
        if (reasoning && indexed != null) {
            val item = indexed.rawItems[itemId]
            val publicItem = indexed.items[itemId]
            if (item != null && publicItem != null) {
                // A reasoning summary is never reply source text: it grows in place, without rebuilding the turn.
                if (!AgentSessionReducer.appendSummary(item, params)) return null
                AgentSessionReducer.appendSummary(publicItem, params)
                return Update(indexed.view, publicItem, null, emptyList())
            }
        }
        if (method == "item/agentMessage/delta" && indexed != null) {
            val item = indexed.rawItems[itemId]
            if (item != null) {
                if (item.optBoolean("_streamCompleted") || item.optString("type") != "agentMessage") return null
                val source = indexed.bindings[itemId]
                if (source == null || source.appendItemId == itemId) return append(indexed, item, source, params.optString("delta"))
            }
        }
        if (method == "turn/started") started(turnId)
        if (indexed == null) {
            val rawTurn = AgentSessionReducer.turn(raw, turnId)
            val position = view.getJSONArray("turns").length()
            val publicTurn = JSONObject().put("id", turnId).put("status", "inProgress").put("items", JSONArray())
            view.getJSONArray("turns").put(publicTurn)
            indexed = indexTurn(rawTurn, publicTurn, position)
        }
        val previous = indexed.view
        val oldText = indexed.items[itemId]?.optString("text")
        if (!AgentSessionReducer.applyTurn(indexed.raw, event)) return null
        val turn = MainReplyStream.copy(indexed.raw)
        if (modes[turnId] == false) turn.remove("mainReplies")
        else if (contract != null) projectTurn(turn, previous, false)
        else hideItems(turn)
        view.getJSONArray("turns").put(indexed.position, turn)
        val updated = indexTurn(indexed.raw, turn, indexed.position)
        val changes = ArrayList<JSONObject>()
        val records = turn.optJSONObject("mainReplies")
        if (records != null) for (id in MainReplyStream.keys(records)) {
            val reply = records.getJSONObject(id)
            if (previous.optJSONObject("mainReplies")?.optJSONObject(id)?.optInt("revision") != reply.getInt("revision")) changes.add(envelope(updated, reply))
        }
        changes.addAll(refresh())
        return Update(turn, updated.items[itemId], oldText, changes)
    }

    private fun append(turn: TurnIndex, item: JSONObject, source: Source?, delta: String): Update? {
        if (delta.isEmpty()) return null
        val itemId = item.getString("id")
        val publicItem = turn.items[itemId] ?: return null
        val oldText = publicItem.optString("text")
        val buffer = turn.buffers.getOrPut(itemId) { StringBuilder(item.optString("text")) }
        buffer.append(delta)
        // Private SDK text stays buffered. JSONObject serializes CharSequence as a JSON string
        // when a canonical read or final-item reconciliation actually needs its full value.
        item.put("text", buffer)
        if (source != null && !source.stream.isPlain()) {
            // A canonical prefix can lag live input. Compare only this fragment's overlap.
            val overlap = minOf(delta.length, (source.text.length - source.observedLength).coerceAtLeast(0))
            for (i in 0 until overlap) if (source.text[source.observedLength + i] != delta[i]) { source.stream.fail("SOURCE_CHANGED"); break }
            if (overlap < delta.length) {
                val suffix = if (overlap == 0) delta else delta.substring(overlap)
                source.stream.push(suffix)
                source.text.append(suffix)
            }
            source.observedLength += delta.length
            source.canonicalPrefix = source.observedLength < source.text.length
            if (source.stream.isFramed() && !source.idsApplied) { source.stream.sources(source.ids); source.idsApplied = true }
            if (!source.stream.isPlain()) {
                if (source.revision == source.stream.snapshotRevision()) return null
                val reply = materialize(source, source.reply, turn.raw.optString("status")) ?: return null
                return Update(turn.view, publicItem, oldText, listOf(install(turn, source, reply)))
            }
        }
        val rawText = buffer.toString()
        item.put("text", rawText)
        if (source == null && modes[turn.raw.getString("id")] != false && recognizable(rawText)) {
            publicItem.put("text", "").remove("content")
            return null
        }
        publicItem.put("text", rawText)
        return Update(turn.view, publicItem, oldText, emptyList())
    }

    fun refresh(): List<JSONObject> {
        if (dirty.isEmpty()) return emptyList()
        val changes = ArrayList<JSONObject>()
        for (source in dirty) {
            val turn = turns[source.turnId] ?: continue
            if (source.revision == source.stream.snapshotRevision()) continue
            val reply = materialize(source, source.reply, turn.raw.optString("status")) ?: continue
            changes.add(install(turn, source, reply))
        }
        dirty.clear()
        return changes
    }

    private fun install(turn: TurnIndex, source: Source, reply: JSONObject): JSONObject {
        val records = turn.view.optJSONObject("mainReplies") ?: JSONObject().also { turn.view.put("mainReplies", it) }
        records.put(reply.getString("id"), reply)
        for ((at, id) in source.memberIds.withIndex()) turn.items[id]?.let { sanitize(it, reply, at == 0) }
        return envelope(turn, reply)
    }

    private fun envelope(turn: TurnIndex, reply: JSONObject): JSONObject {
        val items = JSONArray()
        for (item in turn.items.values) if (item.optString("replyId") == reply.getString("id")) items.put(item)
        return JSONObject().put("turnId", turn.raw.getString("id")).put("reply", reply).put("items", items)
    }

    private fun materialize(source: Source, saved: JSONObject?, status: String): JSONObject? {
        if (source.revision == source.stream.snapshotRevision() && source.reply === saved) return source.reply
        var reply = source.stream.snapshot() ?: run { source.revision = source.stream.snapshotRevision(); return null }
        if (saved != null) {
            if (saved.getJSONObject("provenance").optString("anchorHash") != source.anchor) {
                source.stream.fail("SOURCE_CHANGED")
                reply = source.stream.snapshot()!!
            }
            if (saved.getJSONObject("lifecycle").optString("code") == "RUNTIME_LOST" && status == "inProgress") {
                val ids = reply.getJSONObject("provenance").getJSONArray("sourceItemIds")
                reply = MainReplyStream.copy(saved)
                reply.getJSONObject("provenance").put("sourceItemIds", ids)
            } else if (saved.optJSONObject("card") != null && reply.optJSONObject("card") == null && reply.getJSONObject("lifecycle").optString("phase") in setOf("failed", "interrupted")) {
                reply.put("card", saved.getJSONObject("card")).put("readableText", saved.getString("readableText"))
            }
            if (saved.optInt("revision") >= reply.optInt("revision")) reply.put("revision", saved.getInt("revision") + if (sameSnapshot(saved, reply)) 0 else 1)
        }
        source.revision = source.stream.snapshotRevision()
        source.reply = reply
        return reply
    }

    private fun projectTurn(turn: JSONObject, oldTurn: JSONObject?, canonical: Boolean) {
        val manifest = contract ?: return
        val turnId = turn.getString("id")
        val items = turn.optJSONArray("items") ?: JSONArray().also { turn.put("items", it) }
        val replies = JSONObject()
        val saved = oldTurn?.optJSONObject("mainReplies") ?: turn.optJSONObject("mainReplies")
        if (saved != null) for (id in MainReplyStream.keys(saved)) replies.put(id, saved.getJSONObject(id))
        var ordinal = -1
        var anchor: String
        val groups = LinkedHashMap<Int, MutableList<JSONObject>>()
        val anchors = HashMap<Int, String>()
        val lastTool = HashMap<Int, Int>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("type") == "userMessage") {
                ordinal++
                anchor = MainReplyStream.hash(MainReplyStream.canonical(item.opt("content") ?: item.opt("text") ?: ""))
                anchors[ordinal] = anchor
                groups[ordinal] = ArrayList()
            } else if (ordinal >= 0 && item.optString("type") != "agentMessage") lastTool[ordinal] = i
        }
        var current = -1
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("type") == "userMessage") { current++; continue }
            if (current < 0 || item.optString("type") != "agentMessage") continue
            val phase = if (item.isNull("phase")) "" else item.optString("phase")
            val known = sourcesByTurn[turnId]?.firstOrNull { item.optString("id") in it.aliases }
            if (known != null && known.ordinal != current) {
                sanitize(item, known.reply, false)
                continue
            }
            if (phase != "final_answer" && (phase.isNotEmpty() || i <= (lastTool[current] ?: -1))) continue
            if (item.optString("status") in setOf("failed", "cancelled", "interrupted") && known == null) continue
            groups[current]?.add(item)
        }
        for ((group, members) in groups) {
            val identity = "$turnId/$group"
            val digest = anchors[group] ?: continue
            var source = sources[identity]
            if (source != null && source.stream.isPlain()) {
                val previous = source
                if (members.none { it.optString("id") in previous.aliases }) {
                    sources.remove(identity)
                    sourcesByTurn[turnId]?.remove(previous)
                    dirty.remove(previous)
                    source = null
                }
            }
            if (source == null) {
                if (members.isEmpty()) continue
                source = Source(turnId, group, digest, MainReplyStream(manifest, threadId, turnId, group, digest))
                sources[identity] = source
                sourcesByTurn.getOrPut(turnId) { ArrayList() }.add(source)
            }
            if (source.anchor != digest) source.stream.fail("SOURCE_CHANGED")
            if (group < ordinal) source.stream.fail("USER_ANCHOR_CHANGED")
            val ids = JSONArray()
            val combined = StringBuilder()
            var replacement: String? = null
            var replacementId: String? = null
            var allAliases = source.text.startsWith(MainReplyStream.HEADER)
            for (item in members) {
                val itemId = item.optString("id")
                if (itemId.isNotEmpty()) { ids.put(itemId); source.aliases.add(itemId) }
                // Public checkpoints are never a source of wire input.
                if (!item.has("replyId")) {
                    val body = item.optString("text")
                    combined.append(body)
                    if (body.isNotEmpty()) {
                        if (!body.startsWith(MainReplyStream.HEADER) || !(body.startsWith(source.text) || source.text.startsWith(body))) allAliases = false
                        else if (replacement == null || body.length > replacement.length) { replacement = body; replacementId = itemId }
                    }
                }
            }
            val text = if (allAliases && replacement != null) replacement else combined.toString()
            if (text.startsWith(source.text)) {
                source.stream.push(text.substring(source.text.length))
                source.text.append(text, source.text.length, text.length)
                source.canonicalPrefix = false
            } else if (source.text.startsWith(text) && (canonical || source.canonicalPrefix)) source.canonicalPrefix = true
            else source.stream.fail("SOURCE_CHANGED")
            if (turn.optString("status") in setOf("completed", "failed", "interrupted", "cancelled")) source.stream.finish(turn.optString("status"))
            source.memberIds = members.map { it.getString("id") }
            source.appendItemId = if (allAliases && replacement != null) replacementId else source.memberIds.lastOrNull()
            source.observedLength = text.length
            source.ids = ids
            source.idsApplied = source.stream.isFramed()
            if (source.idsApplied) source.stream.sources(ids)
            val reply = materialize(source, replies.optJSONObject(source.stream.replyId()), turn.optString("status"))
            if (reply != null) replies.put(reply.getString("id"), reply)
            if (!source.stream.isPlain()) for ((at, item) in members.withIndex()) sanitize(item, reply, at == 0)
        }
        val previousItems = oldTurn?.optJSONArray("items")
        if (previousItems != null) for (replyId in MainReplyStream.keys(replies)) {
            if ((0 until items.length()).any { items.getJSONObject(it).optString("replyId") == replyId }) continue
            for (i in 0 until previousItems.length()) {
                val item = previousItems.getJSONObject(i)
                if (item.optString("replyId") == replyId && item.optString("text").isNotEmpty()) {
                    val anchorOrdinal = replies.getJSONObject(replyId).getJSONObject("provenance").getInt("anchorOrdinal")
                    var seen = -1
                    var at = items.length()
                    for (j in 0 until items.length()) if (items.getJSONObject(j).optString("type") == "userMessage") {
                        seen++
                        if (seen > anchorOrdinal) { at = j; break }
                    }
                    for (j in items.length() - 1 downTo at) items.put(j + 1, items.get(j))
                    items.put(at, MainReplyStream.copy(item))
                    break
                }
            }
        }
        if (replies.length() > 0) turn.put("mainReplies", replies) else turn.remove("mainReplies")
        hideItems(turn)
    }

    companion object {
        private fun sameSnapshot(a: JSONObject, b: JSONObject): Boolean = MainReplyStream.canonical(MainReplyStream.copy(a).put("revision", 0)) == MainReplyStream.canonical(MainReplyStream.copy(b).put("revision", 0))
        private fun hideItems(turn: JSONObject) {
            val items = turn.optJSONArray("items") ?: return
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                if (!item.has("replyId") && item.optString("type") == "agentMessage" && recognizable(item.optString("text"))) sanitize(item, null, false)
            }
        }
        private fun sanitize(item: JSONObject, reply: JSONObject?, body: Boolean) {
            item.put("text", if (body && reply != null) reply.getString("readableText") else "")
            item.remove("content")
            if (body && reply != null) item.put("replyId", reply.getString("id")) else item.remove("replyId")
        }
        internal fun recognizable(text: String): Boolean {
            if (text.isEmpty()) return false
            val probe = MainReplyStream.prefix(text, true)
            return probe != MainReplyStream.Prefix.Plain || text.trimStart().isNotEmpty() && MainReplyStream.HEADER.startsWith(text.trimStart())
        }
        internal fun restored(thread: JSONObject): JSONObject {
            val view = MainReplyStream.copy(thread)
            val turns = view.optJSONArray("turns") ?: return view
            for (i in 0 until turns.length()) {
                val replies = turns.getJSONObject(i).optJSONObject("mainReplies") ?: continue
                for (id in MainReplyStream.keys(replies)) {
                    val reply = replies.getJSONObject(id)
                    val lifecycle = reply.getJSONObject("lifecycle")
                    if (lifecycle.optString("phase") in setOf("streaming", "sealed")) {
                        lifecycle.put("phase", "interrupted").put("code", "RUNTIME_LOST")
                        reply.put("revision", reply.optInt("revision") + 1)
                        val fields = reply.optJSONObject("card")?.optJSONObject("assembly")?.optJSONObject("fields")
                        if (fields != null) for (path in MainReplyStream.keys(fields)) if (fields.optString(path) in setOf("pending", "streaming")) fields.put(path, "failed")
                    }
                }
            }
            return view
        }
    }
}
