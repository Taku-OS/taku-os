package org.agentos.assistant

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Microphone to an AAC-LC MP4 (16 kHz mono, 64 kbit/s) written by this process.
 *
 * [MediaRecorder] hands its output file to mediaserver, and mediaserver may not read or write a
 * system app's private files (`system_app_data_file`; the Assistant runs as `android.uid.system`),
 * so every saved recording failed under enforcing SELinux (BUG-0045). Here the PCM is read with
 * [AudioRecord], the codec only exchanges buffers, and [MediaMuxer] writes the file from this
 * process. The capture still needs a microphone foreground service (`AudioCaptureService`).
 *
 * One worker thread owns the recorder, codec and muxer and releases them; [stop] asks it to end
 * the stream and waits for the finished file. [failed] reports a capture failure or the size
 * limit (the file up to the limit is still finalized) from a thread of its own, so it may call
 * [stop].
 */
internal class MicrophoneAacRecorder(private val file: File, private val maxBytes: Long, private val failed: (String) -> Unit) {
    private val record: AudioRecord
    private val stopping = AtomicBoolean()
    private var worker: Thread? = null
    @Volatile private var finalized = false
    @Volatile private var error: String? = null

    init {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw IOException("RECORDING_UNSUPPORTED")
        // Saved microphone audio is private. Keep it in Android's sensitive-capture lane:
        // otherwise this system-UID recorder wins the ordinary capture lane and silences
        // the Audio Engine's concurrent dictation (BUG-0045). Only the privileged engine,
        // with its existing concurrent-capture permission, may recognize alongside it.
        record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 4, CHUNK_BYTES * 4))
            .setPrivacySensitive(true)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("RECORDING_UNAVAILABLE")
        }
    }

    val activeRecordingConfiguration: AudioRecordingConfiguration? get() = record.activeRecordingConfiguration

    fun registerAudioRecordingCallback(executor: Executor, callback: AudioManager.AudioRecordingCallback) =
        record.registerAudioRecordingCallback(executor, callback)

    /** Starts capture; throws if the microphone or encoder cannot start (nothing keeps running then). */
    fun start() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) throw IOException("RECORDING_NOT_STARTED")
            codec.start()
        } catch (failure: Exception) {
            runCatching { record.stop() }
            runCatching { codec.release() }
            runCatching { muxer?.release() }
            throw failure
        }
        val output = muxer
        worker = Thread({ encode(codec, output) }, "assistant-microphone-aac").also { it.start() }
    }

    private fun encode(codec: MediaCodec, muxer: MediaMuxer) {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var muxing = false
        var inputEnded = false
        var pcmBytes = 0L
        var written = 0L
        try {
            while (true) {
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        if (stopping.get()) {
                            codec.queueInputBuffer(index, 0, 0, presentationUs(pcmBytes), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val wanted = minOf(buffer.remaining(), CHUNK_BYTES) and 1.inv()
                            val count = record.read(buffer, wanted)
                            if (count < 0) throw IOException("RECORDING_FAILED:read:$count")
                            codec.queueInputBuffer(index, 0, count, presentationUs(pcmBytes), 0)
                            pcmBytes += count
                        }
                    }
                }
                var outputEnded = false
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (inputEnded) TIMEOUT_US else 0)
                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxing) throw IOException("RECORDING_FAILED:format")
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxing = true
                        continue
                    }
                    if (index < 0) continue
                    val buffer = codec.getOutputBuffer(index)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxing) {
                        buffer.position(info.offset).limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                        written += info.size
                        if (written >= maxBytes && error == null) {
                            // Like MediaRecorder's size limit: keep what fits, end the stream cleanly.
                            error = "RECORDING_SIZE_LIMIT"
                            stopping.set(true)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { outputEnded = true; break }
                }
                if (outputEnded) break
            }
            runCatching { record.stop() }
            codec.stop()
            if (muxing && written > 0) {
                muxer.stop()
                finalized = true
            }
        } catch (failure: Exception) {
            if (error == null) error = (failure as? IOException)?.message?.takeIf { it.startsWith("RECORDING_") } ?: "RECORDING_FAILED"
        } finally {
            runCatching { record.stop() }
            runCatching { codec.release() }
            runCatching { muxer.release() }
            record.release()
        }
        // A stop the caller asked for is not a failure; the size limit and capture errors are.
        val code = error
        if (code != null) Thread({ failed(code) }, "assistant-microphone-failed").start()
    }

    /**
     * Ends capture and waits for the file. Throws when nothing playable was saved (no encoded
     * audio, or the stream could not be finalized); the caller deletes the file then.
     */
    fun stop() {
        stopping.set(true)
        val thread = worker ?: throw IOException("RECORDING_NOT_STARTED")
        if (thread !== Thread.currentThread()) thread.join(STOP_TIMEOUT_MS)
        if (thread.isAlive) throw IOException("RECORDING_FINALIZE_TIMEOUT")
        if (!finalized) throw IOException("RECORDING_FINALIZE_FAILED")
    }

    /** Releases the microphone if [start] never ran or [stop] was not called. */
    fun release() {
        stopping.set(true)
        if (worker == null) record.release()
    }

    private fun presentationUs(pcmBytes: Long) = pcmBytes / 2 * 1_000_000L / SAMPLE_RATE

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 64_000
        /** 64 ms of 16 kHz mono PCM per codec input buffer. */
        const val CHUNK_BYTES = 2048
        const val TIMEOUT_US = 10_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
