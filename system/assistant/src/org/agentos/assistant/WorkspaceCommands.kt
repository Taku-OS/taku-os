package org.agentos.assistant

import android.content.Context
import android.content.Intent
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object WorkspaceCommands {
    private val leaseFields = setOf("threadId", "screenKey", "generation")
    private val fields = mapOf(
        "workspaces/list" to setOf("threadId"),
        "workspaces/apps" to emptySet(),
        "workspaces/configure" to setOf("patch"),
        "workspaces/release" to leaseFields + "returnToThread",
        "workspaces/preview" to leaseFields,
        "workspaces/open" to leaseFields,
        "workspaces/pause" to leaseFields,
        "workspaces/takeControl" to leaseFields,
        "workspaces/input" to leaseFields + setOf("controlRevision", "input"),
        "workspaces/returnAndResume" to leaseFields + setOf("controlRevision", "requestId"),
        "workspaces/addRequirement" to leaseFields + setOf("controlRevision", "requestId", "text"),
        "workspaces/executionStatus" to leaseFields + "requestId",
    )
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val screenKey = Regex("task-[a-f0-9]{20}")
    private val unavailable = setOf("stale", "disconnected", "unavailable", "rebind_required", "cleanup_failed", "stopping")

    @JvmStatic
    @Throws(Exception::class)
    fun request(method: String, input: JSONObject): JSONObject {
        val allowed = fields[method] ?: throw IOException("UNKNOWN_WORKSPACE_ACTION")
        if (input.keys().asSequence().any { it !in allowed }) throw IOException("INVALID_ARGUMENT")
        if ("screenKey" in allowed) {
            requireString(input, "threadId", uuid, "INVALID_SCREEN_THREAD")
            requireString(input, "screenKey", screenKey, "INVALID_SCREEN_KEY")
            requireString(input, "generation", uuid, "INVALID_SCREEN_LEASE")
        } else if (input.has("threadId")) requireString(input, "threadId", uuid, "INVALID_SCREEN_THREAD")
        if (method == "workspaces/configure" && input.opt("patch") !is JSONObject) throw IOException("MISSING_WORKSPACE_PATCH")
        if (input.has("returnToThread") && input.opt("returnToThread") !is Boolean) throw IOException("INVALID_ARGUMENT")
        if ("requestId" in allowed) requireString(input, "requestId", uuid, "INVALID_WORKSPACE_REQUEST")
        if ("controlRevision" in allowed) {
            val revision = input.opt("controlRevision")
            if ((revision !is Int && revision !is Long) || (revision as Number).toLong() < 0)
                throw IOException("INVALID_SCREEN_CONTROL_REVISION")
        }
        if (method == "workspaces/input" && input.opt("input") !is JSONObject) throw IOException("INVALID_WORKSPACE_INPUT")
        if (method == "workspaces/addRequirement") {
            val text = input.opt("text")
            if (text !is String || text.isBlank() || text.length > 4000) throw IOException("INVALID_WORKSPACE_REQUIREMENT")
        }
        return input
    }

    @JvmStatic
    @Throws(Exception::class)
    fun call(context: Context, method: String, input: JSONObject, callerUid: Int): JSONObject {
        ComposerState.userError(context, callerUid)?.let { throw IOException(it) }
        request(method, input)
        return when (method) {
            "workspaces/list" -> catalog(context, input.optString("threadId"))
            "workspaces/apps" -> apps(context)
            "workspaces/preview" -> {
                val lease = exactLease(context, input)
                if (!lease.optBoolean("admitted") || lease.optInt("displayId", -1) <= 0 ||
                    lease.optBoolean("stopping") || lease.optString("status") in unavailable)
                    throw IOException("SCREEN_REBIND_REQUIRED")
                // Deliver the lease to onNewIntent even when a viewer from an older lease is open.
                val intent = Intent(context, MirrorActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                for (key in leaseFields) intent.putExtra(key, lease.getString(key))
                context.startActivity(intent)
                JSONObject().put("opened", true).put("threadId", lease.getString("threadId"))
            }
            "workspaces/open" -> {
                val lease = exactLease(context, input)
                requireThread(lease.getString("threadId"))
                exactLease(context, input)
                if (!AgentFrontendLauncher.openThread(context, lease.getString("threadId")))
                    throw IOException("AGENT_FRONTEND_UNAVAILABLE")
                JSONObject().put("opened", true).put("threadId", lease.getString("threadId"))
            }
            else -> {
                val action = method.substring("workspaces/".length)
                val command = JSONObject().put("op", "workspaces").put("action", action)
                for (key in input.keys()) if (key != "returnToThread") command.put(key, input.get(key))
                val result = NativeClient.exchange(command)
                if (method == "workspaces/release" && input.optBoolean("returnToThread")) {
                    val thread = input.getString("threadId")
                    try {
                        requireThread(thread)
                        if (!AgentFrontendLauncher.openThread(context, thread)) throw IOException("AGENT_FRONTEND_UNAVAILABLE")
                        result.put("opened", true).put("threadId", thread)
                    } catch (failure: Exception) {
                        result.put("opened", false).put("threadId", thread)
                            .put("returnError", if (failure.message == "AGENT_FRONTEND_UNAVAILABLE") failure.message else "THREAD_UNAVAILABLE")
                    }
                }
                result
            }
        }
    }

    private fun requireString(input: JSONObject, field: String, pattern: Regex, error: String) {
        val value = input.opt(field)
        if (value !is String || !pattern.matches(value)) throw IOException(error)
    }

    private fun requireThread(thread: String) {
        val current = TaskRequests.control("thread/read", JSONObject().put("threadId", thread))
        if (current.optBoolean("threadUnavailable") || !current.optBoolean("loaded") || current.optString("threadId") != thread)
            throw IOException("THREAD_UNAVAILABLE")
    }

    private fun catalog(context: Context, thread: String = ""): JSONObject {
        val command = JSONObject().put("op", "workspaces").put("action", "list")
        if (thread.isNotEmpty()) command.put("threadId", thread)
        val result = NativeClient.exchange(command)
        val screens = result.getJSONArray("screens")
        val labels = HashMap<String, String>()
        for (i in 0 until screens.length()) {
            val screen = screens.getJSONObject(i)
            val pkg = screen.optString("packageName")
            if (pkg.isEmpty()) continue
            val label = labels.getOrPut(pkg) {
                try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { screen.optString("appLabel", pkg) }
            }
            screen.put("appLabel", label)
        }
        return result
    }

    private fun exactLease(context: Context, input: JSONObject): JSONObject {
        val screens = catalog(context, input.getString("threadId")).getJSONArray("screens")
        for (i in 0 until screens.length()) {
            val screen = screens.getJSONObject(i)
            if (WorkspaceUi.same(input, screen)) return screen
        }
        throw IOException("SCREEN_LEASE_CHANGED")
    }

    private fun apps(context: Context): JSONObject {
        val state = catalog(context)
        val blocked = state.getJSONObject("policy").getJSONArray("blockedPackages")
        val denied = HashSet<String>()
        for (i in 0 until blocked.length()) denied.add(blocked.getString(i))
        val manager = context.packageManager
        val installed = LinkedHashMap<String, String>()
        for (entry in manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            val info = entry.activityInfo ?: continue
            installed.putIfAbsent(info.packageName, entry.loadLabel(manager).toString())
        }
        val packages = (installed.keys + denied).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { pkg: String -> installed[pkg] ?: pkg }.thenBy { it })
        val rows = JSONArray()
        for (pkg in packages) rows.put(JSONObject().put("packageName", pkg).put("label", installed[pkg] ?: pkg)
            .put("installed", installed.containsKey(pkg)).put("allowed", pkg !in denied))
        return JSONObject().put("apps", rows).put("policy", state.getJSONObject("policy"))
    }
}
