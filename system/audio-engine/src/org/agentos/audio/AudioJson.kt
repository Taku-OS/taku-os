// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONObject

fun AudioEvent.json(): JSONObject = JSONObject().put("protocol","taku/0.1").put("type","event")
    .put("event","audio.session").put("timestamp",java.time.Instant.now().toString())
    .put("data",JSONObject().put("sessionId",sessionId).put("generation",sessionId).put("sequence",sequence)
        .put("monotonicMs",monotonicMs).put("phase",phase.name).put("kind",type)
        .put("text",text).put("segmentId",segment).put("revision",sequence).put("final",final)
        .put("textKind",textKind).put("direction",direction).put("reason",reason).put("completion",completion).put("restored",restored).put("chunkIndex",chunkIndex).put("playbackState",playbackState)
        .put("frames",frames).put("sampleRate",sampleRate).put("nonSilent",nonSilent)
        .apply{if(type=="Level")put("level",level.toDouble())}
        .putDictation(dictation,profileId,language,drainDeadlineMonotonicMs))

internal fun JSONObject.putDictation(metadata: DictationSessionMetadata?,profileId: String,language: String,deadline: Long?): JSONObject {
    if(metadata==null)return this
    return put("settingsScope",metadata.settingsScope ?: JSONObject.NULL).put("settingsRevision",metadata.settingsRevision ?: JSONObject.NULL)
        .put("requestedProfileId",metadata.requestedProfileId).put("profileId",profileId).put("language",language).put("processing",metadata.processing)
        .put("fallbackUsed",metadata.requestedProfileId!=profileId).put("fallbackReason",metadata.fallbackReason ?: JSONObject.NULL)
        .put("maxCaptureMs",metadata.maxCaptureMs ?: JSONObject.NULL).put("drainTimeoutMs",metadata.drainTimeoutMs)
        .put("drainDeadlineMonotonicMs",deadline ?: JSONObject.NULL)
}
