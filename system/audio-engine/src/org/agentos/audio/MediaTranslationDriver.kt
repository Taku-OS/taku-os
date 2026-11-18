// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Device-only capture → fal STS → caption/optional speech. Preparation does not alter the route. */
class MediaTranslationDriver(private val context: Context,private val request: AudioRequest) : AudioDriver {
    private val route=RouteGate()
    private val finishing=AtomicBoolean()
    @Volatile private var capture: PlatformMediaCapture?=null
    @Volatile private var network: StreamingTranslation?=null
    @Volatile private var playback: StreamingPlayback?=null
    private var worker: Thread?=null
    override fun start(sink: AudioDriver.Sink) {
        worker=Thread({
            var complete=false;var restored=true
            try {
                val uid=context.packageManager.getApplicationInfo(request.targetPackage,0).uid
                if(uid==context.applicationInfo.uid)throw AudioFailure("INVALID_TARGET","Cannot translate this app's own audio track")
                val onText={text: String,final: Boolean->if(!route.isCanceled)sink.translation(text,final)}
                val onAudio={pcm: ByteArray->if(!route.isCanceled)playback?.accept(pcm);Unit}
                val onSource={text: String,final: Boolean->if(!route.isCanceled)sink.text(text,final)}
                val cloud=when(request.profileId) {
                    "local-hybrid"->HybridTranslation(context,request,onText,onAudio,onSource)
                    "fal-whisper"->WhisperCaptionSession(request,onText,onSource)
                    "fal-grok"->GrokSession(request.language,request.targetLanguage,request.voiceId,onText,onAudio,onSource)
                    else->KotobaSession(request.language,request.targetLanguage,onText,onAudio)
                }
                network=cloud
                if(route.isCanceled)return@Thread
                cloud.connect(if(request.profileId in AudioProfiles.fal)EngineConfig.control(JSONObject().put("op","falCredential")).getString("key")else "")
                if(route.isCanceled)return@Thread
                sink.progress("Translation connected. Play a video in the selected app")
                val sourceDeadline=android.os.SystemClock.elapsedRealtime()+30000
                while(!route.isCanceled && !PlatformMediaCapture.isPlaying(context,uid)) {
                    if(android.os.SystemClock.elapsedRealtime()>=sourceDeadline)throw AudioFailure("SOURCE_UNAVAILABLE","The selected app has not started audio playback")
                    Thread.sleep(200)
                }
                if(route.isCanceled)return@Thread

                val media=PlatformMediaCapture(context,uid,request.originalAudio=="REPLACE");capture=media
                // ready() takes the runtime lock; report it only after the route lock is released (issue #26 #2).
                if(!route.start({
                        if(request.output=="SPEECH")playback=StreamingPlayback(sink,cloud.outputSampleRate)
                        media.start()
                    },{media.stopInput()}))return@Thread
                sink.ready()
                var checkedAt=android.os.SystemClock.elapsedRealtime()
                var inactiveSince=0L
                while(!route.isCanceled && !finishing.get()) {
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(now-checkedAt>=500) {
                        checkedAt=now
                        if(PlatformMediaCapture.isPlaying(context,uid))inactiveSince=0
                        else if(inactiveSince==0L)inactiveSince=now
                        else if(now-inactiveSince>=1500){sink.inputEnded();break}
                    }
                    val frame=try{media.read()}catch(e: Exception){if(finishing.get() || route.isCanceled)break else throw e}
                    if(!route.isCanceled)cloud.send(frame)
                    playback?.checkHealthy()
                }
                media.stopInput()
                if(!route.isCanceled) {cloud.finish();playback?.finish();complete=true}
            }catch(e: Exception){if(!route.isCanceled)sink.failed((e as? AudioFailure)?.code ?: "PROVIDER_UNAVAILABLE")}
            finally {
                Thread.interrupted() // Cleanup must run even when close interrupted this worker.
                runCatching{network?.close()}.onFailure{restored=false};network=null
                runCatching{playback?.close()}.onFailure{restored=false};playback=null
                runCatching{capture?.close()}.onFailure{restored=false};capture=null
                sink.released(restored)
            }
            if(complete && !route.isCanceled && restored)sink.completed()
        },"audio-media-translation").also{it.start()}
    }
    override fun finish(){finishing.set(true);capture?.stopInput()}
    override fun drainTimeoutMs()=when(request.profileId){"fal-whisper"->70000L;"local-hybrid"->90000L;"fal-grok"->60000L;else->50000L}
    /** Called under the runtime lock: must not block (never waits for the route lock). */
    override fun close(){route.cancel{capture?.stopInput()};network?.cancel();worker?.interrupt()}
}
