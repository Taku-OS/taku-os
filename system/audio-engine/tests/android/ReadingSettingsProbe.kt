// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import org.agentos.audio.EngineConfig
import org.json.JSONObject

/** Real root-owned settings roundtrip; restores an absent preference as absent. */
class ReadingSettingsProbe : Instrumentation() {
    override fun onCreate(args: Bundle?){super.onCreate(args);start()}
    override fun onStart(){
        var before: JSONObject?=null
        var changed=false
        val report=JSONObject().put("passed",false)
        try {
            before=EngineConfig.control(JSONObject().put("op","read"))
            changed=true
            val saved=EngineConfig.control(JSONObject().put("op","configureReading").put("language","en-US").put("voiceId","en-US-local-1").put("speed",1.25))
            check(saved.getLong("audioRevision")>before.getLong("audioRevision"))
            val loaded=EngineConfig.control(JSONObject().put("op","read")).getJSONObject("reading").getJSONObject("en-US")
            check(loaded.getString("voiceId")=="en-US-local-1" && loaded.getDouble("speed")==1.25)
            var rejected=false
            try{EngineConfig.control(JSONObject().put("op","configureReading").put("language","en-US").put("voiceId","invalid").put("speed",0))}catch(_: Exception){rejected=true}
            check(rejected)
            report.put("passed",true).put("rootRoundtrip",true).put("revisionAdvanced",true).put("invalidPreferenceRejected",true)
        }catch(e: Throwable){report.put("error",e.javaClass.simpleName)}
        finally {
            if(changed && before!=null) {
                try{
                    val old=before.optJSONObject("reading")?.optJSONObject("en-US")
                    val request=JSONObject().put("op",if(old==null)"resetReading" else "configureReading").put("language","en-US")
                    if(old!=null)request.put("voiceId",old.getString("voiceId")).put("speed",old.getDouble("speed"))
                    EngineConfig.control(request)
                    val restored=EngineConfig.control(JSONObject().put("op","read")).getJSONObject("reading").optJSONObject("en-US")
                    check(old?.toString()==restored?.toString())
                    report.put("previousPreferenceRestored",true)
                }catch(_: Throwable){report.put("passed",false).put("restoreFailed",true)}
            }
            finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})
        }
    }
}
