// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * MOSS-Live full-duplex driver. One lease and one worker own capture, playback and the provider
 * conversation. Barge-in is server driven: local voice activity never stops playback; the provider's
 * `response.audio.stop` does, and the driver answers with `playback.stopped`. Every chunk and turn
 * that actually played is acknowledged so the provider's context matches what the user heard.
 */
internal class MossLiveDriver(private val context: Context, request: AudioRequest) : AudioDriver {
    private sealed interface Work {
        data class Open(val generation: Long, val socket: WebSocket) : Work
        data class Message(val generation: Long, val json: String) : Work
        data class Binary(val generation: Long, val bytes: ByteArray) : Work
        data class Command(val generation: Long, val command: LiveAudioCommand) : Work
        data object MuteChanged : Work
        class Video(val generation: Long, val source: String, val monotonicUs: Long, val keyFrame: Boolean, val data: ByteArray) : Work
        data class VideoStopped(val generation: Long, val source: String, val reason: String) : Work
    }
    private enum class Handshake { Attaching, Mode, Configuring, Updating, Ready }
    private data class InputTurn(val id: String, val text: StringBuilder = StringBuilder(), var final: Boolean = false)
    private data class ToolCall(val id: String, val itemId: String, val action: String, val text: String,
        val result: AtomicReference<String?> = AtomicReference(null), var delivered: Boolean = false, var sent: Boolean = false)
    private class MossTurn(id: String, inputId: String?, val runId: String, val turnId: String) : LiveOutputTurn(id, inputId) {
        var played = false
        var stopReported = false
    }

    private val stopped = AtomicBoolean()
    private val finishing = AtomicBoolean()
    private val muted = AtomicBoolean(request.muted)
    private val generation = AtomicLong()
    private val failure = AtomicReference<String?>()
    private val incomingBytes = AtomicInteger()
    private val work = ArrayBlockingQueue<Work>(256)
    private val calls = ConcurrentHashMap<String, ToolCall>()
    /** Every call id this connection has seen; `response.done` repeats calls that may already be answered. */
    private val seenCalls = LinkedHashSet<String>()
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val backpressure = MossBackpressure()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var credential: EngineConfig.Control? = null
    /** Written only by the worker; read by [live] to reject commands before the provider is ready. */
    @Volatile private var connected = false
    private var io: LiveAudioIo? = null
    private var outputRate = 48000
    private var worker: Thread? = null
    private lateinit var sink: AudioDriver.Sink
    private var announced = false
    private var handshake = Handshake.Attaching
    /** Provider session kept across mute, reconnect and network loss so the conversation resumes. */
    private var providerSession: String? = null
    private var attachRequested: String? = null
    private var connectionGeneration = 0L
    private var connectDeadline = 0L
    private var inputCounter = 0L
    private var input: InputTurn? = null
    private var output: MossTurn? = null
    private val turns = HashMap<String, MossTurn>()
    private var audioContext: PlaybackChunkAck? = null
    private val history = ArrayDeque<LiveHistoryTurn>(request.history)
    private var historyChars = request.history.sumOf { it.text.length }
    /** Codec the provider negotiated for this connection, or null for audio only. */
    @Volatile private var videoCodec: String? = null
    /** The source the user asked for; it stays wanted while privacy masking withholds it. */
    @Volatile private var videoWanted = "none"
    private var videoSending = false
    private var videoMasked = false
    private var awaitingKey = true
    private var privacyCheckedAt = 0L
    /** Maps encoder timestamps onto the 16 kHz microphone sample clock the provider aligns video with. */
    @Volatile private var clockSamples = 0L
    @Volatile private var clockUs = 0L
    private var videoCreated = false
    private val video by lazy { videoCreated = true; LiveVideoIo(context, object : LiveVideoIo.Listener {
        override fun frame(source: String, monotonicUs: Long, keyFrame: Boolean, data: ByteArray) {
            // A full queue drops the frame; video never fails the voice session.
            work.offer(Work.Video(generation.get(), source, monotonicUs, keyFrame, data))
        }
        override fun stopped(source: String, reason: String) { work.offer(Work.VideoStopped(generation.get(), source, reason)) }
    }) }
    private val keyguard by lazy { context.getSystemService(android.app.KeyguardManager::class.java) }
    private val power by lazy { context.getSystemService(android.os.PowerManager::class.java) }

