// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

import android.app.LocaleManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Android boundary for AssistantEntry dictation. It samples the true system locale and static
 * prerequisites, decodes the root snapshot, and delegates every decision to [DictationPolicy].
 */
internal class DictationResolver(private val context: Context) {
    fun inspect(snapshot: JSONObject): DictationSettingsView =
        DictationPolicy.inspect(decodeDictation(snapshot.opt("dictation")), facts(snapshot))

    fun inspect(language: String, snapshot: JSONObject): List<DictationBackendStatus> =
        DictationPolicy.backends(language, facts(snapshot))

    fun resolve(source: DictationSource, snapshot: JSONObject): ResolvedDictation =
        DictationPolicy.resolve(source, decodeDictation(snapshot.opt("dictation")), revision(snapshot), facts(snapshot))

    private fun revision(snapshot: JSONObject): Long? =
        (snapshot.opt("audioRevision") as? Number)?.toLong()?.takeIf { it >= 0 }

    private fun facts(snapshot: JSONObject): DictationFacts {
        val fal = snapshot.opt("fal") as? JSONObject
        val root = ModelResources.root(context)
        val local = LinkedHashSet<String>()
        for (backend in AudioProfiles.dictation.values) {
            if (!backend.requiresLocalModels) continue
            for (language in backend.languages) if (ModelResources.ready(root, language)) local += language
        }
        return DictationFacts(systemLanguage(), local, fal?.optBoolean("configured") == true)
    }

    private fun systemLanguage(): String? {
        val locale: Locale = context.getSystemService(LocaleManager::class.java)?.systemLocales?.get(0)
            ?: return null
        return when (locale.language) {
            "zh" -> "zh-CN"
            "en" -> "en-US"
            else -> null
        }
    }
}

internal fun DictationBackendStatus.toJson(): JSONObject = JSONObject()
    .put("profileId", profileId)
    .put("languages", JSONArray(languages))
    .put("processing", processing)
    .put("available", available)
    .put("unavailableReasons", JSONArray(unavailableReasons))
    .put("maxCaptureMs", maxCaptureMs ?: JSONObject.NULL)
    .put("drainTimeoutMs", drainTimeoutMs)

internal fun DictationSettingsView.toJson(): JSONObject = JSONObject()
    .put("primaryProfileId", primaryProfileId ?: JSONObject.NULL)
    .put("fallbackProfileId", fallbackProfileId ?: JSONObject.NULL)
    .put("language", language ?: JSONObject.NULL)
    .put("resolvedLanguage", resolvedLanguage ?: JSONObject.NULL)
    .put("isDefault", isDefault)
    .put("error", error?.let { JSONObject().put("code", it.code).put("message", it.message) } ?: JSONObject.NULL)
    .put("backends", JSONArray(backends.map { it.toJson() }))

/** The root `dictation` value: absent means defaults, a present but invalid shape is never defaults. */
private fun decodeDictation(dictation: Any?): StoredDictation {
    if (dictation == null) return StoredDictation.Absent
    if (dictation !is JSONObject) return StoredDictation.Unreadable
    if (!dictation.has(DICTATION_SCOPE)) return StoredDictation.Absent
    val entry = dictation.opt(DICTATION_SCOPE)
    if (entry !is JSONObject) return StoredDictation.Unreadable
    if (entry.keys().asSequence().toSet() != setOf("primaryProfileId", "fallbackProfileId", "language"))
        return StoredDictation.Unreadable
    val primary = entry.opt("primaryProfileId") as? String ?: return StoredDictation.Unreadable
    if (primary !in AudioProfiles.dictation) return StoredDictation.Unreadable
    val rawFallback = entry.opt("fallbackProfileId")
    val fallback = when {
        JSONObject.NULL.equals(rawFallback) -> null
        rawFallback is String -> rawFallback
        else -> return StoredDictation.Unreadable
    }
    if (fallback != null && (fallback !in AudioProfiles.dictation || fallback == primary))
        return StoredDictation.Unreadable
    val language = entry.opt("language") as? String ?: return StoredDictation.Unreadable
    if (language !in DICTATION_LANGUAGES) return StoredDictation.Unreadable
    return StoredDictation.Saved(primary, fallback, language)
}
