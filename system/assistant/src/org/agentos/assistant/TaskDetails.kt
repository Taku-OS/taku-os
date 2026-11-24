package org.agentos.assistant

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import org.json.JSONException
import org.json.JSONObject

internal object TaskDetails {
    private val taskIdPattern = Regex("[0-9]{1,20}")
    private const val maxRecordBytes = 256 * 1024

    @JvmStatic
    fun validTaskId(value: String?): Boolean = value != null && taskIdPattern.matches(value)

    @JvmStatic
    fun notificationThread(filesDir: File, taskId: String): String? = try {
        val directory = taskDirectory(filesDir, taskId)
        val request = readRecord(File(directory, "request.json"), taskId, AgentGatewayProtocol.MAX_NATIVE_TASK_REQUEST_BYTES)
        readState(directory, taskId)
        val conversation = request.opt("conversationId") as? String
        conversation?.takeIf(AgentGatewayProtocol::isUuid)
    } catch (_: IOException) {
        // The detail page resolves the original task ID and displays the failure.
        null
    }

    @JvmStatic
    @Throws(IOException::class)
    fun notificationState(filesDir: File, taskId: String): JSONObject =
        readState(taskDirectory(filesDir, taskId), taskId)

    @JvmStatic
    @Throws(IOException::class)
    fun read(filesDir: File, taskId: String): JSONObject {
        val directory = taskDirectory(filesDir, taskId)
        readRecord(File(directory, "request.json"), taskId, AgentGatewayProtocol.MAX_NATIVE_TASK_REQUEST_BYTES)
        val state = readState(directory, taskId)
        return JSONObject().put("taskId", taskId)
            .put("state", state.optString("state"))
            .put("prompt", state.optString("prompt"))
            .put("message", state.optString("message"))
    }

    private fun taskDirectory(filesDir: File, taskId: String): File {
        if (!validTaskId(taskId)) throw IOException("INVALID_TASK_ID")
        return File(filesDir, "runtime/tasks/$taskId")
    }

    private fun readState(directory: File, taskId: String): JSONObject {
        val state = readRecord(File(directory, "state.json"), taskId)
        if ((state.opt("state") as? String).isNullOrBlank()) throw IOException("TASK_UNAVAILABLE")
        return state
    }

    private fun readRecord(path: File, taskId: String, maximum: Int = maxRecordBytes): JSONObject = try {
        val bytes = Files.newInputStream(path.toPath()).use { it.readNBytes(maximum + 1) }
        if (bytes.size > maximum) throw IOException("TASK_UNAVAILABLE")
        val value = JSONObject(bytes.toString(Charsets.UTF_8))
        if (value.opt("id") != taskId) throw IOException("TASK_UNAVAILABLE")
        value
    } catch (_: NoSuchFileException) {
        throw IOException("TASK_NOT_FOUND")
    } catch (_: IOException) {
        throw IOException("TASK_UNAVAILABLE")
    } catch (_: JSONException) {
        throw IOException("TASK_UNAVAILABLE")
    }
}