    private val listener = object : LiveAudioIo.Listener {
        override fun emit(event: LiveAudioEvent) = this@MossLiveDriver.emit(event)
        override fun level(level: Float, frames: Int) = sink.level(level, frames)
        override fun upload(bytes: ByteArray, count: Int): Boolean {
            val sent = sendBinary(MossWireProtocol.encodeAudio(bytes, count))
            if(sent) { clockSamples = (io?.uploadedFrames ?: 0L) + count / 2; clockUs = System.nanoTime() / 1000 }
            return sent
        }
        /** The provider decides barge-in; local detection only drives levels and UI hints. */
        override fun localSpeechStarted() = Unit
        override fun localSpeechMayEnd(): Boolean = false
        override fun speechChanged(active: Boolean) { if(!active) deliverTools() }
        override fun remember(turn: LiveOutputTurn) = this@MossLiveDriver.remember("model", turn.text.toString())
        override fun chunkPlayed(ack: PlaybackChunkAck) { send(MossWireProtocol.playbackChunkCompleted(ack)) }
        override fun turnPlayed(turn: LiveOutputTurn) {
            val moss = turn as? MossTurn ?: return
            moss.played = true
            send(MossWireProtocol.playbackCompleted(moss.runId, moss.turnId))
            turns.remove(key(moss.runId, moss.turnId))
            if(output === moss) output = null
        }
    }

    override fun start(sink: AudioDriver.Sink) {
        this.sink = sink
        worker = Thread({ run() }, "audio-moss-live").also { it.start() }
    }

