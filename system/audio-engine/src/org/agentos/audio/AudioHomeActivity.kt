// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.app.Activity
import android.app.AlertDialog
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

class AudioHomeActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var snapshot: AudioSettingsSnapshot? = null
    private var busy = false
    private var rendering = false
    private var failure: AudioSettingsFailure? = null
    private var notice = ""
    private var keyDialog: AlertDialog? = null
    private lateinit var state: TextView
    private lateinit var globalState: TextView
    private lateinit var audioEnabled: Switch
    private lateinit var microphone: TextView
    private lateinit var falState: TextView
    private lateinit var saveKey: TextView
    private lateinit var removeKey: TextView
    private lateinit var model: TextView
    private lateinit var liveProvider: TextView
    private lateinit var liveService: TextView
    private lateinit var geminiState: TextView
    private lateinit var saveGeminiKey: TextView
    private lateinit var removeGeminiKey: TextView
    private lateinit var mossState: TextView
    private lateinit var saveMossKey: TextView
    private lateinit var removeMossKey: TextView
    private lateinit var standby: Switch
    private lateinit var lockWake: Switch
    private lateinit var standbyTimeouts: TextView
    private lateinit var refresh: TextView
    private lateinit var ui: AudioUi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = AudioUi(this)
        val root = ui.page(ui.s("audio_home_title"), ui.s("audio_home_subtitle"), "audio_home_page")

        ui.section(root, ui.s("audio_home_settings_section"))
        val settings = ui.card(root)
        state = ui.text("", 13).apply {
            id = ui.id("audio_settings_state")
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(settings, state, bottom = 12)
        refresh = ui.action(settings, ui.s("audio_home_refresh"), "audio_refresh") { request(AudioSettingsCommand.Read) }

        ui.section(root, ui.s("audio_home_voice_section"))
        val voice = ui.card(root)
        audioEnabled = ui.toggle(voice, ui.s("audio_home_enable_voice"), ui.s("audio_home_enable_summary"), "audio_enabled") { enabled ->
            if (!rendering) request(AudioSettingsCommand.SetAudioEnabled(enabled))
        }
        ui.rule(voice)
        globalState = ui.text("", 12)
        ui.add(voice, globalState, 14)
        microphone = ui.action(voice, "", "audio_microphone_permission") {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ||
                (getPreferences(MODE_PRIVATE).getBoolean("microphoneAsked", false) &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO))) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10)
            }
        }

        ui.section(root, ui.s("audio_home_fal_section"))
        val fal = ui.card(root)
        falState = ui.text("", 13, AudioUi.INK).apply { id = ui.id("audio_fal_state") }
        ui.add(fal, falState, bottom = 16)
        saveKey = ui.action(fal, ui.s("audio_home_add_key"), "audio_save_fal_key", primary = true) { editKey(AudioLiveProvider.GROK) }
        removeKey = ui.action(fal, ui.s("audio_home_remove_key"), "audio_remove_fal_key") { confirmRemoveKey(AudioLiveProvider.GROK) }
        model = ui.action(fal, ui.s("audio_home_default_model"), "audio_model") { selectModel() }
        ui.add(fal, ui.text(ui.s("audio_home_fal_summary"), 12), 4)

        ui.section(root, ui.s("audio_home_live_section"))
        val live = ui.card(root)
        liveProvider = ui.action(live, ui.s("audio_home_live_provider"), "audio_live_provider") { selectLiveProvider() }
        liveService = ui.text("", 12)
        ui.add(live, liveService, bottom = 4)
        ui.add(live, ui.text(ui.s("audio_home_live_next"), 12), bottom = 14)
        ui.rule(live)
        geminiState = ui.text("", 13, AudioUi.INK).apply { id = ui.id("audio_gemini_state") }
        ui.add(live, geminiState, 14)
        saveGeminiKey = ui.action(live, ui.s("audio_home_add_gemini_key"), "audio_save_gemini_key", primary = true) { editKey(AudioLiveProvider.GEMINI) }
        removeGeminiKey = ui.action(live, ui.s("audio_home_remove_gemini_key"), "audio_remove_gemini_key") { confirmRemoveKey(AudioLiveProvider.GEMINI) }
        ui.rule(live)
        mossState = ui.text("", 13, AudioUi.INK).apply { id = ui.id("audio_moss_state") }
        ui.add(live, mossState, 14)
        saveMossKey = ui.action(live, ui.s("audio_home_add_moss_key"), "audio_save_moss_key", primary = true) { editKey(AudioLiveProvider.MOSS) }
        removeMossKey = ui.action(live, ui.s("audio_home_remove_moss_key"), "audio_remove_moss_key") { confirmRemoveKey(AudioLiveProvider.MOSS) }
        ui.rule(live)
        standby = ui.toggle(live, ui.s("audio_home_live_standby"), ui.s("audio_home_live_standby_summary"), "audio_live_standby") { enabled ->
            if (!rendering) snapshot?.live?.standby?.let { request(AudioSettingsCommand.ConfigureLiveStandby(it.copy(enabled = enabled))) }
        }
        lockWake = ui.toggle(live, ui.s("audio_home_live_lock_wake"), ui.s("audio_home_live_lock_wake_summary"), "audio_live_lock_wake") { enabled ->
            if (!rendering) snapshot?.live?.standby?.let { request(AudioSettingsCommand.ConfigureLiveStandby(it.copy(lockScreenWakeEnabled = enabled))) }
        }
        standbyTimeouts = ui.action(live, ui.s("audio_home_live_standby_timeouts"), "audio_live_standby_timeouts") { selectStandbyTimeouts() }

        ui.section(root, ui.s("audio_home_features_section"))
        val features = ui.card(root)
        ui.navigation(features, ui.s("audio_home_dictation"), ui.s("audio_home_dictation_summary"), "audio_dictation") { openPage("dictation") }
        ui.rule(features)
        ui.navigation(features, ui.s("audio_home_media"), ui.s("audio_home_media_summary"), "audio_media") { openPage("media") }
        ui.rule(features)
        ui.navigation(features, ui.s("audio_home_call"), ui.s("audio_home_call_summary"), "audio_call") { openPage("call") }
        ui.rule(features)
        ui.navigation(features, ui.s("audio_home_reading"), ui.s("audio_home_reading_summary"), "audio_reading") { openPage("reading") }
        ui.rule(features)
        ui.navigation(features, ui.s("audio_home_status"), ui.s("audio_home_status_summary"), "audio_status") { openPage("status") }
        render()
        if (savedInstanceState == null) intent.getStringExtra("page")?.let { openPage(it) }
    }

    override fun onResume() {
        super.onResume()
        render()
        if (!busy && failure == null) request(AudioSettingsCommand.Read)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (grantResults.isNotEmpty()) getPreferences(MODE_PRIVATE).edit().putBoolean("microphoneAsked", true).apply()
            render()
        }
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        setIntent(next)
        next.getStringExtra("page")?.let { openPage(it) }
    }

    private fun openPage(page: String) {
        val destination = when (page) {
            "dictation" -> Intent(this, DictationSettingsActivity::class.java)
            "media", "call" -> Intent(this, MediaTranslationActivity::class.java).putExtra("page", page)
            "reading" -> Intent(this, AudioSettingsActivity::class.java)
            "status" -> Intent(this, AudioStatusActivity::class.java)
            else -> return
        }
        startActivity(destination)
    }

    private fun request(command: AudioSettingsCommand) {
        if (busy || isFinishing || isDestroyed) return
        busy = true
        failure = null
        notice = ui.s(if (command === AudioSettingsCommand.Read) "audio_home_loading" else "audio_home_saving")
        render()
        io.execute {
            try {
                val next = AudioSettings.execute(applicationContext, command)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    snapshot = next
                    notice = ui.s(if (command === AudioSettingsCommand.Read) "audio_home_refreshed" else "audio_home_saved")
                    render()
                }
            } catch (error: AudioSettingsFailure) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    snapshot = null
                    failure = error
                    notice = ui.s("audio_home_retry_notice")
                    render()
                }
            }
        }
    }

    private fun render() {
        val current = snapshot
        val editable = current != null && !busy && failure == null
        rendering = true
        state.text = failure?.let { "${it.message}\n$notice" } ?: notice
        state.setTextColor(if (failure != null) AudioUi.ORANGE else AudioUi.MUTED)
        refresh.isEnabled = !busy
        audioEnabled.isChecked = current?.audioEnabled == true
        audioEnabled.isEnabled = editable
        val microphoneGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphone.text = ui.s(if (microphoneGranted) "audio_home_microphone_granted" else "audio_home_microphone_allow")
        globalState.text = when {
            current == null -> ui.s("audio_home_global_pending")
            !current.enabled -> ui.s("audio_home_system_paused")
            current.audioEnabled && !microphoneGranted -> ui.s("audio_home_microphone_required")
            current.audioEnabled -> ui.s("audio_home_voice_enabled")
            else -> ui.s("audio_home_voice_paused")
        }
        falState.text = current?.fal?.let { fal ->
            val status = if (fal.configured) ui.s("audio_home_key_saved", fal.masked ?: ui.s("audio_home_key_hidden")) else ui.s("audio_home_key_missing")
            val problem = when (fal.error) {
                AudioFalError.KEY_UNREADABLE -> "\n" + ui.s("audio_home_key_unreadable")
                AudioFalError.MODEL_UNREADABLE -> "\n" + ui.s("audio_home_model_unreadable")
                null -> ""
            }
            status + problem
        } ?: ui.s("audio_home_key_pending")
        saveKey.text = ui.s(if (current?.fal?.configured == true || current?.fal?.error == AudioFalError.KEY_UNREADABLE) "audio_home_replace_key" else "audio_home_add_key")
        saveKey.isEnabled = editable
        removeKey.isEnabled = editable && (current?.fal?.configured == true || current?.fal?.error == AudioFalError.KEY_UNREADABLE)
        model.text = current?.fal?.let { fal -> ui.s("audio_home_default_model_value", fal.models.firstOrNull { it.id == fal.model }?.name ?: fal.model) } ?: ui.s("audio_home_default_model")
        model.isEnabled = editable && current?.fal?.let { it.configured && it.models.isNotEmpty() } == true
        val live = current?.live
        liveProvider.text = live?.let { ui.s("audio_home_live_provider_value", providerName(it.provider)) } ?: ui.s("audio_home_live_provider")
        liveProvider.isEnabled = editable
        liveService.text = when (live?.provider) {
            AudioLiveProvider.GROK -> ui.s(if (live.configured) "audio_home_live_grok_summary" else "audio_home_live_grok_missing")
            AudioLiveProvider.GEMINI -> ui.s(if (live.configured) "audio_home_live_gemini_summary" else "audio_home_live_gemini_missing")
            AudioLiveProvider.MOSS -> ui.s(if (live.configured) "audio_home_live_moss_summary" else "audio_home_live_moss_missing")
            null -> ui.s("audio_home_live_pending")
        }
        val gemini = live?.gemini
        val geminiHasKey = gemini?.configured == true || gemini?.error == AudioGeminiError.KEY_UNREADABLE
        geminiState.text = when {
            gemini == null -> ui.s("audio_home_gemini_key_pending")
            gemini.configured -> ui.s("audio_home_gemini_key_saved", gemini.masked ?: ui.s("audio_home_key_hidden"))
            gemini.error == AudioGeminiError.KEY_UNREADABLE -> ui.s("audio_home_key_unreadable")
            else -> ui.s("audio_home_gemini_key_missing")
        }
        saveGeminiKey.text = ui.s(if (geminiHasKey) "audio_home_replace_gemini_key" else "audio_home_add_gemini_key")
        saveGeminiKey.isEnabled = editable
        removeGeminiKey.isEnabled = editable && geminiHasKey
        val moss = live?.moss
        val mossHasKey = moss?.configured == true || moss?.error == AudioGeminiError.KEY_UNREADABLE
        mossState.text = when {
            moss == null -> ui.s("audio_home_moss_key_pending")
            moss.configured -> ui.s("audio_home_moss_key_saved", moss.masked ?: ui.s("audio_home_key_hidden"))
            moss.error == AudioGeminiError.KEY_UNREADABLE -> ui.s("audio_home_key_unreadable")
            else -> ui.s("audio_home_moss_key_missing")
        }
        saveMossKey.text = ui.s(if (mossHasKey) "audio_home_replace_moss_key" else "audio_home_add_moss_key")
        saveMossKey.isEnabled = editable
        removeMossKey.isEnabled = editable && mossHasKey
        val standbySettings = live?.standby
        standby.isChecked = standbySettings?.enabled == true
        standby.isEnabled = editable && standbySettings != null
        lockWake.isChecked = standbySettings?.lockScreenWakeEnabled == true
        lockWake.isEnabled = editable && standbySettings?.enabled == true
        standbyTimeouts.text = standbySettings?.let { ui.s("audio_home_live_standby_timeouts_value", it.hotTimeoutSec, it.coldTimeoutSec) }
            ?: ui.s("audio_home_live_standby_timeouts")
        standbyTimeouts.isEnabled = editable && standbySettings?.enabled == true
        rendering = false
    }

    private fun editKey(provider: AudioLiveProvider) {
        if (busy || snapshot == null || keyDialog != null) return
        val hint = ui.s(when (provider) {
            AudioLiveProvider.GEMINI -> "audio_home_gemini_key_hint"
            AudioLiveProvider.MOSS -> "audio_home_moss_key_hint"
            AudioLiveProvider.GROK -> "audio_home_key_hint"
        })
        keyDialog = ui.keyDialog(
            title = when (provider) { AudioLiveProvider.GEMINI -> saveGeminiKey; AudioLiveProvider.MOSS -> saveMossKey; AudioLiveProvider.GROK -> saveKey }.text.toString(),
            privacy = ui.s("audio_home_key_privacy"),
            hint = hint,
            required = hint,
            cancel = ui.s("audio_home_cancel"),
            save = ui.s("audio_home_save"),
            inputId = when (provider) { AudioLiveProvider.GEMINI -> "audio_gemini_key_input"; AudioLiveProvider.MOSS -> "audio_moss_key_input"; AudioLiveProvider.GROK -> "audio_fal_key_input" },
            onDismiss = { keyDialog = null }
        ) { value -> request(when (provider) {
            AudioLiveProvider.GEMINI -> AudioSettingsCommand.SaveLiveKey(value)
            AudioLiveProvider.MOSS -> AudioSettingsCommand.SaveMossKey(value)
            AudioLiveProvider.GROK -> AudioSettingsCommand.SaveFalKey(value)
        }) }
    }

    private fun confirmRemoveKey(provider: AudioLiveProvider) {
        if (busy || snapshot == null) return
        val prefix = when (provider) { AudioLiveProvider.GEMINI -> "audio_home_remove_gemini_key"; AudioLiveProvider.MOSS -> "audio_home_remove_moss_key"; AudioLiveProvider.GROK -> "audio_home_remove_key" }
        AlertDialog.Builder(this).setTitle(ui.s("${prefix}_title"))
            .setMessage(ui.s("${prefix}_summary"))
            .setNegativeButton(ui.s("audio_home_cancel"), null).setPositiveButton(ui.s("audio_home_remove")) { _, _ ->
                request(when (provider) {
                    AudioLiveProvider.GEMINI -> AudioSettingsCommand.RemoveLiveKey
                    AudioLiveProvider.MOSS -> AudioSettingsCommand.RemoveMossKey
                    AudioLiveProvider.GROK -> AudioSettingsCommand.RemoveFalKey
                })
            }.show()
    }

    private fun providerName(provider: AudioLiveProvider): String = ui.s(when (provider) {
        AudioLiveProvider.GROK -> "audio_home_live_grok"
        AudioLiveProvider.GEMINI -> "audio_home_live_gemini"
        AudioLiveProvider.MOSS -> "audio_home_live_moss"
    })

    private fun selectLiveProvider() {
        val selected = snapshot?.live?.provider ?: return
        if (busy) return
        val providers = AudioLiveProvider.entries
        AlertDialog.Builder(this).setTitle(ui.s("audio_home_live_provider"))
            .setSingleChoiceItems(providers.map(::providerName).toTypedArray(), providers.indexOf(selected)) { dialog, index ->
                dialog.dismiss()
                if (providers[index] != selected) request(AudioSettingsCommand.SelectLiveProvider(providers[index]))
            }.setNegativeButton(ui.s("audio_home_cancel"), null).show()
    }

    /** Hot keeps the provider connected; cold disconnects and listens on the device until you speak. */
    private fun selectStandbyTimeouts() {
        val current = snapshot?.live?.standby ?: return
        if (busy) return
        val choices = listOf(10 to 30, 15 to 60, 30 to 120, 60 to 300)
        AlertDialog.Builder(this).setTitle(ui.s("audio_home_live_standby_timeouts"))
            .setSingleChoiceItems(choices.map { (hot, cold) -> ui.s("audio_home_live_standby_timeouts_value", hot, cold) }.toTypedArray(),
                choices.indexOf(current.hotTimeoutSec to current.coldTimeoutSec)) { dialog, index ->
                dialog.dismiss()
                val (hot, cold) = choices[index]
                if (hot != current.hotTimeoutSec || cold != current.coldTimeoutSec)
                    request(AudioSettingsCommand.ConfigureLiveStandby(current.copy(hotTimeoutSec = hot, coldTimeoutSec = cold)))
            }.setNegativeButton(ui.s("audio_home_cancel"), null).show()
    }

    private fun selectModel() {
        val fal = snapshot?.fal ?: return
        if (busy || !fal.configured) return
        AlertDialog.Builder(this).setTitle(ui.s("audio_home_default_model"))
            .setSingleChoiceItems(fal.models.map { it.name }.toTypedArray(), fal.models.indexOfFirst { it.id == fal.model }) { dialog, index ->
                dialog.dismiss()
                if (fal.models[index].id != fal.model || fal.error == AudioFalError.MODEL_UNREADABLE)
                    request(AudioSettingsCommand.SelectFalModel(fal.models[index].id))
            }.setNegativeButton(ui.s("audio_home_cancel"), null).show()
    }

    override fun onDestroy() {
        keyDialog?.dismiss()
        io.shutdownNow()
        super.onDestroy()
    }
}
