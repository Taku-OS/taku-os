// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Private companion of interruptible reading. ASR, microphone and AEC stay under its lease. */
internal class BargeInCapture(private val context: Context,language: String,
    private val transcript: (String,Boolean)->Unit,private val failure: (String)->Unit) : AutoCloseable {
    private val stopped=AtomicBoolean()
    private val endpoint=AtomicBoolean()
    private val released=CountDownLatch(1)
    private var recognizer: StreamingRecognizer?=null
    private var recorder: AudioRecord?=null
    private var echo: AcousticEchoCanceler?=null
    private var worker: Thread?=null
    init {
        if(!AcousticEchoCanceler.isAvailable())throw AudioFailure("AEC_UNAVAILABLE","This device does not support echo cancellation. Use turn-taking mode")
        try {
            recognizer=StreamingRecognizer(ModelResources.root(context),language) { text,final ->
                if(!stopped.get()) { transcript(text,final);if(final)endpoint.set(true) }
            }
            recorder=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,16000,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,maxOf(32000,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)))
            check(recorder!!.state==AudioRecord.STATE_INITIALIZED)
            echo=AcousticEchoCanceler.create(recorder!!.audioSessionId)
            if(echo==null || echo!!.setEnabled(true)!=AudioEffect.SUCCESS || !echo!!.enabled)
                throw AudioFailure("AEC_UNAVAILABLE","Could not enable echo cancellation. Use turn-taking mode")
        }catch(e: Exception){release();throw e}
    }
    fun start() {
        if(stopped.get()){release();return}
        worker=Thread({
            val audio=recorder!!
            val callback=object: AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if(!stopped.get() && audio.activeRecordingConfiguration?.isClientSilenced==true)failure("CAPTURE_SILENCED")
                }
            }
            try {
                if(stopped.get())return@Thread
                audio.registerAudioRecordingCallback(context.mainExecutor,callback)
                audio.startRecording()
                val buffer=ShortArray(1600)
                while(!stopped.get() && !endpoint.get()) {
                    val count=audio.read(buffer,0,buffer.size)
                    if(stopped.get())break
                    if(count<=0)throw AudioFailure("CAPTURE_FAILED","Interruption recognition recording was interrupted")
                    recognizer!!.accept(buffer,count)
                }
            }catch(e: Exception){if(!stopped.get())failure((e as? AudioFailure)?.code ?: "RECOGNITION_FAILED")}
            finally {runCatching{audio.unregisterAudioRecordingCallback(callback)};release()}
        },"audio-reading-barge-in").also{it.start()}
    }
    fun awaitEndpoint(): Boolean = released.await(15,TimeUnit.SECONDS) && endpoint.get()
    override fun close() { stopped.set(true);runCatching{recorder?.stop()} }
    /** Called only by the reading worker, never the UI/Binder thread. */
    fun closeAndAwait() {
        close()
        if(worker==null)release()
        var interrupted=false
        while(true)try{released.await();break}catch(_: InterruptedException){interrupted=true}
        if(interrupted)Thread.currentThread().interrupt()
    }
    private fun release() {
        try {
            recorder?.let{runCatching{it.stop()};it.release()};recorder=null
            echo?.release();echo=null
            recognizer?.close();recognizer=null
        }finally{released.countDown()}
    }
}