    override fun live(command: LiveAudioCommand) {
        if(stopped.get()) throw AudioFailure("INPUT_CLOSED", "The live session has closed")
        if(command is LiveAudioCommand.Mute) {
            if(muted.getAndSet(command.muted) == command.muted) return
            generation.incrementAndGet()
            socket?.cancel()
            io?.stopNow()
            offer(Work.MuteChanged)
            return
        }
        if(muted.get()) throw AudioFailure("MIC_MUTED", "Resume Live Talk before sending input")
        if(!connected) throw AudioFailure("LIVE_CONNECTING", "Wait for Live Talk to connect")
        if(command is LiveAudioCommand.SetVideo && command.source != "none") {
            if(videoCodec == null) throw AudioFailure("VIDEO_UNSUPPORTED", "The provider did not accept a video stream for this connection")
            if(deviceLocked()) throw AudioFailure("DEVICE_LOCKED", "Unlock the device before sharing the screen or camera")
            if(command.source == "camera" && context.checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw AudioFailure("PERMISSION_DENIED", "Allow Taku Audio to use the camera")
        }
        if(command is LiveAudioCommand.ToolResult) {
            val call = calls[command.callId] ?: throw AudioFailure("SESSION_CHANGED", "The provider tool call is no longer active")
            val prior = call.result.get()
            if(prior != null) {
                if(prior != command.result) throw AudioFailure("REQUEST_CONFLICT", "Tool result differs from the accepted result")
                return
            }
            if(!call.result.compareAndSet(null, command.result)) throw AudioFailure("REQUEST_CONFLICT", "Tool result already accepted")
        }
        offer(Work.Command(generation.get(), command))
    }

    override fun finish() { finishing.set(true); close() }
    override fun close() {
        if(!stopped.compareAndSet(false, true)) return
        generation.incrementAndGet()
        credential?.close()
        socket?.cancel()
        io?.stopNow()
        worker?.interrupt()
    }

    private fun captureAllowed(): Boolean = !muted.get() && !stopped.get() && connectionGeneration == generation.get()

    private fun offer(item: Work) {
        if(!work.offer(item)) failure.compareAndSet(null, "QUEUE_BACKLOG")
    }

    private fun run() {
        var restored = true
        try {
            if(muted.get()) {
                announced = true
                sink.ready()
                sink.live(LiveAudioEvent.Mic(true))
            } else openConnection()
            while(!stopped.get()) {
                failure.get()?.let { throw AudioFailure(it, "Live Talk did not complete") }
                val item = work.poll(10, TimeUnit.MILLISECONDS)
                if(item != null) handle(item)
                if(stopped.get()) break
                if(!connected && connectDeadline != 0L && SystemClock.elapsedRealtime() >= connectDeadline)
                    throw AudioFailure("CONNECT_TIMEOUT", "Live Talk did not become ready")
                if(connected && !muted.get() && connectionGeneration == generation.get()) {
                    io?.capture()
                    io?.play()
                    val now = SystemClock.elapsedRealtime()
                    if(now - privacyCheckedAt >= PRIVACY_CHECK_MS) { privacyCheckedAt = now; checkPrivacy() }
                }
            }
        } catch(_: InterruptedException) {
            if(!stopped.get()) sink.failed("CAPTURE_FAILED")
        } catch(error: Exception) {
            Log.w("AudioLive", "provider=moss-live failure=${(error as? AudioFailure)?.code ?: "AUDIO_UNAVAILABLE"} detail=${if(error is AudioFailure) error.message else error.javaClass.simpleName}")
            if(!stopped.get()) sink.failed((error as? AudioFailure)?.code ?: "AUDIO_UNAVAILABLE")
        } finally {
            stopped.set(true)
            connected = false
            generation.incrementAndGet()
            credential?.close()
            socket?.close(1000, null); socket?.cancel(); socket = null
            io?.let { restored = it.release() && restored; it.clear() }
            if(videoCreated) video.release()
            work.clear(); calls.clear(); history.clear(); turns.clear()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            Log.i("AudioLive", "released provider=moss-live monotonicMs=${SystemClock.elapsedRealtime()} capturedFrames=${io?.capturedFrames ?: 0} uploadedFrames=${io?.uploadedFrames ?: 0} playbackFrames=${io?.playbackFrames ?: 0} restored=$restored")
            if(finishing.get()) sink.completed()
            sink.released(restored)
        }
    }

    private fun openConnection() {
        if(stopped.get() || muted.get()) return
        connected = false
        handshake = Handshake.Attaching
        connectionGeneration = generation.incrementAndGet()
        val current = connectionGeneration
        connectDeadline = SystemClock.elapsedRealtime() + 20000
        val operation = EngineConfig.Control(JSONObject().put("op", "mossCredential"))
        credential = operation
        val key = try { operation.use { it.execute().getString("key") } } finally { credential = null }
        if(stopped.get() || current != generation.get()) return
        val request = Request.Builder().url(MossWireProtocol.ENDPOINT).header("Authorization", "Bearer $key").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if(stopped.get() || current != generation.get()) webSocket.cancel()
                else offer(Work.Open(current, webSocket))
            }
            override fun onMessage(webSocket: WebSocket, text: String) = receive(current, text.length) { Work.Message(current, text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(current, bytes.size) { Work.Binary(current, bytes.toByteArray()) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if(!stopped.get() && current == generation.get())
                    failure.compareAndSet(null, when(response?.code) { 401, 403 -> "PROVIDER_AUTH_FAILED"; 429 -> "PROVIDER_REJECTED"; else -> "NETWORK_FAILED" })
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                if(!stopped.get() && current == generation.get())
                    failure.compareAndSet(null, when(code) { 1008 -> "PROVIDER_REJECTED"; 1013 -> "PROVIDER_REJECTED"; else -> "NETWORK_FAILED" })
            }
        })
    }

    private fun receive(current: Long, size: Int, item: () -> Work) {
        if(stopped.get() || current != generation.get()) return
        if(size > 1_048_576 || incomingBytes.addAndGet(size) > 1_048_576) {
            failure.compareAndSet(null, "QUEUE_BACKLOG")
            return
        }
        if(!work.offer(item())) {
            incomingBytes.addAndGet(-size)
            failure.compareAndSet(null, "QUEUE_BACKLOG")
        }
    }

    private fun handle(item: Work) {
        when(item) {
            is Work.Open -> if(item.generation == generation.get()) {
                socket = item.socket
                attachRequested = providerSession
                send(MossWireProtocol.attachSession(providerSession))
            }
            is Work.Message -> {
                incomingBytes.addAndGet(-item.json.length)
                if(item.generation == generation.get()) handleEvent(MossWireProtocol.parse(item.json))
            }
            is Work.Binary -> {
                incomingBytes.addAndGet(-item.bytes.size)
                if(item.generation == generation.get()) handleAudio(item.bytes)
            }
            is Work.Command -> if(item.generation == generation.get()) handleCommand(item.command)
            is Work.Video -> if(item.generation == generation.get()) sendVideo(item)
            is Work.VideoStopped -> if(item.generation == generation.get() && item.source == videoWanted) {
                stopVideo(item.reason)
            }
            Work.MuteChanged -> {
                stopVideo("MIC_MUTED")
                connected = false; connectDeadline = 0
                socket?.close(1000, null); socket?.cancel(); socket = null
                io?.let { audio ->
                    audio.stopPlayback("interrupted", output)
                    audio.setSpeech(false)
                    if(!audio.release()) throw AudioFailure("RESTORE_FAILED", "Live audio route could not be restored")
                    audio.clear()
                }
                io = null
                input = null; output = null; audioContext = null; turns.clear(); calls.clear(); seenCalls.clear()
                if(muted.get()) {
                    if(!announced) { announced = true; sink.ready() }
                    sink.live(LiveAudioEvent.Mic(true))
                } else openConnection()
            }
        }
    }

