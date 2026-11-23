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
    var lastError: String? = null
        private set
    private var onChanged: () -> Unit = {}

    fun start(changed: () -> Unit) {
        onChanged = changed
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) { reload() }
        }
        for (key in listOf(ENABLED, ALLOWED, PROTOCOL)) {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer)
        }
        reload()
    }

    fun canPost(packageName: String): Boolean = packageName in allowedPackages ||
        context.packageManager.checkPermission(POST_PERMISSION, packageName) == PackageManager.PERMISSION_GRANTED

    fun setEnabled(value: String) {
        require(value == "0" || value == "1") { "invalid_enabled" }
        require(Settings.Global.putString(context.contentResolver, ENABLED, value)) { "settings_write_failed" }
        reload()
    }

    fun setAllowed(value: String) {
        val packages = validatePackages(value)
        require(Settings.Global.putString(context.contentResolver, ALLOWED, packages.sorted().joinToString(","))) {
            "settings_write_failed"
        }
        reload()
    }

    fun snapshot(): JSONObject = JSONObject().put("enabled", enabled).put("protocol", PROTOCOL_VERSION)
        .put("allowedPackages", JSONArray(allowedPackages.sorted())).put("error", lastError ?: JSONObject.NULL)

    private fun reload() {
        val resolver = context.contentResolver
        val oldEnabled = enabled
        val oldAllowed = allowedPackages
        lastError = null
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
        // First run only: seed the first-party partners. A user's later edit, including an empty
        // list, is a non-null value and is never overwritten.
        val stored = Settings.Global.getString(resolver, ALLOWED)
        if (stored == null) Settings.Global.putString(resolver, ALLOWED, DEFAULT_ALLOWED)
        val rawAllowed = stored ?: DEFAULT_ALLOWED
        try { allowedPackages = validatePackages(rawAllowed) }
        catch (invalid: IllegalArgumentException) {
            lastError = "invalid_allowed_packages"
            Settings.Global.putString(resolver, ALLOWED, allowedPackages.sorted().joinToString(","))
        }
        // Global Settings has no per-key read-only primitive. Repair attempts to change this
        // capability marker; provider/cmd reject protocol writes synchronously.
        if (Settings.Global.getString(resolver, PROTOCOL) != PROTOCOL_VERSION.toString())
            Settings.Global.putString(resolver, PROTOCOL, PROTOCOL_VERSION.toString())
        if (enabled != oldEnabled || allowedPackages != oldAllowed) onChanged()
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
        const val PROTOCOL = "taku_island_protocol"
        const val POST_PERMISSION = "org.agentos.permission.POST_ISLAND"
        /** Seeded once when the allow-list was never written: Notebook is the first partner. */
        const val DEFAULT_ALLOWED = "org.agentos.notebook"
        /** Newest custom wire version; v1 is still accepted through the adapter. */
        const val PROTOCOL_VERSION = 2
    }
}
