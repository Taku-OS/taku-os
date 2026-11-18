// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Device-direct fal Whisper → system text translation. No relay, TTS or playback in this profile. */
class WhisperCaptionSession(private val request: AudioRequest,private val text: (String,Boolean)->Unit,
    private val sourceText: (String,Boolean)->Unit) : StreamingTranslation {
    private data class Pending(val segment: FileSpeechSegments.Segment,val at: Long)
    override val outputSampleRate=16000
    private val canceled=AtomicBoolean();private val finished=AtomicBoolean()
    private val queue=ArrayBlockingQueue<Pending>(3)
    private val segments=FileSpeechSegments { segment ->
        if(!queue.offer(Pending(segment,SystemClock.elapsedRealtime())))throw AudioFailure("QUEUE_BACKLOG","Whisper caption recognition is backlogged")
    }
    private val transcriber=WhisperTranscriber()
    @Volatile private var translation: EngineConfig.Control?=null
    @Volatile private var failure: AudioFailure?=null
    @Volatile private var activeAt=0L
    private var worker: Thread?=null
    private fun active(){if(canceled.get())throw InterruptedException();failure?.let{throw it}}
    override fun connect(key: String) {
        require(request.purpose=="MEDIA_TRANSLATE" && request.output=="CAPTIONS")
        val model=EngineConfig.control(JSONObject().put("op","prepareTranslation")).getString("model")
        active();transcriber.prepare(key)
        worker=Thread({
            val overlap=CaptionOverlap()
            try {
                while(!canceled.get()) {
                    val pending=queue.poll(100,TimeUnit.MILLISECONDS)
                    if(pending==null){if(finished.get())break else continue}
                    activeAt=pending.at
                    val source=overlap.fresh(transcriber.transcribe(key,request.language,pending.segment.pcm),pending.segment.overlapping)
                    active()
                    if(source.isNotBlank()) {
                        sourceText(source,true)
                        val operation=EngineConfig.Control(JSONObject().put("op","translate").put("model",model).put("text",source)
                            .put("sourceLanguage",request.language).put("targetLanguage",request.targetLanguage))
                        translation=operation
                        val result=try{operation.execute().getString("text")}finally{operation.close();translation=null}
                        active();text(result,true)
                    }
                    activeAt=0
                }
            }catch(e: Exception){if(!canceled.get())failure=e as? AudioFailure ?: AudioFailure("TEXT_TRANSLATION_FAILED","Text translation did not complete")}
        },"audio-whisper-captions").also{it.start()}
    }
    override fun send(samples: ShortArray) {
        active()
        val head=queue.peek()?.at ?: 0L;val running=activeAt
        val oldest=if(head>0 && running>0)minOf(head,running)else maxOf(head,running)
        if(oldest>0 && SystemClock.elapsedRealtime()-oldest>20000)throw AudioFailure("QUEUE_BACKLOG","Whisper caption backlog persists")
        segments.accept(samples)
    }
    override fun finish(){active();segments.finish();finished.set(true);worker?.join(65000);active();if(worker?.isAlive==true)throw AudioFailure("PROCESSING_TIMEOUT","Whisper caption tail did not complete")}
    override fun cancel(){canceled.set(true);transcriber.cancel();translation?.close();worker?.interrupt();queue.clear()}
    override fun close(){cancel();val running=worker;if(running!=null)joinUninterruptibly(running);segments.reset();transcriber.close()}
}
