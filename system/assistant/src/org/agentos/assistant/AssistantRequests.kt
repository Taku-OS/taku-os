package org.agentos.assistant

import android.net.Uri
import android.os.Bundle
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object AssistantRequests {
    data class Request(val owner: String, val key: String, val method: String, val params: JSONObject) {
        val secret: Boolean get() = when (method) {
            "attestation/generate", "account/chatgptAuthTokens/refresh", "mcpServer/elicitation/request" -> true
            "item/tool/requestUserInput" -> questions().any { it.optBoolean("isSecret") }
            else -> false
        }
        fun questions(): List<JSONObject> {
            val rows = params.getJSONArray("questions")
            if (rows.length() !in 1..32) throw IOException("REQUEST_UNAVAILABLE")
            return List(rows.length(), rows::getJSONObject)
        }
    }

    private val auxiliary = setOf("account/chatgptAuthTokens/refresh", "attestation/generate",
        "item/tool/requestUserInput", "item/tool/call", "mcpServer/elicitation/request",
        "applyPatchApproval", "execCommandApproval", "item/commandExecution/requestApproval",
        "item/fileChange/requestApproval", "item/permissions/requestApproval")
    private val legacyApproval = setOf("applyPatchApproval", "execCommandApproval")

    /**
     * [trusted] callers are the settings app and the on-device Agent. Only they may read or
     * answer a request that carries or asks for a secret; a third-party app gets
     * `SECURE_INPUT_REQUIRED` for those.
     */
    @JvmStatic
    fun execute(method: String, input: JSONObject, extras: Bundle?, trusted: Boolean): JSONObject {
        val fields = when (method) {
            "requests/list" -> setOf("threadId")
            "requests/read", "requests/cancel" -> setOf("threadId", "key")
            "requests/respond" -> setOf("threadId", "key", "response")
            "requests/submit" -> setOf("threadId", "key", "values")
            else -> throw IOException("UNKNOWN_METHOD")
        }
        PanelProtocol.exact(input, fields)
        val thread = input.opt("threadId") as? String ?: throw IOException("INVALID_ARGUMENT")
        if (thread.isNotEmpty()) AgentGatewayProtocol.requireId(input, "threadId")
        val key = if (input.has("key")) input.getString("key").also { AgentGatewayProtocol.requireId(input, "key") } else ""
        return when (method) {
            "requests/list" -> JSONObject().put("requests", JSONArray(visible(thread).map(::description)))
            "requests/read" -> find(thread, key).let { request ->
                if (request.secret && !trusted) throw IOException("SECURE_INPUT_REQUIRED")
                form(request)
            }
            "requests/submit" -> {
                val request = find(thread, key)
                if (request.secret && !trusted) throw IOException("SECURE_INPUT_REQUIRED")
                val values = input.optJSONObject("values") ?: JSONObject()
                // Secret fields may arrive as a JSON object in a read-only descriptor instead.
                AssistantAccounts.credentialText(extras)?.let { text ->
                    val secrets = try { JSONObject(text) } catch (_: org.json.JSONException) { throw IOException("INVALID_ARGUMENT") }
                    for (id in secrets.keys()) {
                        if (values.has(id)) throw IOException("KEY_SOURCE_AMBIGUOUS")
                        values.put(id, secrets.get(id))
                    }
                }
                respond(request, answer(request, values), secure = true)
            }
            "requests/cancel" -> cancel(find(thread, key))
            else -> respond(find(thread, key), input.getJSONObject("response"), secure = trusted)
        }
    }

    private fun field(id: String, label: String, secret: Boolean = false, required: Boolean = true, type: String = "string",
        multiline: Boolean = false, options: List<String> = emptyList()): JSONObject = JSONObject().put("id", id)
        .put("label", label).put("secret", secret).put("required", required).put("type", type).put("multiline", multiline)
        .put("options", JSONArray(options))

    private fun kind(method: String): String = when (method) {
        "attestation/generate" -> "attestation"
        "account/chatgptAuthTokens/refresh" -> "accountAuthorization"
        "mcpServer/elicitation/request" -> "mcp"
        "item/tool/requestUserInput" -> "questions"
        "item/tool/call" -> "toolResult"
        "item/permissions/requestApproval" -> "permissionApproval"
        "item/commandExecution/requestApproval", "execCommandApproval" -> "commandApproval"
        "item/fileChange/requestApproval", "applyPatchApproval" -> "fileChangeApproval"
        else -> "action"
    }

    private fun elicitation(request: Request): Pair<JSONObject, Set<String>> {
        val schema = request.params.optJSONObject("requestedSchema")
        val properties = schema?.optJSONObject("properties") ?: JSONObject()
        if (properties.length() > 32) throw IOException("REQUEST_UNAVAILABLE")
        val required = schema?.optJSONArray("required") ?: JSONArray()
        return properties to (0 until required.length()).map(required::getString).toSet()
    }

    /**
     * What a client needs to present one pending request: its [kind], the text to show, an
     * optional page to open, and the fields to collect. A request with no fields is an approval.
     */
    fun form(request: Request): JSONObject {
        val params = request.params
        val details = ArrayList<String>()
        val fields = ArrayList<JSONObject>()
        var page = ""
        when (request.method) {
            "attestation/generate" -> fields.add(field("token", "Attestation token", secret = true))
            "account/chatgptAuthTokens/refresh" -> {
                fields.add(field("accessToken", "Access token", secret = true))
                fields.add(field("chatgptAccountId", "ChatGPT account ID", secret = true))
            }
            "item/tool/requestUserInput" -> for (question in request.questions()) {
                val id = question.getString("id")
                val options = question.optJSONArray("options")
                fields.add(field(id, question.optString("question", id), secret = question.optBoolean("isSecret"),
                    options = if (options == null) emptyList() else List(options.length()) { options.getJSONObject(it).getString("label") }))
            }
            "item/tool/call" -> {
                details.add(params.optString("tool"))
                fields.add(field("text", "Tool result", multiline = true))
            }
            "mcpServer/elicitation/request" -> {
                details.add(params.optString("message"))
                if (params.optString("mode") == "url") {
                    val uri = Uri.parse(params.optString("url"))
                    if (uri.scheme == "https" && uri.host != null && uri.userInfo == null) page = uri.toString()
                }
                val (properties, mandatory) = elicitation(request)
                for (id in properties.keys()) {
                    val property = properties.getJSONObject(id)
                    val type = property.optString("type").takeIf { it in setOf("boolean", "integer", "number") } ?: "string"
                    fields.add(field(id, property.optString("title", id), secret = property.optString("format") == "password",
                        required = id in mandatory, type = type))
                }
            }
            else -> {
                details.add(params.optString("reason"))
                details.add(params.optString("command"))
                params.optJSONObject("permissions")?.let { details.add(it.toString(2)) }
            }
        }
        return description(request).put("kind", kind(request.method)).put("url", page)
            .put("details", JSONArray(details.filter(String::isNotBlank))).put("fields", JSONArray(fields))
    }

    /** The response [respond] expects, built from the [form]'s field [values]. */
    private fun answer(request: Request, values: JSONObject): JSONObject {
        fun text(id: String): String {
            if (!values.has(id)) return ""
            val value = values.opt(id) as? String ?: throw IOException("INVALID_ARGUMENT")
            if (value.length > 16_000) throw IOException("INVALID_ARGUMENT")
            return value
        }
        val fields = form(request).getJSONArray("fields")
        val known = (0 until fields.length()).map { fields.getJSONObject(it).getString("id") }
        PanelProtocol.exact(values, known.toSet())
        return when (request.method) {
            "attestation/generate", "account/chatgptAuthTokens/refresh" ->
                JSONObject().also { answer -> known.forEach { answer.put(it, text(it)) } }
            "item/tool/requestUserInput" -> {
                val answers = JSONObject()
                known.forEach { answers.put(it, JSONObject().put("answers", JSONArray().put(text(it).trim()))) }
                JSONObject().put("answers", answers)
            }
            "item/tool/call" -> JSONObject().put("text", text("text"))
            "mcpServer/elicitation/request" -> {
                val (properties, mandatory) = elicitation(request)
                val content = JSONObject()
                for (id in known) {
                    val value = text(id)
                    if (value.isEmpty() && id !in mandatory) continue
                    if (value.isEmpty()) throw IOException("INVALID_ARGUMENT")
                    try {
                        content.put(id, when (properties.getJSONObject(id).optString("type")) {
                            "boolean" -> value.toBooleanStrict()
                            "integer" -> value.toLong()
                            "number" -> value.toDouble()
                            else -> value
                        })
                    } catch (_: IllegalArgumentException) { throw IOException("INVALID_ARGUMENT") }
                }
                JSONObject().put("content", content)
            }
            else -> JSONObject().put("decision", "accept")
        }
    }

    private fun description(request: Request): JSONObject = JSONObject().put("threadId", request.owner)
        .put("key", request.key).put("method", request.method).put("secure", request.secret)
        // `inline` requests are the question cards a conversation answers in place.
        .put("inline", request.owner.isNotEmpty() && !request.secret && request.method in AgentGatewayProtocol.QUESTIONS)

    fun visible(thread: String): List<Request> {
        val pending = NativeClient.state(thread).optJSONArray("requests") ?: return emptyList()
        return buildList {
            for (i in 0 until pending.length()) {
                val value = pending.optJSONObject(i) ?: continue
                val params = value.optJSONObject("params") ?: continue
                val owner = params.optString("threadId")
                if (owner.isNotEmpty() && owner != thread) continue
                val method = value.optString("method")
                if (method !in auxiliary && method !in AgentGatewayProtocol.QUESTIONS) continue
                add(Request(owner, value.getString("key"), method, params))
            }
        }
    }

    fun find(thread: String, key: String): Request = visible(thread).firstOrNull { it.key == key }
        ?: throw IOException("REQUEST_EXPIRED")

    fun respond(request: Request, response: JSONObject, secure: Boolean): JSONObject {
        if (request.secret && !secure) throw IOException("SECURE_INPUT_REQUIRED")
        val normalized = when (request.method) {
            "item/tool/call" -> {
                PanelProtocol.exact(response, setOf("text"))
                val text = response.opt("text") as? String ?: throw IOException("INVALID_ARGUMENT")
                if (text.isBlank() || text.length > 16_000) throw IOException("INVALID_ARGUMENT")
                JSONObject().put("success", true).put("contentItems", JSONArray()
                    .put(JSONObject().put("type", "inputText").put("text", text)))
            }
            "item/tool/requestUserInput" -> questionAnswers(request, response)
            "item/permissions/requestApproval" -> {
                requireChoice(response, "accept")
                JSONObject().put("permissions", request.params.getJSONObject("permissions")).put("scope", "turn")
            }
            in legacyApproval -> { requireChoice(response, "accept"); JSONObject().put("decision", "approved") }
            "item/commandExecution/requestApproval", "item/fileChange/requestApproval" -> {
                requireChoice(response, "accept"); JSONObject().put("decision", "accept")
            }
            "attestation/generate", "account/chatgptAuthTokens/refresh" -> {
                val fields = if (request.method == "attestation/generate") setOf("token") else setOf("accessToken", "chatgptAccountId")
                PanelProtocol.exact(response, fields)
                for (field in fields) {
                    val value = response.opt(field) as? String ?: throw IOException("INVALID_ARGUMENT")
                    if (value.isBlank() || value.length > 16_000) throw IOException("INVALID_ARGUMENT")
                }
                response
            }
            "mcpServer/elicitation/request" -> {
                PanelProtocol.exact(response, setOf("content"))
                JSONObject().put("action", "accept").put("content", response.getJSONObject("content"))
            }
            else -> response // Phone question and approval schemas are validated atomically by the broker.
        }
        return finish(request, "respond", normalized)
    }

    private fun questionAnswers(request: Request, response: JSONObject): JSONObject {
        PanelProtocol.exact(response, setOf("answers"))
        val answers = response.getJSONObject("answers")
        val fields = request.questions().map { it.getString("id") }.toSet()
        PanelProtocol.exact(answers, fields)
        for (id in fields) {
            val item = answers.getJSONObject(id)
            PanelProtocol.exact(item, setOf("answers"))
            val values = item.getJSONArray("answers")
            if (values.length() !in 1..32) throw IOException("INVALID_ARGUMENT")
            for (i in 0 until values.length()) {
                val value = values.opt(i) as? String ?: throw IOException("INVALID_ARGUMENT")
                if (value.isBlank() || value.length > 4096) throw IOException("INVALID_ARGUMENT")
            }
        }
        return response
    }

    private fun requireChoice(response: JSONObject, choice: String) {
        PanelProtocol.exact(response, setOf("decision"))
        if (response.opt("decision") != choice) throw IOException("INVALID_ARGUMENT")
    }

    fun cancel(request: Request): JSONObject {
        val response = when (request.method) {
            "account/chatgptAuthTokens/refresh", "attestation/generate", "phone/askUser" ->
                return finish(request, "reject", null)
            "item/tool/requestUserInput" -> JSONObject().put("answers", JSONObject())
            "mcpServer/elicitation/request" -> JSONObject().put("action", "cancel").put("content", JSONObject.NULL)
            "item/permissions/requestApproval" -> JSONObject().put("permissions", JSONObject()).put("scope", "turn")
            "item/tool/call" -> JSONObject().put("success", false).put("contentItems", JSONArray())
            else -> JSONObject().put("decision", if (request.method in legacyApproval) "abort" else "cancel")
        }
        return finish(request, "respond", response)
    }

    private fun finish(request: Request, operation: String, response: JSONObject?): JSONObject {
        find(request.owner, request.key)
        val input = JSONObject().put("op", operation).put("key", request.key)
        if (response != null) input.put("response", response)
        try { NativeClient.exchange(input) }
        catch (_: Exception) {
            if (visible(request.owner).none { it.key == request.key }) throw IOException("REQUEST_EXPIRED")
            throw IOException("REQUEST_RESPONSE_FAILED")
        }
        return JSONObject().put("key", request.key).put("resolved", true)
    }
}
