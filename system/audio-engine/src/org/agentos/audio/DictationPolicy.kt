// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

/**
 * Pure AssistantEntry dictation policy. No Android or org.json types live here so host tests can
 * pin the decoding, language, availability, and selection rules with plain values.
 */
internal const val DICTATION_SCOPE = "assistant-entry"
internal val DICTATION_LANGUAGES = listOf("system", "zh-CN", "en-US")

internal data class DictationError(val code: String, val message: String)

internal data class DictationBackendStatus(
    val profileId: String,
    val languages: List<String>,
    val processing: String,
    val available: Boolean,
    val unavailableReasons: List<String>,
    val maxCaptureMs: Long?,
    val drainTimeoutMs: Long,
)

/** Enriched `dictation.assistant-entry` scope entry; [toJson] lives at the JSON boundary. */
internal class DictationSettingsView(
    val primaryProfileId: String?,
    val fallbackProfileId: String?,
    val language: String?,
    val resolvedLanguage: String?,
    val isDefault: Boolean,
    val error: DictationError?,
    val backends: List<DictationBackendStatus>,
)

internal sealed interface DictationSource {
    data object Scoped : DictationSource
    data class Explicit(val language: String, val profileId: String) : DictationSource
}

internal data class ResolvedDictation(val request: AudioRequest, val metadata: DictationSessionMetadata)

/** Sampled once per snapshot: the true system language plus static backend prerequisites. */
internal data class DictationFacts(
    val systemLanguage: String?,
    val localModels: Set<String>,
    val falConfigured: Boolean,
)

/** Decoded root `dictation` value for one scope. A present but invalid block is never defaults. */
internal sealed interface StoredDictation {
    data class Saved(val primaryProfileId: String, val fallbackProfileId: String?, val language: String) : StoredDictation
    data object Absent : StoredDictation
    data object Unreadable : StoredDictation
}

internal object DictationPolicy {
    private const val UNREADABLE_MESSAGE = "Saved home dictation settings cannot be read. Select them again in Taku Audio"
    private const val UNSUPPORTED_MESSAGE = "The current system language is not supported. Select Chinese or English"
    private const val MODEL_MISSING_MESSAGE = "Local recognition model is not ready"
    private const val PROVIDER_MISSING_MESSAGE = "First add or save your fal Key again in Taku Audio"

    fun resolvedLanguage(savedLanguage: String, facts: DictationFacts): String? =
        if (savedLanguage == "system") facts.systemLanguage else savedLanguage

    fun backends(language: String?, facts: DictationFacts): List<DictationBackendStatus> =
        AudioProfiles.dictation.map { (profileId, backend) -> status(profileId, backend, language, facts) }

    fun blockedBackends(reason: String): List<DictationBackendStatus> =
        AudioProfiles.dictation.map { (profileId, backend) ->
            DictationBackendStatus(profileId, backend.languages, backend.processing, false, listOf(reason),
                backend.maxCaptureMs, backend.drainTimeoutMs)
        }

    fun inspect(stored: StoredDictation, facts: DictationFacts): DictationSettingsView {
        val primary: String?
        val fallback: String?
        val language: String?
        val resolved: String?
        val error: DictationError?
        val rows: List<DictationBackendStatus>
        when (stored) {
            is StoredDictation.Unreadable -> {
                primary = null; fallback = null; language = null; resolved = null
                error = DictationError("SETTINGS_UNREADABLE", UNREADABLE_MESSAGE)
                rows = blockedBackends("SETTINGS_UNREADABLE")
            }
            is StoredDictation.Absent -> {
                primary = "zipformer"; fallback = null; language = "system"
                resolved = resolvedLanguage("system", facts)
                error = if (resolved == null) DictationError("UNSUPPORTED_LANGUAGE", UNSUPPORTED_MESSAGE) else null
                rows = backends(resolved, facts)
            }
            is StoredDictation.Saved -> {
                primary = stored.primaryProfileId; fallback = stored.fallbackProfileId; language = stored.language
                resolved = resolvedLanguage(stored.language, facts)
                error = if (resolved == null) DictationError("UNSUPPORTED_LANGUAGE", UNSUPPORTED_MESSAGE) else null
                rows = backends(resolved, facts)
            }
        }
        return DictationSettingsView(primary, fallback, language, resolved, stored is StoredDictation.Absent, error, rows)
    }

