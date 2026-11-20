// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

internal data class AudioReadingSettings(val voiceId: String, val speed: Double)
internal data class AudioFalModel(val id: String, val name: String)
internal enum class AudioFalError { KEY_UNREADABLE, MODEL_UNREADABLE }
internal data class AudioFalSettings(
    val configured: Boolean,
    val masked: String?,
    val model: String,
    val models: List<AudioFalModel>,
    val error: AudioFalError?
)
internal data class AudioSettingsSnapshot(
    val enabled: Boolean,
    val audioEnabled: Boolean,
    val systemRevision: Long,
    val audioRevision: Long,
    val reading: Map<String, AudioReadingSettings>,
    val fal: AudioFalSettings,
    val dictation: DictationSettingsView
) {
    fun toJson(): JSONObject {
        val profiles = JSONObject()
        for ((language, settings) in reading) profiles.put(language, JSONObject()
            .put("voiceId", settings.voiceId).put("speed", settings.speed))
        val models = JSONArray()
        for (model in fal.models) models.put(JSONObject().put("id", model.id).put("name", model.name))
        return JSONObject().put("enabled", enabled).put("audioEnabled", audioEnabled)
            .put("systemRevision", systemRevision).put("audioRevision", audioRevision).put("reading", profiles)
            .put("dictation", JSONObject().put(DICTATION_SCOPE, dictation.toJson()))
            .put("fal", JSONObject().put("configured", fal.configured).put("masked", fal.masked ?: JSONObject.NULL)
                .put("model", fal.model).put("models", models).put("realtimeAvailable", false)
                .put("error", fal.error?.name ?: JSONObject.NULL))
    }
}

internal sealed class AudioSettingsCommand {
    object Read : AudioSettingsCommand()
    class SaveFalKey(private val key: String) : AudioSettingsCommand() {
        internal fun addKey(request: JSONObject) { request.put("key", key) }
        override fun toString() = "SaveFalKey([redacted])"
    }
    object RemoveFalKey : AudioSettingsCommand()
    data class SelectFalModel(val model: String) : AudioSettingsCommand()
    data class SetAudioEnabled(val enabled: Boolean) : AudioSettingsCommand()
    data class ConfigureReading(val language: String, val voiceId: String, val speed: Double) : AudioSettingsCommand()
    data class ResetReading(val language: String) : AudioSettingsCommand()
    data class ConfigureDictation(
        val scope: String,
        val primaryProfileId: String,
        val fallbackProfileId: String?,
        val language: String
    ) : AudioSettingsCommand()
    data class ResetDictation(val scope: String) : AudioSettingsCommand()
}

internal class AudioSettingsFailure(val code: String, message: String) : Exception(message)

internal object AudioSettings {
    fun decode(arg: String?): AudioSettingsCommand {
        try {
            if (arg == null) invalid()
            val tokens = JSONTokener(arg)
            val request = tokens.nextValue() as? JSONObject ?: invalid()
            if (tokens.nextClean() != '\u0000') invalid()
            val action = string(request, "action")
            val fields = when (action) {
                "read", "removeFalKey" -> setOf("action")
                "saveFalKey" -> setOf("action", "key")
                "selectFalModel" -> setOf("action", "model")
                "setAudioEnabled" -> setOf("action", "enabled")
                "configureReading" -> setOf("action", "language", "voiceId", "speed")
                "resetReading" -> setOf("action", "language")
                "configureDictation" -> setOf("action", "scope", "primaryProfileId", "fallbackProfileId", "language")
                "resetDictation" -> setOf("action", "scope")
                else -> throw failure("UNSUPPORTED_OPERATION")
            }
            if (request.keys().asSequence().toSet() != fields) invalid()
            return when (action) {
                "read" -> AudioSettingsCommand.Read
                "saveFalKey" -> AudioSettingsCommand.SaveFalKey(string(request, "key"))
                "removeFalKey" -> AudioSettingsCommand.RemoveFalKey
                "selectFalModel" -> AudioSettingsCommand.SelectFalModel(string(request, "model"))
                "setAudioEnabled" -> AudioSettingsCommand.SetAudioEnabled(request.opt("enabled") as? Boolean ?: invalid())
                "configureReading" -> {
                    val speed = (request.opt("speed") as? Number)?.toDouble() ?: invalid()
                    if (!speed.isFinite()) invalid()
                    AudioSettingsCommand.ConfigureReading(string(request, "language"), string(request, "voiceId"), speed)
                }
                "configureDictation" -> {
                    val fallback = if (request.isNull("fallbackProfileId")) null else string(request, "fallbackProfileId")
                    AudioSettingsCommand.ConfigureDictation(
                        string(request, "scope"), string(request, "primaryProfileId"), fallback, string(request, "language")
                    )
                }
                "resetDictation" -> AudioSettingsCommand.ResetDictation(string(request, "scope"))
                else -> AudioSettingsCommand.ResetReading(string(request, "language"))
            }
        } catch (error: AudioSettingsFailure) {
            throw error
        } catch (_: Exception) {
            invalid()
        }
    }

