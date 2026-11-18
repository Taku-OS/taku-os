package org.agentos.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/** Fixed supported contracts only. Installed apps and provider permissions remain authoritative. */
internal class SourceAdapters(private val context: Context) {
    private val audio: SourceAdapter = AudioSourceAdapter(context)
    private val weather: SourceAdapter = PublicSourceAdapter(context, "weather")
    private val feed: SourceAdapter = PublicSourceAdapter(context, "feed")

    @Throws(IOException::class)
    fun resolve(kind: String): SourceAdapter {
        if (kind == "memory") return memory
        if (kind == "task-receipt") return receipt
        if (kind == "audio-session") return audio
        if (kind == "weather") return weather
        if (kind == "feed") return feed
        throw IOException("SOURCE_UNAVAILABLE")
    }

    @Throws(Exception::class)
    fun identity(kind: String): JSONObject {
        if (kind == "audio-session") return AudioSessionBridge.identity(context)
        if (PublicSourceAdapter.supports(kind)) return PublicSourceAdapter.identity(kind)
        if (kind == "task-receipt") return JSONObject().put("packageName", context.packageName)
            .put("kind", "task-broker-receipt").put("authority", "TaskBroker")
        if (kind != "memory") throw IOException("SOURCE_UNAVAILABLE")
        val info = context.packageManager.resolveContentProvider("org.agentos.memory", PackageManager.GET_META_DATA)
        if (info == null || !info.exported || !info.enabled || "org.agentos.memory" != info.packageName)
            throw IOException("SOURCE_UNAVAILABLE")
        if (info.readPermission != null && context.checkSelfPermission(info.readPermission) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("SOURCE_PERMISSION_DENIED")
        val app = context.packageManager.getPackageInfo(info.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val certificate = app.signingInfo!!.apkContentsSigners[0].toByteArray()
        val digest = StringBuilder()
        for (b in MessageDigest.getInstance("SHA-256").digest(certificate))
            digest.append(String.format(Locale.ROOT, "%02x", b.toInt() and 255))
        return JSONObject().put("packageName", info.packageName).put("authority", info.authority)
            .put("certificateSha256", digest.toString()).put("kind", "memory-record")
    }

    private val memory = object : SourceAdapter {
        @Throws(Exception::class)
        override fun read(resource: JSONObject): JSONObject {
            val provenance = identity("memory")
            val response = context.contentResolver.call(Uri.parse("content://org.agentos.memory"), "get",
                JSONObject().put("id", resource.getString("resourceId")).toString(), null)
                ?: throw IOException("SOURCE_UNAVAILABLE")
            val envelope = JSONObject(response.getString("result", "{}"))
            if (!envelope.optBoolean("ok")) throw SecurityException("SOURCE_DENIED_OR_REMOVED")
            val record = envelope.getJSONObject("result")
            val body = record.optString("fullText", record.optString("text", record.optString("body")))
            if (body.length > 1000 || record.optString("title").length > 80) throw IOException("SOURCE_TOO_LARGE")
            return JSONObject().put("state", "available").put("observedAt", System.currentTimeMillis())
                .put("sourceVersion", record.optLong("revision"))
                .put("sourceUpdatedAt", record.optLong("last_seen", record.optLong("first_seen")))
                .put("title", record.optString("title")).put("text", body).put("provenance", provenance)
                .put("quality", "stored-memory-evidence").put("anchor", record.optString("ref", resource.getString("resourceId")))
                .put("capabilities", JSONObject().put("read", true).put("watch", true).put("command", false).put("typedPrice", false))
        }

        @Throws(Exception::class)
        override fun watch(resource: JSONObject, invalidated: Runnable): AutoCloseable {
            identity("memory")
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) { invalidated.run() }
            }
            context.contentResolver.registerContentObserver(Uri.parse("content://org.agentos.memory"), true, observer)
            return AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    private val receipt = object : SourceAdapter {
        private fun task(resource: JSONObject): File {
            val id = resource.getString("resourceId")
            if (!java.util.regex.Pattern.matches("[0-9]{1,20}", id)) throw IOException("INVALID_TASK_ID")
            val directory = File(AssistantSession.TASKS, id)
            val request = readObject(File(directory, "request.json"))
            if (resource.getString("threadId") != request.optString("conversationId")) throw IOException("TASK_THREAD_MISMATCH")
            return directory
        }

        @Throws(Exception::class)
        override fun read(resource: JSONObject): JSONObject {
            val directory = task(resource)
            val state = readObject(File(directory, "state.json"))
            val done = File(directory, "done").isFile
            val status = state.optString("state", "unknown")
            val result = JSONObject().put("state", "available").put("observedAt", System.currentTimeMillis())
                .put("sourceVersion", 0).put("sourceUpdatedAt", state.optLong("updatedAt")).put("title", "TaskBroker")
                .put("text", state.optString("message")).put("taskState", status).put("terminal", done)
                .put("provenance", identity("task-receipt")).put("quality", "task-receipt")
                .put("capabilities", JSONObject().put("read", true).put("watch", true).put("command", false).put("typedPrice", false))
            // Task completion never establishes a purchase or booking.
            result.put("deliveryState", if (done) {
                if (status == "failed" || status == "cancelled") status else "taskCompleted"
            } else status)
            val runtime = File(directory, "runtime-info.json")
            if (runtime.isFile) {
                val info = readObject(runtime)
                if (info.has("turnId")) result.put("turnId", info.getString("turnId"))
            }
            return result
        }

        @Throws(Exception::class)
        override fun watch(resource: JSONObject, invalidated: Runnable): AutoCloseable {
            val observer = object : FileObserver(task(resource), FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or FileObserver.CREATE or FileObserver.DELETE_SELF) {
                override fun onEvent(event: Int, path: String?) { invalidated.run() }
            }
            observer.startWatching()
            return AutoCloseable { observer.stopWatching() }
        }
    }

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun readObject(file: File): JSONObject = readObject(file, 128_000)

        @JvmStatic
        @Throws(Exception::class)
        fun readObject(file: File, maximumBytes: Int): JSONObject {
            if (!file.isFile || file.length() > maximumBytes) throw IOException("SOURCE_UNAVAILABLE")
            return JSONObject(String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        }
    }
}
