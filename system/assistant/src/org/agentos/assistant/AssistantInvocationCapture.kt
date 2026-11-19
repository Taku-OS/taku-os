package org.agentos.assistant

import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object AssistantInvocationCapture {
    private data class Invocation(
        val token: String,
        val capturedAt: Long,
        val received: Boolean = false,
        val foreground: String = "Current app",
        val image: Bitmap? = null,
    )

    private var invocation: Invocation? = null
    private val main = Handler(Looper.getMainLooper())
    private var observingPolicy = false

    @JvmStatic
    @Synchronized
    fun clear() { invocation = null }

    @Synchronized
    private fun current(context: Context): Invocation? {
        if (!screenshotsAllowed(context)) clear()
        val current = invocation ?: return null
        if (SystemClock.elapsedRealtime() - current.capturedAt > JevScreenDecision.MAX_AGE_MS && current.image != null)
            invocation = current.copy(image = null)
        return invocation
    }

    @Synchronized
    fun receive(context: Context, input: JSONObject, extras: Bundle?): JSONObject {
        val descriptor = extras?.getParcelable("capture", ParcelFileDescriptor::class.java)
        return descriptor.use {
            ComposerState.userError(context, android.os.Process.myUid())?.let { throw IOException(it) }
            when (input.optString("operation")) {
                "begin" -> {
                    if (input.length() != 2 || input.opt("capturedAt") !is Number || descriptor != null)
                        throw IOException("INVALID_ARGUMENT")
                    val at = input.getLong("capturedAt")
                    val age = SystemClock.elapsedRealtime() - at
                    if (age !in 0..10_000) throw IOException("CAPTURE_EXPIRED")
                    if (!observingPolicy) {
                        context.applicationContext.contentResolver.registerContentObserver(
                            Settings.Secure.getUriFor("assist_screenshot_enabled"), false, object : ContentObserver(main) {
                                override fun onChange(selfChange: Boolean) {
                                    if (!screenshotsAllowed(context)) clear()
                                }
                            })
                        observingPolicy = true
                    }
                    val current = Invocation(UUID.randomUUID().toString(), at)
                    invocation = current
                    main.postDelayed({
                        synchronized(this) {
                            invocation?.takeIf { it.token == current.token }?.let { invocation = it.copy(image = null) }
                        }
                    }, JevScreenDecision.MAX_AGE_MS - age + 1)
                    JSONObject().put("token", current.token)
                }
                "receive" -> {
                    if (input.length() != 3 || input.opt("token") !is String || input.opt("foreground") !is String ||
                        input.getString("foreground").length > 512) throw IOException("INVALID_ARGUMENT")
                    val current = invocation
                    if (current == null || current.received || input.getString("token") != current.token ||
                        SystemClock.elapsedRealtime() - current.capturedAt > 10_000) throw IOException("CAPTURE_EXPIRED")
                    invocation = current.copy(received = true)
                    val image = if (screenshotsAllowed(context)) descriptor?.let { AssistantCaptureBackend.decode(it, false) } else null
                    invocation = current.copy(received = true, foreground = input.getString("foreground"), image = image)
                    JSONObject().put("available", image != null)
                }
                else -> throw IOException("INVALID_ARGUMENT")
            }
        }
    }

    fun suggest(context: Context): JSONObject {
        val current = current(context)
        return AssistantCaptureBackend.suggest(context, current?.image,
            current?.capturedAt ?: SystemClock.elapsedRealtime(), current?.foreground ?: "Current app")
    }

    fun reserve(context: Context, requestId: String, options: JSONObject) {
        if (!options.optBoolean("includeScreenshot")) return
        val current = current(context)
        val image = current?.image ?: return
        val file = screenshotFile(context, requestId)
        val root = file.parentFile!!
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) throw IOException("SCREENSHOT_UNAVAILABLE")
        try {
            FileOutputStream(file).use { output ->
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("SCREENSHOT_UNAVAILABLE")
                output.fd.sync()
            }
            options.put("invocationScreenshot", requestId).put("invocationForeground", current.foreground)
        } catch (failure: Exception) {
            file.delete()
            throw IOException("SCREENSHOT_UNAVAILABLE", failure)
        }
    }

    fun check(context: Context, request: JSONObject) {
        val options = request.optJSONObject("options") ?: return
        if (!options.optBoolean("includeScreenshot")) return
        val id = options.optString("invocationScreenshot")
        if (id != request.getString("requestId") || !AgentGatewayProtocol.isUuid(id) || !screenshotsAllowed(context))
            throw IOException("SCREENSHOT_UNAVAILABLE")
        val file = screenshotFile(context, id)
        if (!file.isFile || !file.canRead() || file.length() == 0L) throw IOException("SCREENSHOT_UNAVAILABLE")
    }

    fun image(context: Context, request: JSONObject): Bitmap? {
        check(context, request)
        val options = request.optJSONObject("options") ?: return null
        if (!options.optBoolean("includeScreenshot")) return null
        return BitmapFactory.decodeFile(screenshotFile(context, request.getString("requestId")).path)
            ?: throw IOException("SCREENSHOT_UNAVAILABLE")
    }

    fun append(context: Context, request: JSONObject, input: JSONArray) {
        check(context, request)
        if (request.optJSONObject("options")?.optBoolean("includeScreenshot") == true)
            input.put(JSONObject().put("type", "localImage").put("path", screenshotFile(context, request.getString("requestId")).path))
    }

    fun settle(context: Context, request: JSONObject, receipt: JSONObject) {
        val state = receipt.optString("state")
        if (state == "notSent" || state == "queued" ||
            state == "accepted" && receipt.optJSONObject("result")?.optBoolean("queuedAfterTurn") == true) {
            if (request.optJSONObject("options")?.optString("invocationScreenshot") == request.getString("requestId"))
                discard(context, request.getString("requestId"))
        }
    }

    fun discard(context: Context, requestId: String) { screenshotFile(context, requestId).delete() }

    private fun screenshotFile(context: Context, id: String): File {
        if (!AgentGatewayProtocol.isUuid(id)) throw IOException("INVALID_REQUEST_ID")
        return File(File(context.filesDir, "composer-attachments"), "screen-$id.png")
    }

    private fun screenshotsAllowed(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, "assist_screenshot_enabled", 1) != 0
}
