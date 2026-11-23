// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.*
import android.os.*
import org.json.JSONObject

/** Main-thread binding and callbacks; service runs audio/model work in its own process. */
class AudioClient(context: Context, private val listener: Listener) : AutoCloseable {
    interface Listener {
        fun connected()
        fun event(event: String)
        fun failed(message: String)
    }
    private val context = context.applicationContext
    private var service: Messenger? = null
    private var bound = false
    private val main = Handler(Looper.getMainLooper())
    private var handshakeId = ""
    private var readinessChecks = 0
    private val events = Messenger(Handler(Looper.getMainLooper()) { message ->
        if(!bound)return@Handler true
        val raw=message.data.getString("json", "{}")
        val value=JSONObject(raw)
        if(value.optString("type")=="response" && value.optString("id")==handshakeId) {
            if(value.optBoolean("ok") && value.getJSONObject("result").optBoolean("configured")) listener.connected()
            else if(++readinessChecks<30) main.postDelayed({ if(bound) handshake() },100)
            else listener.failed("Audio configuration service temporarily unavailable")
        } else listener.event(raw)
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { if(!bound)return;service = Messenger(binder);readinessChecks=0;handshake() }
        override fun onServiceDisconnected(name: ComponentName) { if(!bound)return;service = null; listener.failed("Audio engine disconnected") }
        override fun onBindingDied(name: ComponentName) { close(); listener.failed("Audio engine exited. Retry") }
        override fun onNullBinding(name: ComponentName) { close(); listener.failed("Audio engine unavailable") }
    }
    private fun handshake() { handshakeId=call("audio.capabilities",JSONObject()) }
    fun connect() {
        if (bound) return
        bound = context.bindService(Intent().setComponent(ComponentName("org.agentos.audio", "org.agentos.audio.AudioEngineService")), connection, Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES)
        if (!bound) listener.failed("Install Taku Audio first")
    }
    fun call(method: String, params: JSONObject): String {
        val id = java.util.UUID.randomUUID().toString()
        val message = Message.obtain(null,1)
        message.replyTo = events
        message.data = Bundle().apply { putString("id",id); putString("method",method); putString("params",params.toString()) }
        (service ?: throw IllegalStateException("Audio engine is not connected")).send(message)
        return id
    }
    fun open(request: AudioRequest): String = open(JSONObject().put("purpose",request.purpose).put("language",request.language).put("profileId",request.profileId).put("voiceId",request.voiceId).put("speed",request.speed).apply {
        if(request.interruptible)put("interruptible",true)
        if(request.purpose=="LIVE_TALK") {
            put("muted",request.muted)
            if(request.history.isNotEmpty())put("history",org.json.JSONArray().apply{request.history.forEach{put(JSONObject().put("role",it.role).put("text",it.text))}})
        }
        if(request.purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE"))put("targetLanguage",request.targetLanguage).put("targetPackage",request.targetPackage).put("output",request.output).put("originalAudio",request.originalAudio)
        if(request.purpose=="CALL_TRANSLATE")put("outgoingProfileId",request.outgoingProfileId).put("outgoingVoiceId",request.outgoingVoiceId).put("outgoingSpeed",request.outgoingSpeed)
    })
    fun open(params: JSONObject): String = call("audio.session.open",JSONObject(params.toString()).apply{if(!has("requestKey"))put("requestKey",java.util.UUID.randomUUID().toString())})
    fun append(id: String,index: Long,text: String): String = call("audio.session.append",JSONObject().put("sessionId",id).put("index",index).put("text",text))
    fun finish(id: String) { call("audio.session.finish",JSONObject().put("sessionId",id)) }
    fun cancel(id: String) { call("audio.session.close",JSONObject().put("sessionId",id)) }
    override fun close() {
        service?.let { runCatching { it.send(Message.obtain(null,2).apply { replyTo = events }) } }
        if (bound) context.unbindService(connection)
        bound = false; service = null;main.removeCallbacksAndMessages(null)
    }
}
