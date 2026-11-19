package org.agentos.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.agentos.lab.ComposerInput
import org.json.JSONArray
import org.json.JSONObject

internal object AssistantManagement {
    private val fields = mapOf(
        "thread/name/get" to setOf("threadId"),
        "thread/name/set" to setOf("threadId", "name"),
        "thread/goal/get" to setOf("threadId"),
        "thread/goal/set" to setOf("threadId", "objective", "tokenBudget", "status"),
        "thread/goal/clear" to setOf("threadId"),
        "thread/queue/list" to setOf("threadId", "cursor"),
        "thread/queue/submit" to setOf("threadId", "requestId", "text"),
        "thread/queue/start" to setOf("threadId", "queuedSubmissionId"),
        "thread/queue/reorder" to setOf("threadId", "queuedSubmissionIds"),
        "thread/queue/delete" to setOf("threadId", "queuedSubmissionId"),
        "sessions/removals" to setOf("action", "threadId", "target", "requestId"),
        "taskFiles/list" to setOf("path", "taskId", "threadId"),
        "taskFiles/read" to setOf("path", "taskId", "threadId"),
        "taskFiles/write" to setOf("path", "text"),
        "skills/read" to setOf("threadId"),
        "skills/set-enabled" to setOf("path", "enabled"),
        "skills/select" to setOf("threadId", "path"),
        "skills/community/catalog" to setOf("sourceId"),
        "skills/community/detail" to setOf("sourceId", "name", "commit"),
        "skills/community/install" to setOf("sourceId", "name", "commit"),
        "skills/community/add" to setOf("repo", "path", "private"),
        "skills/community/uninstall" to setOf("path"),
        "skills/community/check-update" to setOf("path"),
        "skills/community/upgrade" to setOf("path", "commit"),
        "skills/community/rollback" to setOf("path"),
        "skills/credentials/status" to setOf("repo"),
        "skills/credentials/open" to setOf("repo"),
        "skills/credentials/remove" to setOf("repo"),
        "harness/read" to setOf("threadId"),
        "harness/refresh" to setOf("threadId"),
        "plugin/list" to emptySet(),
        "plugin/install" to setOf("pluginName", "marketplacePath"),
        "plugin/uninstall" to setOf("pluginId"),
        "plugin/set-enabled" to setOf("pluginId", "enabled"),
        "mcpServerStatus/list" to setOf("cursor"),
        "mcpServer/tool/call" to setOf("threadId", "server", "tool", "arguments"),
        "app/list" to setOf("cursor"),
        "config/mcpServer/reload" to emptySet(),
    )
    private val repoPattern = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
    private val namePattern = Regex("[A-Za-z0-9_.@-]{1,256}")
    private val commitPattern = Regex("[a-fA-F0-9]{40}")
    private val queueIdPattern = Regex("[A-Za-z0-9_.:-]{1,160}")

    @JvmStatic
    fun parse(arg: String?): JSONObject = try {
        if (arg.isNullOrEmpty() || arg.length > AgentGatewayProtocol.MAX_REQUEST) throw IOException("INVALID_ARGUMENT")
        JSONObject(arg)
    } catch (_: org.json.JSONException) {
        throw IOException("INVALID_ARGUMENT")
    }

