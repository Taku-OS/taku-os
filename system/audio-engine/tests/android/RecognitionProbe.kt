// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import org.agentos.audio.StreamingRecognizer
import org.json.*
import java.io.File

/** Fixed audio through the production ASR adapter. Does not simulate microphone quality. */
class RecognitionProbe : Instrumentation() {
    private var inject=false
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);inject=arguments?.getString("injectMicrophone")=="true";start() }
    override fun onStart() {
        if(inject){injectMicrophone();return}
        val report=JSONObject().put("passed",false).put("qualityApproved",false).put("scope","fixed synthetic audio through production local ASR")
        try {
            val results=JSONArray()
            for((language,name) in listOf("en-US" to "incoming","zh-CN" to "outgoing")) {
                val bytes=File("/data/local/tmp/taku-audio-$name.pcm").readBytes()
                val samples=ShortArray(bytes.size/2){((bytes[it*2].toInt() and 255) or (bytes[it*2+1].toInt() shl 8)).toShort()}
                val final=mutableListOf<String>();var partials=0
                val started=android.os.SystemClock.elapsedRealtime()
                StreamingRecognizer(org.agentos.audio.ModelResources.root(targetContext),language) { text, complete -> if(complete)final.add(text)else partials++ }.use { recognizer ->
                    for(offset in samples.indices step 1600)recognizer.accept(samples.copyOfRange(offset,minOf(samples.size,offset+1600)))
                    recognizer.finish()
                }
                check(final.isNotEmpty())
                results.put(JSONObject().put("language",language).put("final",final.joinToString(" ")).put("partials",partials).put("totalMs",android.os.SystemClock.elapsedRealtime()-started))
            }
            report.put("passed",true).put("results",results)
            finish(-1,Bundle().apply{putString("report",report.toString())})
        }catch(e: Throwable){report.put("error",e.javaClass.simpleName);finish(0,Bundle().apply{putString("report",report.toString())})}
    }
    /** Real AudioRecord input on this UID; no test hook or raw PCM endpoint in production. */
    private fun injectMicrophone() {
        val report=JSONObject().put("passed",false).put("syntheticInput",true).put("hostMicrophoneUsed",false)
        val context=targetContext
        val audio=context.getSystemService(android.media.AudioManager::class.java)
        fun type(name: String)=Class.forName("android.media.audiopolicy.$name")
        fun call(receiver: Any,name: String,vararg args: Pair<Class<*>,Any>): Any?=receiver.javaClass.getMethod(name,*args.map{it.first}.toTypedArray()).invoke(receiver,*args.map{it.second}.toTypedArray())
        fun integer(v: Int)=Int::class.javaPrimitiveType!! to v
        var policy: Any?=null;var output: android.media.AudioTrack?=null
        try {
            val attributes=android.media.AudioAttributes.Builder()
            call(attributes,"setInternalCapturePreset",integer(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION))
            val rule=type("AudioMixingRule\$Builder").getConstructor().newInstance()
            call(rule,"addMixRule",integer(2),Any::class.java to attributes.build())
            call(rule,"addMixRule",integer(4),Any::class.java to context.applicationInfo.uid)
            call(rule,"setTargetMixRole",integer(1))
            val mixBuilder=type("AudioMix\$Builder").getConstructor(type("AudioMixingRule")).newInstance(call(rule,"build"))
            val format=android.media.AudioFormat.Builder().setSampleRate(16000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build()
            call(mixBuilder,"setFormat",android.media.AudioFormat::class.java to format);call(mixBuilder,"setRouteFlags",integer(2))
            val mix=call(mixBuilder,"build")!!
            val builder=type("AudioPolicy\$Builder").getConstructor(android.content.Context::class.java).newInstance(context)
            call(builder,"addMix",type("AudioMix") to mix);policy=call(builder,"build")!!
            check(call(audio,"registerAudioPolicy",type("AudioPolicy") to policy)==0)
            output=call(policy,"createAudioTrackSource",type("AudioMix") to mix) as android.media.AudioTrack
            output.play()
            sendStatus(0,Bundle().apply{putString("phase","microphone-route-ready")})
            val deadline=android.os.SystemClock.elapsedRealtime()+45000
            while(android.os.SystemClock.elapsedRealtime()<deadline && audio.activeRecordingConfigurations.none{it.clientAudioSource==6 && it.javaClass.getMethod("getClientUid").invoke(it)==context.applicationInfo.uid})Thread.sleep(50)
            check(android.os.SystemClock.elapsedRealtime()<deadline)
            val bytes=File("/data/local/tmp/taku-conversation.pcm").readBytes()
            val pcm=ByteArray(9600)+bytes+ByteArray(64000)
            var offset=0
            while(offset<pcm.size){val count=output.write(pcm,offset,minOf(640,pcm.size-offset),android.media.AudioTrack.WRITE_BLOCKING);check(count>0);offset+=count}
            report.put("injectedPcmMs",pcm.size*1000/32000)
            Thread.sleep(30000)
            report.put("passed",true)
        }catch(e: Throwable){report.put("error",e.javaClass.simpleName).put("message",(e.cause?.message ?: e.message)?.take(200))}
        finally {
            runCatching{output?.pause();output?.flush();output?.release()}
            if(policy!=null)runCatching{call(audio,"unregisterAudioPolicy",type("AudioPolicy") to policy!!)}
        }
        finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})
    }

}
