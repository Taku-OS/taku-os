package org.agentos.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Signed ordinary-UID UI to system-owned runtime boundary. */
class AgentGatewayProvider : ContentProvider() {
    private val calls = Semaphore(4)
    private val streams = LinkedHashMap<String, SessionStream>()
    private val streamExpiry = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "agent-stream-expiry").apply { isDaemon = true }
    }

    private class SessionStream(val uid: Int, val request: JSONObject) {
        val created = SystemClock.elapsedRealtime()
        var opened = false
        @Volatile var closed = false
        var socket: LocalSocket? = null
        var output: ParcelFileDescriptor? = null

        @Synchronized
        fun close() {
            closed = true
            try { socket?.close() } catch (_: IOException) { }
            try { output?.close() } catch (_: IOException) { }
        }
    }

    private val payloads = LinkedHashMap<String, Payload>()
    private val completeHistory = ConcurrentHashMap<String, Set<String>>()
    private val previewCatalog = ChatWorkspacePreviewProtocol.VerifiedCatalog()
    private lateinit var submissions: AgentGatewaySubmissions
    private lateinit var matters: MatterRuntime
    private lateinit var matterStreams: MatterStreams

    private class Payload(val bytes: ByteArray) {
        val created = SystemClock.elapsedRealtime()
    }

    override fun onCreate(): Boolean {
        submissions = AgentGatewaySubmissions.get(context!!.filesDir)
        matters = MatterRuntime.get(context!!)
        matterStreams = MatterStreams(matters)
        return true
    }

    private fun enforceCaller() {
        context!!.enforceCallingPermission(AgentGatewayProtocol.PERMISSION, "Signed Agent UI required")
        val manager = context!!.packageManager
        val uid = Binder.getCallingUid()
        val names = manager.getPackagesForUid(uid)
        if (uid == android.os.Process.SYSTEM_UID || names == null || names.size != 1 ||
            AgentGatewayProtocol.CLIENT_PACKAGE != names[0] ||
            manager.checkSignatures(context!!.packageName, names[0]) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Agent client identity mismatch")
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if ("nativeMatter" == method || "nativeAudio" == method) {
            val uid = Binder.getCallingUid()
            if (uid != 0 && uid != android.os.Process.SYSTEM_UID && uid != android.os.Process.SHELL_UID)
                throw SecurityException("Native runtime only")
        } else enforceCaller()
        val output = Bundle()
        if (!calls.tryAcquire()) return error(output, "BUSY")
        val callerUid = Binder.getCallingUid()
        val identity = Binder.clearCallingIdentity()
        try {
            if (method == "assistCapture") {
                if (arg == null || arg.length > 4096) throw IOException("INVALID_ARGUMENT")
                val input = JSONObject(arg)
                if (input.keys().asSequence().any { it !in setOf("jevTestToken", "manualScreen", "captureForeground") }) throw IOException("INVALID_ARGUMENT")
                val token = input.getString("jevTestToken")
                if (!JevTestTrigger.pending(token)) throw IOException("CAPTURE_EXPIRED")
                val descriptor = extras?.getParcelable("capture", ParcelFileDescriptor::class.java)
                val bitmap = descriptor?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream ->
                    val bytes = stream.readNBytes(16_777_217)
                    if (bytes.size > 16_777_216) throw IOException("INVALID_CAPTURE")
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                        bounds.outWidth.toLong() * bounds.outHeight > 16_777_216) throw IOException("INVALID_CAPTURE")
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IOException("INVALID_CAPTURE")
                } }
                val shown = CompletableFuture<Boolean>()
                Handler(Looper.getMainLooper()).post {
                    try {
                        if (!JevTestTrigger.pending(token)) { shown.complete(false); return@post }
                        NativeControlsActivity.open(context!!, Bundle().apply {
                            putString("jevTestToken", token)
                            putBoolean("manualScreen", input.optBoolean("manualScreen"))
                            putString("captureForeground", input.optString("captureForeground", "Current app").take(256))
                        }, bitmap)
                        shown.complete(true)
                    } catch (_: Exception) { shown.complete(false) }
                }
                if (!shown.get(3, TimeUnit.SECONDS)) throw IOException("NATIVE_CONTROLS_UNAVAILABLE")
                output.putString("result", JSONObject().put("ok", true).put("result", JSONObject().put("opened", true)).toString())
                return output
            }
            val request: JSONObject
            if ("nativeMatter" == method || "nativeAudio" == method) {
                if (arg == null || arg.length > AgentGatewayProtocol.MAX_REQUEST) throw IOException("REQUEST_TOO_LARGE")
                request = JSONObject(arg)
            } else request = AgentGatewayProtocol.request(method, arg)
            val envelope = JSONObject().put("ok", true).put("result", execute(method, request, callerUid))
            val bytes = envelope.toString().toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_RESULT_BYTES) return error(output, "RESPONSE_TOO_LARGE")
            if (bytes.size <= INLINE_BYTES) output.putString("result", envelope.toString())
            else {
                val token = retain(bytes)
                output.putString("result", JSONObject().put("ok", true).put("resultUri", uri("result", token).toString()).toString())
            }
        } catch (failure: Exception) {
            val code = failure.message
            error(output, if (code != null && java.util.regex.Pattern.matches("[A-Z][A-Z_]{2,80}", code)) code else "RUNTIME_UNAVAILABLE")
        } finally {
            Binder.restoreCallingIdentity(identity)
            calls.release()
        }
        return output
    }

    private fun error(output: Bundle, code: String): Bundle {
        try {
            output.putString("result", JSONObject().put("ok", false).put("code", code).put("message", code).toString())
        } catch (_: Exception) { }
        return output
    }

    private fun preferences(): SharedPreferences = context!!.getSharedPreferences("agent-settings", 0)

    private fun execute(action: String, input: JSONObject, callerUid: Int): JSONObject {
        val thread = input.optString("threadId")
        when (action) {
            "nativeMatter" -> return matters.nativeAction(input)
            "panels" -> return matters.panels.call(input.getString("operation"), input.getJSONObject("args"))
            "cardView" -> return matters.panels.cardView(input.getString("operation"), input.getJSONObject("args"))
            "readMatter" -> return matters.read(input.getString("matterId"))
            "subscribeMatter" -> return matterStreams.subscribe(input.getJSONArray("matterIds"), callerUid)
            "unsubscribeMatter" -> return matterStreams.unsubscribe(input.getString("subscriptionId"), callerUid)
            "dispatchMatter" -> return matters.dispatch(input)
            "analyzeCameraFrame" -> return CameraGuide.analyze(input)
            "audioCapabilities" -> return AudioSessionBridge.get(context!!).capabilities()
            "audioCommand" -> return audioCommand(input)
            "nativeAudio" -> {
                val params = JSONObject(input.toString())
                val operation = params.getString("action")
                params.remove("action")
                val nativeThread = params.optString("threadId")
                params.remove("threadId")
                if (operation == "capabilities") {
                    if (params.length() != 0) throw IOException("UNSUPPORTED_PARAMETER")
                    return AudioSessionBridge.get(context!!).capabilities()
                }
                if (operation != "command") throw IOException("UNSUPPORTED_AUDIO_ACTION")
                val identity = AgentGatewayProtocol.matterIdentity(params.getString("matterId"))
                if (identity[0] != nativeThread) throw IOException("AUDIO_MATTER_MISMATCH")
                return audioCommand(AgentGatewayProtocol.request("audioCommand", params.toString()))
            }
            "readSystemAi", "setSystemAi", "bindSystemAi", "bindSystemAiModel", "reorderSystemAi", "restoreSystemAi",
            "deleteSystemAiGroup", "openAssistantConnection", "openMagpieManagement" -> return SystemAiUiBridge.execute(context!!, action, input)
            "readAppPermissions", "setAppPermissionMode", "setAppPermissionScope", "resetAppPermission" ->
                return AppPermissionUiBridge.execute(context!!, action, input)
            "listThreads" -> return AgentGatewayProtocol.clean(TaskRequests.control("thread/list", JSONObject()), 0) as JSONObject
            "createThread" -> {
                val result = TaskRequests.control("thread/start", JSONObject())
                val created = result.getJSONObject("thread")
                completeHistory[created.getString("id")] = historyIds(created)
                return AgentGatewayProtocol.clean(result, 0) as JSONObject
            }
            "resumeThread" -> {
                previewCatalog.invalidate()
                val resumed = TaskRequests.control("thread/read", JSONObject().put("threadId", thread))
                if (resumed.optBoolean("threadUnavailable") || !resumed.optBoolean("loaded") || thread != resumed.optString("threadId"))
                    throw IOException("THREAD_UNAVAILABLE")
                val selected = snapshot(thread, false)
                if (!preferences().edit().putString("conversation", thread).commit()) throw IOException("SELECTION_SAVE_FAILED")
                return selected
            }
            "readSession" -> return snapshot(thread, input.optBoolean("refresh"), input.optBoolean("statusOnly"))
            "subscribeSession" -> return subscribe(input, callerUid)
            "unsubscribeSession" -> return unsubscribe(input.getString("subscriptionId"), callerUid)
            "sendMessage" -> return submit(input)
            "steer" -> return submissions.steer(input.getString("requestId"), thread, input.getString("expectedTurnId"), input.getString("text"), {
                val state = NativeClient.state(thread)
                if (input.getString("expectedTurnId") != state.optString("activeTurnId")) throw IOException("TURN_CHANGED")
            }, {
                NativeClient.rpcSync("turn/steer", JSONObject().put("threadId", thread)
                    .put("expectedTurnId", input.getString("expectedTurnId")).put("input", textInput(input.getString("text"))))
            }, object : AgentGatewaySubmissions.Fallback {
                @Throws(Exception::class)
                override fun check() { checkSubmitAfterTurn(thread) }
                @Throws(Exception::class)
                override fun publish(before: TaskRequests.BeforePublish): String = publishSubmit(thread, input.getString("text"), false, before)
                override fun published(taskId: String): Boolean = java.util.regex.Pattern.matches("[0-9]+", taskId) && File(File(AssistantSession.TASKS, taskId), "ready").isFile
            })
            "cancelQueued" -> {
                val taskId = input.getString("taskId")
                val task = File(AssistantSession.TASKS, taskId)
                val original = readObject(File(task, "request.json"))
                if (thread != original.optString("conversationId")) throw IOException("TASK_THREAD_MISMATCH")
                if (File(task, "done").isFile) throw IOException("TASK_FINISHED")
                if (NativeClient.state(thread).optString("activeTurnId").isNotEmpty()) throw IOException("TURN_STARTED")
                TaskRequests.write(File(task, "cancel"), "1")
                return JSONObject().put("threadId", thread).put("taskId", taskId).put("cancellationRequested", true)
            }
            "interrupt" -> {
                val state = NativeClient.state(thread)
                if (input.getString("turnId") != state.optString("activeTurnId")) throw IOException("TURN_CHANGED")
                val goal = state.optJSONObject("goal")
                if (goal != null && "active" == goal.optString("status"))
                    NativeClient.rpcSync("thread/goal/set", JSONObject().put("threadId", thread).put("status", "paused").put("origin", "user"))
                return NativeClient.rpcSync("turn/interrupt", JSONObject().put("threadId", thread).put("turnId", input.getString("turnId")))
            }
            "answer" -> {
                AgentGatewayProtocol.pending(NativeClient.state(thread), thread, input.getString("key"))
                // The broker validates the current request/turn and consumes the answer atomically.
                return NativeClient.exchange(JSONObject().put("op", "respond").put("key", input.getString("key")).put("response", input.getJSONObject("response")))
            }
            "getWorkspaces" -> return workspaces(thread)
            "openNativeConversation" -> {
                val current = TaskRequests.control("thread/read", JSONObject().put("threadId", thread))
                if (current.optBoolean("threadUnavailable") || !current.optBoolean("loaded") || thread != current.optString("threadId"))
                    throw IOException("THREAD_UNAVAILABLE")
                val shown = CompletableFuture<Boolean>()
                Handler(Looper.getMainLooper()).post {
                    try {
                        val args = Bundle()
                        args.putString("conversationId", thread)
                        args.putBoolean(AgentFrontendRoute.NATIVE_CONTROLS_ONLY, true)
                        NativeControlsActivity.open(context!!, args, null)
                        shown.complete(true)
                    } catch (_: Exception) { shown.complete(false) }
                }
                if (!shown.get(3, TimeUnit.SECONDS)) throw IOException("NATIVE_CONTROLS_UNAVAILABLE")
                return JSONObject().put("opened", true).put("threadId", thread)
            }
            "openWorkspace" -> {
                val lease = exactLease(thread, input.getString("screenKey"), input.getString("generation"))
                val intent = Intent(context!!, MirrorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                for (key in arrayOf("threadId", "screenKey", "generation")) intent.putExtra(key, lease.getString(key))
                intent.putExtra(AgentFrontendLauncher.RETURN_AGENT_FRONTEND, true)
                intent.putExtra(AgentFrontendLauncher.RETURN_CONVERSATION_ID, thread)
                context!!.startActivity(intent)
                return JSONObject().put("opened", true)
            }
            "openSettings" -> {
                val page = input.optString("page", "models")
                val screen = if (page == "system") SystemAiActivity::class.java
                    else if (page == "workspaces") BackgroundWorkspacesActivity::class.java else ModelManagementActivity::class.java
                context!!.startActivity(Intent(context!!, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return JSONObject().put("opened", true)
            }
            "pluginManager" -> return PluginManagerBridge.execute(input)
            "decide" -> {
                if (!preferences().getBoolean("jevEnabled", false)) throw IOException("JEV_DISABLED")
                val model = preferences().getString("jevModel", "")!!
                if (model.isEmpty()) throw IOException("JEV_NOT_CONFIGURED")
                return AgentGatewayProtocol.clean(JevApiClient().decide(model, input.getJSONObject("state"), input.getJSONObject("questions"), true), 0) as JSONObject
            }
            "generateUi" -> return AgentGatewayProtocol.clean(NativeClient.exchange(JSONObject().put("op", "ui")
                .put("instructions", input.getString("instructions")).put("prompt", input.getString("prompt"))), 0) as JSONObject
            "mainReply" -> {
                val params = JSONObject(input.toString())
                params.remove("action")
                return AgentGatewayProtocol.replyRead(params) { request -> NativeClient.exchange(request) }
            }
            else -> throw IOException("UNSUPPORTED_ACTION")
        }
    }

    private fun audioCommand(input: JSONObject): JSONObject = matters.retain(input.getString("matterId")).use {
        retainedAudioCommand(input)
    }

    private fun retainedAudioCommand(input: JSONObject): JSONObject {
        val matterId = input.getString("matterId")
        val requestId = input.getString("requestId")
        val thread = AgentGatewayProtocol.matterIdentity(matterId)[0]
        matters.read(matterId)
        val params = JSONObject(input.toString()).put("threadId", thread)
        params.remove("requestId")
        if (input.getString("operation") == "status") {
            val source = AudioSessionBridge.get(context!!).command(params)
            matters.registerAudio(matterId, source)
            return JSONObject().put("source", source)
        }
        val receipt = submissions.steer(requestId, thread, "local-media-session", input.toString(), {
            matters.read(matterId)
            if (params.optString("operation") == "start" && params.optString("purpose") == "MEDIA_TRANSLATE")
                params.put("targetPackage", AudioPlaybackTarget.resolve(context!!, params.optString("targetPackage")))
        }, { AudioSessionBridge.get(context!!).command(params) })
        receipt.put("effect", "local-media-session")
        val result = JSONObject().put("receipt", receipt)
        var source = receipt.optJSONObject("result")
        if (source != null && source.has("resourceId")) {
            source = AudioSessionBridge.get(context!!).read(JSONObject().put("resourceId", source.getString("resourceId"))
                .put("threadId", thread).put("matterId", matterId))
            matters.registerAudio(matterId, source)
            result.put("source", source)
        }
        return result
    }

    private fun snapshot(threadId: String, refresh: Boolean): JSONObject = snapshot(threadId, refresh, false)

    private fun snapshot(threadId: String, refresh: Boolean, statusOnly: Boolean): JSONObject {
        val projection = File(AssistantSession.ROOT, "agent-view/" + threadId + ".json")
        val nativeState = try { NativeClient.state(threadId) } catch (_: Exception) { JSONObject().put("ready", false) }
        val result: JSONObject
        if (statusOnly) result = JSONObject().put("thread", JSONObject().put("id", threadId))
            .put("native", AgentGatewayProtocol.nativeView(nativeState, threadId)).put("statusOnly", true)
        else {
            var thread = if (projection.isFile) readObject(projection) else null
            var historyError = ""
            if (refresh && nativeState.optString("activeTurnId").isEmpty()) {
                try {
                    val refreshed = NativeClient.rpcSync("thread/read", JSONObject().put("threadId", threadId).put("includeTurns", true))
                    thread = refreshed.getJSONObject("thread")
                    if (threadId != thread.optString("id")) throw IOException("THREAD_ID_MISMATCH")
                    if (thread.has("turns")) completeHistory[threadId] = historyIds(thread)
                } catch (_: Exception) { historyError = "HISTORY_REFRESH_UNAVAILABLE" }
            }
            if (thread == null) throw IOException("THREAD_UNAVAILABLE")
            if (threadId != thread.optString("id")) throw IOException("THREAD_ID_MISMATCH")
            val proof = completeHistory[threadId]
            var complete = proof != null && historyIds(thread).containsAll(proof)
            val projectionInfo = thread.optJSONObject("_projection")
            if (projectionInfo != null) complete = projectionInfo.optBoolean("historyComplete", complete)
            if (complete) completeHistory[threadId] = historyIds(thread)
            val turns = thread.optJSONArray("turns")
            matters.observeThread(thread)
            result = JSONObject().put("thread", AgentGatewayProtocol.threadView(thread))
                .put("native", AgentGatewayProtocol.nativeView(nativeState, threadId))
                .put("historyComplete", complete).put("historyStatus", if (complete) "complete" else "unknown")
                .put("knownEmpty", complete && turns != null && turns.length() == 0).put("totalTurns", if (turns == null) 0 else turns.length())
            if (historyError.isNotEmpty()) result.put("historyError", historyError)
        }
        val taskId = preferences().getString("task-" + threadId, "")!!
        if (java.util.regex.Pattern.matches("[0-9]+", taskId)) {
            val task = File(AssistantSession.TASKS, taskId)
            val state = File(task, "state.json")
            val request = File(task, "request.json")
            val runtime = File(task, "runtime-info.json")
            if (!request.isFile || threadId != readObject(request).optString("conversationId")) return result
            if (state.isFile) result.put("task", AgentGatewayProtocol.clean(readObject(state)
                .put("published", File(task, "ready").isFile).put("cancelRequested", File(task, "cancel").isFile).put("turnStarted", false), 0))
            if (request.isFile) {
                val raw = readObject(request)
                if (threadId == raw.optString("conversationId")) result.put("request", JSONObject().put("prompt", raw.optString("prompt")))
            }
            if (runtime.isFile) {
                val raw = readObject(runtime)
                val visible = JSONObject()
                for (key in arrayOf("turnStarted", "turnId", "threadId", "conversationId")) if (raw.has(key)) visible.put(key, raw.get(key))
                result.put("runtime", visible)
                if (result.has("task")) result.getJSONObject("task").put("turnStarted", raw.optBoolean("turnStarted"))
            }
        }
        return result
    }

    @Throws(Exception::class)
    private fun submit(input: JSONObject): JSONObject = matters.services.submit(input)

    private fun checkSubmit(thread: String) { matters.services.checkSubmit(thread) }

    private fun publishSubmit(thread: String, text: String, requireConfirmation: Boolean, before: TaskRequests.BeforePublish): String =
        matters.services.publish(JSONObject().put("threadId", thread).put("text", text).put("requireConfirmation", requireConfirmation), before)

    private fun checkSubmitAfterTurn(thread: String) {
        var attempt = 0
        while (true) {
            try { checkSubmit(thread); return } catch (busy: IOException) {
                if (attempt >= 19 || ("THREAD_BUSY" != busy.message && "THREAD_QUEUED" != busy.message)) throw busy
                Thread.sleep(250)
            }
            attempt++
        }
    }

    private fun workspaces(thread: String): JSONObject {
        val revision = previewCatalog.beginRead()
        try {
            val catalog = NativeClient.exchange(JSONObject().put("op", "workspaces").put("action", "list").put("threadId", thread))
            val checkedAt = SystemClock.elapsedRealtime()
            val verified = ChatWorkspacePreviewProtocol.CatalogSnapshot(catalog, checkedAt, checkedAt)
            val selected = verified.select(thread, SystemClock.elapsedRealtime())
            for (i in 0 until selected.length()) {
                val screen = selected.getJSONObject(i)
                screen.put("previewUri", uri("preview", thread, screen.getString("screenKey"), screen.getString("generation")).toString())
            }
            // A stale read must never publish over the winner.
            previewCatalog.publish(revision, verified)
            return JSONObject().put("schema", "taku.agent-workspaces/1").put("screens", selected)
        } catch (failure: Exception) {
            previewCatalog.failedRead(revision)
            throw failure
        }
    }

    private fun exactLease(thread: String, key: String, generation: String): JSONObject {
        val screens = workspaces(thread).getJSONArray("screens")
        for (i in 0 until screens.length()) {
            val screen = screens.getJSONObject(i)
            if (key == screen.optString("screenKey") && generation == screen.optString("generation")) return screen
        }
        throw IOException("SCREEN_LEASE_CHANGED")
    }

    private fun preview(thread: String, key: String, generation: String): ByteArray {
        val lease = previewCatalog.lease(thread, key, generation, SystemClock.elapsedRealtime())
        val binding = lease.binding
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress("taku-mirror-" + key, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 2500
                val input = DataInputStream(socket.inputStream)
                val meta = JSONObject(String(mirrorCall(socket, input, "status", 65536), StandardCharsets.UTF_8))
                ChatWorkspacePreviewProtocol.validateHost(binding, meta, socket.peerCredentials.uid, socket.peerCredentials.pid)
                val frame = mirrorCall(socket, input, "frame", ChatWorkspacePreviewProtocol.MAX_FRAME_BYTES)
                val size = BitmapFactory.Options()
                size.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(frame, 0, frame.size, size)
                if (frame.size < 3 || (frame[0].toInt() and 255) != 255 || (frame[1].toInt() and 255) != 216 ||
                    size.outWidth != meta.getInt("width") || size.outHeight != meta.getInt("height")) throw IOException("INVALID_FRAME")
                previewCatalog.recheck(lease, SystemClock.elapsedRealtime())
                return frame
            }
        } catch (failure: Exception) {
            previewCatalog.failedLease(lease)
            throw failure
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        enforceCaller()
        if ("r" != mode || AgentGatewayProtocol.AUTHORITY != uri.authority) throw FileNotFoundException("Read only")
        if (!calls.tryAcquire()) throw FileNotFoundException("BUSY")
        val callerUid = Binder.getCallingUid()
        val identity = Binder.clearCallingIdentity()
        try {
            val parts = uri.pathSegments
            if (parts.size == 2 && parts[0] == "events") return openSession(parts[1], callerUid)
            if (parts.size == 2 && parts[0] == "matter-events") return matterStreams.open(parts[1], callerUid)
            if (parts.size == 2 && (parts[0] == "audio" || parts[0] == "audio-transcript")) {
                val thread = uri.getQueryParameter("threadId")
                AgentGatewayProtocol.requireId(JSONObject().put("threadId", thread), "threadId")
                AgentGatewayProtocol.requireId(JSONObject().put("sessionId", parts[1]), "sessionId")
                if (parts[0] == "audio-transcript") return AudioSessionBridge.get(context!!).openTranscript(parts[1], thread!!)
                return AudioSessionBridge.get(context!!).openRecording(parts[1], uri.getQueryParameter("recordingId"), thread!!)
            }
            val bytes: ByteArray
            if (parts.size == 2 && parts[0] == "result") bytes = take(parts[1])
            else if (parts.size == 4 && parts[0] == "preview") {
                val lease = AgentGatewayProtocol.request("openWorkspace", JSONObject().put("threadId", parts[1])
                    .put("screenKey", parts[2]).put("generation", parts[3]).toString())
                bytes = preview(lease.getString("threadId"), lease.getString("screenKey"), lease.getString("generation"))
            } else throw IOException("UNKNOWN_RESOURCE")
            return openPipeHelper(uri, getType(uri), null, bytes) { output, _, _, _, data ->
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream -> stream.write(data) }
                } catch (_: IOException) { }
            }
        } catch (_: Exception) {
            throw FileNotFoundException("RESOURCE_UNAVAILABLE")
        } finally {
            Binder.restoreCallingIdentity(identity)
            calls.release()
        }
    }

    @Synchronized
    private fun subscribe(request: JSONObject, uid: Int): JSONObject {
        pruneStreams()
        if (streams.size >= 4) throw IOException("STREAM_BUSY")
        val token = UUID.randomUUID().toString()
        streams[token] = SessionStream(uid, JSONObject(request.toString()))
        return JSONObject().put("streamUri", uri("events", token).toString()).put("subscriptionId", token)
    }

    @Synchronized
    private fun unsubscribe(token: String, uid: Int): JSONObject {
        val stream = streams[token]
        if (stream != null) {
            if (stream.uid != uid) throw IOException("STREAM_IDENTITY_MISMATCH")
            streams.remove(token)
            stream.close()
        }
        return JSONObject().put("closed", true)
    }

    private fun pruneStreams() {
        val now = SystemClock.elapsedRealtime()
        streams.values.removeIf { stream ->
            if (stream.closed || now - stream.created > (if (stream.opened) 300_000 else 30_000)) {
                stream.close()
                true
            } else false
        }
    }

    @Synchronized
    private fun openSession(token: String, uid: Int): ParcelFileDescriptor {
        pruneStreams()
        val stream = streams[token]
        if (stream == null || stream.opened || stream.uid != uid) throw IOException("STREAM_EXPIRED")
        stream.opened = true
        val pipe = ParcelFileDescriptor.createReliablePipe()
        stream.output = pipe[1]
        Os.fcntlInt(pipe[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        val expiry = streamExpiry.schedule({ stream.close() }, 5, TimeUnit.MINUTES)
        val worker = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                    val socket = NativeClient.openSessionStream(stream.request)
                    synchronized(stream) {
                        if (stream.closed) {
                            socket.close()
                            return@Thread
                        }
                        stream.socket = socket
                    }
                    BufferedInputStream(socket.inputStream).use { input ->
                        while (true) {
                            val line = readEventLine(input) ?: break
                            val event = AgentGatewayProtocol.sessionEvent(JSONObject(line), stream.request.getString("threadId"))
                            try {
                                val params = event.getJSONObject("params")
                                when (event.optString("method")) {
                                    "session/snapshot" -> matters.observeThread(params.getJSONObject("thread"))
                                    "session/reply" -> matters.observeReply(stream.request.getString("threadId"), params.getString("turnId"), params.getJSONObject("reply"))
                                    "turn/completed" -> matters.observeThread(JSONObject().put("id", stream.request.getString("threadId"))
                                        .put("turns", JSONArray().put(params.getJSONObject("turn"))))
                                }
                            } catch (_: Exception) { System.err.println("Agent card projection unavailable") }
                            val bytes = (event.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                            if (bytes.size > MAX_RESULT_BYTES) throw IOException("STREAM_EVENT_TOO_LARGE")
                            writeEvent(stream, bytes)
                        }
                    }
                }
            } catch (_: Exception) {
                // EOF/error requires reconnecting with a new baseline.
            } finally {
                expiry.cancel(false)
                stream.close()
                synchronized(this@AgentGatewayProvider) { streams.remove(token) }
            }
        }, "agent-session-stream")
        worker.isDaemon = true
        worker.start()
        return pipe[0]
    }

    @Synchronized
    private fun retain(bytes: ByteArray): String {
        prune()
        var total = bytes.size
        for (payload in payloads.values) total += payload.bytes.size
        if (payloads.size >= 8 || total > MAX_BUFFERED_BYTES) throw IOException("BUSY")
        val token = UUID.randomUUID().toString()
        payloads[token] = Payload(bytes)
        return token
    }

    @Synchronized
    private fun take(token: String): ByteArray {
        prune()
        val payload = payloads.remove(token) ?: throw IOException("RESULT_EXPIRED")
        return payload.bytes
    }

    private fun prune() {
        val now = SystemClock.elapsedRealtime()
        payloads.values.removeIf { now - it.created > 30_000 }
    }

    override fun getType(uri: Uri): String {
        val path = uri.path!!
        return if (path.startsWith("/preview/")) "image/jpeg"
            else if (path.startsWith("/audio/")) "audio/mp4"
            else if (path.startsWith("/events/") || path.startsWith("/matter-events/") || path.startsWith("/audio-transcript/")) "application/x-ndjson"
            else "application/json"
    }
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, order: String?): Cursor = throw UnsupportedOperationException()
    override fun insert(u: Uri, v: ContentValues?): Uri = throw UnsupportedOperationException()
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(u: Uri, s: String?, a: Array<out String>?): Int = throw UnsupportedOperationException()

    companion object {
        private const val INLINE_BYTES = 256 * 1024
        private const val MAX_RESULT_BYTES = 8 * 1024 * 1024
        private const val MAX_BUFFERED_BYTES = 16 * 1024 * 1024

        private fun textInput(text: String): JSONArray = JSONArray().put(JSONObject().put("type", "text").put("text", text))

        private fun mirrorCall(socket: LocalSocket, input: DataInputStream, command: String, limit: Int): ByteArray {
            socket.outputStream.write((command + "\n").toByteArray(StandardCharsets.UTF_8))
            socket.outputStream.flush()
            val size = input.readInt()
            if (size < 0 || size > limit) throw IOException("INVALID_FRAME_SIZE")
            val result = ByteArray(size)
            input.readFully(result)
            return result
        }

        private fun writeEvent(stream: SessionStream, bytes: ByteArray) {
            val descriptor = stream.output!!.fileDescriptor
            val poll = StructPollfd()
            poll.fd = descriptor
            poll.events = OsConstants.POLLOUT.toShort()
            var offset = 0
            while (offset < bytes.size) {
                if (stream.closed) throw IOException("STREAM_CLOSED")
                try { offset += Os.write(descriptor, bytes, offset, bytes.size - offset) } catch (busy: ErrnoException) {
                    if (busy.errno != OsConstants.EAGAIN) throw busy
                    Os.poll(arrayOf(poll), 1000)
                }
            }
        }

        @Throws(IOException::class)
        private fun readEventLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = input.read()
                if (value == -1) break
                if (value == '\n'.code) return bytes.toString("UTF-8")
                bytes.write(value)
                if (bytes.size() > MAX_RESULT_BYTES) throw IOException("STREAM_EVENT_TOO_LARGE")
            }
            if (bytes.size() > 0) throw IOException("INCOMPLETE_STREAM_EVENT")
            return null
        }

        private fun uri(vararg parts: String): Uri {
            val builder = Uri.Builder().scheme("content").authority(AgentGatewayProtocol.AUTHORITY)
            for (part in parts) builder.appendPath(part)
            return builder.build()
        }

        private fun readObject(file: File): JSONObject {
            FileInputStream(file).use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    bytes.write(buffer, 0, count)
                    if (bytes.size() > MAX_RESULT_BYTES) throw IOException("RESPONSE_TOO_LARGE")
                }
                return JSONObject(bytes.toString("UTF-8"))
            }
        }

        private fun historyIds(thread: JSONObject): Set<String> {
            val ids = HashSet<String>()
            val turns = thread.optJSONArray("turns")
            if (turns != null) for (i in 0 until turns.length()) {
                val turn = turns.getJSONObject(i)
                val id = turn.optString("id")
                ids.add("turn:" + id)
                val items = turn.optJSONArray("items")
                if (items != null) for (j in 0 until items.length()) ids.add("item:" + id + ":" + items.getJSONObject(j).optString("id"))
            }
            return ids
        }
    }
}
