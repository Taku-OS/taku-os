package org.agentos.assistant

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import org.json.JSONObject

internal class AgentRepository(context: Context) : AutoCloseable {
    interface Callback<T> {
        fun ready(value: T)
        fun failed(message: String)
    }

    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("agent-settings", Context.MODE_PRIVATE)
    private val credentials = JevCredentialStore(context.filesDir)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var cachedProjection: Projection? = null
    @Volatile private var closed = false

    private data class Projection(val thread: String, val modified: Long, val length: Long, val value: JSONObject)

    private fun loadJevModels(): JSONObject {
        val client = JevApiClient()
        if (credentials.configured() && !preferences.getBoolean("jevLegacyMigrated", false)) {
            val receipt = client.importLegacy(credentials.read())
            if (!receipt.optBoolean("imported") || !preferences.edit().putBoolean("jevLegacyMigrated", true)
                    .putString("jevImportedProvider", receipt.getString("provider")).commit())
                throw IOException("Migration status was not saved; legacy credentials were preserved.")
            try { credentials.clear() } catch (_: Exception) { }
        }
        val result = client.models()
        val legacy = preferences.getString("jevModel", "").orEmpty()
        if (legacy.isNotEmpty()) {
            SceneRoutes.bind(context, "jev", legacy, false)
            if (!preferences.edit().remove("jevModel").commit())
                throw IOException("Legacy decision-model migration was not saved.")
        }
        return result.put("selected", SceneRoutes.get(context, "jev")).put("enabled", preferences.getBoolean("jevEnabled", false))
    }

    fun jevConfig(callback: Callback<JSONObject>) = work(callback, "Cannot read Magpie decision models. Check the local gateway and refresh.") {
        loadJevModels()
    }

    fun saveJevConfig(model: String, enabled: Boolean, callback: Callback<Boolean>) =
        work(callback, "Configuration was not saved. Refresh the catalog and select an available decision model.") {
            if (enabled && findModel(loadJevModels(), model) == null) throw IOException("JEV_MODEL_UNAVAILABLE")
            if (model.isNotEmpty()) SceneRoutes.bind(context, "jev", model, true)
            if (!preferences.edit().putBoolean("jevEnabled", enabled).commit()) throw IOException("JEV_CONFIG_SAVE_FAILED")
            enabled
        }

    fun testJev(callback: Callback<JSONObject>) =
        work(callback, "Magpie decision connection test did not complete. Check the model, service, and quota.") {
            val model = loadJevModels().getString("selected")
            if (model.isEmpty()) throw IOException("JEV_MODEL_UNAVAILABLE")
            JevApiClient().testConnection(model)
        }

    fun analyzeJev(image: Bitmap, foreground: String, capturedAt: Long, callback: Callback<JevScreenDecision>) =
        work(callback, "Screen analysis did not complete. Check the model connection and try again.") {
            if (!preferences.getBoolean("jevEnabled", false)) throw IOException("JEV_DISABLED")
            val config = loadJevModels()
            val model = findModel(config, config.getString("selected")) ?: throw IOException("JEV_MODEL_UNAVAILABLE")
            val decision = JevScreenDecision.analyze(model, image, "", "", foreground, capturedAt)
            if (decision.actionable) ImageScreenSuggestion.generate(image, foreground, capturedAt, decision.receipt) else decision
        }

    fun analyzeScreen(image: Bitmap, foreground: String, capturedAt: Long, callback: Callback<JevScreenDecision>) =
        work(callback, "Screen analysis did not complete. Check the model connection and try again.") {
            ImageScreenSuggestion.generate(image, foreground, capturedAt, null)
        }

    fun snapshot(thread: String, callback: Callback<JSONObject>) = work(callback, "Could not read conversation activity.") {
        if (!AgentGatewayProtocol.isUuid(thread)) throw IOException("INVALID_THREAD_ID")
        val file = File(TaskRuntime.ROOT, "agent-view/$thread.json")
        val result = JSONObject()
        if (file.isFile) {
            val modified = file.lastModified()
            val length = file.length()
            val current = cachedProjection
            val value = if (current != null && current.thread == thread && current.modified == modified && current.length == length) {
                current.value
            } else {
                val bytes = file.inputStream().use { it.readNBytes(1024 * 1024 + 1) }
                if (bytes.size > 1024 * 1024) throw IOException("PROJECTION_TOO_LARGE")
                JSONObject(bytes.toString(Charsets.UTF_8)).also { cachedProjection = Projection(thread, modified, length, it) }
            }
            result.put("thread", value)
        } else cachedProjection = null
        result.put("native", try { NativeClient.state(thread) } catch (_: Exception) { JSONObject() })
    }

    private fun findModel(config: JSONObject, model: String): JSONObject? {
        val models = config.getJSONArray("models")
        for (i in 0 until models.length()) {
            val item = models.getJSONObject(i)
            if (model == item.getString("id")) return item
        }
        return null
    }

    private fun <T> work(callback: Callback<T>, failureMessage: String, action: () -> T) {
        io.execute {
            val result = try { Result.success(action()) } catch (failure: Exception) { Result.failure(failure) }
            main.post {
                if (!closed) result.fold(callback::ready) { callback.failed(failureMessage) }
            }
        }
    }

    override fun close() {
        closed = true
        io.shutdownNow()
    }
}
