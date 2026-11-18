// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import org.json.JSONObject

/**
 * Configuration surface for media/call translation. The session itself lives in
 * [MediaTranslationController]; finishing or recreating this page must not stop it.
 */
class MediaTranslationActivity : Activity(), MediaTranslationController.StateListener {
    private lateinit var ui: AudioUi
    private lateinit var overlayToggle: TextView
    private lateinit var prefs: SharedPreferences
    private var call=false
    private var appliedRequest: MediaTranslationController.Request?=null
    private var defaultsLoaded=false
    private lateinit var outgoingProfile: Spinner
    private lateinit var outgoingVoice: Spinner
    private var outgoingVoiceIds=listOf("")
    private lateinit var disclosure: TextView
    private var voiceIds=listOf("")
    private lateinit var status: TextView
    private lateinit var start: TextView
    private lateinit var target: Spinner
    private lateinit var sourceLanguage: Spinner
    private lateinit var targetLanguage: Spinner
    private lateinit var profile: Spinner
    private lateinit var voice: Spinner
    private lateinit var speech: CheckBox
    private lateinit var replace: CheckBox
    private val packages=mutableListOf<String>()
    private val languages=listOf("en-US","zh-CN","ja-JP","ko-KR")
    private fun startLabel()=ui.s(if(call)"media_start_call" else "media_start_video")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui=AudioUi(this)
        call=MediaTranslationController.activeRequest()?.call ?: (intent.getStringExtra("page")=="call")
        prefs=if(call)getPreferences(0)else getSharedPreferences("media-translation",0).also { saved ->
            // Keep the app/languages selected before the home shortcut was introduced.
            val previous=getPreferences(0)
            if(!saved.contains("target") && previous.contains("target")) {
                val edit=saved.edit().putString("target",previous.getString("target",""))
                    .putInt("sourceLanguage",previous.getInt("sourceLanguage",0)).putInt("targetLanguage",previous.getInt("targetLanguage",1))
                previous.all.filterKeys{it.startsWith("voice-")}.forEach{(key,value)->if(value is String)edit.putString(key,value)}
                edit.apply()
            }
        }
        val root=ui.page(
            ui.s(if(call)"media_call_title" else "media_video_title"),
            ui.s(if(call)"media_call_subtitle" else "media_video_subtitle"),
            if(call)"audio_call_page" else "audio_media_page",
            back=true
        )
        val apps=packageManager.getInstalledApplications(0).filter{it.uid!=applicationInfo.uid && (packageManager.getLaunchIntentForPackage(it.packageName)!=null || it.flags and ApplicationInfo.FLAG_SYSTEM==0)}.sortedBy{packageManager.getApplicationLabel(it).toString()}
        packages.addAll(apps.map{it.packageName})
        ui.section(root,ui.s("media_section_source"))
        val sourceCard=ui.card(root)
        if(call)ui.add(sourceCard,ui.text(ui.s("media_call_instructions"),13),bottom=12)
        target=spinner(sourceCard,ui.s("media_target_app"),apps.map{"${packageManager.getApplicationLabel(it)} · ${it.packageName}"})
        packages.indexOf(prefs.getString("target","")).takeIf{it>=0}?.let{target.setSelection(it)}
        val languageLabels=listOf("media_language_en","media_language_zh","media_language_ja","media_language_ko").map{ui.s(it)}
        sourceLanguage=spinner(sourceCard,ui.s(if(call)"media_other_language" else "media_source_language"),languageLabels)
        targetLanguage=spinner(sourceCard,ui.s(if(call)"media_my_language" else "media_target_language"),languageLabels)
        sourceLanguage.setSelection(prefs.getInt("sourceLanguage",0));targetLanguage.setSelection(prefs.getInt("targetLanguage",1))

        ui.section(root,ui.s(if(call)"media_section_incoming" else "media_section_model"))
        val modelCard=ui.card(root)
        val models=listOf(ui.s("media_model_kotoba"),ui.s("media_model_grok"),ui.s("media_model_local"))
        profile=spinner(modelCard,ui.s(if(call)"media_incoming_model" else "media_model"),if(call)models else models+ui.s("media_model_whisper"))
        voice=spinner(modelCard,ui.s("media_voice"),listOf(ui.s("media_voice_default")))
        val outgoing=ui.col().apply{visibility=if(call)View.VISIBLE else View.GONE}
        ui.add(root,outgoing)
        ui.section(outgoing,ui.s("media_section_outgoing"))
        val outgoingCard=ui.card(outgoing)
        outgoingProfile=spinner(outgoingCard,ui.s("media_outgoing_model"),models)
        outgoingVoice=spinner(outgoingCard,ui.s("media_outgoing_voice"),listOf(ui.s("media_voice_default")))

