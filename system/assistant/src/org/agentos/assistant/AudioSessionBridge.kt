package org.agentos.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.ArrayList
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Application-owned sessions over the installed engine's public AudioClient Messenger contract.
 * Commands run off the main thread. Detaching a view never stops capture.
 */
internal class AudioSessionBridge private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val replies = HashMap<String, CompletableFuture<JSONObject>>()
    private val sessions = LinkedHashMap<String, Session>()
    private val notifications = WatchNotifications { command -> main.post(command) }
    private val early = HashMap<String, MutableList<JSONObject>>()
    private val directory = File(context.filesDir, "audio-sessions")
    private var service: Messenger? = null
    private var connection: CompletableFuture<Void>? = null
    private val callback = Messenger(Handler(Looper.getMainLooper()) { message ->
        receive(message.data.getString("json", "{}"))
        true
    })
    private var bound = false

    private inner class Session(val data: JSONObject) {
        var recorder: MicrophoneAacRecorder? = null
        var captureHost: AudioCaptureService? = null
        var recordingCallback: AudioManager.AudioRecordingCallback? = null
        var recording: File? = null
        var generation = ""
        var sequence = 0L
        var closed: CompletableFuture<Void> = CompletableFuture.completedFuture(null)
    }

    private val binding = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            synchronized(this@AudioSessionBridge) {
                service = Messenger(binder)
                connection!!.complete(null)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { disconnected("ENGINE_DISCONNECTED") }
        override fun onBindingDied(name: ComponentName?) { disconnected("ENGINE_BINDING_DIED") }
        override fun onNullBinding(name: ComponentName?) { disconnected("ENGINE_UNAVAILABLE") }
    }

    @Synchronized
    private fun disconnected(code: String) {
        service = null
        connection?.completeExceptionally(IOException(code))
        for (reply in replies.values) reply.completeExceptionally(IOException(code))
        replies.clear()
        early.clear()
        for (session in sessions.values) {
            if (session.generation.isEmpty()) continue
            try {
                session.data.put("status", "interrupted").put("error", code)
                stopRecording(session)
                changed(session)
            } catch (_: Exception) { }
            releaseMicrophone(session)
            session.closed.complete(null)
            session.generation = ""
        }
        if (bound) {
            context.unbindService(binding)
            bound = false
        }
        connection = null
    }

    @Synchronized
    private fun disconnectOwner() {
        if (service != null) try {
            val disconnect = Message.obtain(null, 2)
            disconnect.replyTo = callback
            service!!.send(disconnect)
        } catch (_: Exception) { }
        disconnected("ENGINE_OPEN_UNCONFIRMED")
    }

    private fun connect() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw IOException("AUDIO_REQUIRES_WORKER_THREAD")
        val ready: CompletableFuture<Void>
        synchronized(this) {
            if (connection == null) {
                connection = CompletableFuture()
                main.post {
                    synchronized(this@AudioSessionBridge) {
                        val attempt = connection
                        try {
                            bound = context.bindService(Intent().setComponent(ComponentName(
                                "org.agentos.audio", "org.agentos.audio.AudioEngineService")), binding,
                                Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES)
                            if (!bound) {
                                attempt!!.completeExceptionally(IOException("ENGINE_NOT_INSTALLED_OR_DENIED"))
                                connection = null
                            }
                        } catch (failure: Exception) {
                            attempt!!.completeExceptionally(failure)
                            connection = null
                        }
                    }
                }
            }
            ready = connection!!
        }
        await(ready, 12)
    }

    private fun call(method: String, params: JSONObject): JSONObject {
        connect()
        val id = UUID.randomUUID().toString()
        val reply = CompletableFuture<JSONObject>()
        synchronized(this) {
            replies[id] = reply
            val message = Message.obtain(null, 1)
            message.replyTo = callback
            val bundle = Bundle()
            bundle.putString("id", id)
            bundle.putString("method", method)
            bundle.putString("params", params.toString())
            message.data = bundle
            try { service!!.send(message) } catch (failure: Exception) {
                replies.remove(id)
                throw failure
            }
        }
        try {
            return await(reply, 15)
        } finally {
            synchronized(this) { replies.remove(id) }
        }
    }

    @Synchronized
    private fun receive(raw: String?) {
        try {
            val envelope = JSONObject(raw!!)
            if ("response" == envelope.optString("type")) {
                val reply = replies.remove(envelope.optString("id")) ?: return
                if (envelope.optBoolean("ok")) reply.complete(envelope.getJSONObject("result"))
                else reply.completeExceptionally(IOException(envelope.getJSONObject("error").toString()))
                return
            }
            if ("audio.session" != envelope.optString("event")) return
            val event = envelope.getJSONObject("data")
            val engineId = event.getString("sessionId")
            for (session in sessions.values) if (engineId == session.generation) {
                event(session, event, envelope.optString("timestamp"))
                return
            }
            // Preparing and Running can precede the open response on the engine's main thread.
            if (early.size >= 4 && !early.containsKey(engineId)) early.remove(early.keys.iterator().next())
            val buffered = early.computeIfAbsent(engineId) { ArrayList() }
            if (buffered.size == 16) buffered.removeAt(0)
            buffered.add(envelope)
        } catch (_: Exception) { }
    }

    @Throws(Exception::class)
    fun capabilities(): JSONObject = call("audio.capabilities", JSONObject()).put("fileInput", false)
        .put("engineRecordingExport", false).put("sessionPauseMode", "finish-and-reopen")
        .put("savedRecording", "assistant-microphone-capture")

    @Throws(Exception::class)
    fun command(input: JSONObject): JSONObject {
        val operation = input.getString("operation")
        if ("start" == operation) return start(input)
        val session: Session
        synchronized(this) {
            session = find(input.getString("sessionId"), input.getString("threadId"))
            checkMatter(session, input.getString("matterId"))
        }
        synchronized(session) {
            if ("summarize" == operation) {
                val transcript: File
                synchronized(this) {
                    if ("stopped" != session.data.optString("status") || "DICTATION" != session.data.getJSONObject("params").optString("purpose"))
                        throw IOException("SUMMARY_REQUIRES_STOPPED_MEETING")
                    transcript = File(directory, session.data.getString("resourceId") + ".jsonl")
                    session.data.put("summaryState", "generating")
                    session.data.remove("summaryError")
                    changed(session)
                }
                try {
                    val summary = AudioMeetingSummary.summarize(transcript, input.optString("language", "en"), session.data.getJSONArray("recordings"))
                    synchronized(this) {
                        session.data.put("summary", summary).put("summaryState", "ready")
                        changed(session)
                    }
                } catch (failure: Exception) {
                    synchronized(this) {
                        session.data.put("summaryState", "failed").put("summaryError", failure.message ?: "SUMMARY_UNAVAILABLE")
                        changed(session)
                    }
                }
                return snapshot(session)
            }
            if ("status" == operation) return snapshot(session)
            if ("resume" == operation) {
                synchronized(this) {
                    if ("paused" != session.data.optString("status")) throw IOException("SESSION_NOT_PAUSED")
                }
                open(session)
                return snapshot(session)
            }
            if ("pause" != operation && "stop" != operation) throw IOException("UNSUPPORTED_AUDIO_OPERATION")
            val engineId: String
            val ended: CompletableFuture<Void>
            synchronized(this) {
                val status = session.data.optString("status")
                if ("stopped" == status || "failed" == status || "interrupted" == status) return snapshot(session)
                if ("paused" == status) {
                    if ("stop" == operation) {
                        session.data.put("status", "stopped")
                        changed(session)
                    }
                    return snapshot(session)
                }
                session.data.put("requestedStatus", if ("pause" == operation) "paused" else "stopped")
                session.data.put("status", "draining")
                changed(session)
                engineId = session.generation
                ended = session.closed
            }
            call("audio.session.finish", JSONObject().put("sessionId", engineId))
            ended.get(105, TimeUnit.SECONDS)
            return snapshot(session)
        }
    }

    private fun start(input: JSONObject): JSONObject {
        val matterId = input.getString("matterId")
        val threadId = input.getString("threadId")
        if (!matterId.startsWith(threadId + "/") || threadId.isEmpty()) throw IOException("AUDIO_MATTER_MISMATCH")
        val purpose = input.optString("purpose", "DICTATION")
        if ("DICTATION" != purpose && "MEDIA_TRANSLATE" != purpose) throw IOException("UNSUPPORTED_AUDIO_PURPOSE")
        if (input.has("uri") || input.has("url") || input.has("file")) throw IOException("ENGINE_FILE_INPUT_UNSUPPORTED")
        if (input.optBoolean("recordAudio", "DICTATION" == purpose) && "DICTATION" != purpose)
            throw IOException("ENGINE_MEDIA_RECORDING_EXPORT_UNSUPPORTED")
        val params = JSONObject().put("purpose", purpose).put("language", input.optString("language", "zh-CN"))
        if (input.has("profileId")) params.put("profileId", input.getString("profileId"))
        if ("MEDIA_TRANSLATE" == purpose) params.put("targetLanguage", input.getString("targetLanguage"))
            .put("targetPackage", input.getString("targetPackage")).put("output", "CAPTIONS").put("originalAudio", "KEEP")
        val session = Session(JSONObject().put("resourceId", UUID.randomUUID().toString()).put("matterId", matterId).put("threadId", threadId)
            .put("status", "preparing").put("createdAt", System.currentTimeMillis()).put("sourceVersion", 0).put("params", params)
            .put("recordAudio", input.optBoolean("recordAudio", "DICTATION" == purpose)).put("segments", JSONArray())
            .put("recordings", JSONArray()).put("segmentsDropped", 0))
        synchronized(this) {
            for (existing in sessions.values) if (java.util.List.of("preparing", "running", "draining").contains(existing.data.optString("status")))
                throw IOException("RESOURCE_BUSY")
            if (sessions.size >= 32) {
                var evict: String? = null
                for ((key, value) in sessions) if (value.generation.isEmpty()) { evict = key; break }
                if (evict == null) throw IOException("AUDIO_SESSION_LIMIT")
                sessions.remove(evict)
            }
            sessions[session.data.getString("resourceId")] = session
            try { changed(session) } catch (_: Exception) {
                session.data.put("status", "failed").put("error", "AUDIO_STORAGE_UNAVAILABLE")
                return snapshot(session)
            }
        }
        synchronized(session) {
            open(session)
            return snapshot(session)
        }
    }

    private fun open(session: Session) {
        try {
            // Dictation captures in the engine's process, which only inherits a microphone
            // capability through its binding (BIND_INCLUDE_CAPABILITIES) from a client that has
            // one. Without this host the engine's VOICE_RECOGNITION capture is silenced
            // (CAPTURE_SILENCED, BUG-0045), whether or not the audio is also saved. Started outside
            // the bridge's lock: the service starts on the main thread, which may be waiting for it.
            if ("DICTATION" == session.data.getJSONObject("params").optString("purpose")) acquireMicrophone(session)
            val available = call("audio.capabilities", JSONObject())
            if (available.optJSONObject("resourceState") != null && available.getJSONObject("resourceState").optBoolean("busy"))
                throw IOException("RESOURCE_BUSY")
            synchronized(this) {
                for (other in sessions.values) if (other !== session && java.util.List.of("preparing", "running", "draining").contains(other.data.optString("status")))
                    throw IOException("RESOURCE_BUSY")
                if (session.data.getJSONArray("recordings").length() >= 64) throw IOException("RECORDING_CHUNK_LIMIT")
                session.data.remove("error")
                session.data.remove("requestedStatus")
                session.data.put("status", "preparing")
                session.data.put("generationStartedAt", System.currentTimeMillis()).put("generationStartedMonotonicMs", SystemClock.elapsedRealtime())
                if (session.data.optBoolean("recordAudio")) startRecording(session)
                session.closed = CompletableFuture()
            }
            val params = JSONObject(session.data.getJSONObject("params").toString()).put("requestKey", UUID.randomUUID().toString())
            val opened = call("audio.session.open", params)
            synchronized(this) {
                session.generation = opened.getString("sessionId")
                session.sequence = 0
                session.data.put("engineSessionId", session.generation).put("engine", opened)
                val buffered = early.remove(session.generation)
                if (buffered != null) for (envelope in buffered)
                    event(session, envelope.getJSONObject("data"), envelope.optString("timestamp"))
                changed(session)
            }
        } catch (failure: Exception) {
            var cause: Throwable = failure
            while (cause.cause != null && (cause is ExecutionException || cause is CompletionException)) cause = cause.cause!!
            val uncertain = cause is TimeoutException || cause is android.os.RemoteException
            synchronized(this) {
                if (uncertain) disconnectOwner()
                session.data.put("status", if (uncertain) "interrupted" else "failed").put("error", cause.message ?: cause.javaClass.simpleName)
                try { stopRecording(session) } catch (_: Exception) { session.data.put("recordingError", "RECORDING_FINALIZE_FAILED") }
                releaseMicrophone(session)
                session.closed.complete(null)
                try { changed(session) } catch (_: Exception) { session.data.put("storageError", "AUDIO_STORAGE_UNAVAILABLE") }
            }
        }
    }

    private fun event(session: Session, event: JSONObject, timestamp: String) {
        val sequence = event.getLong("sequence")
        if (sequence <= session.sequence) return
        session.sequence = sequence
        val latest = JSONObject(event.toString())
        val latestText = latest.optString("text")
        latest.put("text", latestText.substring(0, Math.min(500, latestText.length))).put("textTruncated", latestText.length > 500)
        session.data.put("engineEvent", latest).put("sourceTimestamp", timestamp).put("sourceMonotonicMs", event.optLong("monotonicMs"))
        val phase = event.optString("phase")
        if ("Running" == phase) session.data.put("status", "running")
        if ("Text" == event.optString("kind")) {
            val segments = session.data.getJSONArray("segments")
            val id = session.generation + ":" + event.optLong("segmentId") + ":" + event.optString("textKind") + ":" + event.optString("direction")
            var segment: JSONObject? = null
            for (i in segments.length() - 1 downTo 0) if (id == segments.getJSONObject(i).optString("id")) {
                segment = segments.getJSONObject(i)
                break
            }
            if (segment == null) {
                segment = JSONObject().put("id", id).put("firstObservedMonotonicMs", event.optLong("monotonicMs"))
                segments.put(segment)
                if (segments.length() > 16) {
                    segments.remove(0)
                    session.data.put("segmentsDropped", session.data.optLong("segmentsDropped") + 1)
                }
            }
            val text = event.optString("text")
            val wasFinal = segment.optBoolean("final")
            segment.put("text", text.substring(0, Math.min(500, text.length))).put("textTruncated", text.length > 500).put("final", event.optBoolean("final"))
                .put("sourceTimestamp", timestamp).put("sourceMonotonicMs", event.optLong("monotonicMs")).put("textKind", event.optString("textKind"))
                .put("direction", event.optString("direction")).put("timestampMeaning", "recognition-observed; not word alignment")
            if (session.recording != null) segment.put("recordingId", session.recording!!.name)
                .put("recordingObservedOffsetMs", Math.max(0L, event.optLong("monotonicMs") - session.data.optLong("recordingStartedMonotonicMs")))
            if (event.optBoolean("final") && !wasFinal) {
                val transcript = File(directory, session.data.getString("resourceId") + ".jsonl")
                if (transcript.length() >= 16L * 1024 * 1024) {
                    session.data.put("error", "TRANSCRIPT_STORAGE_LIMIT")
                    Thread({
                        try {
                            command(JSONObject().put("operation", "stop").put("sessionId", session.data.getString("resourceId"))
                                .put("threadId", session.data.getString("threadId")).put("matterId", session.data.getString("matterId")))
                        } catch (_: Exception) { }
                    }, "audio-storage-stop").start()
                } else {
                    val evidence = JSONObject(segment.toString()).put("text", text).put("textTruncated", false)
                    Files.write(transcript.toPath(), (evidence.toString() + "\n").toByteArray(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    session.data.put("transcriptAnchor", Uri.Builder().scheme("content").authority("org.agentos.assistant.agent-gateway")
                        .appendPath("audio-transcript").appendPath(session.data.getString("resourceId"))
                        .appendQueryParameter("threadId", session.data.getString("threadId")).build().toString())
                }
            }
        }
        if ("Closed" == phase) {
            val reason = event.optString("reason")
            val clean = "completed" == reason
            session.data.put("status", if (clean) session.data.optString("requestedStatus", "stopped") else "failed")
            if (!clean) session.data.put("error", reason)
            session.data.put("completion", event.optString("completion")).put("restored", event.optBoolean("restored"))
            stopRecording(session)
            releaseMicrophone(session)
            session.closed.complete(null)
            session.generation = ""
        }
        changed(session)
    }

    private fun startRecording(session: Session) {
        if (context.checkSelfPermission("android.permission.RECORD_AUDIO") != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("RECORD_AUDIO_PERMISSION_DENIED")
        if (session.captureHost == null) throw IOException("CAPTURE_HOST_UNAVAILABLE")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("AUDIO_STORAGE_UNAVAILABLE")
        val file = File(directory, session.data.getString("resourceId") + "-" + UUID.randomUUID() + ".m4a")
        // Written by this process: mediaserver (MediaRecorder) may not write a system app's files.
        val recorder = MicrophoneAacRecorder(file, 128L * 1024 * 1024) { error -> recordingFailed(session, error) }
        try {
            recorder.start()
            session.recorder = recorder
            session.recording = file
            session.recordingCallback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configurations: MutableList<AudioRecordingConfiguration>?) {
                    val active = recorder.activeRecordingConfiguration
                    if (active != null && active.isClientSilenced) recordingFailed(session, "CAPTURE_SILENCED")
                }
            }
            recorder.registerAudioRecordingCallback(context.mainExecutor, session.recordingCallback!!)
            session.data.put("recordingStartedAt", System.currentTimeMillis()).put("recordingStartedMonotonicMs", SystemClock.elapsedRealtime())
                .put("recordingState", "recording")
        } catch (failure: Exception) {
            if (session.recordingCallback != null) try { recorder.unregisterAudioRecordingCallback(session.recordingCallback!!) } catch (_: Exception) { }
            recorder.release()
            file.delete()
            session.recorder = null
            session.recording = null
            session.recordingCallback = null
            throw failure
        }
    }

    /** The visible microphone foreground service, held while any session captures. */
    private fun acquireMicrophone(session: Session) {
        if (session.captureHost != null) return
        // Its own code: a slow start must not read as an uncertain engine call (which disconnects).
        session.captureHost = try { AudioCaptureService.acquire(context) }
            catch (failure: Exception) { throw IOException("CAPTURE_HOST_UNAVAILABLE", failure) }
    }

    @Synchronized
    private fun releaseMicrophone(session: Session) {
        if (session.captureHost == null) return
        session.captureHost = null
        for (other in sessions.values) if (other.captureHost != null) return
        AudioCaptureService.release(context)
    }

    @Synchronized
    private fun recordingFailed(session: Session, error: String) {
        try {
            session.data.put("recordingError", error)
            stopRecording(session)
            changed(session)
        } catch (_: Exception) { }
    }

    private fun stopRecording(session: Session) {
        if (session.recorder == null) return
        try {
            session.recorder!!.stop()
            session.data.getJSONArray("recordings").put(JSONObject().put("id", session.recording!!.name).put("mimeType", "audio/mp4")
                .put("startedAt", session.data.optLong("recordingStartedAt")).put("endedAt", System.currentTimeMillis())
                .put("durationMs", SystemClock.elapsedRealtime() - session.data.optLong("recordingStartedMonotonicMs"))
                .put("bytes", session.recording!!.length()).put("anchor", recordingUri(session, session.recording!!.name))
                .put("url", recordingUri(session, session.recording!!.name)))
            session.data.put("recordingState", if (session.data.has("recordingError")) "failed" else "saved")
        } catch (_: Exception) {
            session.data.put("recordingState", "failed").put("recordingError", "RECORDING_FINALIZE_FAILED")
            session.recording!!.delete()
        } finally {
            if (session.recordingCallback != null) session.recorder!!.unregisterAudioRecordingCallback(session.recordingCallback!!)
            session.recorder!!.release()
            session.recorder = null
            session.recording = null
            session.recordingCallback = null
        }
    }

    private fun recordingUri(session: Session, recordingId: String): String = Uri.Builder().scheme("content")
        .authority("org.agentos.assistant.agent-gateway").appendPath("audio").appendPath(session.data.getString("resourceId"))
        .appendQueryParameter("threadId", session.data.getString("threadId")).appendQueryParameter("recordingId", recordingId).build().toString()

    fun stopFromNotification() {
        Thread({
            val active = ArrayList<JSONObject>()
            synchronized(this) {
                // Every session holding the microphone, dictation-only ones included: they show the notification too.
                for (session in sessions.values) if (session.captureHost != null || session.recorder != null) try {
                    active.add(JSONObject().put("operation", "stop").put("sessionId", session.data.getString("resourceId"))
                        .put("threadId", session.data.getString("threadId")).put("matterId", session.data.getString("matterId")))
                } catch (_: Exception) { }
            }
            for (request in active) try { command(request) } catch (_: Exception) { }
        }, "audio-notification-stop").start()
    }

    fun captureHostDestroyed(host: AudioCaptureService) {
        Thread({
            synchronized(this) {
                for (session in sessions.values) if (session.captureHost === host) {
                    session.captureHost = null
                    if (session.recorder != null) recordingFailed(session, "CAPTURE_HOST_STOPPED")
                }
            }
        }, "audio-capture-host-stop").start()
    }

    @Synchronized
    private fun find(id: String, threadId: String): Session {
        if (!java.util.regex.Pattern.matches("[0-9a-f-]{36}", id)) throw IOException("INVALID_AUDIO_SESSION_ID")
        var session = sessions[id]
        if (session == null) {
            if (sessions.size >= 32) {
                var evict: String? = null
                for ((key, value) in sessions) if (value.generation.isEmpty() && value.recorder == null) { evict = key; break }
                if (evict == null) throw IOException("AUDIO_SESSION_LIMIT")
                sessions.remove(evict)
            }
            val saved = SourceAdapters.readObject(File(directory, id + ".json"))
            session = Session(saved)
            if (!java.util.List.of("stopped", "failed", "paused", "interrupted").contains(saved.optString("status")))
                saved.put("status", "interrupted").put("error", "ASSISTANT_PROCESS_RESTARTED")
            sessions[id] = session
        }
        if (threadId != session.data.getString("threadId")) throw SecurityException("AUDIO_THREAD_MISMATCH")
        return session
    }

    private fun checkMatter(session: Session, matterId: String) {
        if (matterId != session.data.getString("matterId")) throw SecurityException("AUDIO_MATTER_MISMATCH")
    }

    @Synchronized
    @Throws(Exception::class)
    fun read(resource: JSONObject): JSONObject {
        val session = find(resource.getString("resourceId"), resource.getString("threadId"))
        if (resource.has("matterId")) checkMatter(session, resource.getString("matterId"))
        return snapshot(session)
    }

    @Synchronized
    @Throws(Exception::class)
    fun watch(resource: JSONObject, invalidated: Runnable): AutoCloseable {
        val session = find(resource.getString("resourceId"), resource.getString("threadId"))
        if (resource.has("matterId")) checkMatter(session, resource.getString("matterId"))
        return notifications.watch(session.data.getString("resourceId"), invalidated)
    }

    @Synchronized
    @Throws(Exception::class)
    fun openRecording(id: String, recordingId: String?, threadId: String): ParcelFileDescriptor {
        var selected = recordingId
        if (selected == null || selected.isEmpty()) {
            val session = find(id, threadId)
            val recordings = session.data.getJSONArray("recordings")
            if (recordings.length() == 0) throw IOException("SAVED_RECORDING_NOT_FOUND")
            selected = recordings.getJSONObject(recordings.length() - 1).getString("id")
        }
        val session = find(id, threadId)
        val recordings = session.data.getJSONArray("recordings")
        for (i in 0 until recordings.length()) if (selected == recordings.getJSONObject(i).getString("id"))
            return ParcelFileDescriptor.open(File(directory, selected), ParcelFileDescriptor.MODE_READ_ONLY)
        throw IOException("SAVED_RECORDING_NOT_FOUND")
    }

    @Synchronized
    @Throws(Exception::class)
    fun openRecording(id: String, threadId: String): ParcelFileDescriptor = openRecording(id, "", threadId)

    @Synchronized
    @Throws(Exception::class)
    fun openTranscript(id: String, threadId: String): ParcelFileDescriptor {
        find(id, threadId)
        val transcript = File(directory, id + ".jsonl")
        if (!transcript.isFile) throw IOException("TRANSCRIPT_NOT_AVAILABLE")
        return ParcelFileDescriptor.open(transcript, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Synchronized
    private fun snapshot(session: Session): JSONObject {
        val result = JSONObject(session.data.toString())
        val segments = result.getJSONArray("segments")
        val text = if (segments.length() == 0) "" else segments.getJSONObject(segments.length() - 1).optString("text")
        var elapsed = 0L
        val recordings = result.getJSONArray("recordings")
        for (i in 0 until recordings.length()) elapsed += recordings.getJSONObject(i).optLong("durationMs")
        if (session.recorder != null) elapsed += Math.max(0L, SystemClock.elapsedRealtime() - session.data.getLong("recordingStartedMonotonicMs"))
        result.put("elapsedMs", elapsed)
        return result.put("state", "available").put("observedAt", System.currentTimeMillis())
            .put("title", if ("DICTATION" == result.getJSONObject("params").optString("purpose")) "Meeting recording" else "Media captions")
            .put("text", text).put("terminal", java.util.List.of("stopped", "failed", "interrupted").contains(result.optString("status")))
            .put("sourceAlignedTimestamps", false).put("quality", "engine-recognition").put("provenance", identity(context))
            .put("capabilities", JSONObject().put("read", true).put("watch", true).put("command", true).put("typedPrice", false))
    }

    private fun changed(session: Session) {
        session.data.put("sourceVersion", session.data.optLong("sourceVersion") + 1).put("sourceUpdatedAt", System.currentTimeMillis())
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("AUDIO_STORAGE_UNAVAILABLE")
        val target = File(directory, session.data.getString("resourceId") + ".json")
        val temporary = File(directory, target.name + ".tmp")
        Files.write(temporary.toPath(), session.data.toString().toByteArray(StandardCharsets.UTF_8))
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        notifications.changed(session.data.getString("resourceId"))
    }

    companion object {
        private var instance: AudioSessionBridge? = null

        @JvmStatic
        fun get(context: Context): AudioSessionBridge = synchronized(AudioSessionBridge::class.java) {
            if (instance == null) instance = AudioSessionBridge(context.applicationContext)
            instance!!
        }

        private fun <T> await(future: CompletableFuture<T>, seconds: Int): T {
            try {
                return future.get(seconds.toLong(), TimeUnit.SECONDS)
            } catch (wrapped: ExecutionException) {
                val cause = wrapped.cause
                if (cause is Exception) throw cause
                throw wrapped
            }
        }

        @JvmStatic
        @Throws(Exception::class)
        fun identity(context: Context): JSONObject {
            val info = context.packageManager.getPackageInfo("org.agentos.audio", PackageManager.GET_SIGNING_CERTIFICATES)
            val certificate = info.signingInfo!!.apkContentsSigners[0].toByteArray()
            val digest = StringBuilder()
            for (b in MessageDigest.getInstance("SHA-256").digest(certificate))
                digest.append(String.format(Locale.ROOT, "%02x", b.toInt() and 255))
            return JSONObject().put("packageName", "org.agentos.audio").put("kind", "audio-session")
                .put("authority", "org.agentos.audio.AudioEngineService").put("certificateSha256", digest.toString())
        }
    }
}
