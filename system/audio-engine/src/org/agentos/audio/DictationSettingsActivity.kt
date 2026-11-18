// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors

/** Desktop AssistantEntry dictation preferences. Reads and writes the same root settings as Agent commands. */
class DictationSettingsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var snapshot: AudioSettingsSnapshot? = null
    private var busy = false
    private var failure: AudioSettingsFailure? = null
    private var notice = ""
    private var rendering = false
    private var keyDialog: AlertDialog? = null
    private lateinit var ui: AudioUi
    private lateinit var status: TextView
    private lateinit var refresh: TextView
    private lateinit var voice: Switch
    private lateinit var globalState: TextView
    private lateinit var savedState: TextView
    private lateinit var disclosure: TextView
    private lateinit var availability: TextView
    private lateinit var falState: TextView
    private lateinit var saveKey: TextView
    private lateinit var removeKey: TextView
    private lateinit var reset: TextView
    private val primaryChips = linkedMapOf<String, TextView>()
    private val languageChips = linkedMapOf<String, TextView>()
    private val fallbackChips = linkedMapOf<String, TextView>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ui = AudioUi(this)
        val root = ui.page(ui.s("audio_dictation_title"), ui.s("audio_dictation_subtitle"), "audio_dictation_page", back = true)

        ui.section(root, ui.s("audio_dictation_settings_section"))
        val settings = ui.card(root)
        status = ui.text("", 13).apply {
            id = ui.id("audio_settings_state")
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(settings, status, bottom = 12)
        refresh = ui.action(settings, ui.s("audio_dictation_refresh"), "audio_refresh") { request(AudioSettingsCommand.Read) }

        ui.section(root, ui.s("audio_dictation_voice_section"))
        val voiceCard = ui.card(root)
        voice = ui.toggle(voiceCard, ui.s("audio_dictation_enable_voice"), ui.s("audio_dictation_enable_summary"), "audio_enabled") { enabled ->
            if (!rendering) request(AudioSettingsCommand.SetAudioEnabled(enabled))
        }
        ui.rule(voiceCard)
        globalState = ui.text("", 12)
        ui.add(voiceCard, globalState, 14)

        ui.section(root, ui.s("audio_dictation_preferences_section"))
        val preferences = ui.card(root)
        ui.add(preferences, ui.text(ui.s("audio_dictation_primary"), 13), bottom = 10)
        chipRow(preferences, primaryChips, listOf(
            "zipformer" to "audio_dictation_zipformer" to "dictation_primary_zipformer",
            "fal-whisper" to "audio_dictation_whisper" to "dictation_primary_whisper"
        )) { selectPrimary(it) }
        ui.add(preferences, ui.text(ui.s("audio_dictation_language"), 13), 4, 10)
        chipRow(preferences, languageChips, listOf(
            "system" to "audio_dictation_language_system" to "dictation_language_system",
            "zh-CN" to "audio_dictation_language_zh" to "dictation_language_zh",
            "en-US" to "audio_dictation_language_en" to "dictation_language_en"
        )) { selectLanguage(it) }
        ui.add(preferences, ui.text(ui.s("audio_dictation_fallback"), 13), 4, 10)
        chipRow(preferences, fallbackChips, listOf(
            "none" to "audio_dictation_fallback_none" to "dictation_fallback_none",
            "zipformer" to "audio_dictation_zipformer" to "dictation_fallback_zipformer",
            "fal-whisper" to "audio_dictation_whisper" to "dictation_fallback_whisper"
        )) { selectFallback(if (it == "none") null else it) }
        savedState = ui.text("", 12)
        ui.add(preferences, savedState, 0, 8)
        disclosure = ui.text("", 12)
        ui.add(preferences, disclosure, 0, 8)
        availability = ui.text("", 12)
        ui.add(preferences, availability, 0, 10)
        ui.add(preferences, ui.text(ui.s("audio_dictation_next_notice"), 12), 0, 8)
        reset = ui.action(preferences, ui.s("audio_dictation_reset"), "dictation_reset") {
            request(AudioSettingsCommand.ResetDictation(DICTATION_SCOPE))
        }

        ui.section(root, ui.s("audio_dictation_key_section"))
        val fal = ui.card(root)
        falState = ui.text("", 13, AudioUi.INK).apply { id = ui.id("dictation_fal_state") }
        ui.add(fal, falState, bottom = 16)
        saveKey = ui.action(fal, ui.s("audio_dictation_add_key"), "audio_save_fal_key", primary = true) { editKey() }
        removeKey = ui.action(fal, ui.s("audio_dictation_remove_key"), "audio_remove_fal_key") { confirmRemoveKey() }
        ui.add(fal, ui.text(ui.s("audio_dictation_key_summary"), 12), 4)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!busy && failure == null) request(AudioSettingsCommand.Read)
    }

    private fun chipRow(
        parent: LinearLayout,
        target: MutableMap<String, TextView>,
        entries: List<Pair<Pair<String, String>, String>>,
        onSelect: (String) -> Unit
    ) {
        val row = ui.row()
        for ((labels, id) in entries) {
            val chip = ui.chip(ui.s(labels.second), id).apply { setOnClickListener { onSelect(labels.first) } }
            target[labels.first] = chip
            row.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
        }
        ui.add(parent, row, 0, 8)
    }

    private fun request(command: AudioSettingsCommand) {
        if (busy || isFinishing || isDestroyed) return
        busy = true
        failure = null
        notice = ui.s(if (command === AudioSettingsCommand.Read) "audio_dictation_loading" else "audio_dictation_saving")
        render()
        io.execute {
            try {
                val next = AudioSettings.execute(applicationContext, command)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    snapshot = next
                    notice = ui.s(if (command === AudioSettingsCommand.Read) "audio_dictation_refreshed" else "audio_dictation_saved")
                    render()
                }
            } catch (error: AudioSettingsFailure) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    failure = error
                    notice = ui.s("audio_dictation_retry_notice")
                    render()
                }
            }
        }
    }

    private fun render() {
        val current = snapshot
        val view = current?.dictation
        val editable = current != null && !busy && failure == null
        rendering = true
        status.text = failure?.let { "${it.message}\n$notice" } ?: notice
        status.setTextColor(if (failure != null) AudioUi.ORANGE else AudioUi.MUTED)
        refresh.isEnabled = !busy
        voice.isChecked = current?.audioEnabled == true
        voice.isEnabled = editable
        globalState.text = when {
            current == null -> ui.s("audio_dictation_global_pending")
            !current.enabled -> ui.s("audio_dictation_system_paused")
            current.audioEnabled -> ui.s("audio_dictation_voice_enabled")
            else -> ui.s("audio_dictation_voice_paused")
        }
        val primary = view?.primaryProfileId
        val fallback = view?.fallbackProfileId
        val language = view?.language
        for ((id, chip) in primaryChips) {
            ui.selectChip(chip, id == primary)
            chip.isEnabled = editable
        }
        for ((id, chip) in languageChips) {
            ui.selectChip(chip, id == language)
            chip.isEnabled = editable
        }
        for ((id, chip) in fallbackChips) {
            ui.selectChip(chip, primary != null && language != null && id == (fallback ?: "none"))
            chip.isEnabled = editable && (id == "none" || id != primary)
        }
        savedState.text = when {
            view == null -> ui.s("audio_dictation_pending")
            primary == null || language == null -> view.error?.let { errorLabel(it) } ?: ui.s("audio_dictation_pending")
            else -> buildString {
            append(ui.s(if (view.isDefault) "audio_dictation_defaults_state" else "audio_dictation_defaults_saved_state"))
            append('\n')
            append(ui.s(
                "audio_dictation_saved_state",
                profileLabel(primary),
                languageLabel(language, view.resolvedLanguage),
                ui.s("audio_dictation_saved_fallback", fallback?.let { profileLabel(it) } ?: ui.s("audio_dictation_fallback_none"))
            ))
            view.error?.let { append('\n').append(errorLabel(it)) }
        }
        }
        savedState.setTextColor(if (view?.error != null) AudioUi.ORANGE else AudioUi.MUTED)
        val cloud = primary == "fal-whisper" || fallback == "fal-whisper"
        disclosure.text = if (primary == null || language == null) "" else {
            ui.s(if (cloud) "audio_dictation_disclosure_cloud" else "audio_dictation_disclosure_local")
        }
        disclosure.setTextColor(if (cloud) AudioUi.ORANGE else AudioUi.MUTED)
        availability.text = view?.backends?.joinToString("\n") { backendLine(it) } ?: ""
        reset.isEnabled = editable
        falState.text = current?.fal?.let { fal ->
            val value = if (fal.configured) ui.s("audio_dictation_key_saved", fal.masked ?: "")
            else ui.s("audio_dictation_key_missing")
            if (fal.error == AudioFalError.KEY_UNREADABLE) "$value\n${ui.s("audio_dictation_key_unreadable")}" else value
        } ?: ui.s("audio_dictation_pending")
        saveKey.text = ui.s(
            if (current?.fal?.configured == true || current?.fal?.error == AudioFalError.KEY_UNREADABLE) "audio_dictation_replace_key"
            else "audio_dictation_add_key"
        )
        saveKey.isEnabled = current != null && !busy
        removeKey.isEnabled = current != null && !busy &&
            (current.fal.configured || current.fal.error == AudioFalError.KEY_UNREADABLE)
        rendering = false
    }

    private fun profileLabel(profileId: String) = ui.s(
        if (profileId == "fal-whisper") "audio_dictation_whisper" else "audio_dictation_zipformer"
    )

    private fun languageLabel(language: String, resolved: String?): String = when (language) {
        "system" -> resolved?.let { ui.s("audio_dictation_language_resolved", it) } ?: ui.s("audio_dictation_language_system")
        "zh-CN" -> ui.s("audio_dictation_language_zh")
        else -> ui.s("audio_dictation_language_en")
    }

    private fun errorLabel(error: DictationError): String = when (error.code) {
        "SETTINGS_UNREADABLE" -> ui.s("audio_dictation_error_unreadable")
        "UNSUPPORTED_LANGUAGE" -> ui.s("audio_dictation_error_unsupported")
        "PROVIDER_NOT_CONFIGURED" -> ui.s("audio_dictation_error_key")
        else -> error.message
    }

    private fun backendLine(backend: DictationBackendStatus): String {
        val state = when {
            backend.available -> ui.s("audio_dictation_ready")
            "SETTINGS_UNREADABLE" in backend.unavailableReasons -> ui.s("audio_dictation_reason_unreadable")
            "MODEL_MISSING" in backend.unavailableReasons -> ui.s("audio_dictation_reason_model")
            "PROVIDER_NOT_CONFIGURED" in backend.unavailableReasons -> ui.s("audio_dictation_reason_key")
            else -> ui.s("audio_dictation_reason_language")
        }
        val processing = ui.s(
            if (backend.processing == "CLOUD_AUDIO") "audio_dictation_processing_cloud" else "audio_dictation_processing_local"
        )
        return ui.s("audio_dictation_backend_status", profileLabel(backend.profileId), processing, state)
    }

    private fun selectPrimary(profileId: String) {
        val view = snapshot?.dictation ?: return
        if (busy || failure != null || profileId == view.primaryProfileId) return
        val fallback = view.fallbackProfileId?.takeIf { it != profileId }
        request(AudioSettingsCommand.ConfigureDictation(DICTATION_SCOPE, profileId, fallback, view.language ?: "system"))
    }

    private fun selectFallback(profileId: String?) {
        val view = snapshot?.dictation ?: return
        if (busy || failure != null) return
        val primary = view.primaryProfileId ?: "zipformer"
        val fallback = profileId?.takeIf { it != primary }
        if (fallback == view.fallbackProfileId && view.primaryProfileId != null && view.language != null) return
        request(AudioSettingsCommand.ConfigureDictation(DICTATION_SCOPE, primary, fallback, view.language ?: "system"))
    }

    private fun selectLanguage(language: String) {
        val view = snapshot?.dictation ?: return
        if (busy || failure != null || language == view.language) return
        request(AudioSettingsCommand.ConfigureDictation(
            DICTATION_SCOPE, view.primaryProfileId ?: "zipformer", view.fallbackProfileId, language
        ))
    }

    private fun editKey() {
        if (busy || snapshot == null || keyDialog != null) return
        keyDialog = ui.keyDialog(
            title = saveKey.text.toString(),
            privacy = ui.s("audio_dictation_key_privacy"),
            hint = ui.s("audio_dictation_key_hint"),
            required = ui.s("audio_dictation_key_required"),
            cancel = ui.s("audio_dictation_cancel"),
            save = ui.s("audio_dictation_save"),
            inputId = "audio_fal_key_input",
            onDismiss = { keyDialog = null }
        ) { value -> request(AudioSettingsCommand.SaveFalKey(value)) }
    }

    private fun confirmRemoveKey() {
        if (busy || snapshot == null) return
        AlertDialog.Builder(this).setTitle(ui.s("audio_dictation_remove_key_title"))
            .setMessage(ui.s("audio_dictation_remove_key_summary"))
            .setNegativeButton(ui.s("audio_dictation_cancel"), null)
            .setPositiveButton(ui.s("audio_dictation_remove")) { _, _ -> request(AudioSettingsCommand.RemoveFalKey) }
            .show()
    }

    override fun onDestroy() {
        keyDialog?.dismiss()
        io.shutdownNow()
        super.onDestroy()
    }
}
