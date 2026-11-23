// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.media.*
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One worker owns inference and native release. No PCM crosses Binder. */
class ZipformerDriver(private val context: Context, private val request: AudioRequest) : AudioDriver {
    private val canceled = AtomicBoolean()
    private val finishing = AtomicBoolean()
    private var worker: Thread? = null
    override fun start(sink: AudioDriver.Sink) {
        worker = Thread({
            var recorder: AudioRecord? = null
            var recognizer: StreamingRecognizer? = null
            var recordingCallback: AudioManager.AudioRecordingCallback? = null
            var complete=false
            try {
                // A missing model fails before the microphone ever turns on.
                if(!ModelResources.ready(ModelResources.root(context),request.language))throw AudioFailure("MODEL_MISSING","Install the speech model first")
                // The microphone starts first and the model loads meanwhile: a press can be spoken
                // into at once, and whatever is said during the load is kept (pre-roll below).
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,maxOf(32000,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)))
                recorder = audio
                check(audio.state == AudioRecord.STATE_INITIALIZED)
                if (canceled.get()) return@Thread
                recordingCallback=object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if(!canceled.get() && audio.activeRecordingConfiguration?.isClientSilenced == true) sink.failed("CAPTURE_SILENCED")
                    }
                }
                audio.registerAudioRecordingCallback(context.mainExecutor,recordingCallback)
                audio.startRecording()
                if(audio.recordingState!=AudioRecord.RECORDSTATE_RECORDING)throw AudioFailure("CAPTURE_FAILED","Microphone has not started recording")
                sink.ready()
                val buffer = ShortArray(maxOf(1600,audio.bufferSizeInFrames))
                val meter=PcmLevelMeter { level, frames -> if(!canceled.get())sink.level(level,frames) }
                // Pre-roll: one short-lived reader keeps the audio (and the level meter) while the
                // model loads on this thread, then the recognizer hears it first, in order.
                val preroll=ArrayList<ShortArray>()
                val loading=AtomicBoolean(true)
                var prerollFailed=false
                val reader=Thread({
                    val chunk=ShortArray(1600)
                    var kept=0
                    while(loading.get() && !canceled.get()) {
                        val count=audio.read(chunk,0,chunk.size,AudioRecord.READ_NON_BLOCKING)
                        if(count>0) {
                            val copy=chunk.copyOf(count)
                            if(kept<PREROLL_MAX_SAMPLES){preroll.add(copy);kept+=count}
                            meter.accept(copy,count)
                        } else if(count<0){prerollFailed=true;break}
                        else Thread.sleep(10)
                    }
                },"audio-zipformer-preroll")
                reader.start()
                try {
                    recognizer = StreamingRecognizer(ModelResources.root(context),request.language) { text, final ->
                        if(!canceled.get())sink.text(text,final)
                    }
                } finally {
                    loading.set(false)
                    reader.join()
                }
                if (canceled.get()) return@Thread
                if(prerollFailed)throw AudioFailure("CAPTURE_FAILED","Recording did not complete")
                for(chunk in preroll)recognizer.accept(chunk,chunk.size)
                preroll.clear()
                while (!canceled.get() && !finishing.get()) {
                    val count = audio.read(buffer,0,minOf(1600,buffer.size),AudioRecord.READ_NON_BLOCKING)
                    if(canceled.get())break
                    if(count>0){meter.accept(buffer,count);recognizer.accept(buffer,count)}
                    else if(count<0)throw AudioFailure("CAPTURE_FAILED","Recording did not complete")
                    else if(count==0 && !finishing.get())Thread.sleep(10)
                }
                var tail=0
                while(!canceled.get() && tail<buffer.size) {
                    val count=audio.read(buffer,tail,buffer.size-tail,AudioRecord.READ_NON_BLOCKING)
                    if(count<0)throw AudioFailure("CAPTURE_FAILED","Recording tail did not complete")
                    if(count==0)break
                    tail+=count
                }
                audio.stop()
                audio.unregisterAudioRecordingCallback(recordingCallback);recordingCallback=null
                audio.release();recorder=null
                if (!canceled.get()) {
                    if(tail>0){meter.accept(buffer,tail);recognizer.accept(buffer,tail)}
                    meter.flush()
                    recognizer.finish()
                    complete=true
                }
            } catch (e: Exception) {
                if (!canceled.get()) sink.failed((e as? AudioFailure)?.code ?: "RECOGNITION_FAILED")
            } finally {
                var restored=true
                recorder?.let { audio ->
                    recordingCallback?.let { runCatching { audio.unregisterAudioRecordingCallback(it) } }
                    runCatching { audio.stop() }
                    if(runCatching { audio.release() }.isFailure)restored=false
                }
                if(runCatching { recognizer?.close() }.isFailure)restored=false
                sink.released(restored)
            }
            if (complete && !canceled.get()) sink.completed()
        },"audio-zipformer").also { it.start() }
    }
    override fun finish() { finishing.set(true) }
    private companion object {
        /** At most 30 s of 16 kHz audio is kept while the model loads. */
        const val PREROLL_MAX_SAMPLES = 16_000 * 30
    }
    override fun close() { canceled.set(true);worker?.interrupt() }
}