        ui.section(root,ui.s("media_section_output"))
        val outputCard=ui.card(root)
        speech=CheckBox(this).apply{text=ui.s("media_play_speech");ui.tint(this);ui.add(outputCard,this)}
        replace=CheckBox(this).apply{text=ui.s("media_replace_audio");isEnabled=false;ui.tint(this);ui.add(outputCard,this)}
        speech.setOnCheckedChangeListener{_,checked->replace.isEnabled=!MediaTranslationController.isActive() && checked;if(!checked)replace.isChecked=false}
        ui.add(outputCard,ui.text(ui.s("media_voice_help"),12),top=8)

        ui.section(root,ui.s("media_section_privacy"))
        disclosure=ui.text("",13)
        ui.add(ui.card(root),disclosure)
        val choices=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: View?,position: Int,itemId: Long){updateVoices()}
            override fun onNothingSelected(parent: AdapterView<*>?){}
        }
        profile.onItemSelectedListener=choices;targetLanguage.onItemSelectedListener=choices
        outgoingProfile.onItemSelectedListener=choices;sourceLanguage.onItemSelectedListener=choices;updateVoices()
        if(call){speech.isChecked=true;replace.isChecked=true;speech.isEnabled=false;replace.isEnabled=false}
        else if(!MediaTranslationController.isActive())restoreOutput(intent.getBooleanExtra("quickStart",false))

        ui.section(root,ui.s("media_section_session"))
        val sessionCard=ui.card(root)
        status=ui.text("",14,AudioUi.INK)
        ui.add(sessionCard,status,bottom=14)
        start=ui.action(sessionCard,startLabel(),primary=true){begin()}
        ui.action(sessionCard,ui.s("media_stop")){MediaTranslationController.stop()}
        overlayToggle=ui.action(sessionCard,""){toggleOverlay()}
        updateOverlayToggle()
        MediaTranslationController.activeRequest()?.let{applyRequest(it);appliedRequest=it}
        Thread({
            val defaults=runCatching{EngineConfig.control(JSONObject().put("op","read")).optJSONObject("fal")?.optString("model")}.getOrNull()
            runOnUiThread {
                if(isDestroyed)return@runOnUiThread
                defaultsLoaded=true
                val active=MediaTranslationController.activeRequest()
                if(active!=null) {
                    if(active.call!=call){recreate();return@runOnUiThread}
                    if(active!=appliedRequest){applyRequest(active);appliedRequest=active}
                } else {
                    profile.setSelection(if(defaults=="grok")1 else if(defaults=="whisper" && !call)3 else 0)
                    if(intent.getBooleanExtra("quickStart",false))quickStart()
                }
            }
        },"audio-media-settings").start()
    }

    override fun onStart() {
        super.onStart()
        MediaTranslationController.attach(this)
    }

    override fun onResume() {
        super.onResume()
        updateOverlayToggle()
        OverlayHost.setSessionVisible(false)
        if(OverlayPreference.enabled(this)) {
            OverlayHost.setEntrySuppressed(true)
            runCatching{startService(Intent(this,TranslationOverlayService::class.java))}
        }
        onTranslationState(MediaTranslationController.snapshot())
    }

    override fun onStop() {
        MediaTranslationController.detach(this)
        if(MediaTranslationController.isActive()) {
            OverlayHost.setEntrySuppressed(false)
            OverlayHost.setSessionVisible(true)
        } else if(OverlayPreference.enabled(this) && Settings.canDrawOverlays(this)) {
            OverlayHost.setEntrySuppressed(false);OverlayHost.showEntry(this)
        } else {
            OverlayHost.setEntrySuppressed(false);OverlayHost.closeEntry()
        }
        super.onStop()
    }

    override fun onDestroy() {
        MediaTranslationController.detach(this)
        super.onDestroy()
    }

    override fun onTranslationState(snapshot: MediaTranslationController.Snapshot) {
        if(isDestroyed)return
        runOnUiThread {
            if(isDestroyed)return@runOnUiThread
            val request=snapshot.request
            if(snapshot.active && request!=null) {
                if(request.call!=call){recreate();return@runOnUiThread}
                if(request!=appliedRequest){applyRequest(request);appliedRequest=request}
            } else appliedRequest=null
            status.text=snapshot.status
            start.text=if(snapshot.active)ui.s("media_active") else startLabel()
            controls(!snapshot.active)
            updateOverlayToggle()
        }
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        val active=MediaTranslationController.activeRequest()
        if(active!=null) {
            setIntent(next)
            if(active.call!=call){recreate();return}
            if(next.getBooleanExtra("quickStart",false))quickStart()
            return
        }
        if((next.getStringExtra("page")=="call")!=call) {
            setIntent(next);recreate();return
        }
        setIntent(next)
        if(next.getBooleanExtra("quickStart",false))quickStart()
    }

    private fun profileIndex(id: String,count: Int)=when(id){
        "fal-grok"->1;"local-hybrid"->2;"fal-whisper"->3;else->0
    }.coerceIn(0,maxOf(0,count-1))

    /** Rebuild the disabled controls from the controller's actual session request. */
    private fun applyRequest(request: MediaTranslationController.Request) {
        packages.indexOf(request.targetPackage).takeIf{it>=0}?.let{target.setSelection(it)}
        languages.indexOf(request.language).takeIf{it>=0}?.let{sourceLanguage.setSelection(it)}
        languages.indexOf(request.targetLanguage).takeIf{it>=0}?.let{targetLanguage.setSelection(it)}
        profile.setSelection(profileIndex(request.profileId,profile.adapter.count))
        if(call)outgoingProfile.setSelection(profileIndex(request.outgoingProfileId,outgoingProfile.adapter.count))
        updateVoices()
        voiceIds.indexOf(request.voiceId).takeIf{it>=0}?.let{voice.setSelection(it)}
        if(call)outgoingVoiceIds.indexOf(request.outgoingVoiceId).takeIf{it>=0}?.let{outgoingVoice.setSelection(it)}
        if(call) {
            speech.isChecked=true;replace.isChecked=true
        } else {
            val speechOn=request.output=="SPEECH"
            speech.isChecked=speechOn
            replace.isChecked=speechOn && request.originalAudio=="REPLACE"
        }
    }

    private fun updateOverlayToggle() {
        if(!::overlayToggle.isInitialized)return
        overlayToggle.text=ui.s(if(OverlayPreference.enabled(this))"media_overlay_disable" else "media_overlay_enable")
    }

    private fun toggleOverlay() {
        val enable=!OverlayPreference.enabled(this)
        if(enable && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))
            status.text=ui.s("media_overlay_permission_enable");return
        }
        if(enable) {
            OverlayPreference.set(this,true)
            OverlayHost.setEntrySuppressed(true)
            runCatching{startService(Intent(this,TranslationOverlayService::class.java))}
            status.text=ui.s("media_overlay_enabled")
        } else {
            MediaTranslationController.closeOverlay(this)
            status.text=ui.s("media_overlay_disabled")
        }
        updateOverlayToggle()
    }

    private fun restoreOutput(quick: Boolean) {
        val captions=profile.selectedItemPosition==3
        speech.isChecked=!captions && prefs.getBoolean("speech",quick)
        replace.isChecked=speech.isChecked && prefs.getBoolean("replace",quick)
    }

    private fun quickStart() {
        if(call)return
        if(MediaTranslationController.isActive()){MediaTranslationController.bringSourceToFront();return}
        if(!defaultsLoaded)return
        val targetIndex=packages.indexOf(prefs.getString("target",""))
        if(targetIndex<0){status.text=ui.s("media_first_use");return}
        target.setSelection(targetIndex)
        sourceLanguage.setSelection(prefs.getInt("sourceLanguage",0));targetLanguage.setSelection(prefs.getInt("targetLanguage",1))
        profile.setSelection(prefs.getInt("profile",profile.selectedItemPosition))
        updateVoices();restoreOutput(true)
        start.post{if(!isDestroyed && !MediaTranslationController.isActive())begin()}
    }

    private fun spinner(parent: LinearLayout,label: String,items: List<String>): Spinner {
        ui.add(parent,ui.text(label,13),top=8,bottom=6)
        return Spinner(this).apply{
            contentDescription=label
            adapter=ui.adapter(items)
            ui.styleSpinner(this)
            ui.add(parent,this,bottom=8)
        }
    }

    private fun controls(enabled: Boolean) {
        start.isEnabled=enabled
        for(view in listOf(target,profile,outgoingProfile,sourceLanguage,targetLanguage))view.isEnabled=enabled
        voice.isEnabled=enabled && profile.selectedItemPosition in 1..2
        speech.isEnabled=enabled && !call && profile.selectedItemPosition!=3
        replace.isEnabled=enabled && speech.isChecked && !call
        outgoingVoice.isEnabled=enabled && outgoingProfile.selectedItemPosition!=0
    }

    private fun updateVoices() {
        val active=MediaTranslationController.isActive()
        val kind=profile.selectedItemPosition
        val target=languages[targetLanguage.selectedItemPosition]
        val local=VoiceCatalog.voices.filter{it.language==target}
        voiceIds=when(kind){1->AudioProfiles.grokVoices.keys.toList();2->listOf("")+local.map{it.id};else->listOf("")}
        val labels=when(kind){1->AudioProfiles.grokVoices.values.toList();2->listOf(ui.s("media_voice_follow_reading"))+local.map{it.label};else->listOf(ui.s("media_voice_default"))}
        voice.adapter=ui.adapter(labels)
        voiceIds.indexOf(prefs.getString("voice-$kind-$target","")).takeIf{it>=0}?.let{voice.setSelection(it)}
        voice.isEnabled=!active && kind in 1..2
        if(::speech.isInitialized && !call){if(kind==3){speech.isChecked=false;replace.isChecked=false};speech.isEnabled=!active && kind!=3;replace.isEnabled=!active && kind!=3 && speech.isChecked}
        val outKind=outgoingProfile.selectedItemPosition
        val outLocal=VoiceCatalog.voices.filter{it.language==languages[sourceLanguage.selectedItemPosition]}
        outgoingVoiceIds=when(outKind){1->AudioProfiles.grokVoices.keys.toList();2->listOf("")+outLocal.map{it.id};else->listOf("")}
        val outLabels=when(outKind){1->AudioProfiles.grokVoices.values.toList();2->listOf(ui.s("media_voice_follow_reading"))+outLocal.map{it.label};else->listOf(ui.s("media_voice_default"))}
        outgoingVoice.adapter=ui.adapter(outLabels)
        outgoingVoice.isEnabled=!active && outKind!=0
        disclosure.text=ui.s(if(call) {
            if(kind==2 && outKind==2)"media_disclosure_call_local" else "media_disclosure_call_cloud"
        } else when(kind) {
            3->"media_disclosure_whisper"
            2->"media_disclosure_local"
            else->"media_disclosure_cloud"
        })
    }

    private fun begin() {
        if(MediaTranslationController.isActive()){onTranslationState(MediaTranslationController.snapshot());return}
        if(packages.isEmpty())return
        if(sourceLanguage.selectedItemPosition==targetLanguage.selectedItemPosition){status.text=ui.s("media_different_languages");return}
        if(!Settings.canDrawOverlays(this)){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));status.text=ui.s("media_overlay_permission_start");return}
        val sourcePackage=packages[target.selectedItemPosition]
        val source=languages[sourceLanguage.selectedItemPosition];val dest=languages[targetLanguage.selectedItemPosition]
        val grok=profile.selectedItemPosition==1
        val hybrid=profile.selectedItemPosition==2
        val whisper=profile.selectedItemPosition==3
        if((hybrid || call && outgoingProfile.selectedItemPosition==2) && (source !in listOf("en-US","zh-CN") || dest !in listOf("en-US","zh-CN"))){status.text=ui.s("media_local_languages");return}
        val voiceId=voiceIds[voice.selectedItemPosition]
        val playSpeech=speech.isChecked;val replaceOriginal=replace.isChecked
        val providerLabel=ui.s(if(whisper)"media_model_whisper" else if(hybrid)"media_model_local" else if(grok)"media_model_grok" else "media_model_kotoba")
        prefs.edit().putString("target",sourcePackage).putString("voice-${profile.selectedItemPosition}-$dest",voiceId)
            .putInt("sourceLanguage",sourceLanguage.selectedItemPosition).putInt("targetLanguage",targetLanguage.selectedItemPosition)
            .apply{if(!call)putInt("profile",profile.selectedItemPosition).putBoolean("speech",playSpeech).putBoolean("replace",replaceOriginal)}.apply()
        controls(false)
        val request=MediaTranslationController.Request(
            call=call,
            targetPackage=sourcePackage,
            language=source,
            targetLanguage=dest,
            profileId=if(whisper)"fal-whisper" else if(hybrid)"local-hybrid" else if(grok)"fal-grok" else "fal-kotoba",
            voiceId=voiceId,
            output=if(playSpeech)"SPEECH" else "CAPTIONS",
            originalAudio=if(replaceOriginal)"REPLACE" else "KEEP",
            outgoingProfileId=if(call)when(outgoingProfile.selectedItemPosition){1->"fal-grok";2->"local-hybrid";else->"fal-kotoba"} else "",
            outgoingVoiceId=if(call)outgoingVoiceIds[outgoingVoice.selectedItemPosition] else "",
            providerLabel=providerLabel,
            title=if(call)ui.s("media_call_session_title") else ui.s("media_video_session_title",targetLanguage.selectedItem)
        )
        val started=runCatching{MediaTranslationController.start(this,request)}.getOrDefault(false)
        if(!started)onTranslationState(MediaTranslationController.snapshot())
    }
}
