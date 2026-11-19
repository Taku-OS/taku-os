package org.agentos.assistant

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal object AssistantCaptureBackend {
    private enum class State { CAPTURING, ANALYZING, READY, NO_SUGGESTION, NO_SCREENSHOT, EXPIRED, DISMISSED, ACCEPTED, FAILED }

    private class Capture(val id: String, val manual: Boolean, val capturedAt: Long, val foreground: String) {
        val expiresAt = System.currentTimeMillis() + JevScreenDecision.MAX_AGE_MS - (SystemClock.elapsedRealtime() - capturedAt)
        val captureDeadline = SystemClock.elapsedRealtime() + 10_000
        var state = State.CAPTURING
        var decision: JevScreenDecision? = null
        var message = "Waiting for the system screenshot."
    }

    private val main = Handler(Looper.getMainLooper())
    private val captures = linkedMapOf<String, Capture>()
    private val captureId = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private var repository: AgentRepository? = null

    @JvmStatic
    @JvmOverloads
    fun begin(context: Context, id: String, manual: Boolean, capturedAt: Long, page: String = foreground(context)): JSONObject = onMain {
        captures.values.forEach(::expire)
        if (captures.values.any { it.state == State.CAPTURING || it.state == State.ANALYZING || it.state == State.READY })
            throw IOException("CAPTURE_BUSY")
        while (captures.size >= 16) captures.remove(captures.keys.first())
        val capture = Capture(id, manual, capturedAt, page)
        captures[id] = capture
        main.postDelayed({ expire(capture) }, 10_001)
        main.postDelayed({ expire(capture) }, JevScreenDecision.MAX_AGE_MS + 1)
        snapshot(context, capture)
    }

    @JvmStatic
    fun suggest(context: Context, image: Bitmap?, capturedAt: Long, page: String): JSONObject = onMain {
        ComposerState.userError(context, android.os.Process.myUid())?.let { throw IOException(it) }
        val id = java.util.UUID.randomUUID().toString()
        val at = capturedAt
        begin(context, id, true, at, page)
        val capture = captures.getValue(id)
        if (SystemClock.elapsedRealtime() - at > JevScreenDecision.MAX_AGE_MS) {
            capture.state = State.EXPIRED
            capture.message = "Suggestion expired. Capture the screen again."
        } else if (image == null) {
            capture.state = State.NO_SCREENSHOT
            capture.message = "Android did not return a screenshot. Enable screenshot access, return to the target app, and invoke the assistant again. No model was called."
        } else {
            try {
                val owned = JevScreenDecision.capture(image)
                capture.state = State.ANALYZING
                capture.message = "Understanding the previous screen…"
                analyze(context.applicationContext, capture, owned)
            } catch (failure: Exception) {
                unavailable(id)
                throw failure
            }
        }
        if (!AgentFrontendLauncher.openRoute(context, "/assistant-suggestion?captureId=$id"))
            throw IOException("AGENT_UI_UNAVAILABLE")
        snapshot(context, capture)
    }

    @JvmStatic
    fun unavailable(id: String) = onMain {
        captures[id]?.apply { state = State.FAILED; message = "The system assistant could not capture the screen. Try again." }
        Unit
    }

    @JvmStatic
    fun receive(context: Context, input: JSONObject, extras: Bundle?): JSONObject {
        val descriptor = extras?.getParcelable("capture", ParcelFileDescriptor::class.java)
        return descriptor.use {
            ComposerState.userError(context, android.os.Process.myUid())?.let { throw IOException(it) }
            if (input.keys().asSequence().any { it !in setOf("jevTestToken", "manualScreen", "captureForeground") })
                throw IOException("INVALID_ARGUMENT")
            val token = input.opt("jevTestToken") as? String ?: throw IOException("INVALID_ARGUMENT")
            val authorization = JevTestTrigger.consume(token)
            try {
                val image = descriptor?.let { decode(it) }
                onMain {
                    val capture = captures[authorization.captureId] ?: throw IOException("CAPTURE_EXPIRED")
                    expire(capture)
                    if (capture.state != State.CAPTURING) throw IOException("CAPTURE_EXPIRED")
                    if (image == null) {
                        capture.state = State.NO_SCREENSHOT
                        capture.message = "Android did not return a screenshot. Screenshot access may be disabled or this app may protect its screen. No model was called."
                    } else {
                        capture.state = State.ANALYZING
                        capture.message = if (capture.manual) "Understanding the previous screen…" else "Jev is checking this screenshot…"
                        analyze(context.applicationContext, capture, image)
                    }
                    snapshot(context, capture)
                }
            } catch (failure: Exception) {
                if (failure.message != "CAPTURE_EXPIRED") unavailable(authorization.captureId)
                throw failure
            }
        }
    }

    @JvmStatic
    fun execute(context: Context, method: String, input: JSONObject): JSONObject {
        if (method == "capture/request") {
            if (input.keys().asSequence().any { it !in setOf("manual", "source") } ||
                input.has("manual") && input.opt("manual") !is Boolean ||
                input.has("source") && input.opt("source") !in setOf("system", "invocation"))
                throw IOException("INVALID_ARGUMENT")
            if (input.optString("source") == "invocation") {
                if (!input.optBoolean("manual", true)) throw IOException("INVALID_ARGUMENT")
                return AssistantInvocationCapture.suggest(context)
            }
            return JevTestTrigger.request(context, input.optBoolean("manual", true))
        }
        if (method !in setOf("capture/read", "capture/accept", "capture/dismiss", "capture/openConversation") ||
            input.length() != 1 || input.opt("captureId") !is String || !captureId.matches(input.getString("captureId")))
            throw IOException("INVALID_ARGUMENT")
        return onMain {
            val id = input.getString("captureId")
            val job = BackgroundScreenTasks.status(context, id)
            if (method == "capture/openConversation") {
                val thread = job?.optString("threadId")?.takeIf(AgentGatewayProtocol::isUuid)
                    ?: throw IOException("THREAD_UNAVAILABLE")
                if (!AgentFrontendLauncher.openThread(context, thread)) throw IOException("AGENT_UI_UNAVAILABLE")
                return@onMain JSONObject().put("opened", true).put("threadId", thread)
            }
            val capture = captures[id]
            if (job != null) {
                capture?.apply { state = State.ACCEPTED; decision = null }
                return@onMain accepted(id, job)
            }
            if (capture == null) {
                if (method == "capture/accept") throw IOException("CAPTURE_EXPIRED")
                return@onMain JSONObject().put("captureId", id).put("state", "expired")
                    .put("manual", false).put("title", "Suggestion expired").put("suggestion", "")
                    .put("expiresAt", 0).put("message", "This screen suggestion is no longer available. Capture the screen again.")
            }
            expire(capture)
            when (method) {
                "capture/accept" -> {
                    if (capture.state == State.ACCEPTED) return@onMain snapshot(context, capture)
                    if (capture.state == State.EXPIRED || capture.state == State.DISMISSED) throw IOException("CAPTURE_EXPIRED")
                    val decision = capture.decision
                    if (capture.state != State.READY || decision == null) throw IOException("CAPTURE_NOT_ACTIONABLE")
                    val receipt = BackgroundScreenTasks.enqueue(context, id, decision)
                    capture.state = State.ACCEPTED
                    capture.decision = null
                    capture.message = "The task belongs to the background assistant. You can close this screen."
                    return@onMain accepted(id, receipt)
                }
                "capture/dismiss" -> if (capture.state != State.ACCEPTED) {
                    capture.state = State.DISMISSED
                    capture.decision = null
                    capture.message = "Suggestion dismissed. No task was started."
                }
            }
            snapshot(context, capture)
        }
    }

    private fun analyze(context: Context, capture: Capture, image: Bitmap) {
        val owner = repository ?: AgentRepository(context).also { repository = it }
        val callback = object : AgentRepository.Callback<JevScreenDecision> {
            override fun ready(decision: JevScreenDecision) {
                expire(capture)
                if (capture.state != State.ANALYZING) return
                capture.state = if (decision.actionable && decision.receipt.optBoolean("generated")) State.READY else State.NO_SUGGESTION
                capture.decision = if (capture.state == State.READY) decision else null
                capture.message = if (capture.state == State.READY) "A background task starts only after you accept."
                    else "No suitable suggestion for the current screen."
            }
            override fun failed(message: String) {
                expire(capture)
                if (capture.state != State.ANALYZING) return
                capture.state = State.FAILED
                capture.message = "Screen analysis did not complete. Check the model connection and try again."
            }
        }
        if (capture.manual) owner.analyzeScreen(image, capture.foreground, capture.capturedAt, callback)
        else owner.analyzeJev(image, capture.foreground, capture.capturedAt, callback)
    }

    private fun expire(capture: Capture) {
        val age = SystemClock.elapsedRealtime() - capture.capturedAt
        if (capture.state == State.CAPTURING && SystemClock.elapsedRealtime() > capture.captureDeadline ||
            (capture.state == State.ANALYZING || capture.state == State.READY) && age > JevScreenDecision.MAX_AGE_MS) {
            capture.state = State.EXPIRED
            capture.decision = null
            capture.message = "Suggestion expired. Capture the screen again."
        }
    }

    private fun snapshot(context: Context, capture: Capture): JSONObject {
        expire(capture)
        if (capture.state == State.ACCEPTED) {
            BackgroundScreenTasks.status(context, capture.id)?.let { return accepted(capture.id, it) }
        }
        val decision = capture.decision
        val parts = decision?.suggestion?.split('\n', limit = 2)
        val title = when (capture.state) {
            State.CAPTURING, State.ANALYZING -> if (capture.manual) "See how Taku can help" else "Analyzing current screen"
            State.READY -> parts?.firstOrNull().orEmpty()
            State.NO_SUGGESTION -> "No suggestion this time"
            State.NO_SCREENSHOT -> "No screenshot from Android"
            State.EXPIRED -> "Suggestion expired"
            State.DISMISSED -> "Suggestion dismissed"
            State.ACCEPTED -> "Background task accepted"
            State.FAILED -> "Screen analysis unavailable"
        }
        return JSONObject().put("captureId", capture.id).put("state", capture.state.name.lowercase(java.util.Locale.ROOT))
            .put("manual", capture.manual).put("expiresAt", capture.expiresAt).put("title", title)
            .put("suggestion", if (capture.state == State.READY) parts?.getOrNull(1).orEmpty() else "")
            .put("message", capture.message).apply {
                decision?.receipt?.let { source ->
                    val receipt = JSONObject()
                    for (key in arrayOf("generated", "userTriggered", "gate", "model", "systemRevision", "inputMode", "gateway", "elapsedMs", "imageWidth", "imageHeight", "gateModel"))
                        if (source.has(key)) receipt.put(key, source.get(key))
                    put("receipt", receipt)
                }
            }
    }

    private fun accepted(id: String, job: JSONObject) = JSONObject().put("captureId", id).put("state", "accepted")
        .put("manual", job.optBoolean("manual")).put("expiresAt", 0).put("title", "Background task accepted").put("suggestion", "")
        .put("message", "The task belongs to the background assistant. You can close this screen.").put("job", job)

    fun decode(descriptor: ParcelFileDescriptor, suggestion: Boolean = true): Bitmap {
        if (Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE != OsConstants.O_RDONLY)
            throw IOException("INVALID_CAPTURE")
        return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { stream ->
            val bytes = stream.readNBytes(16_777_217)
            if (bytes.size > 16_777_216) throw IOException("INVALID_CAPTURE")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 16_777_216)
                throw IOException("INVALID_CAPTURE")
            val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("INVALID_CAPTURE")
            if (!suggestion) return@use image
            try { JevScreenDecision.capture(image) }
            finally { image.recycle() }
        }
    }

    private fun foreground(context: Context): String {
        try {
            val manager = context.getSystemService(ActivityManager::class.java)
            for (task in manager.getRunningTasks(20)) {
                val activity = task.topActivity ?: continue
                if (task.javaClass.getField("displayId").getInt(task) == 0 &&
                    activity.packageName != context.packageName && activity.packageName != "org.agentos.agent")
                    return activity.flattenToShortString()
            }
        } catch (_: Exception) { }
        return "Current app"
    }

    private fun <T> onMain(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val task = FutureTask(action)
        main.post(task)
        try { return task.get(5, TimeUnit.SECONDS) }
        catch (failure: ExecutionException) { throw failure.cause ?: failure }
        catch (failure: Exception) { task.cancel(false); throw failure }
    }
}
