// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

class AudioSettingsActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private var language = "zh-CN"
    private var snapshot: AudioSettingsSnapshot? = null
    private var busy = false
    private var failure: AudioSettingsFailure? = null
    private var notice = ""
    private lateinit var status: TextView
    private lateinit var policy: TextView
    private lateinit var refresh: TextView
    private lateinit var voice: TextView
    private lateinit var speed: TextView
    private lateinit var reset: TextView
    private val languageChips = linkedMapOf<String, TextView>()
    private lateinit var ui: AudioUi

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        state?.getString("language")?.takeIf { it in setOf("zh-CN", "en-US") }?.let { language = it }
        ui = AudioUi(this)
        val subtitle = ui.s(if (ModelResources.ttsReady(ModelResources.root(this))) "audio_reading_ready" else "audio_reading_not_ready")
        val root = ui.page(ui.s("audio_reading_title"), subtitle, "audio_reading_page", back = true)

        ui.section(root, ui.s("audio_reading_settings_section"))
        val settings = ui.card(root)
        status = ui.text("", 13).apply {
            id = ui.id("audio_settings_state")
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ui.add(settings, status, bottom = 12)
        policy = ui.text("", 12)
        ui.add(settings, policy, bottom = 12)
        refresh = ui.action(settings, ui.s("audio_reading_refresh"), "audio_refresh") { request(AudioSettingsCommand.Read) }

        ui.section(root, ui.s("audio_reading_preferences_section"))
        val preferences = ui.card(root)
        ui.add(preferences, ui.text(ui.s("audio_reading_language"), 13), bottom = 10)
        val languages = ui.row()
        for ((id, label) in listOf("zh-CN" to ui.s("audio_reading_chinese"), "en-US" to ui.s("audio_reading_english"))) {
            val chip = ui.chip(label, if (id == "zh-CN") "reading_language_zh" else "reading_language_en").apply {
                setOnClickListener { language = id; render() }
            }
            languageChips[id] = chip
            languages.addView(chip, LinearLayout.LayoutParams(0, -2, 1f))
        }
        ui.add(preferences, languages, bottom = 16)
        voice = ui.action(preferences, ui.s("audio_reading_voice"), "reading_voice") { selectVoice() }
        speed = ui.action(preferences, ui.s("audio_reading_speed"), "reading_speed") { selectSpeed() }
        ui.add(preferences, ui.text(ui.s("audio_reading_next_notice"), 12), 4)
        reset = ui.action(root, ui.s("audio_reading_reset"), "reading_reset") { request(AudioSettingsCommand.ResetReading(language)) }
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!busy && failure == null) request(AudioSettingsCommand.Read)
    }

    private fun render() {
        val current = snapshot
        val editable = current != null && !busy && failure == null
        status.text = failure?.let { "${it.message}\n$notice" } ?: notice
        status.setTextColor(if (failure != null) AudioUi.ORANGE else AudioUi.MUTED)
        refresh.isEnabled = !busy
        policy.text = when {
            current == null -> ui.s("audio_reading_policy_pending")
            !current.enabled -> ui.s("audio_reading_system_paused")
            !current.audioEnabled -> ui.s("audio_reading_voice_paused")
            else -> ""
        }
        policy.visibility = if (policy.text.isEmpty()) View.GONE else View.VISIBLE
        for ((id, chip) in languageChips) {
            ui.selectChip(chip, language == id)
            chip.isEnabled = !busy && language != id
        }
        val settings = current?.reading?.get(language)
        val selected = runCatching { VoiceCatalog.select(language, settings?.voiceId ?: "") }.getOrNull()
        voice.text = when {
            current == null -> ui.s("audio_reading_voice")
            selected != null -> ui.s("audio_reading_voice_value", voiceLabel(selected))
            else -> ui.s("audio_reading_voice_unsupported", settings?.voiceId.orEmpty())
        }
        speed.text = if (current == null) ui.s("audio_reading_speed") else ui.s("audio_reading_speed_value", settings?.speed ?: 1.0)
        voice.isEnabled = editable
        speed.isEnabled = editable
        reset.isEnabled = editable
    }

    private fun voiceLabel(voice: LocalVoice): String = when (voice.id) {
        "zh-CN-local-3" -> ui.s("audio_reading_voice_zh_female_one")
        "zh-CN-local-20" -> ui.s("audio_reading_voice_zh_female_two")
        "zh-CN-local-58" -> ui.s("audio_reading_voice_zh_male_one")
        "zh-CN-local-80" -> ui.s("audio_reading_voice_zh_male_two")
        "en-US-local-0" -> ui.s("audio_reading_voice_en_female_one")
        "en-US-local-1" -> ui.s("audio_reading_voice_en_female_two")
        else -> voice.label
    }

    private fun selectVoice() {
        val current = snapshot ?: return
        if (busy) return
        val settings = current.reading[language]
        val selected = runCatching { VoiceCatalog.select(language, settings?.voiceId ?: "") }.getOrNull()
        val choices = VoiceCatalog.voices.filter { it.language == language }
        AlertDialog.Builder(this).setTitle(ui.s("audio_reading_choose_voice"))
            .setSingleChoiceItems(choices.map { voiceLabel(it) }.toTypedArray(), choices.indexOfFirst { it.id == selected?.id }) { dialog, index ->
                dialog.dismiss()
                request(AudioSettingsCommand.ConfigureReading(language, choices[index].id, settings?.speed ?: 1.0))
            }.setNegativeButton(ui.s("audio_reading_cancel"), null).show()
    }

    private fun selectSpeed() {
        val current = snapshot ?: return
        if (busy) return
        val settings = current.reading[language]
        val voiceId = settings?.voiceId?.takeIf { it.isNotEmpty() } ?: VoiceCatalog.select(language, "").id
        val speeds = listOf(.75, 1.0, 1.25, 1.5)
        AlertDialog.Builder(this).setTitle(ui.s("audio_reading_choose_speed"))
            .setSingleChoiceItems(speeds.map { ui.s("audio_reading_speed_option", it) }.toTypedArray(), speeds.indexOf(settings?.speed ?: 1.0)) { dialog, index ->
                dialog.dismiss()
                request(AudioSettingsCommand.ConfigureReading(language, voiceId, speeds[index]))
            }.setNegativeButton(ui.s("audio_reading_cancel"), null).show()
    }

    private fun request(command: AudioSettingsCommand) {
        if (busy || isFinishing || isDestroyed) return
        busy = true
        failure = null
        notice = ui.s(if (command === AudioSettingsCommand.Read) "audio_reading_loading" else "audio_reading_saving")
        render()
        io.execute {
            try {
                val next = AudioSettings.execute(applicationContext, command)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    snapshot = next
                    notice = ui.s(if (command === AudioSettingsCommand.Read) "audio_reading_refreshed" else "audio_reading_saved")
                    render()
                }
            } catch (error: AudioSettingsFailure) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    busy = false
                    snapshot = null
                    failure = error
                    notice = ui.s("audio_reading_retry_notice")
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(state: Bundle) {
        state.putString("language", language)
        super.onSaveInstanceState(state)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