    fun resolve(source: DictationSource, stored: StoredDictation, revision: Long?, facts: DictationFacts): ResolvedDictation =
        when (source) {
            DictationSource.Scoped -> resolveScoped(stored, revision, facts)
            is DictationSource.Explicit -> resolveExplicit(source)
        }

    private fun resolveExplicit(source: DictationSource.Explicit): ResolvedDictation {
        val backend = AudioProfiles.dictation[source.profileId]
            ?: throw AudioFailure("INVALID_ARGUMENT", "Unsupported speech recognition backend")
        if (source.language !in backend.languages) throw AudioFailure("UNSUPPORTED_LANGUAGE", UNSUPPORTED_MESSAGE)
        return ResolvedDictation(
            AudioRequest(purpose = "DICTATION", language = source.language, profileId = source.profileId),
            DictationSessionMetadata(null, null, source.profileId, unscopedProcessing(backend), null,
                backend.maxCaptureMs, backend.drainTimeoutMs)
        )
    }

    private fun resolveScoped(stored: StoredDictation, revision: Long?, facts: DictationFacts): ResolvedDictation {
        if (stored is StoredDictation.Unreadable) throw AudioFailure("SETTINGS_UNREADABLE", UNREADABLE_MESSAGE)
        val primary = (stored as? StoredDictation.Saved)?.primaryProfileId ?: "zipformer"
        val fallback = (stored as? StoredDictation.Saved)?.fallbackProfileId
        val savedLanguage = (stored as? StoredDictation.Saved)?.language ?: "system"
        val language = resolvedLanguage(savedLanguage, facts)
            ?: throw AudioFailure("UNSUPPORTED_LANGUAGE", UNSUPPORTED_MESSAGE)
        val primaryReasons = unavailableReasons(primary, language, facts)
        var actual = primary
        var fallbackReason: String? = null
        if (primaryReasons.isNotEmpty()) {
            if (fallback == null) throw AudioFailure(primaryReasons.first(), message(primaryReasons.first()))
            val fallbackReasons = unavailableReasons(fallback, language, facts)
            if (fallbackReasons.isNotEmpty()) {
                throw AudioFailure(fallbackReasons.first(),
                    message(fallbackReasons.first()) + " (primary backend: ${primaryReasons.first()})")
            }
            actual = fallback
            fallbackReason = primaryReasons.first()
        }
        val backend = AudioProfiles.dictation.getValue(actual)
        return ResolvedDictation(
            AudioRequest(purpose = "DICTATION", language = language, profileId = actual),
            DictationSessionMetadata(DICTATION_SCOPE, revision, primary, backend.processing, fallbackReason,
                backend.maxCaptureMs, backend.drainTimeoutMs)
        )
    }

    private fun status(profileId: String, backend: DictationBackend, language: String?, facts: DictationFacts): DictationBackendStatus {
        val reasons = unavailableReasons(profileId, language, facts)
        return DictationBackendStatus(profileId, backend.languages, backend.processing, reasons.isEmpty(), reasons,
            backend.maxCaptureMs, backend.drainTimeoutMs)
    }

    private fun unavailableReasons(profileId: String, language: String?, facts: DictationFacts): List<String> {
        val backend = AudioProfiles.dictation[profileId] ?: return listOf("UNSUPPORTED_LANGUAGE")
        if (language == null || language !in backend.languages) return listOf("UNSUPPORTED_LANGUAGE")
        val reasons = mutableListOf<String>()
        if (backend.requiresLocalModels && language !in facts.localModels) reasons += "MODEL_MISSING"
        if (backend.requiresFalKey && !facts.falConfigured) reasons += "PROVIDER_NOT_CONFIGURED"
        return reasons
    }

    /** Unscoped local requests keep the legacy LOCAL_ONLY wire value; cloud stays CLOUD_AUDIO. */
    private fun unscopedProcessing(backend: DictationBackend): String =
        if (backend.requiresFalKey) "CLOUD_AUDIO" else "LOCAL_ONLY"

    private fun message(reason: String): String = when (reason) {
        "MODEL_MISSING" -> MODEL_MISSING_MESSAGE
        "PROVIDER_NOT_CONFIGURED" -> PROVIDER_MISSING_MESSAGE
        "UNSUPPORTED_LANGUAGE" -> UNSUPPORTED_MESSAGE
        else -> UNREADABLE_MESSAGE
    }
}
