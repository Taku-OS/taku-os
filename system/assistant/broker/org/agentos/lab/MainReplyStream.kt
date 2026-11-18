package org.agentos.lab

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest

/** The main model's bounded reply protocol. No device, model, settings or storage access. */
internal class MainReplyStream(
    private val contract: JSONObject,
    threadId: String,
    turnId: String,
    anchorOrdinal: Int,
    anchorHash: String
) {
    private enum class Frame { Probe, Plain, Records }
    internal enum class Prefix { Pending, Plain, Header, Invalid, Unsupported }
    private class LimitExceeded : IllegalArgumentException()
    private var frame = Frame.Probe
    private val line = StringBuilder()
    private var total = 0
    private var count = 0
    private val records = ArrayList<String>()
    private var sealed = false
    private var terminal = false
    private var phase = "streaming"
    private var code: String? = null
    private val id = "reply-" + hash("$threadId\n$turnId\n$anchorOrdinal").take(32)
    private val provenance = JSONObject().put("kind", "primary-assistant-reply").put("threadId", threadId).put("turnId", turnId)
        .put("anchorOrdinal", anchorOrdinal).put("anchorHash", anchorHash).put("sourceItemIds", JSONArray())
        .put("contractHash", contract.getString("contractHash"))
    private var request = JSONObject().put("version", 2).put("key", id).put("mode", "card")
        .put("title", "").put("summary", "").put("blocks", JSONArray()).put("actions", JSONArray())
    private var fields = JSONObject().put("title", "absent").put("summary", "absent").put("body", "absent").put("actions", "absent")
    private var elements = JSONObject()
    private var parents = LinkedHashMap<String, String?>()
    private var root: String? = null
    private var begun = false
    private var revision = 0
    private var published: JSONObject? = null

    fun isPlain(): Boolean = frame == Frame.Plain
    fun isFramed(): Boolean = frame == Frame.Records
    fun snapshot(): JSONObject? = published?.let(::copy)
    fun snapshotRevision(): Int = revision
    fun replyId(): String = id
    fun hasSource(): Boolean = total > 0

    fun sources(ids: JSONArray) {
        if (canonical(ids) == canonical(provenance.getJSONArray("sourceItemIds"))) return
        val previous = provenance.getJSONArray("sourceItemIds")
        provenance.put("sourceItemIds", JSONArray(ids.toString()))
        if (published != null && snapshotValue(phase).toString().length > MAX_SNAPSHOT - 128) {
            provenance.put("sourceItemIds", previous)
            fail("REPLY_LIMIT_EXCEEDED")
        } else if (published != null) publish()
    }

    fun push(delta: String) {
        if (terminal || frame == Frame.Plain || delta.isEmpty()) return
        if (delta.length > MAX_SOURCE - total) {
            val probe = (line.toString() + delta.take(MAX_LINE)).take(MAX_LINE)
            if (frame == Frame.Probe && prefix(probe, true) == Prefix.Plain) frame = Frame.Plain
            else fail("REPLY_LIMIT_EXCEEDED")
            return
        }
        total += delta.length
        for (c in delta) {
            if (terminal || frame == Frame.Plain) break
            if (frame == Frame.Probe) {
                if (line.length == MAX_LINE) {
                    if (prefix(line.toString(), true) == Prefix.Plain) frame = Frame.Plain else fail("REPLY_LIMIT_EXCEEDED")
                    break
                }
                line.append(c)
                if (c == '\n' || line.length <= HEADER.length + 3) advanceProbe(false)
            } else if (c == '\n') {
                val text = line.toString()
                line.setLength(0)
                accept(text)
            } else {
                if (line.length == MAX_LINE) { fail("REPLY_LIMIT_EXCEEDED"); break }
                line.append(c)
            }
        }
    }

    private fun advanceProbe(ended: Boolean) {
        when (prefix(line.toString(), ended)) {
            Prefix.Pending -> Unit
            Prefix.Plain -> { frame = Frame.Plain; line.setLength(0) }
            Prefix.Header -> { frame = Frame.Records; line.setLength(0) }
            Prefix.Invalid -> fail("INVALID_REPLY_FRAME")
            Prefix.Unsupported -> fail("UNSUPPORTED_REPLY_VERSION")
        }
    }

    fun finish(status: String) {
        if (terminal || frame == Frame.Plain) return
        if (frame == Frame.Probe) {
            advanceProbe(true)
            if (terminal || frame == Frame.Plain) return
        }
        if (status != "completed") { fail(if (status == "interrupted" || status == "cancelled") "TURN_INTERRUPTED" else "TURN_FAILED"); return }
        if (line.isNotEmpty()) {
            val last = line.toString()
            line.setLength(0)
            try { StrictReplyJson(last).check() } catch (_: Exception) { fail("INCOMPLETE_REPLY"); return }
            accept(last)
        }
        if (terminal) return
        if (!sealed) { fail("INCOMPLETE_REPLY"); return }
        phase = "completed"
        terminal = true
        publish()
    }

    fun fail(reason: String) {
        if (terminal && (phase != "completed" || reason != "SOURCE_CHANGED")) return
        if (frame == Frame.Plain || frame == Frame.Probe && total == 0 && reason in setOf("TURN_INTERRUPTED", "USER_ANCHOR_CHANGED", "RUNTIME_LOST")) return
        frame = Frame.Records
        terminal = true
        phase = if (reason in setOf("TURN_INTERRUPTED", "USER_ANCHOR_CHANGED", "RUNTIME_LOST")) "interrupted" else "failed"
        code = reason
        settle(fields, true)
        publish()
    }

    private fun accept(text: String) {
        if (terminal) return
        var recordType = ""
        try {
            require(text.isNotBlank())
            StrictReplyJson(text).check()
            val record = JSONObject(text)
            val n = record.opt("n")
            require(n is Number && n.toDouble() == n.toInt().toDouble() && n.toInt() > 0)
            val number = n.toInt()
            val serialized = canonical(record)
            if (number <= records.size) {
                if (records[number - 1] != serialized) fail("RECORD_SEQUENCE_CONFLICT")
                return
            }
            if (number != records.size + 1) { fail("RECORD_SEQUENCE_CONFLICT"); return }
            if (sealed) { fail("INVALID_REPLY_RECORD"); return }
            if (++count > MAX_RECORDS) { fail("REPLY_LIMIT_EXCEEDED"); return }
            val next = copy(request)
            val readiness = copy(fields)
            val nodes = copy(elements)
            val ancestry = LinkedHashMap(parents)
            var nextRoot = root
            val type = record.getString("type")
            recordType = type
            when (type) {
                "begin" -> {
                    exact(record, setOf("n", "type", "catalogVersion", "mode", "title", "summary", "pending"))
                    require(!begun && number == 1 && record.getString("catalogVersion") == contract.getString("catalogVersion"))
                    require(record.getString("mode") in setOf("card", "panel"))
                    next.put("mode", record.getString("mode"))
                    card(record, next, readiness)
                }
                "card" -> {
                    require(begun)
                    exact(record, setOf("n", "type", "title", "summary", "pending"))
                    require(record.has("title") || record.has("summary"))
                    card(record, next, readiness)
                }
                "block" -> {
                    require(begun)
                    exact(record, setOf("n", "type", "id", "kind", "title", "text", "value", "image", "url", "items", "config", "pending"))
                    val blockId = identity(record.getString("id"))
                    val blocks = next.getJSONArray("blocks")
                    val old = find(blocks, blockId)
                    val block = old ?: emptyBlock(blockId, record.getString("kind"))
                    if (old == null) {
                        if (blocks.length() >= 8) throw LimitExceeded()
                        if (block.getString("kind") in setOf("metric", "progress", "input")) require(record.has("value"))
                        if (block.getString("kind") == "input") require(record.optJSONObject("config")?.has("editor") == true)
                        blocks.put(block)
                    } else {
                        if (record.has("kind")) require(record.getString("kind") == old.getString("kind"))
                        if (record.has("value")) require(record.getJSONObject("value").getString("type") == old.getJSONObject("value").getString("type"))
                        if (record.has("config")) require(canonical(record.getJSONObject("config")) == canonical(old.getJSONObject("config")))
                    }
                    merge(block, record, setOf("n", "type", "pending", "items"))
                    val supplied = record.optJSONArray("items")
                    if (supplied != null) {
                        val seen = HashSet<String>()
                        for (i in 0 until supplied.length()) {
                            val item = supplied.getJSONObject(i)
                            require(seen.add(item.getString("id")))
                            mergeItem(block, item, readiness)
                        }
                    }
                    readiness(record, block, "blocks/$blockId/", BLOCK_FIELDS, readiness, old == null)
                    readiness.put("body", "ready")
                }
                "item" -> {
                    require(begun)
                    exact(record, setOf("n", "type", "blockId", "id", "label", "detail", "value", "image", "url", "icon", "pending"))
                    mergeItem(find(next.getJSONArray("blocks"), record.getString("blockId")) ?: error("missing parent"), record, readiness)
                    readiness.put("body", "ready")
                }
                "action" -> {
                    require(begun)
                    exact(record, setOf("n", "type", "id", "label", "reply", "style"))
                    val actionId = identity(record.getString("id"))
                    val actions = next.getJSONArray("actions")
                    val action = find(actions, actionId) ?: JSONObject().put("id", actionId).also {
                        if (actions.length() >= 32) throw LimitExceeded()
                        require(record.has("label") && record.has("reply") && record.has("style"))
                        actions.put(it)
                    }
                    merge(action, record, setOf("n", "type"))
                    readiness.put("actions", "ready")
                }
                "element" -> {
                    require(begun)
                    exact(record, setOf("n", "type", "id", "parent", "component", "props", "on"))
                    val nodeId = identity(record.getString("id"))
                    require(record.has("parent"))
                    val parent = if (record.isNull("parent")) null else identity(record.getString("parent"))
                    val component = record.getString("component")
                    val definition = contract.getJSONObject("components").getJSONObject(component)
                    val node = JSONObject().put("type", component).put("props", record.getJSONObject("props")).put("children", JSONArray())
                    if (record.has("on")) node.put("on", record.getJSONObject("on"))
                    if (nodes.has(nodeId)) {
                        val previous = copy(nodes.getJSONObject(nodeId)).put("children", JSONArray())
                        require(ancestry[nodeId] == parent && canonical(previous) == canonical(node))
                    } else {
                        if (nodes.length() >= 96) throw LimitExceeded()
                        if (parent == null) {
                            require(nextRoot == null && component == "Card")
                            nextRoot = nodeId
                        } else {
                            require(component != "Card" && nodes.has(parent))
                            val parentNode = nodes.getJSONObject(parent)
                            require(contract.getJSONObject("components").getJSONObject(parentNode.getString("type")).getBoolean("children"))
                            var depth = 1
                            var cursor: String? = parent
                            while (cursor != null) { depth++; cursor = ancestry[cursor] }
                            if (depth > 32) throw LimitExceeded()
                            parentNode.getJSONArray("children").put(nodeId)
                        }
                        require(definition.has("props"))
                        nodes.put(nodeId, node)
                        ancestry[nodeId] = parent
                    }
                }
                "done" -> {
                    require(begun)
                    exact(record, setOf("n", "type"))
                    require(nextRoot != null && useful(next) && next.getJSONArray("blocks").length() > 0)
                    validateView(next, nodes, true)
                    settle(readiness, false)
                }
                else -> error("unknown record")
            }
            validateRequest(next)
            if (nodes.length() > 0) validateView(next, nodes, false)
            val previousRequest = request
            val previousFields = fields
            val previousElements = elements
            val previousParents = parents
            val previousRoot = root
            request = next; fields = readiness; elements = nodes; parents = ancestry; root = nextRoot
            // Reserve terminal lifecycle metadata and revision digits inside the persisted record bound.
            if (snapshotValue(phase).toString().length > MAX_SNAPSHOT - 128) {
                request = previousRequest; fields = previousFields; elements = previousElements; parents = previousParents; root = previousRoot
                fail("REPLY_LIMIT_EXCEEDED")
                return
            }
            records.add(serialized)
            if (type == "begin") begun = true
            if (type == "done") { sealed = true; phase = "sealed" }
            publish()
        } catch (error: Exception) {
            fail(if (error is LimitExceeded) "REPLY_LIMIT_EXCEEDED" else if (recordType == "element" || recordType == "done") "INVALID_REPLY_VIEW" else "INVALID_REPLY_RECORD")
        }
    }

    private fun card(record: JSONObject, next: JSONObject, readiness: JSONObject) {
        for (key in arrayOf("title", "summary")) if (record.has(key)) next.put(key, record.get(key))
        readiness(record, next, "", arrayOf("title", "summary", "body", "actions"), readiness, false)
    }

    private fun mergeItem(block: JSONObject, record: JSONObject, readiness: JSONObject) {
        exact(record, setOf("n", "type", "blockId", "id", "label", "detail", "value", "image", "url", "icon", "pending"))
        val itemId = identity(record.getString("id"))
        val items = block.getJSONArray("items")
        val old = find(items, itemId)
        val item = old ?: emptyItem(itemId).also {
            if (items.length() >= 20) throw LimitExceeded()
            require(record.has("label"))
            items.put(it)
        }
        if (old != null && record.has("value") && meaningful(old.getJSONObject("value"))) require(record.getJSONObject("value").getString("type") == old.getJSONObject("value").getString("type"))
        merge(item, record, setOf("n", "type", "blockId", "pending"))
        val prefix = "blocks/${block.getString("id")}/items"
        readiness(record, item, "$prefix/$itemId/", ITEM_FIELDS, readiness, old == null)
        readiness.put(prefix, "ready")
    }

    private fun readiness(record: JSONObject, value: JSONObject, prefix: String, names: Array<String>, into: JSONObject, fresh: Boolean) {
        val pending = record.optJSONArray("pending")
        val waiting = HashSet<String>()
        if (record.has("pending")) require(pending != null)
        if (pending != null) for (i in 0 until pending.length()) {
            val name = pending.getString(i)
            require(name in names && waiting.add(name))
        }
        for (name in names) {
            if (!fresh && !record.has(name) && name !in waiting) continue
            val present = meaningful(value.opt(name)) || name == "value" && record.has(name) && name !in waiting
            into.put(prefix + name, if (name in waiting) { if (present) "streaming" else "pending" } else if (present) "ready" else "absent")
        }
    }

    private fun settle(readiness: JSONObject, failed: Boolean) {
        for (path in keys(readiness)) when (readiness.getString(path)) {
            "pending" -> readiness.put(path, if (failed) "failed" else "absent")
            "streaming" -> readiness.put(path, if (failed) "failed" else "ready")
        }
    }

    private fun validateRequest(value: JSONObject) {
        val schema = UiCardRequest.schema()
        for (key in arrayOf("version", "key", "mode", "title", "summary", "actions")) UiCardRequest.validateSchema(value.get(key), schema.getJSONObject(key))
        if (value.getJSONArray("blocks").length() > 0) {
            UiCardRequest.validateSchema(value.getJSONArray("blocks"), schema.getJSONObject("blocks"))
            UiCardRequest.validate(value)
        }
    }

    private fun validateView(data: JSONObject, nodes: JSONObject, complete: Boolean) {
        val state = state(data)
        val reads = HashSet<String>()
        val writes = HashSet<String>()
        val actionIds = HashSet<String>()
        val writable = writable(data)
        for (nodeId in keys(nodes)) {
            val node = nodes.getJSONObject(nodeId)
            val definition = contract.getJSONObject("components").getJSONObject(node.getString("type"))
            val props = node.getJSONObject("props")
            val content = definition.getJSONArray("contentProps")
            for (i in 0 until content.length()) {
                val prop = content.getString(i)
                if (!props.has(prop)) continue
                val binding = props.optJSONObject(prop) ?: error("content must bind source")
                require(binding.optString("\$state").startsWith("/data/") || binding.optString("\$bindState") in writable)
            }
            UiCardRequest.validateSchema(resolve(props, state, writable, reads, writes), definition.getJSONObject("props"))
            val on = node.optJSONObject("on") ?: continue
            val events = definition.getJSONArray("events")
            for (event in keys(on)) {
                require((0 until events.length()).any { events.getString(it) == event })
                val raw = on.get(event)
                val handlers = if (raw is JSONArray) raw else JSONArray().put(raw)
                require(handlers.length() in 1..8)
                for (i in 0 until handlers.length()) {
                    val handler = handlers.getJSONObject(i)
                    exact(handler, setOf("action", "params"))
                    val action = handler.getString("action")
                    val params = handler.getJSONObject("params")
                    val resolved = resolve(params, state, writable, reads, writes) as JSONObject
                    UiCardRequest.validateSchema(resolved, contract.getJSONObject("actions").getJSONObject(action).getJSONObject("params"))
                    when (action) {
                        "setState" -> require(params.getString("statePath") in writable)
                        "reply" -> {
                            val actionId = params.getString("actionId")
                            require(find(data.getJSONArray("actions"), actionId) != null)
                            require(params.getJSONObject("text").optString("\$state") == "/data/actionsById/$actionId/reply")
                            require(props.optJSONObject("disabled")?.optString("\$state") == "/data/actionsById/$actionId/disabled")
                            val select = params.optJSONArray("select")
                            if (select != null) for (j in 0 until select.length()) require(select.getString(j) in writable && select.getString(j).startsWith("/ui/collections/"))
                            actionIds.add(actionId)
                        }
                        "openURL" -> {
                            require(params.optJSONObject("url")?.optString("\$state")?.startsWith("/data/") == true)
                            require(resolved.getString("url").matches(Regex("https?://\\S+")))
                        }
                    }
                }
            }
        }
        validateGroups(data, nodes, complete)
        if (!complete) return
        fun has(path: String) = reads.any { it == path || it.startsWith("$path/") }
        val blocks = data.getJSONArray("blocks")
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            val blockId = block.getString("id")
            val p = "/data/blocksById/$blockId"
            require(when (block.getString("kind")) {
                "input" -> "/ui/fields/$blockId/draft" in writes
                "choices" -> has("$p/items") && "/ui/collections/$blockId/selectedItemIds" in writes
                "collection", "timeline", "route", "sources" -> has("$p/items")
                "metric" -> has("$p/value") && (block.getJSONArray("items").length() == 0 || has("$p/items"))
                "progress" -> has("$p/value")
                "media" -> has("$p/image") && has("$p/url")
                "overlay" -> has("$p/image") && has("$p/text")
                "text" -> has("$p/text")
                else -> false
            })
        }
        val actions = data.getJSONArray("actions")
        for (i in 0 until actions.length()) require(actions.getJSONObject(i).getString("id") in actionIds)
    }

    private fun validateGroups(data: JSONObject, nodes: JSONObject, complete: Boolean) {
        val rows = data.getJSONArray("blocks")
        val groups = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until rows.length()) {
            val block = rows.getJSONObject(i)
            val config = block.getJSONObject("config")
            if (config.has("group")) groups.getOrPut(config.getString("group")) { ArrayList() }.add(block)
        }
        for ((group, members) in groups) {
            val primary = members.first()
            if (!groupPrimary(primary) || primary.getJSONObject("config").getString("size") == "full_size") continue
            val valuePath = "/data/blocksById/${primary.getString("id")}/value"
            val parent = keys(nodes).firstOrNull { nodes.getJSONObject(it).getString("type") == "Metric" && nodes.getJSONObject(it).getJSONObject("props").optJSONObject("value")?.optString("\$state") == valuePath }
            if (parent == null && !complete) continue
            require(parent != null)
            require(nodes.getJSONObject(parent).getJSONObject("props").optJSONObject("expanded")?.optString("\$bindState") == "/ui/views/$group/expanded")
            val descendants = HashSet<String>()
            fun visit(id: String) {
                val children = nodes.getJSONObject(id).getJSONArray("children")
                for (i in 0 until children.length()) if (descendants.add(children.getString(i))) visit(children.getString(i))
            }
            visit(parent)
            for (member in members.drop(1)) for (nodeId in keys(nodes)) {
                val paths = HashSet<String>()
                resolve(nodes.getJSONObject(nodeId).getJSONObject("props"), state(data), writable(data), paths, HashSet())
                if (paths.any { it.startsWith("/data/blocksById/${member.getString("id")}/") }) require(nodeId in descendants)
            }
        }
    }

    private fun resolve(value: Any, state: JSONObject, allowed: Set<String>, reads: MutableSet<String>, writes: MutableSet<String>): Any {
        if (value is JSONArray) return JSONArray().also { array -> for (i in 0 until value.length()) array.put(resolve(value.get(i), state, allowed, reads, writes)) }
        if (value !is JSONObject) return value
        val read = value.opt("\$state")
        val write = value.opt("\$bindState")
        if (read != null || write != null) {
            require(value.length() == 1)
            val path = (read ?: write) as? String ?: error("invalid binding")
            require(path.startsWith("/") && !Regex("/(?:blocks|actions|items)/[0-9]+(?:/|$)").containsMatchIn(path))
            if (write != null) { require(path in allowed); writes.add(path) }
            else { require(path.startsWith("/data/") || path in allowed); reads.add(path) }
            var cursor: Any = state
            for (part in path.drop(1).split('/')) cursor = (cursor as? JSONObject)?.opt(part) ?: error("unresolved binding")
            return cursor
        }
        val result = JSONObject()
        for (key in keys(value)) { require(!key.startsWith("$")); result.put(key, resolve(value.get(key), state, allowed, reads, writes)) }
        return result
    }

    private fun groupPrimary(block: JSONObject): Boolean {
        val type = block.getJSONObject("value").getString("type")
        return block.getString("kind") == "metric" && type != "instant" && type != "duration" && block.getJSONObject("config").has("size")
    }

    private fun writable(data: JSONObject): Set<String> {
        val allowed = HashSet<String>()
        val blocks = data.getJSONArray("blocks")
        val groups = HashSet<String>()
        for (i in 0 until blocks.length()) {
            val block = blocks.getJSONObject(i)
            val id = block.getString("id")
            if (block.getString("kind") == "input") allowed.add("/ui/fields/$id/draft")
            if (block.getString("kind") == "choices") allowed.add("/ui/collections/$id/selectedItemIds")
            val config = block.getJSONObject("config")
            if (config.has("group") && groups.add(config.getString("group")) && groupPrimary(block))
                allowed.add("/ui/views/${config.getString("group")}/expanded")
        }
        return allowed
    }

    private fun state(data: JSONObject): JSONObject {
        val projected = copy(data)
        val blocks = JSONObject()
        val actions = JSONObject()
        val uiFields = JSONObject()
        val collections = JSONObject()
        val views = JSONObject()
        val rows = data.getJSONArray("blocks")
        for (i in 0 until rows.length()) {
            val block = rows.getJSONObject(i)
            val id = block.getString("id")
            blocks.put(id, block)
            if (block.getString("kind") == "input") uiFields.put(id, JSONObject().put("draft", block.getJSONObject("value").get("value")))
            if (block.getString("kind") == "choices") collections.put(id, JSONObject().put("selectedItemIds", JSONArray()))
            val group = block.getJSONObject("config").optString("group")
            if (group.isNotEmpty()) views.put(group, JSONObject().put("expanded", false))
        }
        val commands = data.getJSONArray("actions")
        for (i in 0 until commands.length()) { val action = copy(commands.getJSONObject(i)); actions.put(action.getString("id"), action.put("disabled", true)) }
        projected.put("blocksById", blocks).put("actionsById", actions)
        return JSONObject().put("data", projected).put("ui", JSONObject().put("fields", uiFields).put("collections", collections).put("views", views))
    }

    private fun publish() {
        val value = snapshotValue(phase)
        val prior = published
        if (prior != null && canonical(copy(prior).put("revision", 0)) == canonical(value)) return
        value.put("revision", ++revision)
        published = value
    }

    private fun snapshotValue(currentPhase: String): JSONObject {
        val lifecycle = JSONObject().put("phase", currentPhase)
        if (code != null) lifecycle.put("code", code)
        val value = JSONObject().put("schema", "taku.main-reply/1").put("id", id).put("key", id).put("revision", 0)
            .put("readableText", readable(request)).put("provenance", copy(provenance)).put("lifecycle", lifecycle).put("card", JSONObject.NULL)
        if (root != null && request.getJSONArray("blocks").length() > 0 && renderable()) {
            val metrics = JSONObject().put("level", "primary").put("fallback", "").put("ms", 0).put("jevSteps", 0)
                .put("inputTokens", JSONObject.NULL).put("llmUsage", JSONObject.NULL)
            val view = JSONObject().put("catalogVersion", contract.getString("catalogVersion")).put("signature", signature(request))
                .put("spec", JSONObject().put("root", root).put("elements", copy(elements))).put("metrics", metrics)
            value.put("card", JSONObject().put("request", copy(request)).put("view", view).put("assembly", JSONObject().put("version", 1).put("fields", copy(fields))))
        }
        return value
    }

    private fun renderable(): Boolean {
        val state = state(request)
        for (nodeId in keys(elements)) {
            val node = elements.getJSONObject(nodeId)
            val content = contract.getJSONObject("components").getJSONObject(node.getString("type")).getJSONArray("contentProps")
            val props = node.getJSONObject("props")
            for (i in 0 until content.length()) {
                val name = content.getString(i)
                if (name == "config" || !props.has(name)) continue
                val value = resolve(props.get(name), state, writable(request), HashSet(), HashSet())
                if (meaningful(value)) return true
            }
        }
        return false
    }

    companion object {
        private val RECORD_TYPES = setOf("begin", "card", "block", "item", "action", "element", "done")
        private val RECORD_TYPE = Regex("\"type\"\\s*:\\s*\"(?:begin|card|block|item|action|element|done)\"")
        private val RECORD_NUMBER = Regex("\"n\"\\s*:\\s*[1-9][0-9]*")
        internal fun prefix(text: String, ended: Boolean): Prefix {
            val probe = text.trimStart()
            if (probe.isEmpty()) return if (ended) Prefix.Plain else Prefix.Pending
            if (probe.startsWith("@taku.reply")) {
                if (!probe.contains('\n') && !ended) return Prefix.Pending
                val header = probe.substringBefore('\n').trimEnd()
                if (header != HEADER && header.startsWith("@taku.reply/")) return Prefix.Unsupported
                return if (text.startsWith(HEADER + "\n")) Prefix.Header else Prefix.Invalid
            }
            if (HEADER.startsWith(probe)) return if (ended) Prefix.Plain else Prefix.Pending
            if (probe.startsWith("```") || "```".startsWith(probe)) {
                if (probe.removePrefix("```").trimStart().startsWith("@taku.reply")) return Prefix.Invalid
                if (!probe.contains('\n')) return if (ended) Prefix.Plain else Prefix.Pending
                val body = probe.substringAfter('\n').trimStart()
                if (body.startsWith("@taku.reply") || primaryRecord(body.substringBefore('\n'))) return Prefix.Invalid
                if (body.isEmpty() || HEADER.startsWith(body) || body.startsWith("{") && !body.contains('\n')) return if (ended) Prefix.Plain else Prefix.Pending
                return Prefix.Plain
            }
            if (probe.startsWith("{")) {
                if (primaryRecord(probe.substringBefore('\n'))) return Prefix.Invalid
                return if (ended || probe.contains('\n')) Prefix.Plain else Prefix.Pending
            }
            return Prefix.Plain
        }

        private fun primaryRecord(text: String): Boolean {
            if (!text.startsWith("{")) return false
            try {
                val record = JSONObject(text)
                return record.optString("type") in RECORD_TYPES && (record.opt("n") is Number || record.optString("type") == "begin" && record.has("catalogVersion"))
            } catch (_: Exception) {
                return RECORD_TYPE.containsMatchIn(text) && (RECORD_NUMBER.containsMatchIn(text) || text.contains("\"catalogVersion\""))
            }
        }
        const val HEADER = "@taku.reply/1"
        const val MAX_SOURCE = 262144
        const val MAX_LINE = 16384
        const val MAX_RECORDS = 4096
        const val MAX_SNAPSHOT = 128000
        private val BLOCK_FIELDS = arrayOf("title", "text", "value", "image", "url", "items", "config")
        private val ITEM_FIELDS = arrayOf("label", "detail", "value", "image", "url", "icon")
        private val STRUCTURAL = setOf("layout", "emphasis", "size", "group", "editor", "multi", "mediaType", "captureSource")
        internal fun copy(value: JSONObject) = JSONObject(value.toString())
        internal fun keys(value: JSONObject): List<String> = value.keys().asSequence().toList()
        internal fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        @JvmStatic fun manifestHash(manifest: JSONObject): String = hash(canonical(manifest))
        internal fun canonical(value: Any?): String = when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> keys(value).sorted().joinToString(",", "{", "}") { quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> quote(value)
            is Number -> {
                val number = value.toDouble()
                require(number.isFinite())
                if (number == 0.0) "0" else {
                    val decimal = BigDecimal(value.toString()).stripTrailingZeros()
                    if (Math.abs(number) >= 1e21 || Math.abs(number) < 1e-6) decimal.toString().replace('E', 'e') else decimal.toPlainString()
                }
            }
            else -> value.toString()
        }
        private fun quote(text: String): String {
            val result = StringBuilder(text.length + 2).append('"')
            for (i in text.indices) {
                val c = text[i]
                when (c) {
                    '"', '\\' -> result.append('\\').append(c)
                    '\b' -> result.append("\\b")
                    '\u000c' -> result.append("\\f")
                    '\n' -> result.append("\\n")
                    '\r' -> result.append("\\r")
                    '\t' -> result.append("\\t")
                    else -> {
                        val unpaired = Character.isSurrogate(c) && !(Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) || Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(text[i - 1]))
                        if (c < ' ' || unpaired) result.append("\\u").append(c.code.toString(16).padStart(4, '0')) else result.append(c)
                    }
                }
            }
            return result.append('"').toString()
        }
        private fun identity(id: String): String { require(id.matches(Regex("[a-z0-9-]{1,64}"))); return id }
        private fun exact(value: JSONObject, allowed: Set<String>) { require(keys(value).all { it in allowed }) }
        private fun merge(target: JSONObject, source: JSONObject, excluded: Set<String>) { for (key in keys(source)) if (key !in excluded) target.put(key, source.get(key)) }
        internal fun find(array: JSONArray, id: String): JSONObject? { for (i in 0 until array.length()) if (array.getJSONObject(i).optString("id") == id) return array.getJSONObject(i); return null }
        private fun unknown() = JSONObject().put("type", "text").put("value", JSONObject.NULL)
        private fun emptyBlock(id: String, kind: String) = JSONObject().put("id", id).put("kind", kind).put("title", "").put("text", "")
            .put("value", unknown()).put("image", "").put("url", "").put("items", JSONArray()).put("config", JSONObject())
        private fun emptyItem(id: String) = JSONObject().put("id", id).put("label", "").put("detail", "").put("value", unknown()).put("image", "").put("url", "").put("icon", "")
        private fun meaningful(value: Any?): Boolean = when (value) {
            null, JSONObject.NULL -> false
            is String -> value.isNotBlank()
            is JSONArray -> value.length() > 0
            is JSONObject -> if (value.has("type") && value.has("value")) meaningful(value.opt("value")) else value.length() > 0
            else -> true
        }
        private fun useful(request: JSONObject): Boolean = readable(request).isNotBlank()
        @JvmStatic fun signature(request: JSONObject): String {
            val blocks = request.getJSONArray("blocks")
            val sorted = (0 until blocks.length()).map { blocks.getJSONObject(it) }.sortedBy { it.getString("id") }
            val shape = sorted.joinToString(",", "[", "]") { block ->
                val config = block.getJSONObject("config")
                val structural = keys(config).filter { it in STRUCTURAL }.sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(config.get(it)) }
                "{\"id\":" + JSONObject.quote(block.getString("id")) + ",\"kind\":" + JSONObject.quote(block.getString("kind")) + ",\"type\":" + JSONObject.quote(block.getJSONObject("value").getString("type")) + ",\"config\":" + structural + "}"
            }
            val actions = request.getJSONArray("actions")
            val ids = (0 until actions.length()).map { actions.getJSONObject(it).getString("id") }.sorted().joinToString(",", "[", "]") { JSONObject.quote(it) }
            return "{\"version\":2,\"mode\":" + JSONObject.quote(request.getString("mode")) + ",\"layout\":" + JSONObject.quote(request.optString("layout", "compose")) + ",\"blocks\":" + shape + ",\"actions\":" + ids + "}"
        }
        @JvmStatic fun readable(request: JSONObject): String {
            val lines = ArrayList<String>()
            fun text(value: JSONObject, key: String) { if (!value.isNull(key) && value.optString(key).isNotBlank()) lines.add(value.getString(key)) }
            fun scalar(value: JSONObject) {
                if (value.isNull("value")) return
                val raw = value.get("value")
                val rendered = when (value.getString("type")) {
                    "money" -> BigDecimal(raw.toString()).movePointLeft(value.getInt("scale")).setScale(value.getInt("scale")).toPlainString() + " " + value.getString("currency")
                    "instant" -> raw.toString() + " [" + value.getString("timeZone") + "]"
                    "duration" -> canonical(raw) + " ms"
                    "number" -> canonical(raw)
                    else -> raw.toString()
                }
                if (rendered.isNotBlank()) lines.add(rendered + if (value.optString("unit").isNotBlank()) " " + value.getString("unit") else "")
            }
            text(request, "title"); text(request, "summary")
            val blocks = request.getJSONArray("blocks")
            for (i in 0 until blocks.length()) {
                val block = blocks.getJSONObject(i)
                text(block, "title"); text(block, "text"); scalar(block.getJSONObject("value")); text(block, "url"); text(block, "image")
                val items = block.getJSONArray("items")
                for (j in 0 until items.length()) {
                    val item = items.getJSONObject(j)
                    text(item, "label"); text(item, "detail"); scalar(item.getJSONObject("value")); text(item, "url"); text(item, "image")
                }
            }
            val actions = request.getJSONArray("actions")
            for (i in 0 until actions.length()) text(actions.getJSONObject(i), "label")
            return lines.joinToString("\n")
        }
    }
}

