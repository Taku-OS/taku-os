// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/** Local ASR → existing Agent → sentence reading. Optional AEC-backed interruption stays local. */
class AudioConversation(private val context: Context,private val listener: Listener) {
    interface InterruptResult {fun done(accepted: Boolean)}
    interface Listener {
        fun state(active: Boolean,message: String)
        /** Hand text to the existing composer/send path. No credentials or Agent protocol live here. */
        fun input(text: String): Boolean
        fun preserve(text: String)
        /** Request cancellation of this mode's old task; terminal state still comes through answer. */
        fun interrupt(taskId: String,result: InterruptResult)
    }
    private val main=Handler(Looper.getMainLooper())
    private var client: AudioClient?=null
    private var reader: AudioReader?=null
    private var speech: SpeechChunks.Stream?=null
    private var language="zh-CN"
    private var interruptible=false
    private var phase=""
    private fun phase(value: String){if(phase!=value){phase=value;android.util.Log.d("TakuAudioConversation","phase=$value")}}
    private var request="";private var session="";private var task=""
    private var input="";private var endpoint=false;private var answerEnded=false
    private var oldTaskEnded=false;private var captureClosed=false;private var cancellation: Boolean?=null
    private var interruptDeadline: Runnable?=null
    private var generation=0L
    var active=false;private set
    @JvmOverloads fun start(language: String,interruptible: Boolean=false) {
        check(!active);this.language=language;this.interruptible=interruptible;active=true;listen()
    }
    private fun listen() {
        if(!active)return
        phase("preparing");input="";endpoint=false;session="";task="";answerEnded=false
        speech=null;reader=null;oldTaskEnded=false;captureClosed=false;cancellation=null
        val token=++generation
        listener.state(true,"Continuous conversation: preparing to listen…")
        client=AudioClient(context,object: AudioClient.Listener {
            override fun connected(){if(active && token==generation)request=client!!.open(JSONObject().put("purpose","DICTATION").put("language",language))}
            override fun failed(message: String){if(token==generation)stop(message)}
            override fun event(event: String) {
                if(!active || token!=generation)return
                val value=JSONObject(event)
                if(value.optString("type")=="response") {
                    if(!value.optBoolean("ok")){stop(value.optJSONObject("error")?.optString("message") ?: "Continuous conversation could not start");return}
                    if(value.optString("id")==request)session=value.getJSONObject("result").getString("sessionId")
                    return
                }
                val data=value.getJSONObject("data");if(data.optString("sessionId")!=session)return
                when(data.optString("kind")) {
                    "Running"->{phase("listening");listener.state(true,"Continuous conversation: listening; sends after a pause. Tap the microphone to exit")}
                    "Text"->{
                        val text=data.optString("text")
                        if(data.optBoolean("final")) {
                            input=(input+" "+text).trim()
                            if(!endpoint){endpoint=true;client!!.finish(session)}
                        }else listener.state(true,text)
                    }
                    "Closed"->{
                        client?.close();client=null;session=""
                        if(data.optString("reason")!="completed"){stop("Continuous conversation stopped (${data.optString("reason")}）");return}
                        if(input.isBlank()){listen();return}
                        submitInput(input)
                    }
                }
            }
        })
        try{client!!.connect()}catch(_: Exception){stop("Audio engine temporarily unavailable")}
    }
    private fun submitInput(text: String) {
        interruptDeadline?.let{main.removeCallbacks(it)};interruptDeadline=null
        generation++;phase("waiting");task="";input="";answerEnded=false;oldTaskEnded=false
        speech=null;reader=null
        listener.state(true,"Continuous conversation: waiting for reply…")
        if(!listener.input(text)){listener.preserve(text);stop("Could not send automatically. Check the draft and retry")}
    }
    /** Called after the existing Agent submission returns its real task ID. */
    fun accepted(taskId: String){if(active && phase=="waiting")task=taskId}
    /** Only the matching new task may feed this mode; old history is never read automatically. */
    fun answer(taskId: String,text: String,complete: Boolean,successful: Boolean) {
        if(!active || task.isEmpty() || task!=taskId)return
        if(phase=="interrupting") {
            if(complete){oldTaskEnded=true;submitInterruptedIfReady()}
            return
        }
        if(phase !in listOf("waiting","speaking") || answerEnded)return
        if(complete && !successful){stop("This reply did not complete. Continuous conversation ended");return}
        if(speech==null)speech=SpeechChunks.Stream()
        val chunks=try{speech!!.accept(text,complete)}catch(_: Exception){stop("The reply changed. Continuous conversation ended");return}
        if(chunks.isNotEmpty() && reader==null) {
            phase("speaking")
            val token=generation
            reader=AudioReader(context,object: AudioReader.Listener {
                override fun state(active: Boolean,message: String) {
                    if(!this@AudioConversation.active || token!=generation)return
                    if(phase=="interrupting") {
                        if(!active){if(message=="Voice interruption complete"){captureClosed=true;submitInterruptedIfReady()}else stop(message)}
                    } else if(active)listener.state(true,if(interruptible)"Continuous conversation: reading; speak to interrupt. Tap the microphone to exit"else"Continuous conversation: reading. Tap the microphone to exit")
                    else if(message=="Reading complete" && answerEnded)listen()
                    else stop(message)
                }
            },object: AudioReader.InterruptionListener {
                override fun text(value: String,final: Boolean) {
                    if(!active || token!=generation)return
                    if(phase!="interrupting") {
                        phase("interrupting");input="";speech=null;captureClosed=false
                        oldTaskEnded=answerEnded;cancellation=if(oldTaskEnded)true else null
                        if(!oldTaskEnded)listener.interrupt(task,object: InterruptResult {
                            override fun done(accepted: Boolean){if(active && token==generation && phase=="interrupting"){cancellation=accepted;submitInterruptedIfReady()}}
                        })
                    }
                    if(final)input=value.trim()
                    listener.state(true,if(final)"Continuous conversation: interruption recognized; ending the previous turn…"else value)
                }
            })
            reader!!.startStream(language,interruptible)
        }
        for(chunk in chunks)reader?.append(chunk)
        if(complete) {
            answerEnded=true
            if(reader==null)listen() else reader!!.finishInput()
        }
    }
    private fun submitInterruptedIfReady() {
        if(phase!="interrupting" || !captureClosed)return
        if(input.isBlank()){stop("Interruption recognition did not complete. Continuous conversation ended");return}
        // A terminal task snapshot is authoritative even if its cancellation acknowledgement is late.
        if(oldTaskEnded){submitInput(input);return}
        if(cancellation==false){stop("Could not stop the previous turn. Check the interruption draft");return}
        if(interruptDeadline==null) {
            val token=generation
            interruptDeadline=Runnable{if(active && token==generation && phase=="interrupting")stop("The previous turn has not ended. Check the interruption draft and send it later")}
            main.postDelayed(interruptDeadline!!,5000)
        }
    }
    fun stop(message: String="Left continuous conversation") {
        if(!active)return
        if(input.isNotBlank())listener.preserve(input)
        active=false;generation++;phase("stopped")
        interruptDeadline?.let{main.removeCallbacks(it)};interruptDeadline=null
        client?.close();client=null
        reader?.stop(null);reader=null;speech=null
        listener.state(false,message)
    }
}
