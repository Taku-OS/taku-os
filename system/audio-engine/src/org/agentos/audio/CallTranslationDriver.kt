// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Two model sessions, one UID-confined call route. No global mode or volume changes. */
class CallTranslationDriver(private val context: Context,private val request: AudioRequest) : AudioDriver {
    private val route=RouteGate()
    private val finishing=AtomicBoolean()
    @Volatile private var capture: PlatformCallCapture?=null
    @Volatile private var incoming: StreamingTranslation?=null
    @Volatile private var outgoing: StreamingTranslation?=null
    @Volatile private var incomingPlayer: StreamingPlayback?=null
    @Volatile private var outgoingPlayer: StreamingPlayback?=null
    private var worker: Thread?=null
    private fun model(request: AudioRequest,direction: String,sink: AudioDriver.Sink): StreamingTranslation {
        var resampler: PcmResampler?=null
        val audio={bytes: ByteArray ->
            if(!route.isCanceled) {
                val instance=if(direction=="incoming")incoming else outgoing
                val rate=instance!!.outputSampleRate
                val pcm=if(rate==16000)bytes else {
                    if(resampler==null)resampler=PcmResampler(rate)
                    val samples=FloatArray(bytes.size/2){i->(((bytes[2*i].toInt() and 255) or (bytes[2*i+1].toInt() shl 8)).toShort())/32768f}
                    val samples16=resampler.accept(samples)
                    ByteArray(samples16.size*2).also{out->for(i in samples16.indices){out[2*i]=samples16[i].toByte();out[2*i+1]=(samples16[i].toInt() shr 8).toByte()}}
                }
                (if(direction=="incoming")incomingPlayer else outgoingPlayer)?.accept(pcm)
            };Unit
        }
        val target={text: String,final: Boolean->if(!route.isCanceled)sink.directionalText(direction,"target",text,final);Unit}
        val source={text: String,final: Boolean->if(!route.isCanceled)sink.directionalText(direction,"source",text,final);Unit}
        return when(request.profileId) {
            "local-hybrid"->HybridTranslation(context,request,target,audio,source)
            "fal-grok"->GrokSession(request.language,request.targetLanguage,request.voiceId,target,audio,source)
            else->KotobaSession(request.language,request.targetLanguage,target,audio)
        }
    }
    private fun outputSink(sink: AudioDriver.Sink,direction: String)=object: AudioDriver.Sink by sink {
        override fun playback(index: Long,state: String,frames: Long,rate: Int,nonSilent: Boolean){sink.directionalPlayback(direction,index,state,frames,rate,nonSilent)}
    }
    override fun start(sink: AudioDriver.Sink) {
        worker=Thread({
            var complete=false;var restored=true
            try {
                val uid=PlatformCallCapture.uidFor(context,request.targetPackage)
                PlatformCallCapture.requireActiveTarget(context,uid)
                val reverse=request.copy(language=request.targetLanguage,targetLanguage=request.language,
                    profileId=request.outgoingProfileId,voiceId=request.outgoingVoiceId,speed=request.outgoingSpeed)
                val first=model(request,"incoming",sink);incoming=first
                val second=model(reverse,"outgoing",sink);outgoing=second
                val key=if(request.profileId in AudioProfiles.fal || reverse.profileId in AudioProfiles.fal)
                    EngineConfig.control(JSONObject().put("op","falCredential")).getString("key") else ""
                if(route.isCanceled)return@Thread
                sink.progress("Connecting incoming translation…");first.connect(key)
                if(route.isCanceled)return@Thread
                sink.progress("Connecting outgoing translation…");second.connect(key)
                if(route.isCanceled)return@Thread
                // ready() takes the runtime lock; report it only after the route lock is released (issue #26 #2).
                if(!route.start({
                        val call=PlatformCallCapture(context,uid);capture=call
                        call.start({direction,pcm->
                            if(!route.isCanceled && !finishing.get())(if(direction==CallDirection.INCOMING)first else second).send(pcm)
                        },{code->if(!route.isCanceled)sink.failed(code)})
                        incomingPlayer=StreamingPlayback(outputSink(sink,"incoming"),16000,call.incomingOutput)
                        outgoingPlayer=StreamingPlayback(outputSink(sink,"outgoing"),16000,call.outgoingOutput)
                    },{capture?.stopInput()}))return@Thread
                sink.ready()
                while(!route.isCanceled && !finishing.get()) {
                    incomingPlayer?.checkHealthy();outgoingPlayer?.checkHealthy();Thread.sleep(100)
                }
                // The capture threads feed the models; they must have exited before the
                // recognizers flush their tail (issue #26 #3).
                val drained=capture?.stopInputAndJoin() ?: true
                if(!route.isCanceled) {
                    if(!drained)throw AudioFailure("CAPTURE_STUCK","Call capture thread could not stop")
                    first.finish();second.finish()
                    incomingPlayer?.finish();outgoingPlayer?.finish();complete=true
                }
            }catch(e: Exception){if(!route.isCanceled)sink.failed((e as? AudioFailure)?.code ?: "CALL_UNAVAILABLE")}
            finally {
                Thread.interrupted()
                // Order matters (issue #26 #3): 1. no capture callback is inside a model,
                // 2. release the models, 3. the players, 4. the route. This runs on the driver
                // worker, never the main/Binder thread. Models also guard their native state,
                // so a capture thread that outlived the join cannot reach freed memory.
                runCatching{capture?.stopInputAndJoin()}
                for(model in listOf(incoming,outgoing))runCatching{model?.close()}.onFailure{restored=false}
                incoming=null;outgoing=null
                for(player in listOf(incomingPlayer,outgoingPlayer))runCatching{player?.close()}.onFailure{restored=false}
                incomingPlayer=null;outgoingPlayer=null
                runCatching{capture?.close()}.onFailure{restored=false};capture=null
                sink.released(restored)
            }
            if(complete && !route.isCanceled && restored)sink.completed()
        },"audio-call-translation").also{it.start()}
    }
    override fun finish(){finishing.set(true);capture?.stopInput()}
    override fun drainTimeoutMs()=180000L
    /** Called under the runtime lock: must not block (never waits for the route lock or threads). */
    override fun close(){
        route.cancel{capture?.stopInput()}
        incoming?.cancel();outgoing?.cancel();worker?.interrupt()
    }
}