/** org.json is lenient. Reject extensions and duplicate decoded keys before constructing records. */
private class StrictReplyJson(private val text: String) {
    private var at = 0
    fun check() { space(); require(at < text.length && text[at] == '{'); value(0); space(); require(at == text.length) }
    private fun value(depth: Int) {
        require(depth <= 32)
        space(); require(at < text.length)
        val c = text[at++]
        if (c == '{' || c == '[') {
            val end = if (c == '{') '}' else ']'
            val fields = if (c == '{') HashSet<String>() else null
            space(); if (take(end)) return
            while (true) {
                if (fields != null) {
                    space(); val start = at; string()
                    require(fields.add(JSONArray("[" + text.substring(start, at) + "]").getString(0)))
                    space(); require(take(':'))
                }
                value(depth + 1); space(); if (take(end)) return; require(take(','))
            }
        }
        if (c == '"') { at--; string(); return }
        if (c == 't' && literal("rue") || c == 'f' && literal("alse") || c == 'n' && literal("ull")) return
        at--; val start = at
        while (at < text.length && text[at] in "0123456789+-.eE") at++
        require(text.substring(start, at).matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")))
    }
    private fun string() {
        require(take('"'))
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') return
            require(c >= ' ')
            if (c == '\\') {
                require(at < text.length)
                val escape = text[at++]
                if (escape == 'u') repeat(4) { require(at < text.length && text[at++] in "0123456789abcdefABCDEF") }
                else require(escape in "\"\\/bfnrt")
            }
        }
        error("unterminated string")
    }
    private fun literal(suffix: String): Boolean { if (!text.startsWith(suffix, at)) return false; at += suffix.length; return true }
    private fun take(c: Char): Boolean { if (at == text.length || text[at] != c) return false; at++; return true }
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
}
