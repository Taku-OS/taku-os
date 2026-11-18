// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio.tests

import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import org.agentos.audio.AudioClient
import org.agentos.audio.EngineConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class DictationSessionProbe : Instrumentation() {
    private val replies=LinkedBlockingQueue<JSONObject>()
    private val events=mutableListOf<JSONObject>()
    private lateinit var client: AudioClient
    override fun onCreate(arguments: Bundle?){super.onCreate(arguments);start()}

    private fun settings(action: String,language: String?=null,saved: JSONObject?=null): JSONObject {
        val request=JSONObject().put("op","settings").put("action",action)
        if(action!="read")request.put("scope","assistant-entry")
        if(action=="configureDictation") {
            request.put("primaryProfileId",saved?.getString("primaryProfileId") ?: "zipformer")
                .put("fallbackProfileId",saved?.get("fallbackProfileId") ?: JSONObject.NULL)
                .put("language",saved?.getString("language") ?: language)
        }
        return EngineConfig.control(request)
    }

    private fun request(method: String,params: JSONObject): JSONObject {
        var id=""
        runOnMainSync{id=client.call(method,params)}
        val deadline=SystemClock.elapsedRealtime()+15000
        while(true) {
            val remaining=deadline-SystemClock.elapsedRealtime()
            check(remaining>0){"Timed out waiting for $method"}
            val message=replies.poll(remaining,TimeUnit.MILLISECONDS) ?: error("Missing response for $method")
            if(message.optString("type")=="event"){events.add(message.getJSONObject("data"));continue}
            if(message.optString("id")==id)return message
            check(message.optString("type")!="connectionError"){message.optString("message")}
        }
    }

    private fun result(method: String,params: JSONObject): JSONObject = request(method,params).let {
        check(it.optBoolean("ok")){"$method failed with ${it.optJSONObject("error")?.optString("code")}"}
        it.getJSONObject("result")
    }

    private fun awaitPhase(id: String,phase: String): JSONObject {
        val deadline=SystemClock.elapsedRealtime()+15000
        while(true) {
            val status=result("audio.session.status",JSONObject().put("sessionId",id))
            if(status.getString("phase")==phase)return status
            check(status.getString("phase")!="Closed"){"Session closed with ${status.optString("reason")}"}
            check(SystemClock.elapsedRealtime()<deadline){"Session did not reach $phase"}
            Thread.sleep(20)
        }
    }

    override fun onStart() {
        val report=JSONObject().put("passed",false)
        var original: JSONObject?=null
        var saved: JSONObject?=null
        var changed=false
        var session: String?=null
        try {
            original=settings("read")
            check(original.getBoolean("enabled") && original.getBoolean("audioEnabled")){"Enable audio before this probe"}
            val scopes=original.getJSONObject("dictation")
            if(scopes.has("assistant-entry"))saved=scopes.getJSONObject("assistant-entry")
            val connected=java.util.concurrent.CountDownLatch(1)
            runOnMainSync {
                client=AudioClient(targetContext,object : AudioClient.Listener {
                    override fun connected(){connected.countDown()}
                    override fun event(event: String){replies.put(JSONObject(event))}
                    override fun failed(message: String){replies.put(JSONObject().put("type","connectionError").put("message",message));connected.countDown()}
                })
                client.connect()
            }
            check(connected.await(10,TimeUnit.SECONDS)){"Client did not connect"}
            val firstSettings=settings("configureDictation","en-US");changed=true
            val firstRevision=firstSettings.getLong("audioRevision")
            val key="dictation-probe-${System.nanoTime()}"
            val scoped=JSONObject().put("settingsScope","assistant-entry").put("requestKey",key)
            val first=result("audio.session.open",scoped)
            session=first.getString("sessionId")
            check(first.getLong("settingsRevision")==firstRevision && first.getString("language")=="en-US")
            check(first.getString("processing")=="LOCAL" && !first.getBoolean("fallbackUsed"))
            awaitPhase(session,"Running")
            val secondSettings=settings("configureDictation","zh-CN")
            val secondRevision=secondSettings.getLong("audioRevision")
            val repeated=result("audio.session.open",scoped)
            check(repeated.getString("sessionId")==session && repeated.getLong("settingsRevision")==firstRevision && repeated.getString("language")=="en-US")
            val conflict=request("audio.session.open",JSONObject().put("profileId","zipformer").put("requestKey",key))
            check(!conflict.getBoolean("ok") && conflict.getJSONObject("error").getString("code")=="REQUEST_CONFLICT")
            result("audio.session.close",JSONObject().put("sessionId",session))
            check(awaitPhase(session,"Closed").getBoolean("restored"))
            val next=result("audio.session.open",JSONObject().put("settingsScope","assistant-entry").put("requestKey","$key-next"))
            session=next.getString("sessionId")
            check(next.getLong("settingsRevision")==secondRevision && next.getString("language")=="zh-CN")
            awaitPhase(session,"Running")
            result("audio.session.finish",JSONObject().put("sessionId",session))
            val final=awaitPhase(session,"Closed")
            check(final.getString("reason")=="completed" && final.getString("completion")=="complete" && final.getBoolean("restored"))
            check(final.getLong("drainTimeoutMs")==5000L && final.getLong("drainDeadlineMonotonicMs")>0)
            val scopedEvents=events.filter{it.optString("sessionId")==first.getString("sessionId")}
            check(scopedEvents.isNotEmpty() && scopedEvents.all{it.getLong("settingsRevision")==firstRevision && it.getString("settingsScope")=="assistant-entry"})
            val capabilities=result("audio.capabilities",JSONObject().put("settingsScope","assistant-entry"))
            check(capabilities.getJSONObject("dictation").getJSONObject("assistant-entry").getString("language")=="zh-CN")
            val availability=capabilities.getJSONArray("availability")
            check(availability.length()==2)
            for(index in 0 until availability.length()) {
                val row=availability.getJSONObject(index)
                check(row.getString("purpose")=="DICTATION")
                check(!row.getJSONArray("unavailableReasons").toString().contains("TEXT_MODEL_NOT_CONFIGURED"))
            }
            report.put("passed",true).put("revisions",JSONArray().put(firstRevision).put(secondRevision)).put("metadataEvents",scopedEvents.size)
        }catch(error: Throwable){report.put("error",error.toString())}
        finally {
            if(::client.isInitialized) {
                session?.let{id->runCatching{result("audio.session.close",JSONObject().put("sessionId",id));awaitPhase(id,"Closed")}}
                runOnMainSync{client.close()}
            }
            if(changed)try {
                if(saved==null)settings("resetDictation")else settings("configureDictation",saved=saved)
                val restored=settings("read")
                check(restored.getJSONObject("dictation").toString()==original!!.getJSONObject("dictation").toString())
                report.put("settingsRestored",true)
            }catch(error: Throwable){report.put("passed",false).put("restoreError",error.toString())}
        }
        finish(if(report.optBoolean("passed"))-1 else 0,Bundle().apply{putString("report",report.toString())})
    }
}
