package org.agentos.lab

import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.json.JSONObject

internal object WorkspaceLaunch {
    private data class App(val id: String, val packageName: String, val component: String)
    private val componentPattern = Regex("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+")
    private val errorPattern = Regex("[A-Z][A-Z0-9_]+")

    @JvmStatic
    @Throws(Exception::class)
    fun launch(input: JSONObject): JSONObject {
        if (input.keys().asSequence().any { it != "op" && it != "appId" }) throw IOException("INVALID_ARGUMENT")
        val id = input.opt("appId") as? String ?: throw IOException("INVALID_APP_ID")
        if (id.length > 400 || !componentPattern.matches(id)) throw IOException("INVALID_APP_ID")
        val parts = id.split('/', limit = 2)
        val app = App(id, parts[0], parts[0] + "/" + if (parts[1].startsWith('.')) parts[0] + parts[1] else parts[1])
        PhoneAppCatalog.packageName(app.packageName)
        var thread: String? = null
        val reservation = UUID.randomUUID().toString()
        try {
            if (!WorkspacePolicy.snapshot().getBoolean("allowBackground")) throw IOException("BACKGROUND_WORK_DISABLED")
            val metadata = PhoneAppCatalog.requireLaunchableComponent(app.packageName, app.component)
            thread = startThread()
            NativeBridge.admissions.reserve(thread, reservation)
            if (!WorkspaceControl.isQuiescent(WorkspaceControl.runtime(thread))) throw IOException("WORKSPACE_EXECUTION_BUSY")
            val label = metadata.optString("label").ifBlank { app.packageName }
            val screen = DedicatedScreensOnDevice.shared().launch(thread, app.packageName, label) { handle ->
                PhoneAppLaunch.open(handle, app.packageName, app.component)
            }
            return JSONObject().put("launched", true).put("appId", app.id).put("threadId", thread)
                .put("screen", screen)
        } catch (failure: Exception) {
            val interrupted = Thread.interrupted()
            try {
                if (thread != null) try {
                    SessionRemovalRuntime.submit(thread, "delete")
                } catch (cleanup: Exception) {
                    failure.addSuppressed(cleanup)
                    throw IOException("WORKSPACE_LAUNCH_CLEANUP_FAILED").also { it.addSuppressed(failure) }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
            val code = when (failure) {
                is InterruptedException -> "WORKSPACE_LAUNCH_INTERRUPTED"
                is TimeoutException -> "WORKSPACE_RUNTIME_UNAVAILABLE"
                else -> NativeBridge.publicError(failure).substringBefore(':').trim().takeIf(errorPattern::matches)
                    ?: "WORKSPACE_LAUNCH_FAILED"
            }
            throw IOException(code).also { it.addSuppressed(failure) }
        } finally {
            if (thread != null) NativeBridge.admissions.releaseReservation(thread, reservation)
        }
    }

    private fun startThread(): String {
        val call = NativeBridge.Call("thread/start", JSONObject().put("cwd", "/data/local/agentos/work"))
        if (!NativeBridge.enqueue(call)) throw IOException("WORKSPACE_RUNTIME_BUSY")
        try {
            return call.result.get(15, TimeUnit.SECONDS).getJSONObject("thread").getString("id")
        } catch (failure: Exception) {
            call.result.thenAcceptAsync { result ->
                try {
                    SessionRemovalRuntime.submit(result.getJSONObject("thread").getString("id"), "delete")
                } catch (cleanup: Exception) {
                    android.util.Log.w("TakuWorkspaceLaunch", "Could not retire an abandoned launch thread", cleanup)
                }
            }
            throw failure
        }
    }
}
