package org.agentos.assistant

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

class AssistantRequestActivity : Activity() {
    private lateinit var ui: AgentSettingsUi
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private var thread = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thread = intent.getStringExtra("threadId").orEmpty()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val theme = DrawerTheme(this, dark)
        ui = AgentSettingsUi(this, theme)
        body = ui.column().apply { setBackgroundColor(theme.surface) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(theme.surface)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(ui.dp(20), bars.top + ui.dp(16), ui.dp(20), bars.bottom + ui.dp(16))
                insets
            }
            addView(body)
        })
        button(body, "Back to chat", false, ::finish)
        ui.add(body, ui.text("Pending requests", 26, true), 20)
        status = ui.text("", 14, false)
        ui.add(body, status, 10)
        content = ui.column()
        ui.add(body, content, 8)
        val key = intent.getStringExtra("key").orEmpty()
        if (key.isEmpty()) readList() else openForm(key)
    }

    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, action: () -> Unit) {
        ui.add(parent, ui.button(label, primary) { if (!busy) action() }, 12)
    }

    private fun field(label: String, secret: Boolean): EditText = ui.input(label, InputType.TYPE_CLASS_TEXT or
        if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        if (secret) setSingleLine()
        ui.add(content, this, 8)
    }

    private fun label(text: String) {
        if (text.isNotBlank()) ui.add(content, ui.text(text, 15, true), 12)
    }

    private fun <T> work(operation: () -> T, ready: (T) -> Unit) {
        if (busy) return
        busy = true
        status.text = "Working…"
        NativeClient.io.execute {
            val result = runCatching(operation)
            NativeClient.main.post {
                busy = false
                if (isFinishing || isDestroyed) return@post
                try {
                    status.text = ""
                    ready(result.getOrThrow())
                } catch (failure: Exception) {
                    status.text = when (failure.message) {
                        "REQUEST_EXPIRED" -> "This request has ended. Refresh the pending requests."
                        "INVALID_ARGUMENT" -> "Check the response fields."
                        else -> "The request could not be completed."
                    }
                }
            }
        }
    }

    private fun readList() = work({ AssistantRequests.nativeOnly(thread) }) { requests ->
        content.removeAllViews()
        if (requests.isEmpty()) label("No pending authorization requests")
        for (request in requests) button(content, title(request), action = { openForm(request.key) })
        button(content, "Refresh", action = ::readList)
    }

    private fun openForm(key: String) = work({ AssistantRequests.find(thread, key) }, ::renderForm)

    private fun title(request: AssistantRequests.Request): String = when (request.method) {
        "attestation/generate" -> "Device attestation"
        "account/chatgptAuthTokens/refresh" -> "External account authorization"
        "mcpServer/elicitation/request" -> "MCP request"
        "item/tool/requestUserInput" -> "Answer requested questions"
        "item/tool/call" -> "Extension tool result"
        "item/permissions/requestApproval" -> "Permission approval"
        "item/commandExecution/requestApproval", "execCommandApproval" -> "Command approval"
        "item/fileChange/requestApproval", "applyPatchApproval" -> "File change approval"
        else -> "Pending action"
    }

    private fun renderForm(request: AssistantRequests.Request) {
        content.removeAllViews()
        label(title(request))
        val fields = LinkedHashMap<String, EditText>()
        val response: () -> JSONObject = when (request.method) {
            "attestation/generate", "account/chatgptAuthTokens/refresh" -> {
                val attestation = request.method == "attestation/generate"
                label(if (attestation) "Submit an existing attestation token. This ROM cannot issue one automatically." else "Submit valid authorization for the externally managed account.")
                val labels = if (attestation) mapOf("token" to "Attestation token") else
                    mapOf("accessToken" to "Access token", "chatgptAccountId" to "ChatGPT account ID")
                for ((id, text) in labels) fields[id] = field(text, true)
                ({ JSONObject().also { answer -> fields.forEach { (id, view) -> answer.put(id, view.text.toString()) } } })
            }
            "item/tool/requestUserInput" -> questionForm(request, fields)
            "item/tool/call" -> {
                label(request.params.optString("tool"))
                val input = field("Tool result", false)
                ({ JSONObject().put("text", input.text.toString()) })
            }
            "mcpServer/elicitation/request" -> elicitationForm(request, fields)
            else -> {
                label(request.params.optString("reason"))
                label(request.params.optString("command"))
                request.params.optJSONObject("permissions")?.let { label(it.toString(2)) }
                ({ JSONObject().put("decision", "accept") })
            }
        }
        button(content, "Submit response", true) {
            try {
                val answer = response()
                work({ AssistantRequests.respond(request, answer, secure = true) }) { finish() }
            } catch (_: Exception) { status.text = "Check the response fields." }
        }
        button(content, "Cancel request") { work({ AssistantRequests.cancel(request) }) { finish() } }
        button(content, "All requests", action = ::readList)
    }

    private fun questionForm(request: AssistantRequests.Request, fields: MutableMap<String, EditText>): () -> JSONObject {
        for (question in request.questions()) {
            val id = question.getString("id")
            label(question.optString("question"))
            val input = field(question.optString("question", id), question.optBoolean("isSecret"))
            fields[id] = input
            val options = question.optJSONArray("options") ?: continue
            for (i in 0 until options.length()) {
                val option = options.getJSONObject(i).getString("label")
                button(content, option) { input.setText(option) }
            }
        }
        return {
            val answers = JSONObject()
            fields.forEach { (id, view) -> answers.put(id, JSONObject().put("answers", JSONArray().put(view.text.toString().trim()))) }
            JSONObject().put("answers", answers)
        }
    }

    private fun elicitationForm(request: AssistantRequests.Request, fields: MutableMap<String, EditText>): () -> JSONObject {
        val params = request.params
        label(params.optString("message"))
        if (params.optString("mode") == "url") {
            val uri = Uri.parse(params.optString("url"))
            if (uri.scheme == "https" && uri.host != null && uri.userInfo == null) button(content, "Open authorization page") {
                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                catch (_: android.content.ActivityNotFoundException) { status.text = "A browser is required to open this page." }
            }
        }
        val schema = params.optJSONObject("requestedSchema")
        val properties = schema?.optJSONObject("properties") ?: JSONObject()
        val required = schema?.optJSONArray("required") ?: JSONArray()
        val mandatory = (0 until required.length()).map(required::getString).toSet()
        if (properties.length() > 32) throw IOException("REQUEST_UNAVAILABLE")
        for (id in properties.keys()) {
            val property = properties.getJSONObject(id)
            fields[id] = field(property.optString("title", id), property.optString("format") == "password")
        }
        return {
            val value = JSONObject()
            for ((id, view) in fields) {
                val text = view.text.toString()
                if (text.isEmpty() && id !in mandatory) continue
                if (text.isEmpty()) throw IOException("INVALID_ARGUMENT")
                val typed = when (properties.getJSONObject(id).optString("type")) {
                    "boolean" -> text.toBooleanStrict()
                    "integer" -> text.toLong()
                    "number" -> text.toDouble()
                    else -> text
                }
                value.put(id, typed)
            }
            JSONObject().put("content", value)
        }
    }
}
