// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import org.json.JSONObject

/** Native/CLI entry validation matches the public JS contract. */
object AudioParameters {
    /** [falModel] is the BYOK-selected fal model, which decides the default media profile. */
    fun validate(method: String,params: JSONObject,falModel: String?=null) {
        val keys=when(method) {
            "audio.capabilities" -> setOf("purpose","language","settingsScope")
            "audio.legacy.acquire" -> emptySet()
            "audio.session.open" -> setOf("purpose","language","profileId","voiceId","speed","requestKey","targetLanguage","targetPackage","output","originalAudio","outgoingProfileId","outgoingVoiceId","outgoingSpeed","interruptible","settingsScope","muted","history")
            "audio.session.append" -> setOf("sessionId","index","text")
            "audio.session.finish","audio.session.close","audio.session.status" -> setOf("sessionId")
            "audio.live.mute" -> setOf("sessionId","muted")
            "audio.live.text" -> setOf("sessionId","text")
            "audio.live.result" -> setOf("sessionId","offerId","afterInput","text")
            "audio.live.tool" -> setOf("sessionId","callId","result")
            "audio.live.video" -> setOf("sessionId","source")
            "audio.live.keyframe" -> setOf("sessionId","reason")
            "audio.live.context" -> setOf("sessionId","text")
            else -> throw AudioFailure("METHOD_NOT_FOUND","Audio method is not implemented")
        }
        if(params.keys().asSequence().any{it !in keys})invalid("Audio parameters contain an unknown field")
        if(params.has("settingsScope")) {
            if(string(params,"settingsScope","")!="assistant-entry" || string(params,"purpose","DICTATION")!="DICTATION" || params.has("language") || params.has("profileId"))invalid("Recognition settings scope applies only to dictation without an explicit language or model")
        }
        if(method=="audio.capabilities") {
            if(params.has("purpose") && string(params,"purpose","") !in listOf("DICTATION","SPEAK","MEDIA_TRANSLATE","CALL_TRANSLATE","LIVE_TALK"))invalid("This purpose is not integrated yet")
            if(params.has("language") && string(params,"language","") !in listOf("zh-CN","en-US","ja-JP","ko-KR"))invalid("Select an available language")
        }
        if(method=="audio.session.open") {
            val purpose=string(params,"purpose","DICTATION")
            if(purpose !in listOf("DICTATION","SPEAK","MEDIA_TRANSLATE","CALL_TRANSLATE","LIVE_TALK"))invalid("This purpose is not integrated yet")
            if(params.has("muted") && (purpose!="LIVE_TALK" || params.opt("muted") !is Boolean))invalid("Initial mute applies only to Live Talk and must be boolean")
            if(params.has("history")) {
                if(purpose!="LIVE_TALK")invalid("Conversation history applies only to Live Talk")
                val history=params.optJSONArray("history") ?: invalid("History must be an array")
                if(history.length()>24)invalid("History may contain at most 24 turns")
                var length=0
                for(i in 0 until history.length()) {
                    val turn=history.optJSONObject(i) ?: invalid("History turns must be objects")
                    if(turn.keys().asSequence().any{it !in setOf("role","text")} || string(turn,"role","") !in listOf("user","model"))invalid("History turns require a user or model role")
                    val text=string(turn,"text","")
                    if(text.isBlank())invalid("History text must not be blank")
                    length+=text.length
                }
                if(length>12000)invalid("History may contain at most 12000 characters")
            }
            if(params.has("interruptible") && (params.opt("interruptible") !is Boolean || purpose!="SPEAK"))invalid("Voice interruption accepts a boolean only for the reading purpose")
            val language=string(params,"language","zh-CN")
            val call=purpose=="CALL_TRANSLATE"
            val media=purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE")
            val languages=if(media)listOf("zh-CN","en-US","ja-JP","ko-KR")else listOf("zh-CN","en-US")
            if(language !in languages)invalid("Select an available language")
            val profile=if(purpose=="LIVE_TALK")AudioProfiles.LIVE_GROK else if(media)AudioProfiles.defaultMedia(falModel,call) else if(purpose=="SPEAK")"kokoro" else "zipformer"
            val selected=string(params,"profileId",profile)
            val supportedProfiles = when {
                media -> AudioProfiles.media
                purpose == "DICTATION" -> AudioProfiles.dictation.keys
                purpose == "LIVE_TALK" -> AudioProfiles.live
                else -> setOf(profile)
            }
            if(selected !in supportedProfiles)invalid("The model does not support this purpose")
            if(selected=="fal-whisper" && (purpose !in listOf("DICTATION","MEDIA_TRANSLATE") || string(params,"output","CAPTIONS")!="CAPTIONS" || string(params,"voiceId","").isNotEmpty() || params.optDouble("speed",1.0)!=1.0))invalid("Whisper supports transcription or captions only, without voice or speed settings")
            if(purpose=="LIVE_TALK" && (string(params,"voiceId","").isNotEmpty() || params.optDouble("speed",1.0)!=1.0))invalid("Live Talk uses its configured voice")
            if(string(params,"voiceId","").length>128)invalid("Voice identifier is too long")
            if(params.has("speed")){val speed=params.opt("speed");if(speed !is Number || !speed.toDouble().isFinite() || speed.toDouble() !in .5..2.0)invalid("Speed must be between 0.5 and 2")}
            if(params.has("requestKey") && !string(params,"requestKey","").matches(Regex("[A-Za-z0-9._:-]{1,128}")))invalid("Invalid request identifier")
            if(media) {
                if(string(params,"targetLanguage","zh-CN") !in languages || string(params,"targetLanguage","zh-CN")==language)invalid("Select different translation languages")
                if(!string(params,"targetPackage","").matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))invalid("Select a target app")
                val output=string(params,"output","CAPTIONS");val original=string(params,"originalAudio","KEEP")
                if(output !in listOf("CAPTIONS","SPEECH") || original !in listOf("KEEP","REPLACE") || output=="CAPTIONS" && original=="REPLACE")invalid("Captions-only mode must preserve original audio")
                val voice=string(params,"voiceId","")
                if(selected=="local-hybrid") {
                    if(language !in listOf("en-US","zh-CN") || string(params,"targetLanguage","zh-CN") !in listOf("en-US","zh-CN"))invalid("The local combination currently supports Chinese and English")
                    VoiceCatalog.select(string(params,"targetLanguage","zh-CN"),voice)
                } else if(params.optDouble("speed",1.0)!=1.0 || selected=="fal-kotoba" && voice.isNotEmpty() || voice.isNotEmpty() && voice !in AudioProfiles.grokVoices)invalid("The selected model does not support this voice or speed")
                if(call) {
                    if(output!="SPEECH" || original!="REPLACE")invalid("Call translation requires bidirectional speech replacement")
                    val outgoing=params.optString("outgoingProfileId",selected)
                    val reverse=JSONObject().put("purpose","MEDIA_TRANSLATE").put("profileId",outgoing)
                        .put("language",string(params,"targetLanguage","zh-CN")).put("targetLanguage",language)
                        .put("targetPackage",string(params,"targetPackage","")).put("output","SPEECH").put("originalAudio","REPLACE")
                        .put("voiceId",string(params,"outgoingVoiceId","")).put("speed",params.opt("outgoingSpeed") ?: 1.0)
                    validate("audio.session.open",reverse)
                } else if(listOf("outgoingProfileId","outgoingVoiceId","outgoingSpeed").any{params.has(it)})invalid("Bidirectional parameters apply only to calls")
            } else if(listOf("targetLanguage","targetPackage","output","originalAudio","outgoingProfileId","outgoingVoiceId","outgoingSpeed").any{params.has(it)})invalid("Media parameters apply only to media translation")
        } else if(method.startsWith("audio.session.") || method.startsWith("audio.live.")) {
            if(!string(params,"sessionId","").matches(Regex("audio-[1-9][0-9]*")))invalid("Invalid session identifier")
            if(method=="audio.session.append") {
                val index=params.opt("index")
                if(index !is Number || !index.toDouble().isFinite() || index.toDouble()<0 || index.toDouble()>9007199254740991.0 || index.toDouble()!=index.toLong().toDouble())invalid("Segment index must be a nonnegative integer")
                val text=string(params,"text","");if(text.isBlank() || text.length>500)invalid("A segment must contain 1–500 characters")
            }
            when(method) {
                "audio.live.mute" -> if(params.opt("muted") !is Boolean)invalid("Muted must be a boolean")
                "audio.live.text" -> {
                    val text=string(params,"text","")
                    if(text.isBlank() || text.length>12000)invalid("Live text must contain 1–12000 characters")
                }
                "audio.live.tool" -> {
                    val callId=string(params,"callId","")
                    if(callId.isBlank() || callId.length>256)invalid("Invalid tool call identifier")
                    val result=string(params,"result","")
                    if(result.length>12000)invalid("Tool result is too long")
                    try{JSONObject(result)}catch(_: Exception){invalid("Tool result must be a JSON object encoded as a string")}
                }
                "audio.live.video" -> if(string(params,"source","") !in listOf("none","screen","camera"))invalid("Video source must be none, screen or camera")
                "audio.live.keyframe" -> if(!string(params,"reason","").matches(Regex("[a-z_]{1,32}")))invalid("Invalid keyframe reason")
                "audio.live.context" -> {
                    val text=string(params,"text","")
                    if(text.isBlank() || text.length>4000)invalid("Live context must contain 1–4000 characters")
                }
                "audio.live.result" -> {
                    val offerId=string(params,"offerId", "")
                    val afterInput=string(params,"afterInput", "")
                    val text=string(params,"text", "")
                    if(offerId.isBlank() || offerId.length>64 || !params.has("afterInput") || afterInput.length>64)
                        invalid("Invalid task result offer")
                    if(text.isBlank() || text.length>12000)invalid("Live text must contain 1–12000 characters")
                }
            }
        }
    }
    private fun string(params: JSONObject,key: String,default: String): String {
        if(!params.has(key))return default
        return params.opt(key) as? String ?: invalid("Parameter $key must be a string")
    }
    private fun invalid(message: String): Nothing = throw AudioFailure("INVALID_PARAMS",message)
}
