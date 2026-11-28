// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONObject

private fun accepts(method: String, params: JSONObject) = AudioParameters.validate(method, params)
private fun rejects(label: String, method: String, params: JSONObject) {
    try { AudioParameters.validate(method, params) } catch(error: AudioFailure) {
        if(error.code != "INVALID_PARAMS") throw AssertionError("$label: ${error.code}"); return
    }
    throw AssertionError("$label accepted")
}

fun main() {
    val id = "audio-7"
    for(source in listOf("none", "screen", "camera")) accepts("audio.live.video", JSONObject().put("sessionId", id).put("source", source))
    rejects("unknown video source", "audio.live.video", JSONObject().put("sessionId", id).put("source", "window"))
    rejects("missing video source", "audio.live.video", JSONObject().put("sessionId", id))
    rejects("extra video field", "audio.live.video", JSONObject().put("sessionId", id).put("source", "screen").put("fps", 30))
    rejects("bad session", "audio.live.video", JSONObject().put("sessionId", "x").put("source", "screen"))
    accepts("audio.live.keyframe", JSONObject().put("sessionId", id).put("reason", "speech_start"))
    rejects("keyframe reason format", "audio.live.keyframe", JSONObject().put("sessionId", id).put("reason", "Speech Start"))
    accepts("audio.live.context", JSONObject().put("sessionId", id).put("text", "Task 1 is searching flights"))
    rejects("blank context", "audio.live.context", JSONObject().put("sessionId", id).put("text", " "))
    rejects("long context", "audio.live.context", JSONObject().put("sessionId", id).put("text", "x".repeat(4001)))
    accepts("audio.session.open", JSONObject().put("purpose", "LIVE_TALK").put("profileId", "moss-live"))
    rejects("unknown live profile", "audio.session.open", JSONObject().put("purpose", "LIVE_TALK").put("profileId", "moss-live-2"))
    println("PASS AudioParametersLiveTest: video source, keyframe and silent-context validation; moss-live open")
}
