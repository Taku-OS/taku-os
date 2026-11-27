/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

/** Settings UI, content/cmd operations and raw settings writes share this policy and validation. */
@SysUISingleton
class IslandPolicy @Inject constructor(private val context: Context, @Main private val handler: Handler) {
    var enabled = true
        private set
    var allowedPackages: Set<String> = emptySet()
        private set
    /** Activities also show on the lock screen. The lock status (padlock) is not an activity. */
    var lockScreen = true
        private set
    var lastError: String? = null
        private set
    private var onChanged: () -> Unit = {}

    fun start(changed: () -> Unit) {
        onChanged = changed
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { reload() }
        }
        for (key in listOf(ENABLED, ALLOWED, PROTOCOL, LOCK_SCREEN)) {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        }
        reload()
    }

    /**
     * Custom island extras from apps signed with the platform certificate (the same certificate as
     * SystemUI, such as Taku Agent and Notebook) are accepted without any registration. Ordinary
     * third-party apps need an entry in the reviewed allow-list.
     */
    fun canPost(packageName: String, userId: Int? = null): Boolean =
        packageName in allowedPackages || platformSigned(packageName, userId)

    /**
     * The one platform-signature check for publishers, evaluated in the posting user's package
     * manager ([userId] null means SystemUI's own user). Answers are cached for a few seconds so a
     * burst of notifications, and the main-thread heads-up decision, do not each cross Binder.
     */
    fun platformSigned(packageName: String, userId: Int? = null): Boolean {
        val key = "${userId ?: -1}|$packageName"
        val now = android.os.SystemClock.elapsedRealtime()
        signatures[key]?.let { (at, signed) -> if (now - at < SIGNATURE_CACHE_MS) return signed }
        val signed = runCatching {
            val pm = if (userId == null) context.packageManager
                else context.createContextAsUser(android.os.UserHandle.of(userId), 0).packageManager
            pm.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH
        }.getOrDefault(false)
        if (signatures.size > 256) signatures.clear()
        signatures[key] = now to signed
        return signed
    }

    fun publisher(packageName: String, userId: Int? = null) =
        IslandPayload.Publisher(packageName, platformSigned(packageName, userId))

    private val signatures = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun setEnabled(value: String) {
        require(value == "0" || value == "1") { "invalid_enabled" }
        require(Settings.Global.putString(context.contentResolver, ENABLED, value)) { "settings_write_failed" }
        reload()
    }

    fun setLockScreen(value: String) {
        require(value == "0" || value == "1") { "invalid_lock_screen" }
        require(Settings.Global.putString(context.contentResolver, LOCK_SCREEN, value)) { "settings_write_failed" }
        reload()
    }

    fun setAllowed(value: String) {
        val packages = validatePackages(value)
        require(Settings.Global.putString(context.contentResolver, ALLOWED, packages.sorted().joinToString(","))) {
            "settings_write_failed"
        }
        reload()
    }

    fun snapshot(): JSONObject = JSONObject().put("enabled", enabled).put("lockScreen", lockScreen).put("protocol", PROTOCOL_VERSION)
        .put("allowedPackages", JSONArray(allowedPackages.sorted())).put("error", lastError ?: JSONObject.NULL)

    private fun reload() {
        val resolver = context.contentResolver
        val oldEnabled = enabled
        val oldAllowed = allowedPackages
        val oldLockScreen = lockScreen
        lastError = null
        when (Settings.Global.getString(resolver, LOCK_SCREEN)) {
            null -> Settings.Global.putString(resolver, LOCK_SCREEN, if (lockScreen) "1" else "0")
            "0" -> lockScreen = false
            "1" -> lockScreen = true
            else -> {
                lastError = "invalid_lock_screen"
                Settings.Global.putString(resolver, LOCK_SCREEN, if (lockScreen) "1" else "0")
            }
        }
        val rawEnabled = Settings.Global.getString(resolver, ENABLED)
        when (rawEnabled) {
            null -> Settings.Global.putString(resolver, ENABLED, if (enabled) "1" else "0")
            "0" -> enabled = false
            "1" -> enabled = true
            else -> {
                lastError = "invalid_enabled"
                Settings.Global.putString(resolver, ENABLED, if (enabled) "1" else "0")
            }
        }
        // Platform-signed apps need no entry; the list holds reviewed third-party apps only.
        val rawAllowed = Settings.Global.getString(resolver, ALLOWED).orEmpty()
        try { allowedPackages = validatePackages(rawAllowed) }
        catch (invalid: IllegalArgumentException) {
            lastError = "invalid_allowed_packages"
            Settings.Global.putString(resolver, ALLOWED, allowedPackages.sorted().joinToString(","))
        }
        // Global Settings has no per-key read-only primitive. Repair attempts to change this
        // capability marker; provider/cmd reject protocol writes synchronously.
        if (Settings.Global.getString(resolver, PROTOCOL) != PROTOCOL_VERSION.toString())
            Settings.Global.putString(resolver, PROTOCOL, PROTOCOL_VERSION.toString())
        if (enabled != oldEnabled || allowedPackages != oldAllowed || lockScreen != oldLockScreen) onChanged()
    }

    private fun validatePackages(value: String): Set<String> {
        require(value.length <= 32768) { "invalid_allowed_packages" }
        if (value.isBlank()) return emptySet()
        val parts = value.split(',').map { it.trim() }
        require(parts.size <= 128 && parts.all {
            it.length <= 255 && it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))
        }) { "invalid_allowed_packages" }
        return parts.toSet()
    }

    companion object {
        const val ENABLED = "taku_island_enabled"
        const val ALLOWED = "taku_island_allowed_packages"
        const val LOCK_SCREEN = "taku_island_lock_screen"
        const val PROTOCOL = "taku_island_protocol"
        /** The Taku Island protocol (`taku.island.v2`); there is no other custom wire version. */
        const val PROTOCOL_VERSION = 2
        private const val SIGNATURE_CACHE_MS = 10_000L
    }
}