    @JvmStatic
    fun execute(context: Context, input: JSONObject): JSONObject {
        val operation = text(input, "operation", 64)
        val allowed = fields[operation] ?: throw IOException("UNSUPPORTED_MANAGEMENT_OPERATION")
        val params = JSONObject()
        for (key in input.keys()) {
            if (key == "operation") continue
            if (key !in allowed) throw IOException("UNSUPPORTED_PARAMETER")
            params.put(key, input.get(key))
        }
        val thread = if (params.has("threadId")) {
            val id = text(params, "threadId", 36)
            if (!AgentGatewayProtocol.isUuid(id)) throw IOException("INVALID_THREAD_ID")
            id
        } else null
        if (thread == null && (operation.startsWith("thread/") || operation.startsWith("harness/") ||
                operation == "skills/select" || operation == "mcpServer/tool/call"))
            throw IOException("INVALID_THREAD_ID")
        if (params.has("cursor")) text(params, "cursor", 1024)

        val result = when (operation) {
            "sessions/removals" -> NativeClient.exchange(params.put("op", "sessionRemovals"))
            "thread/name/get" -> readTitle(thread!!)
            "thread/name/set" -> {
                val name = text(params, "name", AgentGatewayProtocol.MAX_REQUEST).trim()
                if (name.length > 120) throw IOException("INVALID_ARGUMENT")
                TaskRequests.control(operation, params.put("name", name))
            }
            "thread/goal/set" -> {
                val status = text(params, "status", 16)
                if (status !in setOf("active", "paused")) throw IOException("INVALID_GOAL_STATUS")
                if (status == "active" || params.has("objective")) text(params, "objective", 16_000)
                if (params.has("tokenBudget")) {
                    val budget = params.get("tokenBudget")
                    if (budget != JSONObject.NULL && ((budget !is Int && budget !is Long) || (budget as Number).toLong() <= 0))
                        throw IOException("INVALID_TOKEN_BUDGET")
                }
                NativeClient.rpcSync(operation, params.put("origin", "user"))
            }
            "thread/goal/clear" -> NativeClient.rpcSync(operation, params.put("origin", "user"))
            "thread/queue/submit" -> {
                val requestId = text(params, "requestId", 36)
                if (!AgentGatewayProtocol.isUuid(requestId)) throw IOException("INVALID_REQUEST_ID")
                val message = text(params, "text", 16_000)
                val state = NativeClient.state(thread!!)
                if (!state.optBoolean("ready")) throw IOException("THREAD_UNAVAILABLE")
                val active = (state.opt("activeTurnId") as? String)?.isNotEmpty() == true
                val args = JSONObject().put("threadId", thread).put("clientUserMessageId", requestId)
                    .put("input", ComposerInput.text(message))
                NativeClient.rpcSync(if (active) "thread/queue/add" else "turn/start", args)
                    .put("submission", if (active) "queued" else "started")
            }
            "thread/queue/reorder" -> {
                val ids = params.optJSONArray("queuedSubmissionIds") ?: throw IOException("INVALID_QUEUE_IDS")
                val unique = HashSet<String>()
                if (ids.length() !in 1..500) throw IOException("INVALID_QUEUE_IDS")
                for (i in 0 until ids.length()) {
                    val id = ids.opt(i) as? String ?: throw IOException("INVALID_QUEUE_IDS")
                    if (!queueIdPattern.matches(id) || !unique.add(id)) throw IOException("INVALID_QUEUE_IDS")
                }
                NativeClient.rpcSync(operation, params)
            }
            "thread/queue/start", "thread/queue/delete" -> {
                if (operation.endsWith("delete") || params.has("queuedSubmissionId"))
                    match(params, "queuedSubmissionId", 160, queueIdPattern)
                NativeClient.rpcSync(operation, params)
            }
            "thread/goal/get", "thread/queue/list" -> NativeClient.rpcSync(operation, params)
            "taskFiles/list", "taskFiles/read", "taskFiles/write" -> files(context, operation, params, thread)
            "skills/credentials/status", "skills/credentials/remove" ->
                credential(repository(params), operation.substringAfterLast('/'))
            "skills/credentials/open" -> {
                val repo = repository(params)
                context.startActivity(Intent(context, SkillRepositoryCredentialActivity::class.java)
                    .putExtra("repo", repo).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                JSONObject().put("opened", true)
            }
            "harness/read", "harness/refresh" -> {
                requireCurrentThread(context, thread!!)
                val prefs = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
                TaskRequests.control(operation, params.put("responseStyle", prefs.getString("responseStyle", "concise"))
                    .put("includeScreenshot", prefs.getBoolean("includeScreenshot", false)))
            }
            "plugin/list" -> pluginList()
            "plugin/install", "plugin/uninstall", "plugin/set-enabled" -> changePlugin(operation, params)
            "mcpServerStatus/list" -> NativeClient.rpcSync(operation, params.put("limit", 100).put("detail", "toolsAndAuthOnly"))
            "mcpServer/tool/call" -> callMcpTool(thread!!, params)
            "app/list" -> NativeClient.rpcSync(operation, params.put("limit", 100))
            "config/mcpServer/reload" -> NativeClient.rpcSync(operation, params)
            "skills/select" -> {
                text(params, "path", 4096)
                requireCurrentThread(context, thread!!)
                TaskRequests.control(operation, params)
            }
            else -> skills(operation, params, thread)
        }
        return AgentGatewayProtocol.clean(result, 0) as JSONObject
    }

    private fun readTitle(threadId: String): JSONObject {
        val params = JSONObject().put("threadId", threadId).put("includeTurns", false)
        val thread = NativeClient.rpcSync("thread/read", params).optJSONObject("thread") ?: throw IOException("THREAD_UNAVAILABLE")
        if (thread.optString("id") != threadId) throw IOException("THREAD_UNAVAILABLE")
        val name = thread.opt("name") as? String
        return JSONObject().put("threadId", threadId).put("name", name ?: JSONObject.NULL)
            .put("title", AgentConversation.title(thread))
    }

    private fun callMcpTool(threadId: String, params: JSONObject): JSONObject {
        val server = text(params, "server", 256)
        val tool = text(params, "tool", 256)
        if (params.optJSONObject("arguments") == null) throw IOException("INVALID_ARGUMENT")

        val scope = JSONObject().put("threadId", threadId).put("serverName", server).put("detail", "toolsAndAuthOnly")
        val status = try {
            NativeClient.rpcSync("mcpServerStatus/list", scope)
        } catch (failure: IOException) {
            if (failure.message != "thread not found: $threadId") throw failure
            // Selecting a saved conversation reads its history without loading its MCP connection.
            NativeClient.rpcSync("thread/resume", JSONObject().put("threadId", threadId).put("excludeTurns", true))
            NativeClient.rpcSync("mcpServerStatus/list", scope)
        }
        val services = status.getJSONArray("data")
        val listed = (0 until services.length()).any { index ->
            val service = services.getJSONObject(index)
            service.optString("name") == server && service.optJSONObject("tools")?.optJSONObject(tool) != null
        }
        if (!listed) throw IOException("MCP_TOOL_UNAVAILABLE")
        return NativeClient.rpcSync("mcpServer/tool/call", params)
    }

    private fun requireCurrentThread(context: Context, thread: String) {
        when (PanelNativeServices(context).threadState(thread)) {
            "thread-unavailable" -> throw IOException("THREAD_UNAVAILABLE")
            "offline" -> throw IOException("RUNTIME_UNAVAILABLE")
        }
    }

    private fun skills(operation: String, params: JSONObject, thread: String?): JSONObject {
        when (operation) {
            "skills/read" -> if (thread == null) params.put("threadId", "")
            "skills/set-enabled" -> {
                text(params, "path", 4096)
                boolean(params, "enabled")
            }
            "skills/community/uninstall", "skills/community/check-update", "skills/community/rollback" -> text(params, "path", 4096)
            "skills/community/catalog" -> text(params, "sourceId", 160)
            "skills/community/detail", "skills/community/install" -> {
                text(params, "sourceId", 160)
                match(params, "name", 256, namePattern)
                match(params, "commit", 40, commitPattern)
            }
            "skills/community/upgrade" -> {
                text(params, "path", 4096)
                match(params, "commit", 40, commitPattern)
            }
            "skills/community/add" -> {
                repository(params)
                if (params.has("path")) text(params, "path", 512, empty = true)
                if (params.has("private")) boolean(params, "private")
            }
            else -> throw IOException("UNSUPPORTED_MANAGEMENT_OPERATION")
        }
        return TaskRequests.control(operation, params)
    }

    private fun files(context: Context, operation: String, params: JSONObject, thread: String?): JSONObject {
        if (params.has("taskId")) {
            val id = text(params, "taskId", 20)
            if (!TaskDetails.validTaskId(id)) throw IOException("INVALID_TASK_ID")
            val directory = File(TaskRuntime.TASKS, id).toPath()
            if (!Files.isDirectory(directory, NOFOLLOW_LINKS) || !Files.isRegularFile(directory.resolve("ready"), NOFOLLOW_LINKS))
                throw IOException("TASK_NOT_FOUND")
            val detail = TaskDetails.read(context.filesDir, id)
            if (thread != null && TaskDetails.notificationThread(context.filesDir, id) != thread)
                throw IOException("TASK_THREAD_MISMATCH")
            val result = directory.resolve("result.txt")
            if (operation == "taskFiles/list") {
                if (params.has("path")) throw IOException("UNSUPPORTED_PARAMETER")
                val entries = JSONArray()
                if (Files.isRegularFile(result, NOFOLLOW_LINKS))
                    entries.put(JSONObject().put("name", "result.txt").put("directory", false))
                return JSONObject().put("entries", entries).put("state", detail.getString("state"))
            }
            if (text(params, "path", 20) != "result.txt" || !Files.isRegularFile(result, NOFOLLOW_LINKS))
                throw IOException("FILE_NOT_FOUND")
            val bytes = Files.newInputStream(result, NOFOLLOW_LINKS).use { it.readNBytes(128 * 1024 + 1) }
            if (bytes.size > 128 * 1024) throw IOException("FILE_TOO_LARGE")
            return JSONObject().put("path", "result.txt").put("text", bytes.toString(Charsets.UTF_8))
        }
        if (thread != null) throw IOException("UNSUPPORTED_PARAMETER")
        if (operation != "taskFiles/list" || params.has("path")) text(params, "path", 512, empty = operation.endsWith("list"))
        if (operation == "taskFiles/write") text(params, "text", 32_000, empty = true)
        return NativeClient.exchange(params.put("op", "workspaceTextFiles").put("action", operation.substringAfter('/')))
    }

    private fun pluginList(): JSONObject = NativeClient.rpcSync("plugin/list",
        JSONObject().put("cwds", JSONArray().put("/data/local/agentos/work")))

    private fun changePlugin(operation: String, params: JSONObject): JSONObject {
        val method: String
        val args: JSONObject
        when (operation) {
            "plugin/install" -> {
                val name = match(params, "pluginName", 256, namePattern)
                val path = text(params, "marketplacePath", 4096)
                val markets = pluginList().optJSONArray("marketplaces") ?: throw IOException("PLUGIN_NOT_FOUND")
                val listed = (0 until markets.length()).any { i ->
                    val market = markets.optJSONObject(i)
                    val plugins = market?.optJSONArray("plugins")
                    market?.optString("path") == path && plugins != null &&
                        (0 until plugins.length()).any { plugins.optJSONObject(it)?.optString("name") == name }
                }
                if (!listed) throw IOException("PLUGIN_NOT_FOUND")
                method = operation
                args = params
            }
            "plugin/uninstall" -> {
                match(params, "pluginId", 256, namePattern)
                method = operation
                args = params
            }
            else -> {
                val id = match(params, "pluginId", 256, namePattern)
                method = "config/value/write"
                args = JSONObject().put("keyPath", "plugins.\"$id\".enabled")
                    .put("mergeStrategy", "upsert").put("value", boolean(params, "enabled"))
            }
        }
        val result = NativeClient.rpcSync(method, args)
        try {
            NativeClient.rpcSync("config/mcpServer/reload", JSONObject())
            result.put("mcpReloaded", true)
        } catch (_: Exception) {
            result.put("mcpReloaded", false)
        }
        return result
    }

    private fun text(input: JSONObject, key: String, max: Int, empty: Boolean = false): String {
        val value = input.opt(key) as? String ?: throw IOException("INVALID_ARGUMENT")
        if (value.length > max || (!empty && value.isBlank()) || '\u0000' in value) throw IOException("INVALID_ARGUMENT")
        return value
    }

    private fun boolean(input: JSONObject, key: String): Boolean = input.opt(key) as? Boolean
        ?: throw IOException("INVALID_ARGUMENT")

    private fun match(input: JSONObject, key: String, max: Int, pattern: Regex): String =
        text(input, key, max).also { if (!pattern.matches(it)) throw IOException("INVALID_ARGUMENT") }

    private fun repository(input: JSONObject): String = match(input, "repo", 200, repoPattern)
        .also { if (it.split('/').any { part -> part == "." || part == ".." }) throw IOException("INVALID_ARGUMENT") }

    internal fun credential(repo: String, action: String, token: String? = null): JSONObject {
        val request = JSONObject().put("op", "skillCredential").put("repo", repo).put("action", action)
        if (token != null) request.put("token", token)
        return NativeClient.exchange(request)
    }
}

class SkillRepositoryCredentialActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val repo = intent.getStringExtra("repo") ?: run { finish(); return }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
                insets
            }
        }
        val prompt = TextView(this).apply { text = "GitHub read-only token for $repo" }
        val token = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setSingleLine()
            contentDescription = "GitHub token"
        }
        val status = TextView(this)
        val save = Button(this).apply { text = "Save token" }
        save.setOnClickListener {
            val secret = token.text.toString()
            if (secret.length !in 8..1024 || secret.any { it !in '!'..'~' }) {
                status.text = "Enter a valid GitHub token."
                return@setOnClickListener
            }
            token.text.clear()
            save.isEnabled = false
            NativeClient.io.execute {
                val saved = runCatching { AssistantManagement.credential(repo, "set", secret).optBoolean("configured") }.getOrDefault(false)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (saved) finish() else {
                        save.isEnabled = true
                        status.text = "Could not save the token."
                    }
                }
            }
        }
        layout.addView(prompt)
        layout.addView(token)
        layout.addView(save)
        layout.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        layout.addView(status)
        setContentView(layout)
    }
}
