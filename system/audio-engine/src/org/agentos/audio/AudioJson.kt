// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONObject

fun AudioEvent.json(): JSONObject = JSONObject().put("protocol","taku/0.1").put("type","event")
    .put("event","audio.session").put("timestamp",java.time.Instant.now().toString())
    .put("data",JSONObject().put("sessionId",sessionId).put("generation",sessionId).put("sequence",sequence)
        .put("monotonicMs",monotonicMs).put("phase",phase.name).put("kind",type).put("type",type)
        .put("text",text).put("segmentId",segment).put("revision",sequence).put("final",final)
        .put("textKind",textKind).put("direction",direction).put("reason",reason).put("completion",completion).put("restored",restored).put("chunkIndex",chunkIndex).put("playbackState",playbackState)
        .put("frames",frames).put("sampleRate",sampleRate).put("nonSilent",nonSilent)
        .apply{if(type=="Level")put("level",level.toDouble())}
        .apply{live?.let{put("live",it.json())}}
        .putDictation(dictation,profileId,language,drainDeadlineMonotonicMs))

internal fun JSONObject.putDictation(metadata: DictationSessionMetadata?,profileId: String,language: String,deadline: Long?): JSONObject {
    if(metadata==null)return this
    return put("settingsScope",metadata.settingsScope ?: JSONObject.NULL).put("settingsRevision",metadata.settingsRevision ?: JSONObject.NULL)
        .put("requestedProfileId",metadata.requestedProfileId).put("profileId",profileId).put("language",language).put("processing",metadata.processing)
        .put("fallbackUsed",metadata.requestedProfileId!=profileId).put("fallbackReason",metadata.fallbackReason ?: JSONObject.NULL)
        .put("maxCaptureMs",metadata.maxCaptureMs ?: JSONObject.NULL).put("drainTimeoutMs",metadata.drainTimeoutMs)
        .put("drainDeadlineMonotonicMs",deadline ?: JSONObject.NULL)
}

private fun LiveAudioEvent.json(): JSONObject = JSONObject().apply {
    when(val event=this@json) {
        is LiveAudioEvent.Input -> put("kind","Input").put("itemId",event.itemId).put("text",event.text).put("final",event.final)
        is LiveAudioEvent.Output -> put("kind","Output").put("responseId",event.responseId).put("text",event.text).put("final",event.final)
        is LiveAudioEvent.Playback -> put("kind","Playback").put("responseId",event.responseId).put("state",event.state)
        is LiveAudioEvent.ResponseDone -> put("kind","ResponseDone").put("responseId",event.responseId).put("itemId",event.itemId)
        is LiveAudioEvent.Tool -> put("kind","Tool").put("callId",event.callId).put("itemId",event.itemId).put("action",event.action).put("text",event.text)
        is LiveAudioEvent.Mic -> put("kind","Mic").put("muted",event.muted)
        is LiveAudioEvent.Speech -> put("kind","Speech").put("active",event.active)
        is LiveAudioEvent.TaskResult -> put("kind","TaskResult").put("offerId",event.offerId).put("sent",event.sent)
    }
}
