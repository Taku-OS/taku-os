// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.*
import org.agentos.audio.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Opt-in paid network smoke. Synthetic audio only, actual engine UID and stored device BYOK. */
class FalDirectProbe : Instrumentation() {
    private var model="kotoba"
    override fun onCreate(args: Bundle?){super.onCreate(args);model=args?.getString("model") ?: "kotoba";require(model in listOf("kotoba","grok","hybrid","whisper"));start()}
    override fun onStart(){
        val report=JSONObject().put("passed",false).put("qualityApproved",false).put("physicalOutputVerified",false)
        var session: StreamingTranslation?=null
        try {
            val snapshot=EngineConfig.control(JSONObject().put("op","read"))
            if(model!="hybrid")check(snapshot.getJSONObject("fal").getBoolean("configured"))
            check(!snapshot.toString().contains("\"key\""))
            val bytes=File("/data/local/tmp/taku-audio-incoming.pcm").readBytes()
            val samples=ShortArray(bytes.size/2){((bytes[it*2].toInt() and 255) or (bytes[it*2+1].toInt() shl 8)).toShort()}
            val started=SystemClock.elapsedRealtime();val firstAudio=AtomicLong();val firstText=AtomicLong();val outputBytes=AtomicLong()
            var translated="";var source="";var final=false;var nonSilent=false
            val onText={text: String,done: Boolean->if(firstText.get()==0L)firstText.compareAndSet(0,SystemClock.elapsedRealtime());translated=text;final=final||done}
            val onAudio={pcm: ByteArray->
                if(firstAudio.get()==0L)firstAudio.compareAndSet(0,SystemClock.elapsedRealtime())
                outputBytes.addAndGet(pcm.size.toLong())
                for(i in 0 until pcm.size-1 step 2)if(kotlin.math.abs(((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort().toInt())>100){nonSilent=true;break}
            }
            session=when(model) {
                "hybrid"->HybridTranslation(targetContext,AudioRequest(purpose="MEDIA_TRANSLATE",profileId="local-hybrid",language="en-US",targetLanguage="zh-CN",targetPackage="org.taku.callfixture.target",output="SPEECH"),onText,onAudio,{_,_->})
                "whisper"->WhisperCaptionSession(AudioRequest(purpose="MEDIA_TRANSLATE",profileId="fal-whisper",language="en-US",targetLanguage="zh-CN",targetPackage="org.taku.callfixture.target"),onText,{value,_->source=value})
                "grok"->GrokSession("en-US","zh-CN","eve",onText,onAudio)
                else->KotobaSession("en-US","zh-CN",onText,onAudio)
            }
            session.connect(if(model=="hybrid")""else EngineConfig.control(JSONObject().put("op","falCredential")).getString("key"))
            val ready=SystemClock.elapsedRealtime()
            for(offset in samples.indices step 320) {session.send(samples.copyOfRange(offset,minOf(samples.size,offset+320)));Thread.sleep(20)}
            val inputEnd=SystemClock.elapsedRealtime();session.finish();val done=SystemClock.elapsedRealtime()
            check(final && translated.contains("咖啡"))
            if(model=="whisper")check(source.lowercase().contains("coffee") && outputBytes.get()==0L)else check(outputBytes.get()>0 && nonSilent)
            report.put("model",model).put("outputSampleRate",session.outputSampleRate).put("passed",true).put("deviceDirect",true).put("hostRelay",false).put("audioUploaded",model!="hybrid").put("credentialSnapshotMasked",true)
                .put("sourceText",source).put("translatedText",translated).put("finalObserved",final).put("outputPcmBytes",outputBytes.get()).put("nonSilent",nonSilent)
                .put("connectMs",ready-started).put("firstTranslationAfterReadyMs",firstText.get()-ready).put("firstAudioAfterReadyMs",if(firstAudio.get()>0)firstAudio.get()-ready else JSONObject.NULL).put("tailCommitMs",done-inputEnd).put("inputDurationMs",samples.size*1000/16000)
        }catch(e: Throwable){report.put("error",(e as? AudioFailure)?.code ?: e.javaClass.simpleName);if(model=="whisper" && e is AudioFailure)report.put("detail",e.message)}
        finally {session?.close();finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})}
    }
}