    private fun handleEvent(event: MossWireProtocol.Event) {
        when(event) {
            is MossWireProtocol.Event.SessionAttached -> {
                if(handshake != Handshake.Attaching) return
                // A rejected resume yields a fresh session: replay the confirmed conversation as context.
                val resumed = attachRequested != null && event.sessionId == attachRequested
                providerSession = event.sessionId
                handshake = Handshake.Mode
                send(MossWireProtocol.mediaMode())
                handshake = Handshake.Configuring
                send(MossWireProtocol.mediaConfigure(48000))
                if(resumed) Log.i("AudioLive", "provider=moss-live resumed session")
            }
            is MossWireProtocol.Event.MediaConfigured -> {
                if(handshake != Handshake.Configuring) return
                if(event.error != null) throw AudioFailure("PROVIDER_REJECTED", "Live provider rejected the audio format")
                outputRate = event.outputSampleRate
                videoCodec = event.videoCodec
                handshake = Handshake.Updating
                send(MossWireProtocol.sessionUpdate(history.toList()))
            }
            is MossWireProtocol.Event.SessionUpdated -> {
                if(handshake != Handshake.Updating) return
                if(event.error != null) throw AudioFailure("PROVIDER_REJECTED", "Live provider rejected the session")
                handshake = Handshake.Ready
                ready()
            }
            is MossWireProtocol.Event.Failure -> {
                if(handshake == Handshake.Attaching && attachRequested != null) {
                    // The saved provider session cannot be resumed; open a fresh one that replays the
                    // confirmed conversation in its instructions.
                    Log.i("AudioLive", "provider=moss-live resume rejected code=${event.code}")
                    providerSession = null; attachRequested = null
                    socket?.cancel(); socket = null
                    openConnection()
                    return
                }
                if(event.code in RECOVERABLE) return
                throw AudioFailure("PROVIDER_REJECTED", "Live provider rejected the session")
            }
            is MossWireProtocol.Event.Closing -> Log.i("AudioLive", "provider=moss-live closing code=${event.code} reconnect=${event.reconnect}")
            else -> if(connected && !muted.get()) conversation(event)
        }
    }

    private fun ready() {
        val audio = io ?: LiveAudioIo(context, failure, stopped, ::captureAllowed, listener, outputRate).also { io = it }
        if(!audio.active) audio.start()
        if(!captureAllowed()) return
        send(MossWireProtocol.micState(true))
        connected = true; connectDeadline = 0
        audio.resumeClock()
        if(!announced) { announced = true; sink.ready() }
        sink.live(LiveAudioEvent.Mic(false))
        Log.i("AudioLive", "provider=moss-live ready monotonicMs=${SystemClock.elapsedRealtime()} outputRate=$outputRate")
    }

