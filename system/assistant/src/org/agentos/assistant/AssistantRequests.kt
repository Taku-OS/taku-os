package org.agentos.assistant

import android.content.Context
import android.content.Intent
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

    @JvmStatic
    fun execute(context: Context, method: String, input: JSONObject): JSONObject {
        val fields = when (method) {
            "requests/list" -> setOf("threadId")
            "requests/open", "requests/cancel" -> setOf("threadId", "key")
            "requests/respond" -> setOf("threadId", "key", "response")
            else -> throw IOException("UNKNOWN_METHOD")
        }
        PanelProtocol.exact(input, fields)
        val thread = input.opt("threadId") as? String ?: throw IOException("INVALID_ARGUMENT")
        if (thread.isNotEmpty()) AgentGatewayProtocol.requireId(input, "threadId")
        val key = if (input.has("key")) input.getString("key").also { AgentGatewayProtocol.requireId(input, "key") } else ""
        return when (method) {
            "requests/list" -> JSONObject().put("requests", JSONArray(visible(thread).map(::description)))
            "requests/open" -> {
                if (key.isNotEmpty()) find(thread, key)
                context.startActivity(Intent(context, AssistantRequestActivity::class.java)
                    .putExtra("threadId", thread).putExtra("key", key).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                JSONObject().put("opened", true)
            }
            "requests/cancel" -> cancel(find(thread, key))
            else -> respond(find(thread, key), input.getJSONObject("response"), secure = false)
        }
    }

    private fun description(request: Request): JSONObject = JSONObject().put("threadId", request.owner)
        .put("key", request.key).put("method", request.method).put("secure", request.secret)

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

    fun nativeOnly(thread: String): List<Request> = visible(thread).filter {
        it.owner.isEmpty() || it.secret || it.method !in AgentGatewayProtocol.QUESTIONS
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
