// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Process-wide media/call translation session. The configuration Activity only attaches to this
 * controller and may be destroyed without cancelling the session; the foreground overlay service
 * keeps the process alive while a session is active.
 */
object MediaTranslationController : AudioClient.Listener {
    interface StateListener {
        fun onTranslationState(snapshot: Snapshot)
    }

    data class Request(
        val call: Boolean,
        val targetPackage: String,
        val language: String,
        val targetLanguage: String,
        val profileId: String,
        val voiceId: String,
        val output: String,
        val originalAudio: String,
        val outgoingProfileId: String,
        val outgoingVoiceId: String,
        val providerLabel: String,
        val title: String
    )

    data class Snapshot(val active: Boolean,val status: String,val request: Request?)

    private val main=Handler(Looper.getMainLooper())
    private var app: Context?=null
    private var client: AudioClient?=null
    private var request: Request?=null
    private var listener: WeakReference<StateListener>?=null
    private var stopTimeout: Runnable?=null
    private var id=""
    private var opening=""
    private var status=""
    private var overlayStatus=""
    private var ending=false
    private var history=""
    private var preview=""
    private var sourceHistory=""
    private var sourcePreview=""
    private val callHistory=mutableMapOf<String,String>()
    private val callPreview=mutableMapOf<String,String>()

    fun isActive(): Boolean = client!=null

    fun activeRequest(): Request? = if(client!=null)request else null

    fun snapshot(): Snapshot = Snapshot(client!=null,status,activeRequest())

    fun attach(listener: StateListener) {
        this.listener=WeakReference(listener)
        listener.onTranslationState(snapshot())
    }

    fun detach(listener: StateListener) {
        if(this.listener?.get()===listener)this.listener=null
    }

    /** Returns false when a session is already active or cannot be opened. */
    fun start(context: Context,request: Request): Boolean {
        if(client!=null){notifyState();return false}
        val ctx=context.applicationContext
        if(!Settings.canDrawOverlays(ctx)){status="Allow overlay permission";notifyState();return false}
        app=ctx;this.request=request
        cancelStopTimeout()
        id="";opening="";ending=false
        history="";preview="";sourceHistory="";sourcePreview="";callHistory.clear();callPreview.clear()
        status=if(request.call)"Connecting bidirectional translation. Keep the call active…" else "Connecting ${request.providerLabel}. Play the video after connecting…"
        overlayStatus=status
        OverlayHost.showSession(ctx,request.title,::stop)
        OverlayHost.update(status,"")
        val next=AudioClient(ctx,this)
        client=next
        startOverlayService(ctx)
        notifyState()
        return try {
            next.connect()
            if(client==null)false else {launchSource();true}
        }catch(_: Exception){end("Video translation could not start");false}
    }

    /** Explicit stop; waits for the engine's Closed event, then restores the idle entry. */
    fun stop() {
        val next=client?:return
        if(id.isEmpty()){end("Translation stopped");return}
        status="Stopping translation…";overlayStatus=status
        OverlayHost.update(status,captionText())
        notifyState()
        try{next.cancel(id)}catch(_: Exception){end("Translation stopped");return}
        scheduleStopTimeout(id)
    }

    /** Explicit close from settings: stop the session, hide every window and persist the choice. */
    fun closeOverlay(context: Context) {
        val ctx=context.applicationContext
        OverlayPreference.set(ctx,false)
        stop()
        OverlayHost.closeAll()
        runCatching{ctx.stopService(Intent(ctx,TranslationOverlayService::class.java))}
        notifyState()
    }

    fun bringSourceToFront(){launchSource()}

    override fun connected() {
        val next=client?:return
        try{opening=next.open(buildParams())}catch(_: Exception){end("Video translation could not start")}
    }

    override fun event(event: String) {
        val value=JSONObject(event)
        if(value.optString("type")=="response") {
            if(value.optString("id")==opening) {
                if(!value.optBoolean("ok")){end(value.optJSONObject("error")?.optString("message") ?: "Translation could not start");return}
                id=value.optJSONObject("result")?.optString("sessionId","") ?: ""
            }
            return
        }
        val data=value.optJSONObject("data") ?: return
        if(id.isNotEmpty() && data.optString("sessionId")!=id)return
        when(data.optString("kind")) {
            "Progress" -> publish(data.optString("text"),data.optString("text"))
            "SourceEnded" -> {
                ending=true
                publish("Video paused or ended; finishing remaining ${if(playSpeech)"translated speech" else "captions"}…")
            }
            "Running" -> publish("Translating","$providerLabel · ${if(playSpeech)"translated speech" else "captions only"}")
            "Text" -> {
                val text=data.optString("text")
                if(call) {
                    val key=data.optString("direction")+":"+data.optString("textKind")
                    callPreview[key]=text
                    if(data.optBoolean("final")){callHistory[key]=(callHistory.getOrDefault(key,"")+text+"\n").takeLast(800);callPreview[key]=""}
                } else if(data.optString("textKind")=="target") {
                    preview=text
                    if(data.optBoolean("final")){history=(history+preview+"\n").takeLast(4000);preview=""}
                } else {
                    sourcePreview=text
                    if(data.optBoolean("final")){sourceHistory=(sourceHistory+sourcePreview+"\n").takeLast(1000);sourcePreview=""}
                }
                publish(if(ending)"Finishing remaining ${if(playSpeech)"translated speech" else "captions"}…" else "$providerLabel · Translating")
            }
            "Closed" -> end(closedMessage(data))
        }
    }