    private fun conversation(event: MossWireProtocol.Event) {
        val audio = io ?: return
        when(event) {
            is MossWireProtocol.Event.SpeechStarted -> {
                if(event.active && (input?.final != false)) newInput()
                audio.setSpeech(event.active)
            }
            is MossWireProtocol.Event.InputTranscript -> {
                if(event.text.isEmpty()) return
                val turn = input?.takeUnless { it.final } ?: newInput()
                turn.text.setLength(0)
                appendText(turn.text, event.text)
                if(event.final) finalizeInput() else emit(LiveAudioEvent.Input(turn.id, event.text, false))
            }
            is MossWireProtocol.Event.AudioStarted -> turn(event.runId, event.turnId)
            is MossWireProtocol.Event.AudioContext -> {
                if(event.runId.isBlank() || event.turnId.isBlank() || event.chunkId.isBlank())
                    throw AudioFailure("PROVIDER_PROTOCOL_ERROR", "Live provider returned an incomplete audio context")
                turn(event.runId, event.turnId)
                audioContext = PlaybackChunkAck(event.runId, event.turnId, event.chunkId)
            }
            is MossWireProtocol.Event.OutputTranscript -> {
                val turn = turn(event.runId, event.turnId) ?: return
                if(turn.interrupted) return
                turn.text.setLength(0)
                appendText(turn.text, event.text)
                emit(LiveAudioEvent.Output(turn.id, turn.text.toString(), event.final))
                if(event.final) {
                    turn.final = true
                    // Playback may already have finished; record the reply the user heard.
                    if(turn.played) remember("model", turn.text.toString())
                }
            }
            is MossWireProtocol.Event.AudioDone -> turns[key(event.runId, event.turnId)]?.complete = true
            is MossWireProtocol.Event.AudioStop -> stopTurn(event.runId, event.turnId)
            is MossWireProtocol.Event.Interrupted -> {
                if(event.turnId.isNotBlank()) stopTurn(event.runId, event.turnId)
                else output?.let { stopTurn(it.runId, it.turnId) }
            }
            is MossWireProtocol.Event.ResponseEnded -> turns[key(event.runId, event.turnId)]?.let { turn ->
                turn.complete = true
                if(turn.inputId == input?.id && input?.final == true) input = null
                if(output === turn) output = null
                // A reply that produced no audio has nothing left to acknowledge.
                if(turn.startFrame < 0 && turn.pendingBytes == 0) turns.remove(key(event.runId, event.turnId))
            }
            is MossWireProtocol.Event.ToolCall -> toolCall(event)
            is MossWireProtocol.Event.ToolCalls -> event.calls.forEach(::toolCall)
            is MossWireProtocol.Event.ItemCreated -> calls[event.callId]?.takeIf { it.sent }?.let {
                send(MossWireProtocol.responseCreate(it.id))
                calls.remove(it.id)
            }
            else -> Unit
        }
    }

    private fun handleAudio(bytes: ByteArray) {
        if(!connected || muted.get()) return
        val audio = io ?: return
        val ack = audioContext ?: throw AudioFailure("PROVIDER_PROTOCOL_ERROR", "Live provider sent audio without context")
        audioContext = null
        val turn = turns[key(ack.runId, ack.turnId)] ?: return
        val pcm = MossWireProtocol.decodeAudio(bytes)
        // A chunk for a reply the user already cut off is acknowledged unplayed by playback.stopped.
        if(turn.interrupted) return
        audio.enqueue(turn, pcm, ack)
    }

    private fun stopTurn(runId: String, turnId: String) {
        val turn = turns[key(runId, turnId)] ?: return
        if(turn.stopReported) return
        val playing = io?.outputs?.contains(turn) == true
        val playedMs = if(playing) io?.stopPlayback("interrupted", turn) ?: 0L else 0L
        turn.interrupted = true
        // Flushing playback also drops every later queued reply; each one is reported stopped unheard.
        for(other in turns.values.toList()) if(other === turn || other.interrupted) {
            if(other.stopReported) continue
            other.stopReported = true
            turns.remove(key(other.runId, other.turnId))
            if(output === other) output = null
            send(MossWireProtocol.playbackStopped(other.runId, other.turnId, if(other === turn) playedMs else 0L))
        }
    }

    private fun turn(runId: String, turnId: String): MossTurn? {
        if(runId.isBlank() || turnId.isBlank()) return output
        turns[key(runId, turnId)]?.let { return it }
        if(turns.size >= 64) throw AudioFailure("QUEUE_BACKLOG", "Live reply history exceeds capacity")
        val turn = MossTurn("m$connectionGeneration-$runId-$turnId", input?.id, runId, turnId)
        turns[key(runId, turnId)] = turn
        output = turn
        io?.outputs?.addLast(turn)
        return turn
    }

