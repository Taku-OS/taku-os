// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class WhisperTranscriber(private val http: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(5,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()) : AutoCloseable {
    private val canceled=AtomicBoolean()
    @Volatile private var call: Call?=null
    private var uploadToken=""
    private var uploadAuthorization=""
    private var uploadExpiresAt=0L

    private fun active(deadline: Long?) {
        if(canceled.get())throw InterruptedException()
        if(deadline!=null && SystemClock.elapsedRealtime()>=deadline)throw AudioFailure("PROCESSING_TIMEOUT","Whisper transcription timed out")
    }

    fun prepare(key: String,deadline: Long?=null) {
        active(deadline)
        if(uploadAuthorization.isNotEmpty() && System.currentTimeMillis()<uploadExpiresAt-30000)return
        val result=execute(key,Request.Builder().url("https://rest.fal.ai/storage/auth/token?storage_type=fal-cdn-v3")
            .header("Authorization","Key $key").post("{}".toRequestBody("application/json".toMediaType())).build(),deadline)
        uploadToken=result.getString("token")
        uploadAuthorization=result.getString("token_type")+" "+uploadToken
        uploadExpiresAt=java.time.OffsetDateTime.parse(result.getString("expires_at")).toInstant().toEpochMilli()
    }

    fun transcribe(key: String,language: String,pcm: ShortArray,count: Int=pcm.size,deadline: Long?=null,offset: Int=0): String {
        require(offset in 0..pcm.size && count in 0..pcm.size-offset)
        active(deadline)
        if(!WhisperSignalGate.hasSignal(pcm,count,offset))return ""
        prepare(key,deadline)
        val uploaded=execute(key,Request.Builder().url("https://v3.fal.media/files/upload")
            .header("Authorization",uploadAuthorization).header("X-Fal-File-Name","speech.wav")
            .header("X-Fal-Object-Lifecycle",JSONObject().put("expiration_duration_seconds",300).toString())
            .post(PcmWaveBody(pcm,count,offset){active(deadline)}).build(),deadline)
        val address=uploaded.getString("access_url")
        if(!address.startsWith("https://"))throw AudioFailure("PROVIDER_PROTOCOL_ERROR","fal did not return an HTTPS audio URL")
        val body=JSONObject().put("audio_url",address).put("task","transcribe")
            .put("language",language.substringBefore('-')).put("chunk_level","none").put("diarize",false)
        val response=execute(key,Request.Builder().url("https://fal.run/fal-ai/whisper").header("Authorization","Key $key")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build(),deadline)
        val result=response.opt("text") as? String ?: throw AudioFailure("PROVIDER_PROTOCOL_ERROR","Whisper did not return a transcript")
        if(result.length>8192)throw AudioFailure("PROVIDER_PROTOCOL_ERROR","Whisper transcript is too long")
        return result.trim()
    }

    private fun execute(key: String,request: Request,deadline: Long?): JSONObject {
        // One bounded retry for transport interruption. Never retry authentication/protocol
        // failures, never log URLs/tokens/audio, and always retain the final drain deadline.
        for(attempt in 0..1) {
            try { return executeOnce(key,request,deadline) }
            catch(error: AudioFailure) {
                if(attempt == 1 || error.code !in setOf("NETWORK_FAILED","PROVIDER_TIMEOUT"))throw error
                active(deadline)
                Thread.sleep(250)
            }
        }
        error("unreachable")
    }

    private fun executeOnce(key: String,request: Request,deadline: Long?): JSONObject {
        active(deadline)
        val operation=http.newCall(request)
        if(deadline!=null)operation.timeout().timeout(minOf(30000L,maxOf(1L,deadline-SystemClock.elapsedRealtime())),TimeUnit.MILLISECONDS)
        call=operation
        try {
            active(deadline)
            operation.execute().use { response ->
                val bytes=response.body?.byteStream()?.use{it.readNBytes(65537)} ?: ByteArray(0)
                if(bytes.size>65536)throw AudioFailure("PROVIDER_PROTOCOL_ERROR","fal response is too large")
                val payload=try{JSONObject(String(bytes,Charsets.UTF_8))}catch(_: Exception){throw AudioFailure("PROVIDER_PROTOCOL_ERROR","fal did not return JSON")}
                if(!response.isSuccessful) {
                    val value=payload.opt("detail") ?: payload.opt("error") ?: payload.opt("message")
                    var detail=when(value) {
                        is org.json.JSONArray -> (0 until value.length()).joinToString { index -> val error=value.getJSONObject(index);error.optJSONArray("loc")?.toString().orEmpty()+": "+error.optString("msg") }
                        is JSONObject -> value.optString("message",value.optString("msg",value.optString("type")))
                        else -> value?.toString().orEmpty()
                    }.replace(key,"[key]").replace(Regex("(?:data:|https?://)[^\\s\"]+"),"[audio address]")
                    if(uploadToken.isNotEmpty())detail=detail.replace(uploadToken,"[token]")
                    android.util.Log.w("TakuWhisper","HTTP ${response.code}")
                    throw AudioFailure(when(response.code){401,403->"PROVIDER_AUTH_FAILED";422->"PROVIDER_PROTOCOL_ERROR";429->"PROVIDER_RATE_LIMITED";else->"PROVIDER_UNAVAILABLE"},"HTTP ${response.code}: ${detail.take(500)}")
                }
                active(deadline)
                return payload
            }
        }catch(e: java.io.IOException){
            active(deadline)
            val timeout=e is java.io.InterruptedIOException
            android.util.Log.w("TakuWhisper","Transport ${request.url.host}: ${e.javaClass.simpleName}")
            throw AudioFailure(if(timeout)"PROVIDER_TIMEOUT" else "NETWORK_FAILED",if(timeout)"Whisper request timed out" else "Whisper network request did not complete")
        }
        finally{call=null}
    }

    fun cancel(){canceled.set(true);call?.cancel()}
    override fun close(){cancel();uploadToken="";uploadAuthorization="";http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
}

internal class PcmWaveBody(private val pcm: ShortArray,private val count: Int,private val offset: Int=0,private val active: ()->Unit) : RequestBody() {
    init{require(offset in 0..pcm.size && count in 0..pcm.size-offset)}
    override fun contentType()="audio/wav".toMediaType()
    override fun contentLength()=44L+count*2L
    override fun writeTo(sink: BufferedSink) {
        val header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray());putInt(36+count*2);put("WAVEfmt ".toByteArray());putInt(16);putShort(1);putShort(1)
            putInt(16000);putInt(32000);putShort(2);putShort(16);put("data".toByteArray());putInt(count*2)
        }.array()
        active();sink.write(header)
        val bytes=ByteArray(8192)
        var written=0
        while(written<count) {
            active()
            val samples=minOf(bytes.size/2,count-written)
            for(index in 0 until samples) {
                val sample=pcm[offset+written+index].toInt()
                bytes[index*2]=sample.toByte();bytes[index*2+1]=(sample shr 8).toByte()
            }
            sink.write(bytes,0,samples*2);written+=samples
        }
    }
}