    override fun failed(message: String){end(message)}

    private val call: Boolean get()=request?.call==true
    private val playSpeech: Boolean get()=request?.output=="SPEECH"
    private val providerLabel: String get()=request?.providerLabel ?: ""

    private fun publish(message: String,overlay: String=message) {
        status=message;overlayStatus=overlay
        OverlayHost.update(overlayStatus,captionText())
        notifyState()
    }

    private fun captionText(): String {
        if(call) {
            val parts=mutableListOf<String>()
            for((key,label) in listOf("incoming:source" to "Incoming source","incoming:target" to "Incoming translation","outgoing:source" to "Outgoing source","outgoing:target" to "Outgoing translation")) {
                val text=callHistory.getOrDefault(key,"")+callPreview.getOrDefault(key,"")
                if(text.isNotBlank())parts.add("$label\n$text")
            }
            return parts.joinToString("\n")
        }
        val source=(sourceHistory+sourcePreview).takeLast(1000)
        val target=history+preview
        return if(source.isBlank())target else "Source\n$source\nTranslation\n${target.takeLast(2900)}"
    }

    private fun closedMessage(data: JSONObject): String {
        if(!data.optBoolean("restored"))return "Audio resource release is unconfirmed. Stop video playback"
        return when(data.optString("reason")) {
            "completed"->if(call)"Translation complete; original call restored" else "Translation complete; original audio restored"
            "canceled","client_disconnected"->"Translation stopped; original audio restored"
            "SOURCE_UNAVAILABLE"->"The selected app has not started audio playback; translation stopped"
            "CALL_ENDED"->"Call ended; translation stopped and original call restored"
            "CALL_UNAVAILABLE"->"Start a voice call in the target app first and verify microphone availability"
            "CAPTURE_SILENCED"->"The system paused call capture; translation stopped"
            "CALL_STATE_UNAVAILABLE"->"Cannot confirm call status; translation stopped and original call restored"
            "CAPTURE_FAILED"->"Call audio path was interrupted; translation stopped and original call restored"
            "MODEL_MISSING"->"Install local audio resources for the selected language first"
            "TEXT_TRANSLATION_FAILED"->"Text translation did not complete. Check the Taku AI text model"
            "QUEUE_BACKLOG"->"The current path could not keep up with the video; translation stopped and original audio restored"
            "PROCESSING_TIMEOUT"->"Translation timed out; original audio restored"
            "PROVIDER_AUTH_FAILED","PROVIDER_NOT_CONFIGURED"->"Check your fal Key in Taku Audio"
            "CONFIG_UNAVAILABLE"->"Audio configuration unavailable; translation stopped and original audio restored"
            "PROVIDER_REMOVED","POLICY_REVOKED"->"Audio authorization revoked; translation stopped and original audio restored"
            else->"Translation interrupted; original audio restored. Retry"
        }
    }

    private fun end(message: String) {
        cancelStopTimeout()
        status=message;overlayStatus=message
        OverlayHost.closeSession()
        val previous=client
        client=null
        id="";opening="";ending=false
        runCatching{previous?.close()}
        val ctx=app
        if(ctx!=null) {
            if(OverlayPreference.enabled(ctx) && Settings.canDrawOverlays(ctx)) {
                OverlayHost.showEntry(ctx)
            } else {
                OverlayHost.closeEntry()
                runCatching{ctx.stopService(Intent(ctx,TranslationOverlayService::class.java))}
            }
        }
        notifyState()
    }

    private fun buildParams(): JSONObject {
        val req=request ?: return JSONObject()
        return JSONObject().put("purpose",if(req.call)"CALL_TRANSLATE" else "MEDIA_TRANSLATE")
            .put("profileId",req.profileId)
            .apply {
                if(req.voiceId.isNotEmpty())put("voiceId",req.voiceId)
                if(req.call) {
                    put("outgoingProfileId",req.outgoingProfileId)
                    put("outgoingVoiceId",req.outgoingVoiceId)
                }
            }
            .put("language",req.language).put("targetLanguage",req.targetLanguage)
            .put("targetPackage",req.targetPackage)
            .put("output",req.output).put("originalAudio",req.originalAudio)
    }

    private fun launchSource() {
        val ctx=app ?: return
        val pkg=request?.targetPackage ?: return
        val intent=ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
        runCatching{ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
    }

    private fun startOverlayService(ctx: Context) {
        runCatching{ctx.startForegroundService(Intent(ctx,TranslationOverlayService::class.java))}
    }

    private fun scheduleStopTimeout(session: String) {
        cancelStopTimeout()
        val task=Runnable{if(client!=null && id==session)end("Translation stopped")}
        stopTimeout=task
        main.postDelayed(task,10_000)
    }

    private fun cancelStopTimeout(){stopTimeout?.let{main.removeCallbacks(it)};stopTimeout=null}

    private fun notifyState(){listener?.get()?.onTranslationState(snapshot())}
}