    private fun toolCall(event: MossWireProtocol.Event.ToolCall) {
        if(event.callId.isBlank() || event.callId.length > 256 || event.name != MossWireProtocol.TOOL_NAME)
            throw AudioFailure("PROVIDER_PROTOCOL_ERROR", "Live provider returned an unsupported tool")
        if(!seenCalls.add(event.callId)) return
        if(seenCalls.size > 256) seenCalls.remove(seenCalls.first())
        val args = try { JSONObject(event.arguments.ifBlank { "{}" }) } catch(_: Exception) {
            throw AudioFailure("PROVIDER_PROTOCOL_ERROR", "Live provider returned invalid tool arguments")
        }
        val action = args.optString("action")
        val text = args.optString("text")
        if(action !in MossWireProtocol.TOOL_ACTIONS || text.isBlank() || text.length > 12000)
            throw AudioFailure("PROVIDER_PROTOCOL_ERROR", "Live provider returned invalid tool arguments")
        if(calls.size >= 64) throw AudioFailure("QUEUE_BACKLOG", "Live tool history exceeds capacity")
        val turn = input ?: newInput()
        calls[event.callId] = ToolCall(event.callId, turn.id, action, text)
        deliverTools()
    }

    private fun handleCommand(command: LiveAudioCommand) {
        if(muted.get()) return
        if(!connected) {
            if(command is LiveAudioCommand.Result) emit(LiveAudioEvent.TaskResult(command.offerId, false))
            return
        }
        when(command) {
            is LiveAudioCommand.Text -> {
                // MOSS-Live has no user text input; typed text reaches the model as spoken-reply context.
                output?.let { stopTurn(it.runId, it.turnId) }
                val turn = newInput()
                appendText(turn.text, command.text)
                finalizeInput()
                send(MossWireProtocol.commentary("The user typed this message instead of speaking. Respond to it:\n${command.text}"))
            }
            is LiveAudioCommand.Result -> {
                // Server-driven turn-taking: announce at any quiet gap, not only right after the confirming input.
                val audio = io
                val sent = audio != null && !audio.speaking && output == null && audio.outputs.isEmpty() &&
                    send(MossWireProtocol.commentary("Trusted Taku Agent result. Summarize briefly without starting another task:\n${command.text}"))
                if(sent) input = null
                emit(LiveAudioEvent.TaskResult(command.offerId, sent))
            }
            is LiveAudioCommand.ToolResult -> calls[command.callId]?.let { call ->
                call.sent = true
                send(MossWireProtocol.toolOutput(call.id, JSONObject(command.result)))
            }
            is LiveAudioCommand.SetVideo -> setVideo(command.source)
            is LiveAudioCommand.RequestKeyframe -> if(videoSending) video.requestKeyframe()
            is LiveAudioCommand.ContextNote -> send(MossWireProtocol.thinking(command.text))
            is LiveAudioCommand.Mute -> Unit
        }
    }

    private fun deviceLocked(): Boolean = keyguard.isKeyguardLocked || !power.isInteractive

    private fun setVideo(source: String) {
        if(source == videoWanted && (source == "none" || videoSending || videoMasked)) return
        if(videoWanted != "none") stopVideo("")
        if(source == "none") return
        val codec = videoCodec ?: run { emit(LiveAudioEvent.Video(source, false, reason = "VIDEO_UNSUPPORTED")); return }
        if(deviceLocked()) { emit(LiveAudioEvent.Video(source, false, masked = true, reason = "DEVICE_LOCKED")); return }
        videoWanted = source
        startVideo(source, codec)
    }

    private fun startVideo(source: String, codec: String) {
        videoMasked = false
        awaitingKey = true
        // The provider requires the first frame after enabling to be a key frame.
        send(MossWireProtocol.videoState(source, true))
        videoSending = true
        video.start(source, codec)
        emit(LiveAudioEvent.Video(source, true))
        Log.i("AudioLive", "provider=moss-live video source=$source codec=$codec")
    }

    /** Stops capture and tells the provider; [reason] empty means a plain user switch. */
    private fun stopVideo(reason: String, masked: Boolean = false) {
        val source = videoWanted
        if(source == "none" && !videoSending) return
        video.stop()
        if(videoSending && connected) runCatching { send(MossWireProtocol.videoState(source, false)) }
        videoSending = false
        if(!masked) videoWanted = "none"
        videoMasked = masked
        emit(LiveAudioEvent.Video(if(masked) source else "none", false, masked, reason))
    }

    /** Locked or dark screen: withhold frames immediately and resume the wanted source once unlocked. */
    private fun checkPrivacy() {
        if(videoWanted == "none") return
        val locked = deviceLocked()
        if(locked && !videoMasked) stopVideo("DEVICE_LOCKED", masked = true)
        else if(!locked && videoMasked) videoCodec?.let { startVideo(videoWanted, it) }
    }

