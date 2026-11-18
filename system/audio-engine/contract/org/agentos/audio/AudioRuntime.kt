// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AudioRuntime(private val clock: ()->Long = {System.nanoTime()/1_000_000},
    private val schedule: (Long,()->Unit)->(()->Unit) = { delay, action ->
        val future=deadlines.schedule({action()},delay,TimeUnit.MILLISECONDS)
        val cancel: ()->Unit = { future.cancel(false); Unit }
        cancel
    }, private val factory: (AudioRequest) -> AudioDriver) {
    companion object {
        private val deadlines=ScheduledThreadPoolExecutor(1) { task -> Thread(task,"audio-deadlines").apply{isDaemon=true} }.apply{removeOnCancelPolicy=true}
    }
    private data class Entry(val id: String, val owner: String, val request: AudioRequest, val requestKey: String,
        val listener: (AudioEvent) -> Unit, val driver: AudioDriver?, val dictation: DictationSessionMetadata?, var phase: AudioPhase = AudioPhase.Preparing,
        var sequence: Long = 0, var segment: Long = 0, var latest: AudioEvent? = null,
        var released: Boolean = false, var restored: Boolean = true, var endReason: String = "", var completion: String = "unknown",
        var finishRequested: Boolean = false, var interrupted: Boolean = false, var nextChunk: Long = 0,
        val recentChunks: LinkedHashMap<Long,String> = linkedMapOf(),
        var drainDeadline: Long? = null, var cancelDeadline: (()->Unit)? = null)
    private val entries = linkedMapOf<String, Entry>()
    private var nextId = 0L
    private var active: String? = null
    private var previousRestoreConfirmed: Boolean? = null

    @Synchronized fun open(owner: String, request: AudioRequest, listener: (AudioEvent) -> Unit, requestKey: String = "",
        dictation: DictationSessionMetadata? = null): String {
        if(requestKey.isNotEmpty()) {
            val existing=entries.values.firstOrNull { it.owner==owner && it.requestKey==requestKey }
            if(existing!=null) {
                if(existing.dictation?.settingsScope!=dictation?.settingsScope ||
                    (dictation?.settingsScope==null && existing.request!=request))throw AudioFailure("REQUEST_CONFLICT","Repeated request has different content")
                return existing.id
            }
        }
        if(request.purpose !in listOf("DICTATION","SPEAK","MEDIA_TRANSLATE","CALL_TRANSLATE","LEGACY_REALTIME")) throw AudioFailure("UNSUPPORTED", "This purpose is not integrated yet")
        if(request.purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE")) {
            if(request.language !in listOf("zh-CN","en-US","ja-JP","ko-KR") || request.targetLanguage !in listOf("zh-CN","en-US","ja-JP","ko-KR") || request.language==request.targetLanguage)throw AudioFailure("UNSUPPORTED_LANGUAGE","Select different translation languages")
            if(request.profileId !in AudioProfiles.media)throw AudioFailure("UNSUPPORTED_PROFILE","Select an integrated media model")
            if(request.profileId=="fal-whisper" && (request.purpose!="MEDIA_TRANSLATE" || request.output!="CAPTIONS" || request.voiceId.isNotEmpty()))throw AudioFailure("UNSUPPORTED_PROFILE","Whisper supports segmented captions only, not translated speech or calls")
            if(request.profileId=="local-hybrid" && (request.language !in listOf("en-US","zh-CN") || request.targetLanguage !in listOf("en-US","zh-CN")))throw AudioFailure("UNSUPPORTED_LANGUAGE","The local combination currently supports Chinese and English")
            if(!request.targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) || request.output !in listOf("CAPTIONS","SPEECH") || request.originalAudio !in listOf("KEEP","REPLACE") || request.output=="CAPTIONS" && request.originalAudio=="REPLACE")throw AudioFailure("INVALID_PARAMS","Invalid media target or output mode")
            if(request.profileId=="local-hybrid") {
                if(!request.speed.isFinite() || request.speed !in .5f..2f)throw AudioFailure("UNSUPPORTED_VOICE","Speed must be between 0.5 and 2")
                VoiceCatalog.select(request.targetLanguage,request.voiceId)
            } else if(request.speed!=1f || request.profileId=="fal-kotoba" && request.voiceId.isNotEmpty() || request.profileId=="fal-grok" && request.voiceId.isNotEmpty() && request.voiceId !in AudioProfiles.grokVoices)throw AudioFailure("UNSUPPORTED_VOICE","The selected model does not support this voice or speed")
        } else if(request.purpose != "LEGACY_REALTIME") {
            if(request.language !in listOf("zh-CN","en-US")) throw AudioFailure("UNSUPPORTED_LANGUAGE", "Select Chinese or English")
            if(request.purpose=="DICTATION") {
                if(request.profileId !in AudioProfiles.dictation)throw AudioFailure("UNSUPPORTED_PROFILE","The selected model does not support this purpose")
                if(request.profileId=="fal-whisper" && (request.voiceId.isNotEmpty() || request.speed!=1f))throw AudioFailure("UNSUPPORTED_VOICE","Whisper transcription does not support voice or speed settings")
            } else if(request.profileId!="kokoro")throw AudioFailure("UNSUPPORTED_PROFILE","The selected model does not support this purpose")
            if(!request.speed.isFinite() || request.speed !in .5f..2f) throw AudioFailure("INVALID_PARAMS","Speed must be between 0.5 and 2")
        }
        if(request.purpose=="CALL_TRANSLATE") {
            if(request.output!="SPEECH" || request.originalAudio!="REPLACE")throw AudioFailure("INVALID_PARAMS","Call translation requires bidirectional speech replacement")
            if(request.outgoingProfileId !in AudioProfiles.media || request.outgoingProfileId=="fal-whisper")throw AudioFailure("UNSUPPORTED_PROFILE","The outgoing model is not integrated yet")
            if(request.outgoingProfileId=="local-hybrid") {
                if(request.language !in listOf("en-US","zh-CN") || request.targetLanguage !in listOf("en-US","zh-CN"))throw AudioFailure("UNSUPPORTED_LANGUAGE","The local combination currently supports Chinese and English")
                if(!request.outgoingSpeed.isFinite() || request.outgoingSpeed !in .5f..2f)throw AudioFailure("UNSUPPORTED_VOICE","Speed must be between 0.5 and 2")
                VoiceCatalog.select(request.language,request.outgoingVoiceId)
            } else if(request.outgoingSpeed!=1f || request.outgoingProfileId=="fal-kotoba" && request.outgoingVoiceId.isNotEmpty() || request.outgoingProfileId=="fal-grok" && request.outgoingVoiceId.isNotEmpty() && request.outgoingVoiceId !in AudioProfiles.grokVoices)throw AudioFailure("UNSUPPORTED_VOICE","The outgoing voice is not supported")
        }
        if(request.interruptible && request.purpose!="SPEAK")throw AudioFailure("INVALID_PARAMS","Voice interruption is available only for reading")
        if(active!=null) throw AudioFailure("RESOURCE_BUSY", "Audio resources are in use")
        val id="audio-${++nextId}"
        val driver=if(request.purpose=="LEGACY_REALTIME") null else factory(request)
        val metadata=if(request.purpose=="DICTATION")dictation ?: AudioProfiles.dictation.getValue(request.profileId).let { backend ->
            DictationSessionMetadata(null,null,request.profileId,if(backend.processing=="LOCAL")"LOCAL_ONLY" else backend.processing,null,backend.maxCaptureMs,backend.drainTimeoutMs)
        } else null
        val entry=Entry(id,owner,request,requestKey,listener,driver,metadata)
        entries[id]=entry;active=id
        while(entries.size>32) entries.remove(entries.keys.first())
        emit(entry,"Preparing")
        if(driver==null) {
            phase(entry,AudioPhase.Ready);phase(entry,AudioPhase.Running)
        } else try {
            driver.start(object : AudioDriver.Sink {
                override fun ready() = synchronized(this@AudioRuntime) {
                    if(entry.phase==AudioPhase.Preparing) {
                        phase(entry,AudioPhase.Ready);phase(entry,AudioPhase.Running)
                        if(entry.finishRequested) phase(entry,AudioPhase.Draining)
                    }
                }
                override fun progress(message: String) = synchronized(this@AudioRuntime) {
                    if(entry.phase==AudioPhase.Preparing)emit(entry,"Progress",message.take(200))
                }
                override fun inputEnded() = synchronized(this@AudioRuntime) {
                    if(entry.phase==AudioPhase.Running){emit(entry,"SourceEnded");finish(entry.owner,entry.id)}
                }
                override fun text(text: String, final: Boolean) = synchronized(this@AudioRuntime) {
                    if(entry.phase in listOf(AudioPhase.Running,AudioPhase.Draining) && (text.isNotBlank() || final && entry.request.purpose=="DICTATION")) {
                        if(entry.request.purpose=="MEDIA_TRANSLATE")publish(entry,AudioEvent(entry.id,++entry.sequence,entry.phase,"Text",text,entry.segment,final,direction="incoming"))
                        else emit(entry,"Text",text,final)
                        if(final) entry.segment++
                    }
                }
                override fun level(level: Float, frames: Int) = synchronized(this@AudioRuntime) {
                    if(entry.phase==AudioPhase.Running && entry.request.purpose=="DICTATION" && level.isFinite() && frames>0) {
                        publish(entry,AudioEvent(entry.id,++entry.sequence,entry.phase,"Level",segment=entry.segment,
                            frames=frames.toLong(),sampleRate=16000,level=level.coerceIn(0f,1f)))
                    }
                }
                override fun translation(text: String, final: Boolean) = synchronized(this@AudioRuntime) {
                    if(entry.phase in listOf(AudioPhase.Running,AudioPhase.Draining) && text.isNotBlank()) {
                        publish(entry,AudioEvent(entry.id,++entry.sequence,entry.phase,"Text",text,entry.segment,final,textKind="target",direction="incoming"))
                        if(final)entry.segment++
                    }
                }
                override fun directionalText(direction: String,kind: String,text: String,final: Boolean) = synchronized(this@AudioRuntime) {
                    if(entry.phase in listOf(AudioPhase.Running,AudioPhase.Draining) && text.isNotBlank()) {
                        publish(entry,AudioEvent(entry.id,++entry.sequence,entry.phase,"Text",text,entry.segment,final,textKind=kind,direction=direction))
                        if(final)entry.segment++
                    }
                }
                override fun playback(index: Long, state: String, frames: Long, rate: Int, nonSilent: Boolean) = synchronized(this@AudioRuntime) {
                    if(entry.phase in listOf(AudioPhase.Running,AudioPhase.Draining)) {
                        val event=AudioEvent(entry.id,++entry.sequence,entry.phase,"Playback",chunkIndex=index,
                            playbackState=state,frames=frames,sampleRate=rate,nonSilent=nonSilent)
                        publish(entry,event)
                    }
                }
                override fun directionalPlayback(direction: String,index: Long,state: String,frames: Long,rate: Int,nonSilent: Boolean) = synchronized(this@AudioRuntime) {
                    if(entry.phase in listOf(AudioPhase.Running,AudioPhase.Draining))publish(entry,AudioEvent(entry.id,++entry.sequence,entry.phase,"Playback",chunkIndex=index,playbackState=state,frames=frames,sampleRate=rate,nonSilent=nonSilent,direction=direction))
                }
                override fun interrupted() = synchronized(this@AudioRuntime) {
                    if(entry.request.interruptible && !entry.interrupted && entry.phase in listOf(AudioPhase.Running,AudioPhase.Draining)) {
                        entry.interrupted=true;entry.finishRequested=true
                        emit(entry,"Interrupted")
                        phase(entry,AudioPhase.Draining)
                        entry.driver!!.finish()
                    }
                }
                override fun completed() = synchronized(this@AudioRuntime) {
                    if(entry.phase==AudioPhase.Draining) {
                        if(entry.drainDeadline?.let{clock()>=it}==true)terminate(entry,"PROCESSING_TIMEOUT","incomplete")
                        else terminate(entry,if(entry.interrupted)"interrupted"else"completed",if(entry.interrupted)"incomplete"else"complete")
                    }
                }
                override fun failed(code: String) = synchronized(this@AudioRuntime) { terminate(entry,code,"incomplete") }
                override fun released(restored: Boolean) = synchronized(this@AudioRuntime) {
                    if(entry.released)return@synchronized
                    entry.released=true;entry.restored=restored
                    if(!restored){entry.endReason="RESTORE_FAILED";entry.completion="incomplete"}
                    if(entry.endReason.isNotEmpty()) closed(entry)
                }
            })
        } catch(failure: Exception) { terminate(entry,(failure as? AudioFailure)?.code ?: "MODEL_UNAVAILABLE","incomplete") }
        return id
    }
    @Synchronized fun append(owner: String, id: String, index: Long, text: String) {
        val e=owned(owner,id)
        if(e.request.purpose!="SPEAK") throw AudioFailure("UNEXPECTED_INPUT","Only reading sessions accept text")
        if(index<e.nextChunk) {
            if(e.recentChunks[index]==text) return
            throw AudioFailure("CHUNK_CONFLICT","Text segment expired or repeated content differs")
        }
        if(e.finishRequested || e.phase !in listOf(AudioPhase.Preparing,AudioPhase.Ready,AudioPhase.Running)) throw AudioFailure("INPUT_CLOSED","The session has stopped accepting input")
        if(index!=e.nextChunk || text.isBlank() || text.length>500) throw AudioFailure("INVALID_PARAMS","Segments must have consecutive indexes and contain 1–500 characters")
        e.driver!!.submit(index,text)
        e.recentChunks[index]=text;e.nextChunk++
        while(e.recentChunks.size>32)e.recentChunks.remove(e.recentChunks.keys.first())
    }
    @Synchronized fun finish(owner: String, id: String) {
        val e=owned(owner,id)
        if(e.finishRequested)return
        when(e.phase) {
            AudioPhase.Preparing -> {
                if(e.request.purpose=="SPEAK" && e.nextChunk>0) {e.finishRequested=true;startDeadline(e);e.driver!!.finish()}
                else terminate(e,"canceled","incomplete")
            }
            AudioPhase.Running,AudioPhase.Ready -> {
                e.finishRequested=true;phase(e,AudioPhase.Draining)
                if(e.driver==null) terminate(e,"completed","complete") else e.driver.finish()
            }
            else -> Unit
        }
    }
    @Synchronized fun timeout(owner: String,id: String) {terminate(owned(owner,id),"PROCESSING_TIMEOUT","incomplete")}
    @Synchronized fun close(owner: String,id: String) {terminate(owned(owner,id),"canceled","incomplete")}
    @Synchronized fun resources(owner: String): AudioResourceState {
        val e=active?.let{entries[it]} ?: return AudioResourceState(false,previousRestoreConfirmed=previousRestoreConfirmed)
        return AudioResourceState(true,e.request.purpose,e.request.profileId,e.phase,e.owner==owner,previousRestoreConfirmed)
    }
    @Synchronized fun status(owner: String,id: String): AudioEvent = owned(owner,id).latest!!
    @Synchronized fun drainTimeoutMs(owner: String,id: String): Long = drainTimeout(owned(owner,id))
    @Synchronized fun drainDeadlineMonotonicMs(owner: String,id: String): Long? = owned(owner,id).drainDeadline
    @Synchronized fun dictationMetadata(owner: String,id: String): DictationSessionMetadata? = owned(owner,id).dictation
    @Synchronized fun findRequest(owner: String,requestKey: String): String? =
        if(requestKey.isEmpty())null else entries.values.firstOrNull{it.owner==owner && it.requestKey==requestKey}?.id
    @Synchronized fun request(owner: String,id: String): AudioRequest = owned(owner,id).request
    @Synchronized fun purpose(owner: String,id: String): String = owned(owner,id).request.purpose
    @Synchronized fun disconnect(owner: String) {
        entries.values.filter{it.owner==owner}.forEach {
            if(it.driver==null){it.released=true;if(it.phase==AudioPhase.Stopping)closed(it)else terminate(it,"client_disconnected","incomplete")}
            else terminate(it,"client_disconnected","incomplete")
        }
    }
    @Synchronized fun revokeOwner(owner: String,reason: String) {entries.values.filter{it.owner==owner}.forEach{terminate(it,reason,"incomplete")}}
    @Synchronized fun revoke(reason: String) {entries.values.toList().forEach{terminate(it,reason,"incomplete")}}
    @Synchronized fun revokeProfiles(profiles: Set<String>,reason: String) {entries.values.filter{it.request.profileId in profiles || it.request.purpose=="CALL_TRANSLATE" && it.request.outgoingProfileId in profiles}.forEach{terminate(it,reason,"incomplete")}}
    private fun owned(owner: String,id: String): Entry {
        val e=entries[id] ?: throw AudioFailure("SESSION_NOT_FOUND","Audio session does not exist")
        if(e.owner!=owner)throw AudioFailure("PERMISSION_DENIED","Cannot access another caller's session")
        return e
    }
    private fun drainTimeout(e: Entry): Long = e.dictation?.drainTimeoutMs ?: e.driver?.drainTimeoutMs() ?: 5000
    private fun startDeadline(e: Entry) {
        if(e.drainDeadline!=null)return
        val timeout=drainTimeout(e)
        e.drainDeadline=clock()+timeout
        e.cancelDeadline=schedule(timeout) { synchronized(this) {
            if(e.phase in listOf(AudioPhase.Preparing,AudioPhase.Draining) && e.endReason.isEmpty())terminate(e,"PROCESSING_TIMEOUT","incomplete")
        } }
    }
    private fun phase(e: Entry,phase: AudioPhase) {
        e.phase=phase
        if(phase==AudioPhase.Draining)startDeadline(e)
        emit(e,phase.name)
    }
    private fun emit(e: Entry,type: String,text: String="",final: Boolean=false,reason: String="",completion: String="unknown",restored: Boolean=false) {
        publish(e,AudioEvent(e.id,++e.sequence,e.phase,type,text,e.segment,final,reason,completion,restored))
    }
    private fun publish(e: Entry,event: AudioEvent) {
        val timed=event.copy(monotonicMs=clock(),dictation=e.dictation,profileId=e.request.profileId,language=e.request.language,drainDeadlineMonotonicMs=e.drainDeadline)
        e.latest=timed;e.listener(timed)
    }
    private fun terminate(e: Entry,reason: String,completion: String) {
        if(e.phase==AudioPhase.Closed || e.phase==AudioPhase.Stopping || e.endReason.isNotEmpty())return
        e.cancelDeadline?.invoke();e.cancelDeadline=null
        e.endReason=reason;e.completion=completion
        if(reason!="completed")phase(e,AudioPhase.Stopping)
        if(e.released)closed(e)
        else try{e.driver?.close()}catch(_: Exception){e.endReason="RESTORE_FAILED";emit(e,"Stopping",reason="RESTORE_FAILED")}
    }
    private fun closed(e: Entry) {
        if(e.phase==AudioPhase.Closed)return
        e.cancelDeadline?.invoke();e.cancelDeadline=null
        e.phase=AudioPhase.Closed;if(active==e.id){active=null;previousRestoreConfirmed=e.restored}
        emit(e,"Closed",reason=e.endReason,completion=e.completion,restored=e.restored)
    }
}
