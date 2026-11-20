// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.log10
import kotlin.math.sqrt

/** One generated reply as it moves through the shared playback queue. */
internal open class LiveOutputTurn(val id: String, val inputId: String?) {
    val text = StringBuilder()
    var final = false
    var interrupted = false
    var speaking = false
    var complete = false
    var pendingBytes = 0
    var endFrame = 0L
}

/**
 * Communication route, echo-cancelled capture, local voice activity and paced playback for one
 * Live Talk lease. Every method except [stopNow] runs on the owning driver's worker thread.
 */
internal class LiveAudioIo(
    private val context: Context,
    private val failure: AtomicReference<String?>,
    private val stopped: AtomicBoolean,
    private val allowed: () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun emit(event: LiveAudioEvent)
        fun level(level: Float, frames: Int)
        /** Sends one captured PCM block; returns whether the provider accepted it. */
        fun upload(bytes: ByteArray, count: Int): Boolean
        fun localSpeechStarted()
        fun localSpeechMayEnd(): Boolean = true
        fun speechChanged(active: Boolean) {}
        /** A final reply finished playing (or completed without audio). */
        fun remember(turn: LiveOutputTurn) {}
    }

    private val lock = ReentrantLock()
    private val manager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if(change < 0) failure.compareAndSet(null, "AUDIO_FOCUS_LOST")
        }.build()
    private val deviceListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        if(routeDevice != 0 && device?.id != routeDevice && !stopped.get()) failure.compareAndSet(null, "AUDIO_ROUTE_CHANGED")
    }
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var routeDevice = 0
    private var echo: AcousticEchoCanceler? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldDevice: AudioDeviceInfo? = null
    private var routeOwned = false
    private var focusOwned = false
    private var deviceListenerAdded = false

    val outputs = ArrayDeque<LiveOutputTurn>()
    private val pcm = ArrayDeque<PcmChunk>()
    private var queuedBytes = 0
    private var writtenFrames = 0L
    private var backlogSince = 0L
    private val captureBuffer = ByteArray(1280)
    var speaking = false
        private set
    private var voicedFrames = 0
    private var quietFrames = 0
    private var noiseRms = 100.0
    private var lastCapture = 0L
    private var lastHealthCheck = 0L
    var capturedFrames = 0L
        private set
    var uploadedFrames = 0L
        private set
    var playbackFrames = 0L
        private set
    private var meterFrames = 0
    private var meterSquares = 0.0

    private class PcmChunk(val turn: LiveOutputTurn, val bytes: ByteArray, var offset: Int = 0)

    val active get() = recorder != null

    /** Stops capture and output from any thread without waiting for the worker. */
    fun stopNow() {
        if(!lock.tryLock()) return
        try {
            runCatching { recorder?.stop() }
            runCatching { player?.pause(); player?.flush() }
        } finally { lock.unlock() }
    }

    fun start() {
        if(!AcousticEchoCanceler.isAvailable()) throw AudioFailure("AEC_UNAVAILABLE", "This device cannot enable echo cancellation")
        oldMode = manager.mode; oldDevice = manager.communicationDevice
        if(oldMode == AudioManager.MODE_IN_CALL || oldMode == AudioManager.MODE_IN_COMMUNICATION)
            throw AudioFailure("RESOURCE_BUSY", "A call is using audio resources")
        if(manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            throw AudioFailure("RESOURCE_BUSY", "Audio focus is unavailable")
        focusOwned = true
        routeOwned = true
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        val selected = oldDevice?.takeIf { it.type != AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: throw AudioFailure("AUDIO_ROUTE_UNAVAILABLE", "No communication output is available")
        if(!manager.setCommunicationDevice(selected)) throw AudioFailure("AUDIO_ROUTE_UNAVAILABLE", "Communication audio route could not be selected")
        routeDevice = selected.id
        manager.addOnCommunicationDeviceChangedListener(context.mainExecutor, deviceListener)
        deviceListenerAdded = true
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if(minimum <= 0) throw AudioFailure("CAPTURE_FAILED", "Microphone PCM format is unavailable")
        val capture = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(6400, minimum))
        recorder = capture
        if(capture.state != AudioRecord.STATE_INITIALIZED) throw AudioFailure("CAPTURE_FAILED", "Microphone could not initialize")
        echo = AcousticEchoCanceler.create(capture.audioSessionId)
        if(echo?.setEnabled(true) != AudioEffect.SUCCESS || echo?.enabled != true)
            throw AudioFailure("AEC_UNAVAILABLE", "Microphone echo cancellation could not be enabled")
        val trackMinimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if(trackMinimum <= 0) throw AudioFailure("PLAYBACK_FAILED", "Live output PCM format is unavailable")
        val track = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(4800, trackMinimum)).build()
        player = track
        if(track.state != AudioTrack.STATE_INITIALIZED) throw AudioFailure("PLAYBACK_FAILED", "Live output could not initialize")
        lock.withLock {
            if(!allowed()) return
            capture.startRecording()
            if(capture.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw AudioFailure("CAPTURE_FAILED", "Microphone did not start")
        }
        if(!allowed()) { stopNow(); return }
        voicedFrames = 0; quietFrames = 0; noiseRms = 100.0
        resumeClock()
        Log.i("AudioLive", "captureReady monotonicMs=$lastCapture source=VOICE_COMMUNICATION rate=16000 aec=${echo?.enabled} captureBufferFrames=${capture.bufferSizeInFrames} outputBufferFrames=${track.bufferSizeInFrames} playbackBoundMs=8000 uploadBoundBytes=48000")
    }

    /** Restarts the capture stall clock after the worker intentionally paused capture (reconnect). */
    fun resumeClock() {
        lastCapture = SystemClock.elapsedRealtime(); lastHealthCheck = lastCapture
    }

    fun resetVoice() { voicedFrames = 0; quietFrames = 0 }

    fun capture() {
        val capture = recorder ?: return
        val now = SystemClock.elapsedRealtime()
        if(now - lastCapture > 250) throw AudioFailure("QUEUE_BACKLOG", "Live capture processing stalled")
        lastCapture = now
        if(now - lastHealthCheck >= 1000) {
            lastHealthCheck = now
            if(capture.activeRecordingConfiguration?.isClientSilenced == true) throw AudioFailure("CAPTURE_SILENCED", "Microphone capture was silenced")
            if(echo?.enabled != true) throw AudioFailure("AEC_UNAVAILABLE", "Echo cancellation stopped")
        }
        repeat(5) {
            if(!allowed()) return
            val count = lock.withLock {
                if(!allowed()) return
                capture.read(captureBuffer, 0, captureBuffer.size, AudioRecord.READ_NON_BLOCKING)
            }
            if(!allowed()) { stopNow(); return }
            if(count < 0) throw AudioFailure("CAPTURE_FAILED", "Live microphone capture failed")
            if(count == 0) return
            capturedFrames += count / 2
            detectSpeech(captureBuffer, count)
            if(allowed() && listener.upload(captureBuffer, count)) uploadedFrames += count / 2
        }
    }

    private fun detectSpeech(bytes: ByteArray, count: Int) {
        val frames = count / 2
        if(frames == 0) return
        var sum = 0.0; var crossings = 0; var previous = 0
        for(i in 0 until frames) {
            val sample = ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
            sum += sample.toDouble() * sample
            if(i > 0 && (sample >= 0) != (previous >= 0)) crossings++
            previous = sample
        }
        val rms = sqrt(sum / frames)
        meterFrames += frames; meterSquares += sum
        if(meterFrames >= 1600) {
            val level = ((20 * log10(maxOf(sqrt(meterSquares / meterFrames), 1.0) / 32768) + 60) / 60).toFloat().coerceIn(0f, 1f)
            if(allowed()) listener.level(level, meterFrames)
            meterFrames = 0; meterSquares = 0.0
        }
        val crossingRate = crossings.toDouble() / frames
        val voice = rms > maxOf(450.0, noiseRms * 3.5) && crossingRate in 0.015..0.40
        if(voice) { voicedFrames += frames; quietFrames = 0 }
        else {
            voicedFrames = 0; quietFrames += frames
            if(!speaking) noiseRms = noiseRms * 0.98 + minOf(rms, 1000.0) * 0.02
        }
        if(!speaking && voicedFrames >= 1920) listener.localSpeechStarted()
        else if(speaking && quietFrames >= 9600 && listener.localSpeechMayEnd()) setSpeech(false)
    }

    fun setSpeech(active: Boolean) {
        if(speaking == active) return
        speaking = active
        listener.emit(LiveAudioEvent.Speech(active))
        listener.speechChanged(active)
    }

    fun enqueue(turn: LiveOutputTurn, bytes: ByteArray) {
        if(bytes.size % 2 != 0) throw AudioFailure("PROVIDER_PROTOCOL_ERROR", "Live PCM is not aligned")
        val track = player ?: throw AudioFailure("PLAYBACK_FAILED", "Live output is unavailable")
        val pending = queuedBytes + (writtenFrames - playedFrames(track)).coerceAtLeast(0) * 2
        if(pending + bytes.size > 24000 * 2 * 8) throw AudioFailure("QUEUE_BACKLOG", "Live playback exceeds eight seconds")
        if(bytes.isNotEmpty()) {
            queuedBytes += bytes.size; turn.pendingBytes += bytes.size
            pcm.addLast(PcmChunk(turn, bytes))
        }
    }

    private fun playedFrames(track: AudioTrack): Long = track.playbackHeadPosition.toLong() and 0xffffffffL

    fun play() {
        if(!allowed()) return
        val track = player ?: return
        val chunk = pcm.peekFirst()
        if(chunk != null) {
            val count = lock.withLock {
                if(!allowed()) return
                if(track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                track.write(chunk.bytes, chunk.offset, chunk.bytes.size - chunk.offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            if(!allowed()) { stopNow(); return }
            if(count < 0) throw AudioFailure("PLAYBACK_FAILED", "Live playback write failed")
            if(count > 0) {
                writtenFrames += count / 2
                playbackFrames += count / 2
                queuedBytes -= count; chunk.turn.pendingBytes -= count
                chunk.offset += count; chunk.turn.endFrame = writtenFrames
                if(!chunk.turn.speaking) {
                    chunk.turn.speaking = true
                    listener.emit(LiveAudioEvent.Playback(chunk.turn.id, "speaking"))
                    Log.i("AudioLive", "firstTrack monotonicMs=${SystemClock.elapsedRealtime()} responseId=${chunk.turn.id} writtenFrames=$writtenFrames")
                }
                if(chunk.offset == chunk.bytes.size) pcm.removeFirst()
            }
        }
        val played = playedFrames(track)
        while(outputs.isNotEmpty()) {
            val turn = outputs.peekFirst()
            if(!turn.complete || turn.pendingBytes != 0 || played < turn.endFrame) break
            outputs.removeFirst()
            if(!turn.interrupted) {
                if(turn.speaking) listener.emit(LiveAudioEvent.Playback(turn.id, "stopped"))
                else listener.emit(LiveAudioEvent.ResponseDone(turn.id, turn.inputId ?: ""))
                if(turn.final) listener.remember(turn)
            }
        }
        val pending = queuedBytes + (writtenFrames - played).coerceAtLeast(0) * 2
        val now = SystemClock.elapsedRealtime()
        if(pending > 24000 * 2 * 4) {
            if(backlogSince == 0L) backlogSince = now
            if(now - backlogSince > 5000) throw AudioFailure("QUEUE_BACKLOG", "Live playback backlog persists")
        } else backlogSince = 0
    }

    /** Drops queued audio; only turns that were audibly speaking report the [state]. */
    fun stopPlayback(state: String, current: LiveOutputTurn?) {
        val track = player
        if(outputs.isNotEmpty()) Log.i("AudioLive", "playbackStop monotonicMs=${SystemClock.elapsedRealtime()} state=$state playedFrames=${track?.let { playedFrames(it) } ?: 0} queuedBytes=$queuedBytes")
        if(track != null) lock.withLock { track.pause(); track.flush() }
        pcm.clear(); queuedBytes = 0; writtenFrames = 0; backlogSince = 0
        for(turn in outputs) {
            turn.pendingBytes = 0; turn.endFrame = 0
            if(!turn.interrupted) {
                turn.interrupted = true
                if(turn.speaking) listener.emit(LiveAudioEvent.Playback(turn.id, state))
            }
        }
        current?.interrupted = true
        outputs.clear()
    }

    fun clear() { pcm.clear(); outputs.clear(); queuedBytes = 0 }

    fun release(): Boolean = lock.withLock {
        var restored = true
        recorder?.let { capture ->
            runCatching { capture.stop() }
            if(runCatching { capture.release() }.isFailure) restored = false
        }
        recorder = null
        echo?.let { if(runCatching { it.release() }.isFailure) restored = false }; echo = null
        player?.let { track ->
            runCatching { track.pause(); track.flush() }
            if(runCatching { track.release() }.isFailure) restored = false
        }
        player = null
        routeDevice = 0
        if(deviceListenerAdded) {
            if(runCatching { manager.removeOnCommunicationDeviceChangedListener(deviceListener) }.isFailure) restored = false
            deviceListenerAdded = false
        }
        if(routeOwned) {
            if(runCatching {
                val previous = oldDevice
                if(previous != null) { if(!manager.setCommunicationDevice(previous)) restored = false }
                else manager.clearCommunicationDevice()
                manager.mode = oldMode
            }.isFailure) restored = false
            routeOwned = false
        }
        if(focusOwned) {
            if(runCatching { manager.abandonAudioFocusRequest(focus) }.isFailure) restored = false
            focusOwned = false
        }
        restored
    }
}
