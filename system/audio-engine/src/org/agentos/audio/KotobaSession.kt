// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.util.Base64
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Fixed fal endpoint. Credentials and upstream payloads never enter public errors or logs. */
class KotobaSession(private val source: String,private val target: String,
    private val text: (String,Boolean)->Unit,private val audio: (ByteArray)->Unit) : StreamingTranslation {
    override val outputSampleRate=24000
    private val client=OkHttpClient.Builder().connectTimeout(90,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS)
        .pingInterval(20,TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private val ready=CountDownLatch(1)
    private val committed=CountDownLatch(1)
    private val closed=AtomicBoolean()
    private val finishing=AtomicBoolean()
    @Volatile private var socket: WebSocket?=null
    @Volatile private var failure: String?=null
    private var translated=""
    private fun fail(code: String){failure=code;ready.countDown();committed.countDown()}
    private fun checkActive(){if(closed.get())throw InterruptedException();failure?.let{throw AudioFailure(it,"Realtime translation connection did not complete")}}
    override fun connect(key: String) {
        val request=Request.Builder().url("https://fal.run/kotoba-technologies/kotoba-sts/v1/realtime_voice")
            .header("Authorization","Key $key").build()
        socket=client.newWebSocket(request,object : WebSocketListener() {
            override fun onOpen(ws: WebSocket,response: Response) {
                if(closed.get()){ws.cancel();return}
                val session=JSONObject().put("input_audio_format","pcm16").put("input_audio_sample_rate",16000)
                    .put("input_audio_transcription",JSONObject().put("language",source.substringBefore('-')).put("target_language",target.substringBefore('-')))
                    .put("turn_detection",false)
                if(!ws.send(JSONObject().put("type","voice_session.update").put("session",session).toString()))fail("NETWORK_FAILED")
            }
            override fun onMessage(ws: WebSocket,message: String) {
                if(closed.get())return
                try {
                    if(message.length>1_000_000)throw AudioFailure("QUEUE_BACKLOG","Translated-speech output is too large")
                    val event=JSONObject(message)
                    when(event.optString("type")) {
                        "voice_session.updated" -> ready.countDown()
                        "conversation.item.input_audio_transcription.text.delta" -> {
                            for(part in event.optString("delta").chunked(2000)) {
                                if(translated.length+part.length>3500){text(translated,true);translated=""}
                                translated+=part;text(translated,false)
                            }
                        }
                        "conversation.item.input_audio_transcription.audio.delta" -> {
                            val pcm=Base64.decode(event.getString("delta"),Base64.DEFAULT)
                            if(pcm.size>24000*2*15 || pcm.size%2!=0)throw AudioFailure("QUEUE_BACKLOG","Translated-speech output is too large")
                            audio(pcm)
                        }
                        "input_audio_buffer.committed" -> if(finishing.get()) {
                            if(translated.isNotBlank())text(translated,true)
                            translated="";committed.countDown()
                        }
                        "error","x-fal-error" -> fail("PROVIDER_REJECTED")
                    }
                }catch(e: Exception){fail((e as? AudioFailure)?.code ?: "PROVIDER_PROTOCOL_ERROR")}
            }
            override fun onFailure(ws: WebSocket,t: Throwable,response: Response?) {if(!closed.get())fail(if(response?.code in listOf(401,403))"PROVIDER_AUTH_FAILED" else "NETWORK_FAILED")}
            override fun onClosed(ws: WebSocket,code: Int,reason: String) {if(!closed.get() && committed.count!=0L)fail("NETWORK_FAILED")}
        })
        if(!ready.await(100,TimeUnit.SECONDS))throw AudioFailure("CONNECT_TIMEOUT","Realtime translation connection timed out")
        checkActive()
    }
    override fun send(samples: ShortArray) {
        checkActive()
        val bytes=ByteArray(samples.size*2)
        for(i in samples.indices){bytes[2*i]=samples[i].toByte();bytes[2*i+1]=(samples[i].toInt() shr 8).toByte()}
        val ws=socket ?: throw AudioFailure("NETWORK_FAILED","Translation is not connected")
        // Includes base64/JSON overhead: roughly one second of accepted mono input.
        if(ws.queueSize()>48000)throw AudioFailure("QUEUE_BACKLOG","Audio uploads are backlogged")
        if(!ws.send(JSONObject().put("type","input_audio_buffer.append").put("audio",Base64.encodeToString(bytes,Base64.NO_WRAP)).toString()))throw AudioFailure("NETWORK_FAILED","Audio upload did not complete")
    }
    override fun finish() {
        checkActive()
        if(finishing.compareAndSet(false,true) && socket?.send("{\"type\":\"input_audio_buffer.commit\"}")!=true)throw AudioFailure("NETWORK_FAILED","Audio tail was not submitted")
        if(!committed.await(20,TimeUnit.SECONDS))throw AudioFailure("PROCESSING_TIMEOUT","Timed out waiting for translation tail")
        checkActive()
    }
    fun checkConnection()=checkActive()
    override fun close(){if(closed.compareAndSet(false,true)){socket?.cancel();ready.countDown();committed.countDown();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}}
}
