// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

/** Device-independent session semantics. PCM and credentials stay behind the driver. */
enum class AudioPhase { Preparing, Ready, Running, Draining, Stopping, Closed }
data class AudioRequest @JvmOverloads constructor(val purpose: String = "DICTATION", val language: String = "zh-CN",
    val profileId: String = if(purpose=="LEGACY_REALTIME") "native-realtime" else if(purpose=="SPEAK") "kokoro" else if(purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE")) "fal-kotoba" else "zipformer", val voiceId: String = "", val speed: Float = 1f,
    val targetLanguage: String = "zh-CN", val targetPackage: String = "", val output: String = "CAPTIONS", val originalAudio: String = "KEEP",
    val outgoingProfileId: String = profileId, val outgoingVoiceId: String = "", val outgoingSpeed: Float = 1f,
    val interruptible: Boolean = false)
data class AudioEvent(val sessionId: String, val sequence: Long, val phase: AudioPhase, val type: String,
    val text: String = "", val segment: Long = 0, val final: Boolean = false,
    val reason: String = "", val completion: String = "unknown", val restored: Boolean = false,
    val chunkIndex: Long = -1, val playbackState: String = "", val frames: Long = 0,
    val sampleRate: Int = 0, val nonSilent: Boolean = false, val monotonicMs: Long = 0,
    val textKind: String = "source", val direction: String = "input",
    val dictation: DictationSessionMetadata? = null, val profileId: String = "", val language: String = "",
    val drainDeadlineMonotonicMs: Long? = null, val level: Float = 0f)
data class DictationBackend(
    val profileId: String,
    val languages: List<String>,
    val processing: String,
    val requiresLocalModels: Boolean,
    val requiresFalKey: Boolean,
    val maxCaptureMs: Long?,
    val drainTimeoutMs: Long,
)
data class DictationSessionMetadata(
    val settingsScope: String?,
    val settingsRevision: Long?,
    val requestedProfileId: String,
    val processing: String,
    val fallbackReason: String?,
    val maxCaptureMs: Long?,
    val drainTimeoutMs: Long,
)
/** Shared resource metadata only. No session ID, caller identity, transcript or PCM. */
data class AudioResourceState(val busy: Boolean,val purpose: String="",val profileId: String="",val phase: AudioPhase?=null,
    val ownedByCaller: Boolean=false,val previousRestoreConfirmed: Boolean?=null)
class AudioFailure(val code: String, message: String) : Exception(message)
interface AudioDriver {
    interface Sink {
        fun ready()
        fun progress(message: String) {}
        fun inputEnded() {}
        fun text(text: String, final: Boolean)
        fun level(level: Float, frames: Int) {}
        fun translation(text: String, final: Boolean) {}
        fun directionalText(direction: String,kind: String,text: String,final: Boolean) {}
        fun playback(index: Long, state: String, frames: Long, rate: Int, nonSilent: Boolean) {}
        fun directionalPlayback(direction: String,index: Long,state: String,frames: Long,rate: Int,nonSilent: Boolean){playback(index,state,frames,rate,nonSilent)}
        fun interrupted() {}
        fun completed()
        fun failed(code: String)
        fun released(restored: Boolean = true)
    }
    fun start(sink: Sink)
    fun submit(index: Long, text: String) { throw AudioFailure("UNEXPECTED_INPUT", "This session does not accept text") }
    fun finish()
    fun drainTimeoutMs(): Long = 5000
    /** Request cancellation; Sink.released confirms native resources are no longer in use. */
    fun close()
}

/** Implemented cloud profiles and the voices exposed by this build. */
object AudioProfiles {
    val dictation: Map<String,DictationBackend> = linkedMapOf(
        "zipformer" to DictationBackend("zipformer",listOf("zh-CN","en-US"),"LOCAL",true,false,null,5000),
        "fal-whisper" to DictationBackend("fal-whisper",listOf("zh-CN","en-US"),"CLOUD_AUDIO",false,true,60000,100000),
    )
    val fal=linkedSetOf("fal-kotoba","fal-grok","fal-whisper")
    val media=linkedSetOf("fal-kotoba","fal-grok","local-hybrid","fal-whisper")
    val grokVoices=linkedMapOf("eve" to "Eve","ara" to "Ara","rex" to "Rex","sal" to "Sal","leo" to "Leo")
    /**
     * The profile a media/call session uses when the caller names none: the fal model chosen in
     * BYOK (Whisper is captions-only, so calls fall back to Kotoba). Validation and session
     * opening both use this, so a request is checked against the profile it will actually get.
     */
    fun defaultMedia(falModel: String?,call: Boolean): String {
        val model=falModel?.takeIf{it in listOf("kotoba","grok","whisper")} ?: "kotoba"
        return "fal-"+(if(call && model=="whisper")"kotoba" else model)
    }
}
