// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Cold-standby listener: low-power `VOICE_RECOGNITION` capture with on-device Silero VAD, no provider
 * connection and no communication route. The last 1.5 s of audio stays in a [PcmRingBuffer] so the
 * words that woke Live Talk reach the provider. It yields the microphone to a real call
 * (`MODE_IN_CALL`, ringtone, another app's communication) and stops listening while other apps
 * play media, resuming afterwards. It holds a partial wake lock only while listening.
 */
internal class StandbyVadEngine(
    private val context: Context,
    private val model: File,
    private val onWake: (preroll: ByteArray) -> Unit,
    private val onPaused: (reason: String?) -> Unit,
) {
    private val running = AtomicBoolean()
    private var thread: Thread? = null
    private val manager = context.getSystemService(AudioManager::class.java)
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "taku:live-standby").apply { setReferenceCounted(false) }

    fun start() {
        if(!running.compareAndSet(false, true)) return
        lock.acquire(MAX_WAKE_LOCK_MS)
        thread = Thread({ listen() }, "audio-live-standby").also { it.start() }
    }

    /** Stops listening and releases the microphone, the model and the wake lock before returning. */
    fun stop() {
        if(!running.compareAndSet(true, false)) return
        thread?.let { it.interrupt(); runCatching { it.join(1000) } }
        thread = null
        runCatching { if(lock.isHeld) lock.release() }
    }

    /** Why the microphone must be left alone right now, or null when passive listening may run. */
    private fun yieldReason(): String? = when {
        manager.mode == AudioManager.MODE_IN_CALL || manager.mode == AudioManager.MODE_RINGTONE -> "CALL"
        manager.mode == AudioManager.MODE_IN_COMMUNICATION -> "CALL"
        manager.isMusicActive -> "MEDIA"
        else -> null
    }

    private fun listen() {
        val vad = try {
            Vad(null, VadModelConfig(SileroVadModelConfig(model.path, 0.5f, 0.25f, 0.25f, WINDOW, 10f), TenVadModelConfig(), 16000, 1, "cpu", false))
        } catch(error: Throwable) {
            Log.w("AudioLive", "standby vad unavailable ${error.javaClass.simpleName}")
            running.set(false); onPaused("VAD_UNAVAILABLE"); return
        }
        val ring = PcmRingBuffer()
        val detector = LiveStandbyPolicy.WakeDetector(WINDOW * 1000 / 16000)
        val bytes = ByteArray(WINDOW * 2)
        val samples = FloatArray(WINDOW)
        var recorder: AudioRecord? = null
        var paused: String? = null
        var renewed = System.currentTimeMillis()
        try {
            while(running.get()) {
                if(System.currentTimeMillis() - renewed >= RENEW_MS) { renewed = System.currentTimeMillis(); lock.acquire(MAX_WAKE_LOCK_MS) }
                val reason = yieldReason()
                if(reason != null) {
                    if(paused != reason) { recorder?.let { runCatching { it.stop() }; it.release() }; recorder = null; paused = reason; onPaused(reason) }
                    Thread.sleep(POLL_MS); continue
                }
                if(paused != null) { paused = null; onPaused(null) }
                val capture = recorder ?: AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(WINDOW * 8, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).also {
                    if(it.state != AudioRecord.STATE_INITIALIZED) { it.release(); throw AudioFailure("CAPTURE_FAILED", "Standby microphone could not start") }
                    it.startRecording(); recorder = it; ring.clear(); detector.reset(); vad.reset()
                }
                var read = 0
                while(read < bytes.size && running.get()) {
                    val count = capture.read(bytes, read, bytes.size - read)
                    if(count < 0) throw AudioFailure("CAPTURE_FAILED", "Standby microphone failed")
                    read += count
                }
                if(!running.get()) break
                ring.write(bytes)
                var sum = 0.0
                for(i in 0 until WINDOW) {
                    val sample = ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
                    samples[i] = sample / 32768f; sum += sample.toDouble() * sample
                }
                if(detector.accept(vad.compute(samples), sqrt(sum / WINDOW))) {
                    // Release the passive microphone first; the live route and AEC start only after.
                    runCatching { capture.stop() }; capture.release(); recorder = null
                    running.set(false)
                    onWake(ring.snapshot())
                    break
                }
            }
        } catch(_: InterruptedException) {
        } catch(error: Exception) {
            Log.w("AudioLive", "standby listen failure=${(error as? AudioFailure)?.code ?: error.javaClass.simpleName}")
            running.set(false); onPaused((error as? AudioFailure)?.code ?: "CAPTURE_FAILED")
        } finally {
            recorder?.let { runCatching { it.stop() }; it.release() }
            runCatching { vad.release() }
            runCatching { if(lock.isHeld) lock.release() }
        }
    }

    companion object {
        /** Silero VAD window at 16 kHz (32 ms). */
        const val WINDOW = 512
        const val POLL_MS = 500L
        /** The listener renews this lease every [RENEW_MS]; the cap keeps a stuck listener from holding the CPU. */
        const val MAX_WAKE_LOCK_MS = 5L * 60 * 1000
        const val RENEW_MS = 60_000L
    }
}
