// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Local Zipformer/Kokoro; only finalized transcript text goes to the system text model. */
class HybridTranslation(private val context: Context,private val request: AudioRequest,
    private val text: (String,Boolean)->Unit,private val audio: (ByteArray)->Unit,
    private val sourceText: (String,Boolean)->Unit) : StreamingTranslation {
    private data class Segment(val text: String,val acceptedAt: Long)
    override var outputSampleRate=24000;private set
    private val canceled=AtomicBoolean()
    private val finished=AtomicBoolean()
    private val queue=ArrayBlockingQueue<Segment>(3)
    @Volatile private var failure: String?=null
    @Volatile private var activeAt=0L
    @Volatile private var operation: EngineConfig.Control?=null
    @Volatile private var recognizer: StreamingRecognizer?=null
    /** accept()/finish() run on capture threads; the native recognizer is freed only once none is inside it (issue #26 #3). */
    private val feed=FeedGate()
    private var synthesizer: LocalSynthesizer?=null
    private var worker: Thread?=null
    private var model=""
    private fun active(){if(canceled.get())throw InterruptedException();failure?.let{throw AudioFailure(it,"Text translation did not complete")}}
    override fun connect(key: String) {
        val prepared=EngineConfig.control(JSONObject().put("op","prepareTranslation"));model=prepared.getString("model")
        active()
        val models=ModelResources.root(context)
        recognizer=StreamingRecognizer(models,request.language) { value,final ->
            if(!canceled.get()) {
                sourceText(value,final)
                if(final && !queue.offer(Segment(value,SystemClock.elapsedRealtime())))throw AudioFailure("QUEUE_BACKLOG","Text awaiting translation is backlogged")
            }
        }
        active()
        if(request.output=="SPEECH"){synthesizer=LocalSynthesizer(models);outputSampleRate=synthesizer!!.sampleRate}
        active()
        worker=Thread({
            try {
                while(!canceled.get()) {
                    val segment=queue.poll(100,TimeUnit.MILLISECONDS)
                    if(segment==null){if(finished.get())break else continue}
                    activeAt=segment.acceptedAt
                    val call=EngineConfig.Control(JSONObject().put("op","translate").put("model",model).put("text",segment.text)
                        .put("sourceLanguage",request.language).put("targetLanguage",request.targetLanguage))
                    operation=call
                    val translated=try{call.execute().getString("text")}finally{call.close();operation=null}
                    active();text(translated,true)
                    val tts=synthesizer
                    if(tts!=null) {
                        val voice=VoiceCatalog.select(request.targetLanguage,request.voiceId)
                        tts.generate(translated,voice,request.speed) { samples ->
                            if(canceled.get())0 else {
                                try {
                                    for(offset in samples.indices step 2048) {
                                        if(canceled.get())break
                                        val count=minOf(2048,samples.size-offset);val pcm=ByteArray(count*2)
                                        for(i in 0 until count){val value=(samples[offset+i].coerceIn(-1f,1f)*32767).toInt();pcm[2*i]=value.toByte();pcm[2*i+1]=(value shr 8).toByte()}
                                        audio(pcm)
                                    }
                                    if(canceled.get())0 else 1
                                }catch(e: Exception){failure=(e as? AudioFailure)?.code ?: "PLAYBACK_FAILED";0}
                            }
                        }
                        active()
                    }
                    activeAt=0
                }
            }catch(e: Exception){if(!canceled.get())failure=(e as? AudioFailure)?.code ?: "TEXT_TRANSLATION_FAILED"}
            finally{synthesizer?.close();synthesizer=null}
        },"audio-hybrid-text").also{it.start()}
    }
    override fun send(samples: ShortArray) {
        active()
        val head=queue.peek()?.acceptedAt ?: 0L
        val running=activeAt
        val oldest=if(head>0 && running>0)minOf(head,running)else maxOf(head,running)
        if(oldest>0 && SystemClock.elapsedRealtime()-oldest>15000)throw AudioFailure("QUEUE_BACKLOG","Text translation backlog persists")
        feed.use{(recognizer ?: throw InterruptedException("input closed")).accept(samples)}
    }
    override fun finish() {
        active();feed.use{(recognizer ?: throw InterruptedException("input closed")).finish()};finished.set(true)
        worker?.join(60000);active()
        if(worker?.isAlive==true)throw AudioFailure("PROCESSING_TIMEOUT","Text translation tail did not complete")
    }
    override fun cancel(){canceled.set(true);operation?.close();worker?.interrupt();queue.clear()}
    override fun close() {
        cancel()
        // Native inference releases on its owning worker; do not free it from the Binder thread.
        val running=worker
        if(running!=null)joinUninterruptibly(running)
        if(running==null){synthesizer?.close();synthesizer=null}
        // Refuse further capture frames and wait for an in-flight accept()/finish() before freeing.
        val freed=feed.retire{recognizer?.close();recognizer=null}
        if(!freed)android.util.Log.w("TakuAudio","recognizer still in use at close; left unreleased")
    }
}
