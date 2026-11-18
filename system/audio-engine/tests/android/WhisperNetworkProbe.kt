package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.agentos.audio.*
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException

/** Retry mode is offline. Live mode sends only the explicitly supplied synthetic test PCM. */
class WhisperNetworkProbe : Instrumentation() {
    private var live=false
    override fun onCreate(args: Bundle?) { super.onCreate(args);live=args?.getString("mode")=="live";start() }
    override fun onStart() {
        val report=JSONObject().put("passed",false).put("live",live)
        try {
            if(live) {
                val bytes=File("/data/local/tmp/taku-voice-check.pcm").readBytes()
                val pcm=ShortArray(bytes.size/2){ ((bytes[it*2].toInt() and 255) or (bytes[it*2+1].toInt() shl 8)).toShort() }
                val start=SystemClock.elapsedRealtime()
                val key=EngineConfig.control(JSONObject().put("op","falCredential")).getString("key")
                val text=WhisperTranscriber().use { it.transcribe(key,"zh-CN",pcm,deadline=start+100000) }
                check(text.contains("测试"))
                report.put("text",text).put("elapsedMs",SystemClock.elapsedRealtime()-start)
            } else {
                val pcm=ShortArray(16000){if(it%2==0)1000 else -1000}
                var requests=0
                val client=OkHttpClient.Builder().addInterceptor { chain ->
                    requests++
                    if(requests==1)throw SocketTimeoutException("synthetic transport interruption")
                    val body=when(chain.request().url.host) {
                        "rest.fal.ai" -> """{"token":"test-only","token_type":"Bearer","expires_at":"2050-01-01T00:00:00Z"}"""
                        "v3.fal.media" -> """{"access_url":"https://example.invalid/synthetic.wav"}"""
                        else -> """{"text":"Recovered test"}"""
                    }
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }.build()
                WhisperTranscriber(client).use { check(it.transcribe("test-only","en-US",pcm)=="Recovered test") }
                check(requests==4)
                var denied=0
                val auth=OkHttpClient.Builder().addInterceptor { chain ->
                    denied++
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("test")
                        .body("{}".toResponseBody("application/json".toMediaType())).build()
                }.build()
                WhisperTranscriber(auth).use {
                    val failure=runCatching { it.transcribe("test-only","en-US",pcm) }.exceptionOrNull()
                    check(failure is AudioFailure && failure.code=="PROVIDER_AUTH_FAILED")
                }
                check(denied==1)
                report.put("retryRecovered",true).put("authNotRetried",true)
            }
            report.put("passed",true)
        } catch(e: Throwable) { report.put("error",(e as? AudioFailure)?.code ?: e.javaClass.simpleName) }
        finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})
    }
}
