// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import okio.Buffer
import org.agentos.audio.AudioEvent
import org.agentos.audio.AudioFailure
import org.agentos.audio.AudioParameters
import org.agentos.audio.AudioPhase
import org.agentos.audio.DictationSessionMetadata
import org.agentos.audio.PcmWaveBody
import org.agentos.audio.json
import org.json.JSONObject

class DictationTransportProbe : Instrumentation() {
    override fun onCreate(arguments: Bundle?){super.onCreate(arguments);start()}
    override fun onStart() {
        val report=JSONObject().put("passed",false)
        try {
            val samples=ShortArray(960007){((it*31)%65536-32768).toShort()}
            val encoded=Buffer()
            val body=PcmWaveBody(samples,960000){}
            body.writeTo(encoded)
            check(body.contentLength()==1920044L && encoded.size==1920044L)
            check(encoded.readUtf8(4)=="RIFF" && encoded.readIntLe()==1920036)
            check(encoded.readUtf8(8)=="WAVEfmt " && encoded.readIntLe()==16)
            check(encoded.readShortLe().toInt()==1 && encoded.readShortLe().toInt()==1)
            check(encoded.readIntLe()==16000 && encoded.readIntLe()==32000)
            check(encoded.readShortLe().toInt()==2 && encoded.readShortLe().toInt()==16)
            check(encoded.readUtf8(4)=="data" && encoded.readIntLe()==1920000)
            for(index in 0 until 960000)check(encoded.readShortLe().toInt()==(index*31)%65536-32768)
            check(encoded.exhausted())
            val canceled=Buffer()
            var writes=0
            val failure=runCatching{PcmWaveBody(samples,960000){if(++writes==3)throw InterruptedException()}.writeTo(canceled)}.exceptionOrNull()
            check(failure is InterruptedException && canceled.size in 45L until body.contentLength())
            canceled.clear()
            val ranged=Buffer()
            val rangeBody=PcmWaveBody(samples,8193,32001){}
            rangeBody.writeTo(ranged)
            check(rangeBody.contentLength()==44L+8193*2 && ranged.size==rangeBody.contentLength())
            ranged.skip(40)
            check(ranged.readIntLe()==8193*2)
            for(index in 32001 until 40194)check(ranged.readShortLe()==samples[index])
            check(ranged.exhausted())
            for((count,offset) in listOf(-1 to 0,1 to -1,2 to samples.size-1,Int.MAX_VALUE to 1,0 to Int.MAX_VALUE)) {
                check(runCatching{PcmWaveBody(samples,count,offset){}}.exceptionOrNull() is IllegalArgumentException)
            }
            val empty=Buffer()
            PcmWaveBody(samples,0,samples.size){}.writeTo(empty)
            check(empty.size==44L)

            for(method in listOf("audio.session.open","audio.capabilities")) {
                AudioParameters.validate(method,JSONObject().put("settingsScope","assistant-entry"))
                AudioParameters.validate(method,JSONObject().put("settingsScope","assistant-entry").put("purpose","DICTATION"))
                val invalid=listOf(
                    JSONObject().put("settingsScope",JSONObject.NULL),
                    JSONObject().put("settingsScope","other"),
                    JSONObject().put("settingsScope","assistant-entry").put("purpose","SPEAK"),
                    JSONObject().put("settingsScope","assistant-entry").put("language","en-US"),
                    JSONObject().put("settingsScope","assistant-entry").put("profileId","zipformer"),
                )
                for(params in invalid)check((runCatching{AudioParameters.validate(method,params)}.exceptionOrNull() as AudioFailure).code=="INVALID_PARAMS")
            }
            AudioParameters.validate("audio.session.open",JSONObject().put("profileId","fal-whisper").put("language","en-US"))
            for(params in listOf(
                JSONObject().put("profileId","fal-whisper").put("voiceId","eve"),
                JSONObject().put("profileId","fal-whisper").put("speed",1.1),
                JSONObject().put("profileId","fal-whisper").put("speed","1"),
                JSONObject().put("profileId","fal-whisper").put("output","CAPTIONS"),
            ))check((runCatching{AudioParameters.validate("audio.session.open",params)}.exceptionOrNull() as AudioFailure).code=="INVALID_PARAMS")

            val metadata=DictationSessionMetadata("assistant-entry",17,"zipformer","CLOUD_AUDIO","MODEL_MISSING",60000,100000)
            val event=AudioEvent("audio-1",1,AudioPhase.Draining,"Draining",dictation=metadata,profileId="fal-whisper",language="en-US",drainDeadlineMonotonicMs=120000)
            val wire=event.json().getJSONObject("data")
            check(wire.getString("settingsScope")=="assistant-entry" && wire.getLong("settingsRevision")==17L)
            check(wire.getString("requestedProfileId")=="zipformer" && wire.getString("profileId")=="fal-whisper" && wire.getBoolean("fallbackUsed"))
            check(wire.getString("fallbackReason")=="MODEL_MISSING" && wire.getString("processing")=="CLOUD_AUDIO")
            check(wire.getLong("maxCaptureMs")==60000L && wire.getLong("drainTimeoutMs")==100000L && wire.getLong("drainDeadlineMonotonicMs")==120000L)
            val local=event.copy(dictation=DictationSessionMetadata(null,null,"zipformer","LOCAL_ONLY",null,null,5000),profileId="zipformer",drainDeadlineMonotonicMs=null).json().getJSONObject("data")
            check(local.isNull("settingsScope") && local.isNull("settingsRevision") && local.isNull("fallbackReason") && local.isNull("maxCaptureMs") && local.isNull("drainDeadlineMonotonicMs"))
            check(!local.getBoolean("fallbackUsed"))
            check(!wire.has("level") && !local.has("level"))
            val level=event.copy(type="Level",level=.25f,frames=1600,sampleRate=16000).json().getJSONObject("data")
            check(level.getString("kind")=="Level" && level.getDouble("level")==.25)
            check(level.getInt("frames")==1600 && level.getInt("sampleRate")==16000)
            report.put("passed",true).put("pcmSamples",960000).put("uploadCancellation",true).put("nativeParameterParity",true)
                .put("metadataEncoding",true).put("pinnedPcmRanges",true).put("levelEncoding",true)
        }catch(error: Throwable){report.put("error",error.toString())}
        finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})
    }
}
