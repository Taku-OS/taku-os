// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import org.json.JSONObject
import java.util.ArrayDeque

/** One reading session: sentence input and playback backpressure stay behind this client. */
class AudioReader @JvmOverloads constructor(private val context: Context,private val listener: Listener,private val interruption: InterruptionListener?=null) {
    interface InterruptionListener {fun text(value: String,final: Boolean)}
    interface Listener {fun state(active: Boolean,message: String)}
    private var client: AudioClient?=null
    private var session=""
    private var openRequest=""
    private var closing=false
    private var generation=0L
    private val chunks=ArrayDeque<String>()
    private var queuedChars=0
    private var next=0L
    private var ready=false
    private var playing=false
    private var inputFinished=false
    private var finishSent=false
    private var interruptible=false
    private var interrupted=false
    private val stopped=mutableListOf<Runnable>()
    var active=false;private set

    fun start(text: String,language: String) {
        if(text.isBlank()){listener.state(false,"No text to read aloud");return}
        if(text.length>12000){listener.state(false,"The reply is long. Select a shorter passage to read");return}
        startStream(language)
        append(text)
        finishInput()
    }
    @JvmOverloads fun startStream(language: String,interruptible: Boolean=false) {
        check(!active)
        this.interruptible=interruptible;interrupted=false
        val token=++generation
        active=true;closing=false;ready=false;playing=false;inputFinished=false;finishSent=false;next=0
        chunks.clear();queuedChars=0
        listener.state(true,"Preparing to read…")
        client=AudioClient(context,object : AudioClient.Listener {
            override fun connected(){if(token!=generation||!active)return;try{openRequest=client!!.open(JSONObject().put("purpose","SPEAK").put("language",language).put("interruptible",interruptible))}catch(_: Exception){end("Audio engine temporarily unavailable")}}
            override fun failed(message: String){if(token==generation)end(message)}
            override fun event(event: String) {
                if(token!=generation||!active)return
                try {
                    val e=JSONObject(event)
                    if(e.optString("type")=="response") {
                        if(!e.optBoolean("ok")){
                            // An in-flight append may meet the engine's interruption before its event arrives.
                            if(interruptible && e.optJSONObject("error")?.optString("code")=="INPUT_CLOSED")return
                            end(e.getJSONObject("error").optString("message","Reading did not complete"));return
                        }
                        if(e.optString("id")==openRequest){session=e.getJSONObject("result").getString("sessionId");if(closing)client!!.cancel(session)}
                        return
                    }
                    val data=e.getJSONObject("data")
                    if(data.optString("sessionId")!=session)return
                    when(data.optString("kind")) {
                        "Running" -> {ready=true;if(!closing)advance()}
                        "Interrupted" -> {interrupted=true;ready=false;chunks.clear();queuedChars=0;listener.state(true,"Listening for an interruption…")}
                        "Text" -> if(interrupted)interruption?.text(data.optString("text"),data.optBoolean("final"))
                        "Playback" -> {
                            if(!interrupted && data.optString("playbackState")=="started")listener.state(true,"Reading…")
                            if(data.optString("playbackState")=="chunkComplete" && !closing && !interrupted){playing=false;advance()}
                        }
                        "Closed" -> end(if(data.optString("reason")=="interrupted")"Voice interruption complete" else if(data.optString("reason")=="completed")"Reading complete" else if(closing)"Reading stopped" else "Reading stopped (${data.optString("reason")}）")
                    }
                }catch(_: Exception){end("Reading result did not complete")}
            }
        }).also{it.connect()}
    }
    fun append(text: String) {
        if(interrupted)return
        check(active && !closing && !inputFinished)
        if(text.isBlank())return
        val added=SpeechChunks.split(text)
        val count=added.sumOf{it.length}
        if(queuedChars+count>12000){stop();throw AudioFailure("QUEUE_BACKLOG","Too much content is queued. Read again after playback finishes")}
        chunks.addAll(added);queuedChars+=count;advance()
    }
    fun finishInput(){if(!active || closing || inputFinished || interrupted)return;inputFinished=true;advance()}
    private fun advance() {
        if(!ready || playing || closing || interrupted || session.isEmpty() || finishSent)return
        if(chunks.isNotEmpty()) {
            val text=chunks.removeFirst();queuedChars-=text.length;playing=true
            client!!.append(session,next++,text)
        } else if(inputFinished){finishSent=true;client!!.finish(session)}
        else listener.state(true,"Waiting for the next sentence…")
    }
    @JvmOverloads fun stop(after: Runnable?=null) {
        if(after!=null)stopped.add(after)
        if(!active){releaseCallbacks();return}
        closing=true
        if(session.isNotEmpty())client!!.cancel(session)
        else if(openRequest.isEmpty())end("Reading stopped")
    }
    private fun end(message: String) {
        if(!active)return
        generation++;active=false;client?.close();client=null;session="";openRequest="";chunks.clear();queuedChars=0
        listener.state(false,message);releaseCallbacks()
    }
    private fun releaseCallbacks(){val callbacks=stopped.toList();stopped.clear();callbacks.forEach{it.run()}}
}