    private fun sendVideo(frame: Work.Video) {
        if(!videoSending || frame.source != videoWanted || videoMasked) return
        if(awaitingKey && !frame.keyFrame) return
        val ws = socket ?: return
        when(backpressure.video(ws.queueSize(), frame.keyFrame, SystemClock.elapsedRealtime())) {
            MossBackpressure.Video.SKIP -> return
            MossBackpressure.Video.PAUSE -> {
                send(MossWireProtocol.videoState(frame.source, false))
                emit(LiveAudioEvent.Video(frame.source, false, reason = "NETWORK_THROTTLED"))
                return
            }
            MossBackpressure.Video.RESUME -> {
                send(MossWireProtocol.videoState(frame.source, true))
                emit(LiveAudioEvent.Video(frame.source, true))
                awaitingKey = true
                video.requestKeyframe()
                if(!frame.keyFrame) return
            }
            MossBackpressure.Video.SEND -> Unit
        }
        awaitingKey = false
        val samples = (clockSamples + (frame.monotonicUs - clockUs) * MossWireProtocol.INPUT_SAMPLE_RATE / 1_000_000).coerceAtLeast(0)
        ws.send(MossWireProtocol.encodeVideo(frame.source, samples, frame.keyFrame, frame.data).toByteString())
    }

    private fun send(text: String): Boolean {
        val current = connectionGeneration
        if(stopped.get() || current != generation.get()) return false
        val ws = socket ?: throw AudioFailure("NETWORK_FAILED", "Live Talk is not connected")
        if(!backpressure.audioAllowed(ws.queueSize())) throw AudioFailure("QUEUE_BACKLOG", "Live uploads are backlogged")
        val accepted = ws.send(text)
        if(!accepted && !stopped.get() && current == generation.get()) throw AudioFailure("NETWORK_FAILED", "Live Talk could not send input")
        return accepted
    }

    private fun sendBinary(frame: ByteArray): Boolean {
        val current = connectionGeneration
        if(stopped.get() || current != generation.get()) return false
        val ws = socket ?: throw AudioFailure("NETWORK_FAILED", "Live Talk is not connected")
        if(!backpressure.audioAllowed(ws.queueSize())) throw AudioFailure("QUEUE_BACKLOG", "Live microphone uploads are backlogged")
        val accepted = ws.send(frame.toByteString())
        if(!accepted && !stopped.get() && current == generation.get()) throw AudioFailure("NETWORK_FAILED", "Live Talk could not send input")
        return accepted
    }

    private fun emit(event: LiveAudioEvent) {
        val terminal = event is LiveAudioEvent.Playback && event.state != "speaking" || event is LiveAudioEvent.Speech && !event.active
        if(!stopped.get() && (terminal || !muted.get() && connectionGeneration == generation.get())) sink.live(event)
    }

    private fun deliverTools() {
        val turn = input ?: return
        if(!turn.final || io?.speaking == true || muted.get()) return
        for(call in calls.values) if(!call.delivered && call.itemId == turn.id) {
            call.delivered = true
            emit(LiveAudioEvent.Tool(call.id, call.itemId, call.action, call.text))
        }
    }

    private fun key(runId: String, turnId: String) = "$runId\u0000$turnId"
    private fun newInput(): InputTurn = InputTurn("m$connectionGeneration-input-${++inputCounter}").also { input = it }
    private fun appendText(target: StringBuilder, text: String) {
        if(target.length + text.length > 12000) throw AudioFailure("QUEUE_BACKLOG", "Live transcript exceeds capacity")
        target.append(text)
    }
    private fun remember(role: String, text: String) {
        if(text.isBlank()) return
        history.addLast(LiveHistoryTurn(role, text)); historyChars += text.length
        while(history.size > 24 || historyChars > 12000) historyChars -= history.removeFirst().text.length
    }
    private fun finalizeInput() {
        val turn = input ?: return
        if(turn.final || turn.text.isBlank()) return
        turn.final = true
        remember("user", turn.text.toString())
        emit(LiveAudioEvent.Input(turn.id, turn.text.toString(), true))
        io?.setSpeech(false)
        deliverTools()
    }

    private companion object {
        const val PRIVACY_CHECK_MS = 100L
        /** Request-level rejections that leave the conversation usable. */
        val RECOVERABLE = setOf("unknown_call_id", "tool_task_conflict", "unknown_tool_task", "unsupported_control")
    }
}
