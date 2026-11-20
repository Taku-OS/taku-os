// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

private fun saved(primary: String = "zipformer", fallback: String? = null, language: String = "system"): StoredDictation =
    StoredDictation.Saved(primary, fallback, language)

private fun facts(
    system: String? = "zh-CN",
    local: Set<String> = setOf("zh-CN", "en-US"),
    fal: Boolean = true,
): DictationFacts = DictationFacts(system, local, fal)

private fun expectFailure(code: String, block: () -> Unit) {
    val error = runCatching(block).exceptionOrNull()
    check(error is AudioFailure && error.code == code) { "expected $code, got $error" }
}

fun main() {
    defaults()
    savedScope()
    unreadableScope()
    unsupportedSystemLanguage()
    scopedSelection()
    fallbackSelection()
    explicitSelection()
    languageRows()
    println("PASS dictation policy resolves system language, selects one backend, and keeps metadata truthful")
}

private fun defaults() {
    val view = DictationPolicy.inspect(StoredDictation.Absent, facts())
    check(view.isDefault && view.primaryProfileId == "zipformer" && view.fallbackProfileId == null)
    check(view.language == "system" && view.resolvedLanguage == "zh-CN" && view.error == null)
    check(view.backends.size == 2)
    val local = view.backends[0]
    check(local.profileId == "zipformer" && local.available && local.processing == "LOCAL")
    check(local.maxCaptureMs == null && local.drainTimeoutMs == 5000L)
    val cloud = view.backends[1]
    check(cloud.processing == "CLOUD_AUDIO" && cloud.maxCaptureMs == 60000L)
    check(cloud.drainTimeoutMs == 100000L && cloud.unavailableReasons.isEmpty())
}

private fun savedScope() {
    val view = DictationPolicy.inspect(saved("fal-whisper", "zipformer", "en-US"), facts())
    check(!view.isDefault && view.primaryProfileId == "fal-whisper" && view.fallbackProfileId == "zipformer")
    check(view.language == "en-US" && view.resolvedLanguage == "en-US" && view.error == null)
}

private fun unreadableScope() {
    val view = DictationPolicy.inspect(StoredDictation.Unreadable, facts())
    check(!view.isDefault && view.primaryProfileId == null && view.language == null && view.resolvedLanguage == null)
    check(view.error?.code == "SETTINGS_UNREADABLE")
    check(view.backends.all { !it.available && it.unavailableReasons == listOf("SETTINGS_UNREADABLE") })
    expectFailure("SETTINGS_UNREADABLE") {
        DictationPolicy.resolve(DictationSource.Scoped, StoredDictation.Unreadable, 3, facts())
    }
}

private fun unsupportedSystemLanguage() {
    val view = DictationPolicy.inspect(saved(language = "system"), facts(system = null))
    check(view.primaryProfileId == "zipformer" && view.language == "system" && view.resolvedLanguage == null)
    check(view.error?.code == "UNSUPPORTED_LANGUAGE" && !view.isDefault)
    expectFailure("UNSUPPORTED_LANGUAGE") {
        DictationPolicy.resolve(DictationSource.Scoped, saved("zipformer", "fal-whisper", "system"), 4, facts(system = null))
    }
}

private fun scopedSelection() {
    val local = DictationPolicy.resolve(DictationSource.Scoped, StoredDictation.Absent, 11, facts())
    check(local.request.purpose == "DICTATION" && local.request.profileId == "zipformer" && local.request.language == "zh-CN")
    check(local.request.voiceId.isEmpty() && local.request.speed == 1f)
    check(local.metadata == DictationSessionMetadata(DICTATION_SCOPE, 11, "zipformer", "LOCAL", null, null, 5000))

    expectFailure("PROVIDER_NOT_CONFIGURED") {
        DictationPolicy.resolve(DictationSource.Scoped, saved("fal-whisper"), 1, facts(fal = false))
    }
    val cloud = DictationPolicy.resolve(DictationSource.Scoped, saved("fal-whisper", "zipformer", "en-US"), 11, facts())
    check(cloud.request.profileId == "fal-whisper" && cloud.request.language == "en-US")
    check(cloud.metadata.processing == "CLOUD_AUDIO" && cloud.metadata.maxCaptureMs == 60000L)
    check(cloud.metadata.drainTimeoutMs == 100000L && cloud.metadata.fallbackReason == null)
}

private fun fallbackSelection() {
    val toCloud = DictationPolicy.resolve(
        DictationSource.Scoped, saved("zipformer", "fal-whisper", "zh-CN"), 5, facts(local = emptySet(), fal = true)
    )
    check(toCloud.request.profileId == "fal-whisper" && toCloud.metadata.requestedProfileId == "zipformer")
    check(toCloud.metadata.fallbackReason == "MODEL_MISSING" && toCloud.metadata.processing == "CLOUD_AUDIO")

    val toLocal = DictationPolicy.resolve(
        DictationSource.Scoped, saved("fal-whisper", "zipformer", "zh-CN"), 5, facts(local = setOf("zh-CN"), fal = false)
    )
    check(toLocal.request.profileId == "zipformer" && toLocal.metadata.fallbackReason == "PROVIDER_NOT_CONFIGURED")

    expectFailure("PROVIDER_NOT_CONFIGURED") {
        DictationPolicy.resolve(DictationSource.Scoped, saved("zipformer", "fal-whisper", "zh-CN"), 5,
            facts(local = emptySet(), fal = false))
    }
    expectFailure("MODEL_MISSING") {
        DictationPolicy.resolve(DictationSource.Scoped, saved("zipformer", null, "zh-CN"), 5, facts(local = emptySet()))
    }
}

private fun explicitSelection() {
    val cloud = DictationPolicy.resolve(DictationSource.Explicit("zh-CN", "fal-whisper"), StoredDictation.Absent, 9, facts(fal = false))
    check(cloud.request.profileId == "fal-whisper" && cloud.request.language == "zh-CN")
    check(cloud.metadata.settingsScope == null && cloud.metadata.settingsRevision == null)
    check(cloud.metadata.requestedProfileId == "fal-whisper" && cloud.metadata.fallbackReason == null)
    check(cloud.metadata.processing == "CLOUD_AUDIO" && cloud.metadata.drainTimeoutMs == 100000L)

    val local = DictationPolicy.resolve(DictationSource.Explicit("zh-CN", "zipformer"), StoredDictation.Absent, 9, facts(local = emptySet()))
    check(local.request.profileId == "zipformer" && local.metadata.processing == "LOCAL_ONLY")
    check(local.metadata.maxCaptureMs == null && local.metadata.drainTimeoutMs == 5000L)
    expectFailure("UNSUPPORTED_LANGUAGE") {
        DictationPolicy.resolve(DictationSource.Explicit("ja", "zipformer"), StoredDictation.Absent, 9, facts())
    }
    expectFailure("INVALID_ARGUMENT") {
        DictationPolicy.resolve(DictationSource.Explicit("zh-CN", "unknown"), StoredDictation.Absent, 9, facts())
    }
}

private fun languageRows() {
    val rows = DictationPolicy.backends("en-US", facts(local = setOf("zh-CN"), fal = false))
    check(!rows[0].available && rows[0].unavailableReasons == listOf("MODEL_MISSING"))
    check(!rows[1].available && rows[1].unavailableReasons == listOf("PROVIDER_NOT_CONFIGURED"))
    check(DictationPolicy.backends("ja", facts()).all { it.unavailableReasons == listOf("UNSUPPORTED_LANGUAGE") })
}
