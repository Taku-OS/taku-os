// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import java.lang.reflect.InvocationTargetException

/** Target UID + MEDIA. No opt-out override, microphone, host tunnel or global volume change. */
class PlatformMediaCapture(private val context: Context,private val uid: Int,private val replace: Boolean) : AutoCloseable {
    companion object {
        fun isPlaying(context: Context,uid: Int): Boolean = context.getSystemService(AudioManager::class.java).activePlaybackConfigurations.any {
            it.javaClass.getMethod("getClientUid").invoke(it)==uid && it.audioAttributes.usage==AudioAttributes.USAGE_MEDIA && it.javaClass.getMethod("isActive").invoke(it)==true
        }
    }
    private val manager=context.getSystemService(AudioManager::class.java)
    private var policy: Any?=null
    @Volatile private var record: AudioRecord?=null
    private fun type(name: String)=Class.forName("android.media.audiopolicy.$name")
    private fun call(receiver: Any,name: String,vararg args: Pair<Class<*>,Any>): Any?=try {
        receiver.javaClass.getMethod(name,*args.map{it.first}.toTypedArray()).invoke(receiver,*args.map{it.second}.toTypedArray())
    }catch(e: InvocationTargetException){throw (e.targetException as? Exception ?: e)}
    private fun integer(value: Int)=Int::class.javaPrimitiveType!! to value
    fun start() {
        if(context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING")!=PackageManager.PERMISSION_GRANTED)throw AudioFailure("PERMISSION_DENIED","Media translation requires system audio-routing permission")
        if(!isPlaying(context,uid))throw AudioFailure("SOURCE_UNAVAILABLE","Play a video in the target app first")
        val rule=type("AudioMixingRule\$Builder").getConstructor().newInstance()
        call(rule,"addMixRule",integer(1),Any::class.java to AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        call(rule,"addMixRule",integer(4),Any::class.java to uid)
        val builder=type("AudioMix\$Builder").getConstructor(type("AudioMixingRule")).newInstance(call(rule,"build"))
        val format=AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
        call(builder,"setFormat",AudioFormat::class.java to format)
        call(builder,"setRouteFlags",integer(if(replace)2 else 3))
        val mix=call(builder,"build")!!
        val policyBuilder=type("AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(context)
        call(policyBuilder,"addMix",type("AudioMix") to mix)
        policy=call(policyBuilder,"build")!!
        if(manager.javaClass.getMethod("registerAudioPolicy",type("AudioPolicy")).invoke(manager,policy)!=0)throw AudioFailure("ROUTE_UNAVAILABLE","The system could not access the media track")
        val input=call(policy!!,"createAudioRecordSink",type("AudioMix") to mix) as AudioRecord
        record=input
        if(input.state!=AudioRecord.STATE_INITIALIZED)throw AudioFailure("CAPTURE_FAILED","Media source initialization failed")
        input.startRecording()
    }
    fun read(): ShortArray {
        val input=record ?: throw AudioFailure("CAPTURE_FAILED","Media source is not ready")
        val frame=ShortArray(320);val count=input.read(frame,0,frame.size,AudioRecord.READ_BLOCKING)
        if(count<=0)throw AudioFailure("CAPTURE_FAILED","Media audio capture was interrupted")
        return if(count==frame.size)frame else frame.copyOf(count)
    }
    fun stopInput(){runCatching{record?.stop()}}
    override fun close() {
        stopInput()
        var restored=true
        policy?.let{restored=runCatching{manager.javaClass.getMethod("unregisterAudioPolicy",type("AudioPolicy")).invoke(manager,it)}.isSuccess}
        policy=null
        record?.release();record=null
        if(!restored)throw AudioFailure("RESTORE_FAILED","Media route restoration could not be confirmed")
    }
}