    fun execute(context: Context, command: AudioSettingsCommand): AudioSettingsSnapshot {
        try {
            val request = encode(command).put("op", "settings")
            return snapshot(EngineConfig.control(request), context)
        } catch (error: AudioSettingsFailure) {
            throw error
        } catch (error: AudioFailure) {
            throw failure(error.code)
        } catch (_: Exception) {
            throw failure("CONFIG_UNAVAILABLE")
        }
    }

    fun failure(code: String): AudioSettingsFailure = when (code) {
        "INVALID_ARGUMENT" -> AudioSettingsFailure(code, "Invalid settings parameters. Check and retry")
        "UNSUPPORTED_OPERATION" -> AudioSettingsFailure(code, "This audio settings operation is not supported")
        "PROVIDER_NOT_CONFIGURED" -> AudioSettingsFailure(code, "First add or save your fal Key again in Taku Audio")
        "SETTINGS_UNREADABLE" -> AudioSettingsFailure(code, "Saved audio settings cannot be read. Select them again in Taku Audio")
        "UNSUPPORTED_LANGUAGE" -> AudioSettingsFailure(code, "The recognition language is not supported. Select Chinese or English")
        "SETTINGS_NOT_SAVED" -> AudioSettingsFailure(code, "Could not confirm settings were saved. Refresh to check the current state")
        else -> AudioSettingsFailure("CONFIG_UNAVAILABLE", "Audio configuration service temporarily unavailable. Refresh and retry")
    }

    private fun encode(command: AudioSettingsCommand): JSONObject = JSONObject().apply {
        when (command) {
            AudioSettingsCommand.Read -> put("action", "read")
            is AudioSettingsCommand.SaveFalKey -> { put("action", "saveFalKey"); command.addKey(this) }
            AudioSettingsCommand.RemoveFalKey -> put("action", "removeFalKey")
            is AudioSettingsCommand.SelectFalModel -> put("action", "selectFalModel").put("model", command.model)
            is AudioSettingsCommand.SetAudioEnabled -> put("action", "setAudioEnabled").put("enabled", command.enabled)
            is AudioSettingsCommand.ConfigureReading -> put("action", "configureReading").put("language", command.language)
                .put("voiceId", command.voiceId).put("speed", command.speed)
            is AudioSettingsCommand.ResetReading -> put("action", "resetReading").put("language", command.language)
            is AudioSettingsCommand.ConfigureDictation -> put("action", "configureDictation")
                .put("scope", command.scope).put("primaryProfileId", command.primaryProfileId)
                .put("fallbackProfileId", command.fallbackProfileId ?: JSONObject.NULL).put("language", command.language)
            is AudioSettingsCommand.ResetDictation -> put("action", "resetDictation").put("scope", command.scope)
        }
    }

    private fun snapshot(value: JSONObject, context: Context): AudioSettingsSnapshot {
        fun boolean(objectValue: JSONObject, key: String) = objectValue.get(key) as? Boolean ?: error("Invalid settings state")
        fun text(objectValue: JSONObject, key: String) = objectValue.get(key) as? String ?: error("Invalid settings state")
        fun revision(key: String): Long {
            val number = value.get(key)
            check(number is Int || number is Long)
            return (number as Number).toLong().also { check(it >= 0) }
        }
        val profiles = value.getJSONObject("reading")
        val reading = linkedMapOf<String, AudioReadingSettings>()
        for (language in profiles.keys()) {
            val profile = profiles.getJSONObject(language)
            val speed = profile.get("speed") as? Number ?: error("Invalid reading state")
            check(speed.toDouble().isFinite())
            reading[language] = AudioReadingSettings(text(profile, "voiceId"), speed.toDouble())
        }
        val fal = value.getJSONObject("fal")
        val entries = fal.getJSONArray("models")
        val models = ArrayList<AudioFalModel>(entries.length())
        for (index in 0 until entries.length()) {
            val model = entries.getJSONObject(index)
            models.add(AudioFalModel(text(model, "id"), text(model, "name")))
        }
        check(!boolean(fal, "realtimeAvailable"))
        val masked = if (fal.isNull("masked")) null else text(fal, "masked")
        val falError = if (fal.isNull("error")) null else AudioFalError.valueOf(text(fal, "error"))
        return AudioSettingsSnapshot(boolean(value, "enabled"), boolean(value, "audioEnabled"),
            revision("systemRevision"), revision("audioRevision"), Collections.unmodifiableMap(reading),
            AudioFalSettings(boolean(fal, "configured"), masked, text(fal, "model"), Collections.unmodifiableList(models), falError),
            DictationResolver(context).inspect(value))
    }

    private fun string(request: JSONObject, key: String): String = request.opt(key) as? String ?: invalid()
    private fun invalid(): Nothing = throw failure("INVALID_ARGUMENT")
}
