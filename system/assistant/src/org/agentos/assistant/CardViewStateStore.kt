package org.agentos.assistant

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal class CardViewStateStore(private val store: MatterStore) {
    fun read(matter: JSONObject): JSONObject {
        val id = matter.getString("matterId")
        val saved = store.get("card_view_states", id)
        return project(id, matter.getJSONObject("baseRequest"), saved)
    }

    fun sourceChanged(matter: JSONObject) {
        val id = matter.getString("matterId")
        val previous = store.get("card_view_states", id) ?: return
        val target = project(id, matter.getJSONObject("baseRequest"), previous)
        merge(target, previous, matter.getJSONObject("baseRequest"), id, null)
        if (previous.has("transferredTo")) target.put("transferredTo", previous.getString("transferredTo"))
        if (PanelProtocol.canonical(target) != PanelProtocol.canonical(previous)) {
            target.put("uiRevision", previous.getLong("uiRevision") + 1)
            store.put("card_view_states", id, target)
        }
    }

    fun apply(input: JSONObject): JSONObject {
        val requestId = input.getString("requestId")
        val hash = PanelProtocol.hash(PanelProtocol.canonical(input))
        if (store.get("panel_receipts", requestId) != null || store.get("panel_updates", requestId) != null) throw IOException("IDEMPOTENCY_CONFLICT")
        val prior = store.cardViewReceipt(requestId)
        if (prior != null) {
            if (prior.getString("hash") != hash) throw IOException("IDEMPOTENCY_CONFLICT")
            return PanelProtocol.copy(prior.getJSONObject("result")).put("replayed", true)
        }
        val matterId = input.getString("matterId")
        // Publication and card-view writes share the runtime lock. Once a source has
        // been replaced, its migration is final: a late write must not acknowledge
        // an edit that the current panel will never display. Keep receipt replay
        // above this fence so a previously committed request remains idempotent.
        if (store.get("display_supersessions", matterId) != null ||
            store.get("card_view_states", matterId)?.has("transferredTo") == true) throw IOException("STALE_SOURCE")
        val matter = store.get("matters", matterId) ?: throw IOException("SOURCE_UNAVAILABLE")
        val state = read(matter)
        if (state.getLong("uiRevision") != input.getLong("expectedUiRevision")) throw IOException("VERSION_CONFLICT")
        val blocks = blocks(matter.getJSONObject("baseRequest"))
        val changes = input.getJSONArray("changes")
        for (i in 0 until changes.length()) {
            val change = changes.getJSONObject(i)
            val id = change.getString("id")
            val block = blocks[id] ?: throw IOException("INVALID_ARGUMENT")
            if (change.getString("kind") == "field") {
                if (block.getString("kind") != "input" || !validField(block, change.get("value"))) throw IOException("INVALID_ARGUMENT")
                state.getJSONObject("ui").getJSONObject("fields").put(id, JSONObject().put("draft", change.get("value"))
                    .put("source", PanelProtocol.copy(block.getJSONObject("value"))).put("needsReview", false).put("edited", true))
            } else {
                val selected = change.getJSONArray("itemIds")
                if (!validSelection(block, selected)) throw IOException("INVALID_ARGUMENT")
                state.getJSONObject("ui").getJSONObject("collections").put(id, JSONObject().put("selectedItemIds", selected).put("edited", true).put("source", selectionSchema(block)))
            }
        }
        state.put("uiRevision", state.getLong("uiRevision") + 1)
        store.put("card_view_states", matter.getString("matterId"), state)
        val result = PanelProtocol.copy(state).put("replayed", false)
        store.put("card_view_receipts", requestId, JSONObject().put("hash", hash).put("result", result))
        return result
    }

    fun migrate(old: JSONObject, next: JSONObject) {
        val prior = store.get("card_view_states", old.getString("matterId")) ?: return
        val targetId = next.getString("matterId")
        if (targetId == old.getString("matterId")) return
        val current = store.get("card_view_states", targetId)
        val target = project(targetId, next.getJSONObject("baseRequest"), current)
        merge(target, prior, next.getJSONObject("baseRequest"), old.getString("matterId"), current)
        target.put("uiRevision", target.getLong("uiRevision") + 1)
        store.put("card_view_states", targetId, target)
        store.put("card_view_states", old.getString("matterId"), prior.put("transferredTo", targetId))
    }

    fun importLegacy(matter: JSONObject?, matterId: String, record: JSONObject): String {
        val legacyUi = record.optJSONObject("ui") ?: return "archived-no-card-state"
        val prior = store.get("card_view_states", matterId)
        val request = matter?.getJSONObject("baseRequest") ?: JSONObject().put("blocks", JSONArray())
        val target = project(matterId, request, prior)
        val source = JSONObject().put("ui", legacyUi)
        merge(target, source, request, matterId, prior)
        target.put("uiRevision", target.getLong("uiRevision") + 1)
        store.put("card_view_states", matterId, target)
        return if (matter == null || (target.optJSONArray("review")?.length() ?: 0) > 0) "imported-with-review" else "imported"
    }

    private fun project(id: String, request: JSONObject, prior: JSONObject?): JSONObject {
        val state = JSONObject().put("matterId", id).put("uiRevision", prior?.optLong("uiRevision") ?: 0)
        val fields = JSONObject()
        val collections = JSONObject()
        val ui = prior?.optJSONObject("ui")
        for ((key, block) in blocks(request)) {
            if (block.getString("kind") == "input") {
                val old = ui?.optJSONObject("fields")?.optJSONObject(key)
                val source = block.getJSONObject("value")
                val edited = old != null && edited(old)
                val draft = if (edited && old!!.has("draft")) old.get("draft") else source.get("value")
                fields.put(key, JSONObject().put("draft", draft).put("source", PanelProtocol.copy(source))
                    .put("needsReview", edited && (old!!.optBoolean("needsReview") || !compatible(old.optJSONObject("source"), source) || !validField(block, draft)))
                    .put("edited", edited))
            }
            if (block.getString("kind") == "choices") {
                val old = ui?.optJSONObject("collections")?.optJSONObject(key)
                val selected = old?.optJSONArray("selectedItemIds") ?: JSONArray()
                collections.put(key, JSONObject().put("selectedItemIds", selected).put("edited", old?.optBoolean("edited", old.has("selectedItemIds")) ?: false)
                    .put("source", old?.optJSONObject("source") ?: selectionSchema(block))
                    .put("needsReview", old?.optBoolean("needsReview") == true || !validSelection(block, selected) || old != null && !selectionCompatible(old, block)))
            }
        }
        state.put("ui", JSONObject().put("fields", fields).put("collections", collections).put("views", views(ui?.optJSONObject("views"))))
        if (prior?.optJSONArray("review") != null) state.put("review", JSONArray(prior.getJSONArray("review").toString()))
        return state
    }

    private fun merge(target: JSONObject, source: JSONObject, request: JSONObject, sourceId: String, existing: JSONObject?) {
        val nextBlocks = blocks(request)
        val review = target.optJSONArray("review") ?: JSONArray().also { target.put("review", it) }
        val previousReview = source.optJSONArray("review")
        val reviewed = HashSet<String>()
        for (i in 0 until review.length()) reviewed.add(PanelProtocol.canonical(review.get(i)))
        if (previousReview != null) for (i in 0 until previousReview.length()) if (reviewed.add(PanelProtocol.canonical(previousReview.get(i)))) review.put(previousReview.get(i))
        val ui = source.optJSONObject("ui") ?: return
        for (scope in arrayOf("fields", "collections")) {
            val entries = ui.optJSONObject(scope) ?: continue
            val keys = entries.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val value = entries.optJSONObject(id) ?: continue
                val isField = scope == "fields"
                if (isField && !value.has("draft") || !isField && !value.has("selectedItemIds")) continue
                if (isField && !edited(value)) continue
                if (!isField && value.has("edited") && !value.optBoolean("edited") && !value.optBoolean("needsReview")) continue
                val block = nextBlocks[id]
                val current = existing?.optJSONObject("ui")?.optJSONObject(scope)?.optJSONObject(id)
                val conflict = current != null && (if (isField) edited(current) else current.optBoolean("edited", current.has("selectedItemIds")))
                val valid = block != null && if (isField) block.optString("kind") == "input" &&
                    compatible(value.optJSONObject("source"), block.getJSONObject("value")) && validField(block, value.get("draft")) && !value.optBoolean("needsReview")
                    else validSelection(block, value.optJSONArray("selectedItemIds")) && selectionCompatible(value, block) && !value.optBoolean("needsReview")
                if (valid && !conflict) {
                    val converted = if (isField) JSONObject().put("draft", value.get("draft")).put("source", block!!.getJSONObject("value"))
                        .put("needsReview", false).put("edited", true)
                    else JSONObject().put("selectedItemIds", value.getJSONArray("selectedItemIds")).put("edited", true).put("source", selectionSchema(block!!))
                    target.getJSONObject("ui").getJSONObject(scope).put(id, converted)
                } else {
                    val preserved = JSONObject().put("kind", if (isField) "field" else "selection").put("id", id).put("sourceMatterId", sourceId)
                        .put("reason", if (conflict) "newer-edit" else "schema-changed").put("needsReview", true)
                    if (isField) preserved.put("value", value.get("draft")).put("source", value.optJSONObject("source") ?: JSONObject.NULL)
                    else preserved.put("itemIds", value.get("selectedItemIds"))
                    if (reviewed.add(PanelProtocol.canonical(preserved))) review.put(preserved)
                    if (isField && block?.optString("kind") == "input" && !conflict)
                        target.getJSONObject("ui").getJSONObject("fields").put(id, JSONObject().put("draft", value.get("draft"))
                            .put("source", value.optJSONObject("source") ?: JSONObject()).put("needsReview", true).put("edited", true))
                    if (!isField && block?.optString("kind") == "choices" && !conflict)
                        target.getJSONObject("ui").getJSONObject("collections").put(id, JSONObject().put("selectedItemIds", value.get("selectedItemIds"))
                            .put("source", value.optJSONObject("source") ?: JSONObject()).put("needsReview", true).put("edited", true))
                }
            }
        }
        val preferences = views(ui.optJSONObject("views"))
        val keys = preferences.keys()
        while (keys.hasNext()) { val id = keys.next(); if (!target.getJSONObject("ui").getJSONObject("views").has(id)) target.getJSONObject("ui").getJSONObject("views").put(id, preferences.get(id)) }
    }

    fun checkDispatch(matter: JSONObject, supplied: JSONObject) {
        val ui = read(matter).getJSONObject("ui")
        for (id in supplied.keys().asSequence()) {
            if (ui.getJSONObject("fields").optJSONObject(id)?.optBoolean("needsReview") == true ||
                ui.getJSONObject("collections").optJSONObject(id)?.optBoolean("needsReview") == true) throw IOException("DRAFT_REVIEW_REQUIRED")
        }
    }

    fun retained(): MutableSet<String> {
        val result = HashSet<String>()
        val states = store.all("card_view_states")
        for (i in 0 until states.length()) {
            val state = states.getJSONObject(i)
            if (state.has("transferredTo")) continue
            val ui = state.getJSONObject("ui")
            val fields = ui.optJSONObject("fields") ?: JSONObject()
            val collections = ui.optJSONObject("collections") ?: JSONObject()
            if ((state.optJSONArray("review")?.length() ?: 0) > 0 || fields.keys().asSequence().any { edited(fields.getJSONObject(it)) } ||
                collections.keys().asSequence().any { collections.getJSONObject(it).optBoolean("edited") || (collections.getJSONObject(it).optJSONArray("selectedItemIds")?.length() ?: 0) > 0 }) result.add(state.getString("matterId"))
        }
        return result
    }

    private fun views(raw: JSONObject?): JSONObject {
        val result = JSONObject()
        if (raw != null) for (id in raw.keys().asSequence()) {
            if (!id.matches(Regex("[a-z0-9-]{1,64}"))) continue
            val value = raw.optJSONObject(id) ?: continue
            val clean = JSONObject()
            for (key in arrayOf("expanded", "collapsed")) if (value.opt(key) is Boolean) clean.put(key, value.get(key))
            if (clean.length() > 0) result.put(id, clean)
        }
        return result
    }

    companion object {
        fun blocks(request: JSONObject): Map<String, JSONObject> {
            val result = LinkedHashMap<String, JSONObject>()
            val blocks = request.getJSONArray("blocks")
            for (i in 0 until blocks.length()) { val block = blocks.getJSONObject(i); result[block.getString("id")] = block }
            return result
        }
        private fun edited(value: JSONObject): Boolean = value.optBoolean("edited") || value.optBoolean("needsReview") ||
            value.has("draft") && (!value.optJSONObject("source").let { it != null && it.has("value") } || PanelProtocol.canonical(value.opt("draft")) != PanelProtocol.canonical(value.optJSONObject("source")?.opt("value")))
        private fun compatible(a: JSONObject?, b: JSONObject): Boolean = a != null && arrayOf("type", "unit", "currency", "scale", "timeZone").all { PanelProtocol.canonical(a.opt(it)) == PanelProtocol.canonical(b.opt(it)) }
        private fun selectionSchema(block: JSONObject): JSONObject {
            val types = JSONObject()
            val items = block.getJSONArray("items")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val type = PanelProtocol.copy(item.getJSONObject("value")); type.remove("value")
                types.put(item.getString("id"), type)
            }
            return JSONObject().put("kind", block.getString("kind")).put("multi", block.getJSONObject("config").optBoolean("multi")).put("itemTypes", types)
        }
        private fun selectionCompatible(value: JSONObject, block: JSONObject): Boolean {
            val source = value.optJSONObject("source") ?: return true
            val next = selectionSchema(block)
            if (source.optString("kind") != next.getString("kind") || source.optBoolean("multi") != next.getBoolean("multi")) return false
            val selected = value.optJSONArray("selectedItemIds") ?: return false
            for (i in 0 until selected.length()) {
                val id = selected.optString(i)
                if (PanelProtocol.canonical(source.optJSONObject("itemTypes")?.opt(id)) != PanelProtocol.canonical(next.getJSONObject("itemTypes").opt(id))) return false
            }
            return true
        }

        fun validField(block: JSONObject, value: Any?): Boolean {
            if (value == null || value == JSONObject.NULL) return true
            val type = block.getJSONObject("value").getString("type")
            return when (type) {
                "number" -> value is Number && value.toDouble().isFinite() && block.getJSONObject("config").let {
                    val number = value.toDouble()
                    val step = it.optDouble("step", 0.0)
                    val steps = if (step > 0) (number - it.optDouble("min", 0.0)) / step else 0.0
                    (!it.has("min") || number >= it.getDouble("min")) && (!it.has("max") || number <= it.getDouble("max")) &&
                        (step <= 0 || kotlin.math.abs(steps - Math.rint(steps)) <= 0.0000001)
                }
                "boolean" -> value is Boolean
                else -> value is String && value.length <= 4000 && (block.getJSONObject("config").optString("editor") != "time" || try {
                    if (value.matches(Regex("[0-2][0-9]:[0-5][0-9]"))) java.time.LocalTime.parse(value) else java.time.OffsetDateTime.parse(value)
                    true
                } catch (_: java.time.format.DateTimeParseException) { false })
            }
        }
        fun validSelection(block: JSONObject, selected: JSONArray?): Boolean {
            if (block.optString("kind") != "choices" || selected == null) return false
            if (!block.getJSONObject("config").optBoolean("multi") && selected.length() > 1) return false
            val items = block.getJSONArray("items")
            val allowed = (0 until items.length()).map { items.getJSONObject(it).getString("id") }.toSet()
            val seen = HashSet<String>()
            for (i in 0 until selected.length()) { val id = selected.get(i); if (id !is String || id !in allowed || !seen.add(id)) return false }
            return true
        }
    }
}
