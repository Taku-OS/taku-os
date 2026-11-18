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
            "audio.session.open" -> setOf("purpose","language","profileId","voiceId","speed","requestKey","targetLanguage","targetPackage","output","originalAudio","outgoingProfileId","outgoingVoiceId","outgoingSpeed","interruptible","settingsScope")
            "audio.session.append" -> setOf("sessionId","index","text")
            "audio.session.finish","audio.session.close","audio.session.status" -> setOf("sessionId")
            else -> throw AudioFailure("METHOD_NOT_FOUND","Audio method is not implemented")
        }
        if(params.keys().asSequence().any{it !in keys})invalid("Audio parameters contain an unknown field")
        if(params.has("settingsScope")) {
            if(string(params,"settingsScope","")!="assistant-entry" || string(params,"purpose","DICTATION")!="DICTATION" || params.has("language") || params.has("profileId"))invalid("Recognition settings scope applies only to dictation without an explicit language or model")
        }
        if(method=="audio.capabilities") {
            if(params.has("purpose") && string(params,"purpose","") !in listOf("DICTATION","SPEAK","MEDIA_TRANSLATE","CALL_TRANSLATE"))invalid("This purpose is not integrated yet")
            if(params.has("language") && string(params,"language","") !in listOf("zh-CN","en-US","ja-JP","ko-KR"))invalid("Select an available language")
        }
        if(method=="audio.session.open") {
            val purpose=string(params,"purpose","DICTATION")
            if(purpose !in listOf("DICTATION","SPEAK","MEDIA_TRANSLATE","CALL_TRANSLATE"))invalid("This purpose is not integrated yet")
            if(params.has("interruptible") && (params.opt("interruptible") !is Boolean || purpose!="SPEAK"))invalid("Voice interruption accepts a boolean only for the reading purpose")
            val language=string(params,"language","zh-CN")
            val call=purpose=="CALL_TRANSLATE"
            val media=purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE")
            val languages=if(media)listOf("zh-CN","en-US","ja-JP","ko-KR")else listOf("zh-CN","en-US")
            if(language !in languages)invalid("Select an available language")
            val profile=if(media)AudioProfiles.defaultMedia(falModel,call) else if(purpose=="SPEAK")"kokoro" else "zipformer"
            val selected=string(params,"profileId",profile)
            if(if(media)selected !in AudioProfiles.media else if(purpose=="DICTATION")selected !in AudioProfiles.dictation else selected!=profile)invalid("The model does not support this purpose")
            if(selected=="fal-whisper" && (purpose !in listOf("DICTATION","MEDIA_TRANSLATE") || string(params,"output","CAPTIONS")!="CAPTIONS" || string(params,"voiceId","").isNotEmpty() || params.optDouble("speed",1.0)!=1.0))invalid("Whisper supports transcription or captions only, without voice or speed settings")
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
        } else if(method.startsWith("audio.session.")) {
            if(!string(params,"sessionId","").matches(Regex("audio-[1-9][0-9]*")))invalid("Invalid session identifier")
            if(method=="audio.session.append") {
                val index=params.opt("index")
                if(index !is Number || !index.toDouble().isFinite() || index.toDouble()<0 || index.toDouble()>9007199254740991.0 || index.toDouble()!=index.toLong().toDouble())invalid("Segment index must be a nonnegative integer")
                val text=string(params,"text","");if(text.isBlank() || text.length>500)invalid("A segment must contain 1–500 characters")
            }
        }
    }
    private fun string(params: JSONObject,key: String,default: String): String {
        if(!params.has(key))return default
        return params.opt(key) as? String ?: invalid("Parameter $key must be a string")
    }
    private fun invalid(message: String): Nothing = throw AudioFailure("INVALID_PARAMS",message)
}
