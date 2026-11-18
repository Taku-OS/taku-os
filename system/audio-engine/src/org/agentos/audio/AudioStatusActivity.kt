// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.app.Activity
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import org.json.JSONObject

/** Read-only capability inspection. Opening this page never opens an audio session. */
class AudioStatusActivity : Activity() {
    private lateinit var ui: AudioUi
    private val purposes=listOf("DICTATION","SPEAK","MEDIA_TRANSLATE","CALL_TRANSLATE")
    private val languages=listOf("zh-CN","en-US","ja-JP","ko-KR")
    private var client: AudioClient?=null
    private var request="";private var generation=0L
    private lateinit var purpose: Spinner
    private lateinit var language: Spinner
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui=AudioUi(this)
        val root=ui.page(ui.s("status_title"),ui.s("status_subtitle"),"audio_status_page",back=true)
        ui.section(root,ui.s("status_section_query"))
        val query=ui.card(root)
        ui.add(query,ui.text(ui.s("status_query_purpose"),13),bottom=6)
        purpose=Spinner(this).apply{
            contentDescription=ui.s("status_query_purpose")
            adapter=ui.adapter(listOf("status_purpose_dictation","status_purpose_speak","status_purpose_media","status_purpose_call").map{ui.s(it)})
            ui.styleSpinner(this)
            ui.add(query,this,bottom=16)
        }
        ui.add(query,ui.text(ui.s("status_query_language"),13),bottom=6)
        language=Spinner(this).apply{
            contentDescription=ui.s("status_query_language")
            adapter=ui.adapter(listOf("status_language_zh","status_language_en","status_language_ja","status_language_ko").map{ui.s(it)})
            ui.styleSpinner(this)
            ui.add(query,this)
        }
        ui.add(query,ui.text(ui.s("status_language_help"),12),top=10)
        ui.section(root,ui.s("status_section_current"))
        val current=ui.card(root)
        status=ui.text(ui.s("status_loading"),14,AudioUi.INK)
        ui.add(current,status,bottom=16)
        ui.action(current,ui.s("status_refresh"),primary=true){refresh()}
        ui.section(root,ui.s("status_section_availability"))
        results=ui.col()
        ui.add(root,results)
        ui.section(root,ui.s("status_section_system"))
        val system=ui.card(root)
        ui.navigation(system,ui.s("status_sound_settings"),ui.s("status_sound_settings_summary")){
            try{startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))}catch(_: android.content.ActivityNotFoundException){Toast.makeText(this,ui.s("status_volume_keys"),Toast.LENGTH_SHORT).show()}
        }
        ui.rule(system)
        ui.navigation(system,ui.s("status_app_permissions"),ui.s("status_app_permissions_summary")){
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))
        }
        val choices=object: AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?,view: android.view.View?,position: Int,id: Long){refresh()}
            override fun onNothingSelected(parent: AdapterView<*>?){}
        }
        purpose.onItemSelectedListener=choices;language.onItemSelectedListener=choices
    }
    override fun onStart(){
        super.onStart();val token=++generation
        client=AudioClient(this,object: AudioClient.Listener {
            override fun connected(){if(token==generation)refresh()}
            override fun failed(message: String){if(token==generation)status.text=message}
            override fun event(raw: String){
                if(token!=generation)return
                val response=JSONObject(raw)
                if(response.optString("type")!="response" || response.optString("id")!=request)return
                if(!response.optBoolean("ok")){status.text=response.optJSONObject("error")?.optString("message")?.takeIf{it.isNotBlank()} ?: ui.s("status_unavailable");return}
                render(response.getJSONObject("result"))
            }
        }).also{it.connect()}
    }
    private fun refresh(){
        if(client==null)return
        runCatching{request=client!!.call("audio.capabilities",JSONObject().put("purpose",purposes[purpose.selectedItemPosition]).put("language",languages[language.selectedItemPosition]))}
    }
    private fun render(snapshot: JSONObject) {
        val resource=snapshot.getJSONObject("resourceState")
        val manager=getSystemService(AudioManager::class.java)
        val current=manager.getStreamVolume(AudioManager.STREAM_MUSIC);val maximum=manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val purposeNames=mapOf("DICTATION" to ui.s("status_purpose_dictation"),"SPEAK" to ui.s("status_purpose_speak"),"MEDIA_TRANSLATE" to ui.s("status_purpose_media"),"CALL_TRANSLATE" to ui.s("status_purpose_call"),"LEGACY_REALTIME" to ui.s("status_purpose_realtime"))
        val phaseNames=mapOf("Preparing" to ui.s("status_phase_preparing"),"Ready" to ui.s("status_phase_ready"),"Running" to ui.s("status_phase_running"),"Draining" to ui.s("status_phase_draining"),"Stopping" to ui.s("status_phase_stopping"))
        val resourcePurpose=resource.optString("purpose")
        val resourcePhase=resource.optString("phase")
        status.text=ui.s(if(snapshot.optBoolean("enabled"))"status_enabled" else "status_paused")+"\n"+
            (if(resource.optBoolean("busy"))ui.s("status_resource_busy",purposeNames[resourcePurpose] ?: resourcePurpose.ifEmpty{ui.s("status_purpose_session")},phaseNames[resourcePhase] ?: resourcePhase.ifEmpty{ui.s("status_phase_processing")})else ui.s("status_resource_idle"))+
            "\n"+ui.s("status_media_volume",current,maximum)
        results.removeAllViews()
        if(!snapshot.optBoolean("configured"))ui.add(ui.card(results),ui.text(ui.s("status_config_unavailable"),13,AudioUi.ORANGE))
        if(resource.has("previousRestoreConfirmed") && !resource.isNull("previousRestoreConfirmed") && !resource.optBoolean("previousRestoreConfirmed"))ui.add(ui.card(results),ui.text(ui.s("status_restore_unconfirmed"),13,AudioUi.ORANGE))
        val entries=snapshot.getJSONArray("availability")
        val names=mapOf("zipformer" to ui.s("status_model_recognition"),"kokoro" to ui.s("status_model_reading"),"fal-kotoba" to ui.s("status_model_kotoba"),"fal-grok" to ui.s("status_model_grok"),"local-hybrid" to ui.s("status_model_local"),"fal-whisper" to ui.s("status_model_whisper"))
        val reasons=mapOf("CONFIG_UNAVAILABLE" to ui.s("status_reason_config"),"POLICY_DISABLED" to ui.s("status_paused"),"CONSUMER_NOT_AUTHORIZED" to ui.s("status_reason_authorization"),"HOST_REQUIRED" to ui.s("status_reason_host"),
            "RESOURCE_BUSY" to ui.s("status_reason_busy"),"CALL_IN_PROGRESS" to ui.s("status_reason_call_active"),"CALL_UNAVAILABLE" to ui.s("status_reason_call_missing"),"UNSUPPORTED_LANGUAGE" to ui.s("status_reason_language"),
            "MODEL_MISSING" to ui.s("status_reason_model"),"PROVIDER_NOT_CONFIGURED" to ui.s("status_reason_provider"),"TEXT_MODEL_NOT_CONFIGURED" to ui.s("status_reason_text_model"),"PERMISSION_DENIED" to ui.s("status_reason_permission"))
        for(i in 0 until entries.length()) {
            val entry=entries.getJSONObject(i)
            val output=when(entry.optString("output")){"CAPTIONS"->ui.s("status_output_captions");"SPEECH"->ui.s("status_output_speech");else->""}
            val issues=entry.getJSONArray("unavailableReasons")
            val available=entry.optBoolean("available")
            val description=if(available)ui.s("status_ready")else(0 until issues.length()).joinToString(ui.s("status_reason_separator")){reasons[issues.getString(it)] ?: issues.getString(it)}
            val card=ui.card(results)
            ui.add(card,ui.text("${names[entry.getString("profileId")] ?: entry.getString("profileId")}$output",15,AudioUi.INK,true),bottom=8)
            ui.add(card,ui.text(description,13,if(available)AudioUi.GREEN else AudioUi.ORANGE))
        }
        val notes=ui.card(results)
        ui.add(notes,ui.text(ui.s("status_start_checks"),12),bottom=12)
        val interruption=snapshot.optJSONObject("speechInterruption")
        ui.add(notes,ui.text(ui.s(if(interruption?.optBoolean("echoCancelerAvailable")==true)"status_interruptible_available" else "status_interruptible_unavailable"),12))
    }
    override fun onStop(){generation++;client?.close();client=null;super.onStop()}
}
