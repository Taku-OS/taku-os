// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import org.json.JSONObject
import java.util.UUID

/** Bound audio host. Messenger provides Binder caller UID and client-death tracking. */
class AudioEngineService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val clients = mutableMapOf<IBinder, Pair<String,IBinder.DeathRecipient>>()
    private val runtime = AudioRuntime(clock={SystemClock.elapsedRealtime()}) { request -> when(request.purpose){"SPEAK"->KokoroDriver(this,request);"MEDIA_TRANSLATE"->MediaTranslationDriver(this,request);"CALL_TRANSLATE"->CallTranslationDriver(this,request);else->if(request.profileId=="fal-whisper")WhisperDictationDriver(this,request)else ZipformerDriver(this,request)} }
    private val dictationResolver by lazy { DictationResolver(this) }
    private class PendingRead(val operation: EngineConfig.Control,var failure: AudioFailure?=null)
    private val pendingReads=mutableMapOf<IBinder,MutableSet<PendingRead>>()
    private var speechSession: String?=null
    private var config: JSONObject? = null
    private val hostClosed = object : android.content.BroadcastReceiver() { override fun onReceive(context: android.content.Context, intent: Intent) { if(intent.action=="org.agentos.audio.STOP_PLAYBACK")runtime.revoke("canceled")else closeHostSessions() } }
    private lateinit var cli: AudioSocket
    private lateinit var configuration: EngineConfig
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message -> receive(message); true })
    override fun onCreate() {
        super.onCreate()
        registerReceiver(hostClosed,android.content.IntentFilter("org.agentos.audio.HOST_CLOSED").apply{addAction("org.agentos.audio.STOP_PLAYBACK")},RECEIVER_NOT_EXPORTED)
        configuration = EngineConfig({ next -> main.post { updateConfiguration(next) } }, { main.post {
            config = null
            pendingReads.keys.forEach { cancelPendingReads(it,"CONFIG_UNAVAILABLE") }
            runtime.revoke("CONFIG_UNAVAILABLE")
        } })
        configuration.start()
        cli=AudioSocket(::receive)
    }
    private fun updateConfiguration(snapshot: JSONObject,updatePolicy: Boolean=true) {
        val next=if(updatePolicy)snapshot else JSONObject().apply{snapshot.keys().forEach{key->put(key,snapshot.get(key))}}
        val current=config
        if(current!=null && current.optLong("audioRevision")>next.optLong("audioRevision")) {
            for(key in listOf("audioRevision","reading","dictation","fal"))next.put(key,current.opt(key) ?: JSONObject.NULL)
        }
        if(!updatePolicy && current!=null) {
            for(key in listOf("systemRevision","enabled","audioEnabled","apps","textTranslationConfigured"))next.put(key,current.opt(key) ?: JSONObject.NULL)
        }
        config=next
        if(!next.optBoolean("enabled") || !next.optBoolean("audioEnabled"))runtime.revoke("POLICY_REVOKED")
        if(next.optJSONObject("fal")?.optBoolean("configured")!=true)runtime.revokeProfiles(AudioProfiles.fal,"PROVIDER_REMOVED")
        for((binder,value) in clients.toMap()) {
            val uid=value.first.substringBefore(':').toInt()
            if(!allowedConfig(uid)) {
                runtime.revokeOwner(value.first,"POLICY_REVOKED")
                cancelPendingReads(binder,"POLICY_REVOKED")
            }
        }
    }
    private fun cancelPendingReads(binder: IBinder,code: String) {
        pendingReads[binder]?.forEach { pending ->
            if(pending.failure==null)pending.failure=AudioFailure(code,"Audio configuration is no longer valid")
            pending.operation.close()
        }
    }
    fun closeHostSessions() { for((_, value) in clients) if(value.first.startsWith("0:") || value.first.startsWith("2000:")) runtime.disconnect(value.first) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onBind(intent: Intent): IBinder = endpoint.binder
    private fun trusted(uid: Int): Boolean = uid == 0 || uid == 2000 || uid == 1000 || packageManager.checkSignatures(uid,applicationInfo.uid) == PackageManager.SIGNATURE_MATCH
    private fun allowedConfig(uid: Int,snapshot: JSONObject?=config): Boolean {
        val c = snapshot ?: return false
        return c.optBoolean("enabled") && c.optBoolean("audioEnabled") && authorizedConsumer(uid,c)
    }
    private fun authorizedConsumer(uid: Int,snapshot: JSONObject?=config): Boolean {
        val c=snapshot ?: return false
        if (uid == 0 || uid == 1000 || uid == 2000 || uid == applicationInfo.uid) return true
        val apps = c.optJSONObject("apps") ?: return false
        return packageManager.getPackagesForUid(uid)?.any { apps.optBoolean(it) } == true
    }
    private fun receive(message: Message) = receiveWithSnapshot(message,null)
    private fun receiveWithSnapshot(message: Message,refreshed: JSONObject?) {
        val reply = message.replyTo ?: return
        val requestId = message.data.getString("id","")
        try {
            val uid = message.sendingUid
            if (!trusted(uid)) throw AudioFailure("PERMISSION_DENIED","Audio caller is not authorized")
            val binder = reply.binder
            if (message.what == 2) { disconnect(binder); return }
            if (message.what != 1) throw AudioFailure("INVALID_REQUEST","Unknown audio operation")
            if(clients[binder]?.first?.substringBefore(':')?.toInt()?.let { it != uid } == true) throw AudioFailure("PERMISSION_DENIED","Caller identity changed")
            val owner = clients[binder]?.first ?: run {
                val name = "$uid:${UUID.randomUUID()}"
                val death = IBinder.DeathRecipient { main.post { disconnect(binder) } }
                binder.linkToDeath(death,0); clients[binder] = name to death; name
            }
            val raw = message.data.getString("params","{}")
            if (raw.length > 32000) throw AudioFailure("INVALID_PARAMS","Audio parameters are too long")
            val params = JSONObject(raw)
            val method = message.data.getString("method","")
            val snapshot=refreshed ?: config
            AudioParameters.validate(method,params,snapshot?.optJSONObject("fal")?.optString("model"))
            if(method=="audio.session.open") {
                val previous=runtime.findRequest(owner,params.optString("requestKey"))
                if(previous!=null) {
                    val scope=params.opt("settingsScope") as? String
                    if(runtime.dictationMetadata(owner,previous)?.settingsScope!=scope)throw AudioFailure("REQUEST_CONFLICT","Repeated request has different content")
                    if(scope!=null) {
                        respond(reply,requestId,sessionInfo(owner,previous).put("generation",previous).put("phase",runtime.status(owner,previous).phase.name))
                        return
                    }
                }
            }
            if(refreshed==null && (method=="audio.session.open" && params.optString("purpose","DICTATION")=="DICTATION" || method=="audio.capabilities" && params.has("settingsScope"))) {
                refresh(message,owner)
                return
            }
            val result = when(method) {
                "audio.capabilities" -> capabilities(uid,owner,params,snapshot)
                "audio.session.open", "audio.legacy.acquire" -> {
                    if ((uid == 0 || uid == 2000) && !AudioHostActivity.visible) throw AudioFailure("HOST_REQUIRED","Open the audio window on the device first")
                    if (!allowedConfig(uid,snapshot) || !allowedConfig(uid)) throw AudioFailure("POLICY_DISABLED","Audio features are paused or the configuration service is unavailable")
                    val legacy = method == "audio.legacy.acquire"
                    if (legacy && uid != 1000) throw AudioFailure("PERMISSION_DENIED","For existing native audio entry points only")
                    val mode=getSystemService(android.media.AudioManager::class.java).mode
                    if(params.optString("purpose")!="CALL_TRANSLATE" && (mode==android.media.AudioManager.MODE_IN_CALL || mode==android.media.AudioManager.MODE_IN_COMMUNICATION))throw AudioFailure("RESOURCE_BUSY","A call is using audio resources")
                    val purpose=if(legacy) "LEGACY_REALTIME" else params.optString("purpose","DICTATION")
                    val language=params.optString("language","zh-CN")
                    val call=purpose=="CALL_TRANSLATE"
                    val media=purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE")
                    val targetLanguage=params.optString("targetLanguage","zh-CN")
                    val selectedProfile=params.optString("profileId",if(legacy)"native-realtime" else if(media)AudioProfiles.defaultMedia(snapshot?.optJSONObject("fal")?.optString("model"),call) else if(purpose=="SPEAK")"kokoro" else "zipformer")
                    val hybrid=selectedProfile=="local-hybrid"
                    val defaults=snapshot?.optJSONObject("reading")?.optJSONObject(if(hybrid)targetLanguage else language)
                    val outgoingProfile=params.optString("outgoingProfileId",selectedProfile)
                    if(media && (selectedProfile in AudioProfiles.fal || call && outgoingProfile in AudioProfiles.fal) && snapshot?.optJSONObject("fal")?.optBoolean("configured")!=true)throw AudioFailure("PROVIDER_NOT_CONFIGURED","Add a fal Key in Taku Audio first")
                    val voice=if(purpose=="SPEAK" || media)params.optString("voiceId",if(purpose=="SPEAK" || hybrid)defaults?.optString("voiceId") ?: "" else "")else ""
                    val speed=if(purpose=="SPEAK" || hybrid)params.optDouble("speed",defaults?.optDouble("speed",1.0) ?: 1.0).toFloat()else 1f
                    if(purpose=="DICTATION") {
                        if(checkSelfPermission("android.permission.RECORD_AUDIO")!=PackageManager.PERMISSION_GRANTED)throw AudioFailure("PERMISSION_DENIED","Allow the audio engine to use the microphone")
                        if(runtime.resources(owner).busy && runtime.findRequest(owner,params.optString("requestKey"))==null)throw AudioFailure("RESOURCE_BUSY","Audio resources are in use")
                    }
                    val resolved=if(purpose=="DICTATION")dictationResolver.resolve(
                        if(params.has("settingsScope"))DictationSource.Scoped else DictationSource.Explicit(language,selectedProfile),
                        snapshot ?: throw AudioFailure("CONFIG_UNAVAILABLE","Audio configuration service unavailable"))else null
                    val request=resolved?.request ?: AudioRequest(purpose,language,selectedProfile,voice,speed,targetLanguage,params.optString("targetPackage",""),params.optString("output","CAPTIONS"),params.optString("originalAudio","KEEP"),outgoingProfile,
                        params.optString("outgoingVoiceId",if(outgoingProfile=="local-hybrid")snapshot?.optJSONObject("reading")?.optJSONObject(language)?.optString("voiceId") ?: "" else ""),
                        params.optDouble("outgoingSpeed",if(outgoingProfile=="local-hybrid")snapshot?.optJSONObject("reading")?.optJSONObject(language)?.optDouble("speed",1.0) ?: 1.0 else 1.0).toFloat(),params.optBoolean("interruptible",false))
                    if(purpose=="SPEAK")VoiceCatalog.select(request.language,request.voiceId)
                    if(request.interruptible) {
                        if(checkSelfPermission("android.permission.RECORD_AUDIO")!=PackageManager.PERMISSION_GRANTED)throw AudioFailure("PERMISSION_DENIED","Allow the audio engine to use the microphone")
                        if(!android.media.audiofx.AcousticEchoCanceler.isAvailable())throw AudioFailure("AEC_UNAVAILABLE","This device has no echo cancellation. Use turn-taking mode")
                    }
                    if(purpose=="SPEAK" || media)showPlaybackNotification(if(call)"Taku is translating this call in both directions" else if(media)"Taku is translating video audio" else if(request.interruptible)"Taku is reading and listening for interruptions"else "Taku is reading",call || request.interruptible)
                    val id=try {runtime.open(owner,request,{ event -> main.post {
                        send(reply,event.json())
                        if(event.type=="Closed" && speechSession==event.sessionId){speechSession=null;stopForeground(STOP_FOREGROUND_REMOVE)}
                    } },params.optString("requestKey"),resolved?.metadata)}catch(e: Exception){if(speechSession==null)stopForeground(STOP_FOREGROUND_REMOVE);throw e}
                    if((purpose=="SPEAK" || media) && runtime.status(owner,id).phase!=AudioPhase.Closed)speechSession=id
                    sessionInfo(owner,id).put("generation",id).put("phase",runtime.status(owner,id).phase.name)
                }
                "audio.session.append" -> {
                    runtime.append(owner,params.getString("sessionId"),params.getLong("index"),params.getString("text"));JSONObject()
                }
                "audio.session.finish" -> {
                    val id = params.getString("sessionId"); runtime.finish(owner,id)
                    JSONObject()
                }
                "audio.session.close" -> { runtime.close(owner,params.getString("sessionId")); JSONObject() }
                "audio.session.status" -> { val id=params.getString("sessionId");val data=runtime.status(owner,id).json().getJSONObject("data");val info=sessionInfo(owner,id);info.keys().forEach{key->data.put(key,info.get(key))};data }
                else -> throw AudioFailure("METHOD_NOT_FOUND","Audio method is not implemented")
            }
            respond(reply,requestId,result)
        } catch (e: Exception) {
            respondFailure(reply,requestId,e)
        }
    }
    private fun refresh(message: Message,owner: String) {
        val reply=message.replyTo
        val binder=reply.binder
        val requestId=message.data.getString("id","")
        val retained=Message.obtain(message)
        val operation=EngineConfig.Control(JSONObject().put("op","read"))
        val pending=PendingRead(operation)
        pendingReads.getOrPut(binder){mutableSetOf()}.add(pending)
        Thread({
            val result=runCatching{operation.use{it.execute()}}
            main.post {
                pendingReads[binder]?.let { reads -> reads.remove(pending);if(reads.isEmpty())pendingReads.remove(binder) }
                try {
                    if(clients[binder]?.first==owner) {
                        val invalid=pending.failure ?: if(config==null)AudioFailure("CONFIG_UNAVAILABLE","Audio configuration service unavailable")else null
                        if(invalid!=null)respondFailure(reply,requestId,invalid)
                        else result.fold({ snapshot ->
                        updateConfiguration(snapshot,updatePolicy=false)
                        receiveWithSnapshot(retained,config)
                    },{respondFailure(reply,requestId,it as? AudioFailure ?: AudioFailure("CONFIG_UNAVAILABLE","Audio configuration service unavailable"))})
                    }
                }finally{retained.recycle()}
            }
        },"audio-dictation-settings").start()
    }
    private fun respond(reply: Messenger,id: String,result: JSONObject) {
        send(reply,JSONObject().put("protocol","taku/0.1").put("type","response").put("id",id).put("ok",true).put("result",result))
    }
    private fun respondFailure(reply: Messenger,id: String,error: Throwable) {
        send(reply,JSONObject().put("protocol","taku/0.1").put("type","response").put("id",id).put("ok",false)
            .put("error",JSONObject().put("code",(error as? AudioFailure)?.code ?: "INVALID_REQUEST").put("message",(error as? AudioFailure)?.message ?: "Audio request did not complete").put("retryable",false)))
    }
    private fun sessionInfo(owner: String,id: String): JSONObject {
        val request=runtime.request(owner,id)
        return JSONObject().put("sessionId",id).put("purpose",request.purpose).put("language",request.language)
            .put("profileId",request.profileId).put("processing",if(request.purpose=="LEGACY_REALTIME")"EXTERNAL_LEGACY" else if(request.purpose=="CALL_TRANSLATE")if(request.profileId in AudioProfiles.fal || request.outgoingProfileId in AudioProfiles.fal)"CLOUD_AUDIO" else "CLOUD_TEXT" else if(request.profileId=="local-hybrid")"CLOUD_TEXT" else if(request.purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE"))"CLOUD_AUDIO" else "LOCAL_ONLY")
            .put("voiceId",if(request.purpose=="SPEAK")VoiceCatalog.select(request.language,request.voiceId).id else if(request.profileId=="fal-grok")request.voiceId.ifBlank{"eve"} else if(request.profileId=="local-hybrid")VoiceCatalog.select(request.targetLanguage,request.voiceId).id else "").put("speed",request.speed).put("interruptible",request.interruptible)
            .put("outgoingProfileId",request.outgoingProfileId).put("outgoingProcessing",if(request.outgoingProfileId=="local-hybrid")"CLOUD_TEXT" else "CLOUD_AUDIO").put("outgoingVoiceId",if(request.outgoingProfileId=="fal-grok")request.outgoingVoiceId.ifBlank{"eve"}else if(request.outgoingProfileId=="local-hybrid")VoiceCatalog.select(request.language,request.outgoingVoiceId).id else "").put("outgoingSpeed",request.outgoingSpeed)
            .put("targetLanguage",request.targetLanguage).put("targetPackage",request.targetPackage).put("output",request.output).put("originalAudio",request.originalAudio)
            .putDictation(runtime.dictationMetadata(owner,id),request.profileId,request.language,runtime.drainDeadlineMonotonicMs(owner,id))
    }
    private fun showPlaybackNotification(title: String,microphone: Boolean=false) {
        val notifications=getSystemService(android.app.NotificationManager::class.java)
        notifications.createNotificationChannel(android.app.NotificationChannel("audio-reading","Read aloud",android.app.NotificationManager.IMPORTANCE_LOW))
        val stop=android.app.PendingIntent.getBroadcast(this,1,Intent("org.agentos.audio.STOP_PLAYBACK").setPackage(packageName),android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=android.app.Notification.Builder(this,"audio-reading").setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle(title).setContentText("Tap to stop").setOngoing(true)
            .addAction(android.app.Notification.Action.Builder(null,"Stop",stop).build()).build()
        startForeground(100,notification,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or if(microphone)android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
    }
    private fun capabilities(uid: Int,owner: String,filter: JSONObject,snapshot: JSONObject?=config): JSONObject {
        val dictationView=dictationResolver.inspect(snapshot ?: JSONObject())
        val scoped=filter.has("settingsScope")
        val selectedPurpose=filter.optString("purpose",if(scoped)"DICTATION"else "")
        val scopedLanguage=dictationView.toJson().optString("resolvedLanguage","")
        val dictationBackends=mutableMapOf<String,List<DictationBackendStatus>>()
        val models = ModelResources.root(this)
        val languages = org.json.JSONArray()
        val installedLanguages=mutableSetOf<String>()
        for ((language,path) in listOf("zh-CN" to "asr-zh","en-US" to "asr")) {
            if(ModelResources.ready(models,language)){languages.put(language);installedLanguages.add(language)}
        }
        val voices=org.json.JSONArray()
        val ttsReady=ModelResources.ttsReady(models)
        for(voice in VoiceCatalog.voices)voices.put(JSONObject().put("id",voice.id).put("language",voice.language).put("label",voice.label))
        val fal=snapshot?.optJSONObject("fal")?.optBoolean("configured")==true
        val routing=checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING")==PackageManager.PERMISSION_GRANTED
        val mediaProfiles=org.json.JSONArray()
        for(profile in AudioProfiles.media) {
            val hybrid=profile=="local-hybrid"
            mediaProfiles.put(JSONObject().put("id",profile).put("processing",if(hybrid)"CLOUD_TEXT" else "CLOUD_AUDIO").put("experimental",true)
                .put("languages",org.json.JSONArray(if(hybrid)listOf("en-US","zh-CN")else listOf("en-US","zh-CN","ja-JP","ko-KR")))
                .put("purposes",org.json.JSONArray(if(profile=="fal-whisper")listOf("MEDIA_TRANSLATE")else listOf("MEDIA_TRANSLATE","CALL_TRANSLATE")))
                .put("outputs",org.json.JSONArray(if(profile=="fal-whisper")listOf("CAPTIONS")else listOf("CAPTIONS","SPEECH")))
                .put("requiresTextModel",hybrid || profile=="fal-whisper")
                .put("configured",if(hybrid)snapshot!=null else fal).put("routingGranted",routing).put("sourceTranscript",profile!="fal-kotoba")
                .put("asrResourcesReady",if(hybrid)languages.length()==2 else true).put("ttsResourcesReady",if(hybrid)ttsReady else true)
                .put("voices",org.json.JSONArray(if(hybrid)VoiceCatalog.voices.map{it.id}else if(profile=="fal-grok")AudioProfiles.grokVoices.keys.toList() else emptyList<String>())))
        }
        val resource=runtime.resources(owner)
        val permissions=JSONObject()
        for(name in listOf("RECORD_AUDIO","CAPTURE_AUDIO_OUTPUT","MODIFY_AUDIO_ROUTING","CALL_AUDIO_INTERCEPTION","POST_NOTIFICATIONS"))permissions.put(name,checkSelfPermission("android.permission.$name")==PackageManager.PERMISSION_GRANTED)
        val mode=getSystemService(android.media.AudioManager::class.java).mode
        val inCall=mode==android.media.AudioManager.MODE_IN_CALL || mode==android.media.AudioManager.MODE_IN_COMMUNICATION
        val common=mutableListOf<String>()
        if(snapshot==null)common.add("CONFIG_UNAVAILABLE")
        else {
            if(!snapshot.optBoolean("enabled") || !snapshot.optBoolean("audioEnabled"))common.add("POLICY_DISABLED")
            if(!authorizedConsumer(uid,snapshot))common.add("CONSUMER_NOT_AUTHORIZED")
        }
        if(resource.busy)common.add("RESOURCE_BUSY")
        if((uid==0 || uid==2000) && !AudioHostActivity.visible)common.add("HOST_REQUIRED")
        val availability=org.json.JSONArray()
        for(purpose in listOf("DICTATION","SPEAK","MEDIA_TRANSLATE","CALL_TRANSLATE")) {
            if(selectedPurpose.isNotEmpty() && selectedPurpose!=purpose)continue
            val media=purpose in listOf("MEDIA_TRANSLATE","CALL_TRANSLATE")
            val profiles=if(media)AudioProfiles.media else if(purpose=="DICTATION")AudioProfiles.dictation.keys else listOf("kokoro")
            for(profile in profiles) {
                if(purpose=="CALL_TRANSLATE" && profile=="fal-whisper")continue
                val supported=if(purpose=="DICTATION")AudioProfiles.dictation.getValue(profile).languages else if(media && profile!="local-hybrid")listOf("en-US","zh-CN","ja-JP","ko-KR")else listOf("en-US","zh-CN")
                val inputs=if(scoped && purpose=="DICTATION")listOf(scopedLanguage)else if(filter.has("language"))listOf(filter.getString("language"))else supported
                val outputs=if(!media)listOf("")else if(purpose=="CALL_TRANSLATE")listOf("SPEECH")else if(profile=="fal-whisper")listOf("CAPTIONS")else listOf("CAPTIONS","SPEECH")
                for(language in inputs)for(output in outputs) {
                    val reasons=common.toMutableList();val required=mutableListOf<String>()
                    val dictationStatus=if(purpose=="DICTATION") {
                        val statuses=if(scoped)dictationView.backends else dictationBackends.getOrPut(language){dictationResolver.inspect(language,snapshot ?: JSONObject())}
                        statuses.first{it.profileId==profile}
                    }else null
                    if(dictationStatus!=null)reasons.addAll(dictationStatus.unavailableReasons)
                    else if(language !in supported)reasons.add("UNSUPPORTED_LANGUAGE")
                    if(purpose=="DICTATION" || purpose=="CALL_TRANSLATE")required.add("RECORD_AUDIO")
                    if(media){required.add("CAPTURE_AUDIO_OUTPUT");required.add("MODIFY_AUDIO_ROUTING")}
                    if(purpose=="CALL_TRANSLATE")required.add("CALL_AUDIO_INTERCEPTION")
                    val missing=required.filter{!permissions.optBoolean(it)}
                    if(missing.isNotEmpty())reasons.add("PERMISSION_DENIED")
                    if(inCall && purpose!="CALL_TRANSLATE")reasons.add("CALL_IN_PROGRESS")
                    if(!inCall && purpose=="CALL_TRANSLATE")reasons.add("CALL_UNAVAILABLE")
                    val needsAsr=profile=="local-hybrid"
                    val needsTts=purpose=="SPEAK" || profile=="local-hybrid" && output=="SPEECH"
                    val modelReady=if(dictationStatus!=null)"MODEL_MISSING" !in dictationStatus.unavailableReasons else (!needsAsr || language in installedLanguages) && (!needsTts || ttsReady)
                    if(dictationStatus==null && language in supported && !modelReady)reasons.add("MODEL_MISSING")
                    val textConfigured=snapshot?.optBoolean("textTranslationConfigured")==true
                    val configured=if(profile in AudioProfiles.fal)fal else if(profile=="local-hybrid")textConfigured else true
                    if(dictationStatus==null && profile in AudioProfiles.fal && !fal)reasons.add("PROVIDER_NOT_CONFIGURED")
                    if(media && profile in listOf("local-hybrid","fal-whisper") && !textConfigured)reasons.add("TEXT_MODEL_NOT_CONFIGURED")
                    val row=JSONObject().put("purpose",purpose).put("profileId",profile).put("language",language).put("output",output)
                        .put("registered",true).put("configured",configured).put("modelResourcesReady",modelReady)
                        .put("available",reasons.isEmpty()).put("unavailableReasons",org.json.JSONArray(reasons)).put("missingPermissions",org.json.JSONArray(missing))
                        .put("requiredPermissions",org.json.JSONArray(required)).put("sourceChecked",false).put("requiresActiveSource",media)
                    if(dictationStatus!=null)row.put("processing",dictationStatus.processing).put("maxCaptureMs",dictationStatus.maxCaptureMs ?: JSONObject.NULL).put("drainTimeoutMs",dictationStatus.drainTimeoutMs)
                    availability.put(row)
                }
            }
        }
        val interruption=JSONObject().put("registered",true).put("experimental",true).put("echoCancelerAvailable",android.media.audiofx.AcousticEchoCanceler.isAvailable())
            .put("requiresMicrophone",true).put("readyRecognitionLanguages",languages).put("qualityVerified",false)
        return JSONObject().put("speechInterruption",interruption).put("availability",availability).put("permissions",permissions).put("consumerAuthorized",authorizedConsumer(uid,snapshot))
            .put("dictation",JSONObject().put("assistant-entry",dictationView.toJson()))
            .put("resourceState",JSONObject().put("busy",resource.busy).put("purpose",resource.purpose).put("profileId",resource.profileId).put("phase",resource.phase?.name ?: "Idle")
                .put("ownedByCaller",resource.ownedByCaller).put("previousRestoreConfirmed",resource.previousRestoreConfirmed ?: JSONObject.NULL))
            .put("availabilityScope","Local prerequisites only; source, remote authorization and connectivity are checked at open")
            .put("purposes",org.json.JSONArray().put("DICTATION").put("SPEAK").put("MEDIA_TRANSLATE").put("CALL_TRANSLATE"))
            .put("ttsResourcesReady",ttsReady).put("voices",voices).put("readingDefaults",snapshot?.optJSONObject("reading") ?: JSONObject()).put("systemRevision",snapshot?.optLong("systemRevision") ?: 0).put("audioRevision",snapshot?.optLong("audioRevision") ?: 0)
            .put("languages",languages).put("profileId","zipformer").put("processing","LOCAL_ONLY")
            .put("configured",snapshot != null).put("enabled",snapshot?.optBoolean("enabled") == true && snapshot?.optBoolean("audioEnabled") == true)
            .put("resourcesReady",languages.length()>0).put("falConfigured",fal).put("mediaRoutingGranted",routing)
            .put("falRealtimeAvailable",fal && routing).put("mediaProfiles",mediaProfiles)
            .put("callRouting",JSONObject(PlatformCallCapture.capabilities(this))).put("callExperimental",true)
            .put("defaultMediaProfile","fal-${snapshot?.optJSONObject("fal")?.optString("model","kotoba") ?: "kotoba"}")
    }
    private fun send(reply: Messenger, json: JSONObject) { try {
        reply.send(Message.obtain(null,1).apply { data = Bundle().apply { putString("json",json.toString()) } })
    } catch (_: RemoteException) { main.post { disconnect(reply.binder) } } }
    private fun disconnect(binder: IBinder) {
        pendingReads.remove(binder)?.forEach{it.operation.close()}
        clients.remove(binder)?.let { (owner,death) -> runtime.disconnect(owner); binder.unlinkToDeath(death,0) }
    }
    override fun onDestroy() { unregisterReceiver(hostClosed);cli.close();configuration.close(); runtime.revoke("SERVICE_STOPPED"); clients.keys.toList().forEach(::disconnect); super.onDestroy() }
}
