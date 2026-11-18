// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class WhisperDictationDriver(private val context: Context,private val request: AudioRequest) : AudioDriver {
    private val backend=AudioProfiles.dictation.getValue("fal-whisper")
    private val canceled=AtomicBoolean()
    private val deadline=AtomicLong()
    private val transcriber=WhisperTranscriber()
    private val ranges=WhisperPcmRanges()
    @Volatile private var credential: EngineConfig.Control?=null
    @Volatile private var worker: Thread?=null
    @Volatile private var networkWorker: Thread?=null

    private fun active() {
        if(canceled.get())throw InterruptedException()
        val end=deadline.get()
        if(end!=0L && SystemClock.elapsedRealtime()>=end)throw AudioFailure("PROCESSING_TIMEOUT","Whisper transcription timed out")
    }

    private fun failed(sink: AudioDriver.Sink,error: Exception) {
        if(!canceled.get())try{sink.failed((error as? AudioFailure)?.code ?: "RECOGNITION_FAILED")}finally{close()}
    }

    override fun start(sink: AudioDriver.Sink) {
        worker=Thread({
            var audio: AudioRecord?=null
            var callback: AudioManager.AudioRecordingCallback?=null
            var pcm: ShortArray?=null
            var complete=false
            var restored=true
            try {
                active()
                val operation=EngineConfig.Control(JSONObject().put("op","falCredential"))
                credential=operation
                val key=try{active();operation.execute().getString("key")}finally{operation.close();credential=null}
                active()
                val samples=ShortArray((backend.maxCaptureMs!!*16).toInt())
                pcm=samples
                val recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,maxOf(32000,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)))
                audio=recorder
                if(recorder.state!=AudioRecord.STATE_INITIALIZED)throw AudioFailure("CAPTURE_FAILED","Microphone is not ready")
                active()
                callback=object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        if(!canceled.get() && recorder.activeRecordingConfiguration?.isClientSilenced==true)sink.failed("CAPTURE_SILENCED")
                    }
                }
                recorder.registerAudioRecordingCallback(context.mainExecutor,callback)
                recorder.startRecording()
                if(recorder.recordingState!=AudioRecord.RECORDSTATE_RECORDING)throw AudioFailure("CAPTURE_FAILED","Microphone has not started recording")
                active();sink.ready()
                val meter=PcmLevelMeter { level, frames -> if(!canceled.get())sink.level(level,frames) }
                networkWorker=Thread({
                    try {
                        var processed=0
                        var attempted=0
                        val preview=StringBuilder()
                        while(true) {
                            val range=ranges.next(processed,attempted) ?: break
                            active()
                            val text=try {
                                transcriber.transcribe(key,request.language,samples,range.count,
                                    deadline.get().takeIf{it!=0L},range.offset)
                            } catch(error: AudioFailure) {
                                active()
                                if(range.final || error.code !in setOf("NETWORK_FAILED","PROVIDER_TIMEOUT","PROVIDER_UNAVAILABLE"))throw error
                                attempted=range.offset+range.count
                                android.util.Log.w("TakuWhisper","Preview deferred: ${error.code}")
                                continue
                            }
                            active()
                            if(range.final) {
                                sink.text(text,true)
                                complete=true
                                break
                            }
                            processed=range.offset+range.count
                            if(text.isNotBlank()) {
                                if(preview.isNotEmpty() && Character.UnicodeScript.of(preview.last().code)!=Character.UnicodeScript.HAN &&
                                    Character.UnicodeScript.of(text.first().code)!=Character.UnicodeScript.HAN)preview.append(' ')
                                preview.append(text)
                                sink.text(preview.toString(),false)
                            }
                        }
                    }catch(error: Exception){failed(sink,error)}
                },"audio-whisper-network").also{it.start()}
                val captureDeadline=SystemClock.elapsedRealtime()+backend.maxCaptureMs!!
                var count=0
                while(!canceled.get() && deadline.get()==0L) {
                    val read=recorder.read(samples,count,minOf(1600,samples.size-count),AudioRecord.READ_NON_BLOCKING)
                    active()
                    if(read>0) {
                        meter.accept(samples,read,count)
                        count+=read;ranges.publish(count)
                    } else if(read<0)throw AudioFailure("CAPTURE_FAILED","Recording did not complete")
                    if(count==samples.size || SystemClock.elapsedRealtime()>=captureDeadline) {
                        finish();sink.inputEnded();break
                    }
                    if(read==0 && deadline.get()==0L)Thread.sleep(10)
                }
                var tail=minOf(recorder.bufferSizeInFrames,samples.size-count)
                while(!canceled.get() && tail>0) {
                    val read=recorder.read(samples,count,minOf(1600,tail),AudioRecord.READ_NON_BLOCKING)
                    active()
                    if(read<0)throw AudioFailure("CAPTURE_FAILED","Recording tail did not complete")
                    if(read==0)break
                    meter.accept(samples,read,count)
                    count+=read;tail-=read;ranges.publish(count)
                }
                recorder.stop()
                recorder.unregisterAudioRecordingCallback(callback);callback=null
                recorder.release();audio=null
                active()
                meter.flush()
                ranges.seal()
            }catch(error: Exception) {
                failed(sink,error)
            }finally {
                credential?.close();credential=null
                audio?.let { recorder ->
                    callback?.let{runCatching{recorder.unregisterAudioRecordingCallback(it)}}
                    runCatching{recorder.stop()}
                    if(runCatching{recorder.release()}.isFailure)restored=false
                }
                networkWorker?.let{joinUninterruptibly(it)}
                if(runCatching{transcriber.close()}.isFailure)restored=false
                pcm?.fill(0)
                sink.released(restored)
            }
            if(complete && !canceled.get())sink.completed()
        },"audio-whisper-dictation").also{it.start()}
    }

    override fun finish(){deadline.compareAndSet(0L,SystemClock.elapsedRealtime()+backend.drainTimeoutMs)}
    override fun drainTimeoutMs()=backend.drainTimeoutMs
    override fun close() {
        if(!canceled.compareAndSet(false,true))return
        ranges.cancel()
        Thread({credential?.close();transcriber.cancel();worker?.interrupt();networkWorker?.interrupt()},"audio-whisper-cancel").start()
    }
}
