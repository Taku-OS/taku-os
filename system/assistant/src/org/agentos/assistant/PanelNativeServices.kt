package org.agentos.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal class PanelNativeServices(private val context: Context) {
    val submissions = AgentGatewaySubmissions.get(context.filesDir)
    private fun preferences() = context.getSharedPreferences("agent-settings", 0)
    private val installedContractHash: String by lazy {
        context.assets.open("native/reply-contract.json").use { JSONObject(String(it.readBytes(), Charsets.UTF_8)).getString("contractHash") }
    }
    fun contractHash(): String = installedContractHash
    fun reply(input: JSONObject): JSONObject = NativeClient.exchange(input)
    fun threadState(thread: String): String {
        val marker = try { SourceAdapters.readObject(File(AssistantSession.ROOT, "thread-availability/$thread.json")) } catch (_: Exception) { null }
        val known = if (marker?.optString("schema") == "taku.thread-availability/1" && marker.optString("threadId") == thread) marker.optString("state") else ""
        if (known == "deleted") return "thread-unavailable"
        return try {
            val result = NativeClient.rpcSync("thread/read", JSONObject().put("threadId", thread).put("includeTurns", false))
            if (result.optJSONObject("thread")?.optString("id") == thread) "current" else "thread-unavailable"
        } catch (failure: Exception) {
            val message = failure.message ?: ""
            if (known != "archived" && (message.contains("not found", true) || message.contains("THREAD_NOT_FOUND") || message.contains("deleted", true))) "thread-unavailable" else "offline"
        }
    }
    fun checkSubmit(thread: String) {
        if (threadState(thread) == "thread-unavailable") throw IOException("SOURCE_THREAD_NOT_FOUND")
        val state = NativeClient.state(thread)
        if (!state.optBoolean("ready") || state.optString("activeTurnId").isNotEmpty()) throw IOException("THREAD_BUSY")
        val priorId = preferences().getString("task-$thread", "")!!
        if (priorId.matches(Regex("[0-9]+"))) {
            val prior = File(AssistantSession.TASKS, priorId)
            if (File(prior, "ready").isFile && !File(prior, "done").isFile) throw IOException("THREAD_QUEUED")
        }
    }
    fun submit(input: JSONObject): JSONObject = submissions.submit(input.getString("requestId"), input.getString("threadId"), input.getString("text"),
        { checkSubmit(input.getString("threadId")) }, { before -> publish(input, before) })
    fun publish(input: JSONObject, before: TaskRequests.BeforePublish): String {
        val thread = input.getString("threadId")
        val prefs = preferences()
        val options = JSONObject().put("includeScreenshot", false).put("responseStyle", prefs.getString("responseStyle", "concise"))
        ComposerPreferences.apply(prefs, thread, options)
        options.remove("attachments")
        if (input.optBoolean("requireConfirmation")) options.put("permissionMode", "ask")
        if (input.has("panelUpdate")) options.put("panelUpdate", input.getJSONObject("panelUpdate"))
        return TaskRequests.submit(input.getString("text"), "Taku Agent", null, thread, options) { task ->
            before.persist(task)
            if (!prefs.edit().putString("task-$thread", task).commit()) throw IOException("RECEIPT_SAVE_FAILED")
        }
    }
    fun notifyDirectory() { context.contentResolver.notifyChange(Uri.parse("content://org.agentos.assistant.open-access/panels"), null) }
    fun notifyMatter(id: String) { context.contentResolver.notifyChange(Uri.parse("content://org.agentos.assistant.open-access/matters").buildUpon().appendPath(id).build(), null) }
    fun open(panel: String) {
        context.startActivity(Intent("org.agentos.agent.action.OPEN_PANEL").setClassName("org.agentos.agent", "org.agentos.agent.home.HomeActivity")
            .putExtra("panelId", panel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }
    fun navigation(): JSONObject {
        val unavailable = JSONObject().put("available", JSONArray()).put("visible", JSONArray()).put("selectedPanelId", JSONObject.NULL)
        return try {
            val answer = context.contentResolver.call(Uri.parse("content://org.agentos.agent.panel-navigation"), "navigation", "{}", null) ?: return unavailable
            val envelope = JSONObject(answer.getString("result", "{}"))
            if (!envelope.optBoolean("ok")) unavailable else envelope.getJSONObject("result").let {
                JSONObject().put("available", JSONArray(it.getJSONArray("available").toString())).put("visible", JSONArray(it.getJSONArray("visible").toString()))
                    .put("selectedPanelId", it.opt("selectedPanelId") ?: JSONObject.NULL)
            }
        } catch (_: Exception) { unavailable }
    }
}
