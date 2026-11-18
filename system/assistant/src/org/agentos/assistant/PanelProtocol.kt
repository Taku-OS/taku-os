package org.agentos.assistant

import java.io.IOException
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal object PanelProtocol {
    const val VERSION = "taku.panels/1"
    const val MAX_INTEGER = 9_007_199_254_740_991L
    private val methods = linkedMapOf(
        "panels/describe" to "", "panels/list" to "limit?,cursor?", "panels/get" to "panelId",
        "panels/pin" to "source,requestId", "panels/unpin" to "panelId,expectedListRevision,requestId",
        "panels/rename" to "panelId,title,expectedMetadataRevision,requestId",
        "panels/move" to "panelId,beforePanelId,expectedListRevision,requestId", "panels/open" to "panelId",
        "panels/navigation" to "", "panels/continue" to "panelId,text,expectedSourceVersion,requestId",
        "card-view/read" to "matterId", "card-view/apply" to "matterId,expectedUiRevision,requestId,changes",
        "matter/read" to "matterId", "matter/dispatch" to "matterId,requestId,actionRef,expectedRevision,values")
    val errors = listOf("INVALID_ARGUMENT", "UNKNOWN_METHOD", "PANEL_NOT_FOUND", "REPLY_INCOMPLETE", "SOURCE_CHANGED",
        "SOURCE_UNAVAILABLE", "SOURCE_THREAD_NOT_FOUND", "SOURCE_ALREADY_PINNED", "PANEL_UPDATE_AMBIGUOUS", "STALE_SOURCE",
        "VERSION_CONFLICT", "LIST_CHANGED", "IDEMPOTENCY_CONFLICT", "MATTER_STORAGE_FULL", "MATTER_UNAVAILABLE", "STORAGE_ERROR", "USER_LOCKED",
        "DEVICE_LOCKED", "RESULT_TOO_LARGE", "RESULT_EXPIRED", "RESULT_BUFFER_FULL", "THREAD_BUSY", "THREAD_QUEUED", "DRAFT_REVIEW_REQUIRED", "PANEL_UPDATE_PENDING", "CARD_STATE_MIGRATION_PENDING")

    @JvmStatic fun parse(method: String, input: JSONObject): JSONObject {
        val fields = methods[method] ?: throw IOException("UNKNOWN_METHOD")
        try {
            if (input.toString().length > AgentGatewayProtocol.MAX_REQUEST) invalid()
            val schema = fields.split(',').filter { it.isNotEmpty() }
            exact(input, schema.map { it.removeSuffix("?") }.toSet())
            for (field in schema.filter { !it.endsWith('?') }) if (!input.has(field)) invalid()
            for (field in arrayOf("panelId", "requestId")) if (input.has(field)) uuid(input, field)
            if (input.has("matterId")) AgentGatewayProtocol.matterIdentity(string(input, "matterId", 101))
            for (field in arrayOf("expectedListRevision", "expectedMetadataRevision", "expectedSourceVersion", "expectedUiRevision", "expectedRevision"))
                if (input.has(field)) number(input, field)
            if (input.has("cursor") && !input.isNull("cursor")) string(input, "cursor", 2048)
            if (input.has("limit") && number(input, "limit") !in 1..100) invalid()
            if (input.has("beforePanelId") && !input.isNull("beforePanelId")) uuid(input, "beforePanelId")
            if (input.has("title")) {
                val title = string(input, "title", 480).trim()
                if (title.codePointCount(0, title.length) !in 1..120) invalid()
                input.put("title", title)
            }
            if (input.has("text")) string(input, "text", 16000)
            if (input.has("source")) source(input.getJSONObject("source"))
            if (input.has("changes")) {
                val changes = input.getJSONArray("changes")
                if (changes.length() !in 1..128) invalid()
                val seen = HashSet<String>()
                for (i in 0 until changes.length()) {
                    val change = changes.getJSONObject(i)
                    val kind = change.getString("kind")
                    exact(change, when (kind) { "field" -> setOf("kind", "id", "value"); "selection" -> setOf("kind", "id", "itemIds"); else -> invalid() })
                    val id = string(change, "id", 64)
                    if (!id.matches(Regex("[a-z0-9-]{1,64}")) || !seen.add(id)) invalid()
                    if (kind == "field") { if (!change.has("value")) invalid() }
                    else change.getJSONArray("itemIds")
                }
            }
            if (method == "matter/dispatch") AgentGatewayProtocol.request("dispatchMatter", input.toString())
            return input
        } catch (failure: IOException) {
            if (failure.message == "UNKNOWN_METHOD") throw failure
            throw IOException("INVALID_ARGUMENT", failure)
        } catch (failure: Exception) { throw IOException("INVALID_ARGUMENT", failure) }
    }

    fun source(value: JSONObject) {
        when (value.optString("kind")) {
            "matter" -> { exact(value, setOf("kind", "matterId")); AgentGatewayProtocol.matterIdentity(string(value, "matterId", 101)) }
            "primary" -> {
                exact(value, setOf("kind", "threadId", "turnId", "replyId", "revision", "contractHash"))
                uuid(value, "threadId"); string(value, "turnId", 160)
                if (!string(value, "replyId", 160).matches(Regex("reply-[a-f0-9]{32}")) || !string(value, "contractHash", 64).matches(Regex("[a-f0-9]{64}"))) invalid()
                number(value, "revision")
            }
            else -> invalid()
        }
    }

    @JvmStatic fun exact(value: JSONObject, allowed: Set<String>) {
        val keys = value.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) invalid()
    }
    @JvmStatic fun uuid(value: JSONObject, key: String): String = string(value, key, 36).also {
        if (!it.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) invalid()
    }
    fun string(value: JSONObject, key: String, max: Int): String {
        val raw = value.get(key)
        if (raw !is String || raw.isBlank() || raw.length > max) invalid()
        return raw
    }
    fun number(value: JSONObject, key: String): Long {
        val raw = value.get(key)
        if (raw !is Number || !raw.toDouble().isFinite() || raw.toDouble() != raw.toLong().toDouble() || raw.toLong() !in 0..MAX_INTEGER) invalid()
        return raw.toLong()
    }
    fun invalid(): Nothing = throw IOException("INVALID_ARGUMENT")
    fun copy(value: JSONObject) = JSONObject(value.toString())
    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun describe(): JSONObject {
        fun text(max: Int) = JSONObject().put("type", "string").put("minLength", 1).put("maxLength", max).put("pattern", "\\S")
        fun integer() = JSONObject().put("type", "integer").put("minimum", 0).put("maximum", MAX_INTEGER)
        fun objectSchema(properties: JSONObject) = JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(properties.keys().asSequence().toList())).put("properties", properties)
        val uuid = text(36).put("pattern", "^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
        val matter = text(101).put("description", "Native lowercase thread UUID followed by / and its existing semantic Matter key")
        val semanticId = text(64).put("pattern", "^[a-z0-9-]{1,64}$")
        val pinSource = JSONObject().put("oneOf", JSONArray()
            .put(objectSchema(JSONObject().put("kind", JSONObject().put("const", "matter")).put("matterId", matter)))
            .put(objectSchema(JSONObject().put("kind", JSONObject().put("const", "primary")).put("threadId", uuid)
                .put("turnId", text(160)).put("replyId", text(38).put("pattern", "^reply-[a-f0-9]{32}$"))
                .put("revision", integer()).put("contractHash", text(64).put("pattern", "^[a-f0-9]{64}$")))))
        val change = JSONObject().put("oneOf", JSONArray()
            .put(objectSchema(JSONObject().put("kind", JSONObject().put("const", "field")).put("id", semanticId)
                .put("value", JSONObject().put("type", JSONArray(listOf("string", "number", "boolean", "null"))))))
            .put(objectSchema(JSONObject().put("kind", JSONObject().put("const", "selection")).put("id", semanticId)
                .put("itemIds", JSONObject().put("type", "array").put("uniqueItems", true).put("items", semanticId)))))
        val schemas = JSONObject()
        for ((method, names) in methods) {
            val properties = JSONObject()
            val required = JSONArray()
            for (name in names.split(',').filter { it.isNotEmpty() }) {
                val field = name.removeSuffix("?")
                if (!name.endsWith('?')) required.put(field)
                properties.put(field, when {
                    field in setOf("panelId", "requestId") -> uuid
                    field == "beforePanelId" -> JSONObject().put("anyOf", JSONArray().put(uuid).put(JSONObject().put("type", "null")))
                    field == "cursor" -> JSONObject().put("anyOf", JSONArray().put(text(2048)).put(JSONObject().put("type", "null")))
                    field == "limit" -> integer().put("minimum", 1).put("maximum", 100).put("default", 50)
                    field.startsWith("expected") -> integer()
                    field == "matterId" -> matter
                    field == "changes" -> JSONObject().put("type", "array").put("minItems", 1).put("maxItems", 128).put("items", change)
                        .put("description", "Each semantic ID occurs once; values must match the current native card schema")
                    field == "source" -> pinSource
                    field == "values" -> JSONObject().put("type", "object").put("description", "Only current native action input IDs and their declared value types are accepted")
                    field == "title" -> text(120).put("description", "Trim first; 1 to 120 Unicode characters after trimming")
                    else -> text(if (field == "text") 16000 else 160)
                })
            }
            schemas.put(method, JSONObject().put("type", "object").put("additionalProperties", false).put("required", required).put("properties", properties))
        }
        val panel = objectSchema(JSONObject().put("panelId", uuid).put("originThreadId", uuid).put("matterId", matter)
            .put("sourceVersion", integer()).put("metadataRevision", integer()).put("title", text(120)).put("position", integer())
            .put("createdAt", integer()).put("updatedAt", integer()))
        return JSONObject().put("version", VERSION).put("methods", schemas).put("panelSchema", panel).put("pinSourceSchema", pinSource)
            .put("navigation", JSONObject().put("available", "Ordered Panel IDs in the live desktop projection")
                .put("visible", "Panel IDs with mounted desktop roots").put("selectedPanelId", "Settled desktop panel ID, or null")
                .put("open", "Requests HomeActivity navigation; accepted does not mean the pager has settled"))
            .put("limits", JSONObject().put("requestCharacters", 48000).put("defaultListLimit", 50).put("titleUnicodeCharacters", 120)
                .put("inlineResultBytes", 262144).put("resultBytes", 8388608).put("resultBuffers", 8).put("totalResultBytes", 16777216).put("resultLifetimeSeconds", 30))
            .put("errors", JSONArray(errors)).put("notifications", "content://org.agentos.assistant.open-access/panels")
    }
}
