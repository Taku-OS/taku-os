/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

/** Runs off-main. Invalid Taku data never cancels or removes an Android notification. */
class IslandExtractor @Inject constructor(private val context: Context, private val systemNotifications: IslandSystemNotifications) {
    data class Extraction(val activity: IslandActivity?, val error: String?)

    fun extract(sbn: StatusBarNotification, customAllowed: Boolean): Extraction {
        var error: String? = null
        try {
            val notification = sbn.notification
            val systemCategory = systemNotifications.category(sbn)
            if (systemCategory != null) return Extraction(baseline(sbn, systemCategory, null), null)
            val raw = notification.extras.getString(EXTRA_V2)
            if (raw != null) {
                if (!customAllowed) error = "publisher_not_allowed"
                else {
                    val publisher = publisher(sbn)
                    val result = IslandPayload.parse(raw, publisher)
                    // The island is for ongoing activities: a one-off or dismissible notification
                    // (a static error, a finished event) stays in the shade. Taku Agent's own
                    // reply cards are exempt; they are the assistant's conversation surface.
                    if (result.isValid && !publisher.taku && notification.flags and Notification.FLAG_ONGOING_EVENT == 0) {
                        error = "not_ongoing"
                    } else if (result.isValid) {
                        try {
                            return Extraction(custom(sbn, result.payload), null)
                        } catch (invalid: Exception) {
                            error = "invalid_references_or_host"
                        }
                    } else error = result.error
                }
            }
            if (notification.isPromotedOngoing) {
                return Extraction(baseline(sbn, null, error), error)
            }
        } catch (invalid: Exception) {
            error = "unreadable_notification"
        }
        return Extraction(null, error)
    }

    /** Identity comes from the package manager, never from the payload. */
    fun publisher(sbn: StatusBarNotification): IslandPayload.Publisher {
        val pm = context.createContextAsUser(UserHandle.of(sbn.userId), 0).packageManager
        val platform = runCatching { pm.checkSignatures("android", sbn.packageName) == PackageManager.SIGNATURE_MATCH }
            .getOrDefault(false)
        return IslandPayload.Publisher(sbn.packageName, platform)
    }

    private fun custom(sbn: StatusBarNotification, payload: JSONObject): IslandActivity {
        val extras = sbn.notification.extras
        val picBundle = extras.getBundle("taku.island.pics") ?: Bundle.EMPTY
        val actionBundle = extras.getBundle("taku.island.actions") ?: Bundle.EMPTY
        val pics = mutableMapOf<String, Icon>()
        val actions = mutableMapOf<String, Notification.Action>()
        fun collectPictures(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.has("pic")) {
                        val key = value.getString("pic")
                        val icon = picBundle.getParcelable(key, Icon::class.java)
                            ?: throw IllegalArgumentException("missing_icon")
                        require(validIcon(icon, sbn.packageName))
                        pics[key] = icon
                    }
                    value.keys().forEach { collectPictures(value.get(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) collectPictures(value.get(index))
            }
        }
        collectPictures(payload)
        // The capsule falls back to the notification's own icon when group A is absent.
        sbn.notification.smallIcon?.takeIf { validIcon(it, sbn.packageName) }?.let { pics.putIfAbsent(APP_PIC, it) }
        val expanded = payload.getJSONObject("expanded")
        val actionKeys = (expanded.optJSONArray("actions") ?: JSONArray()).let { list ->
            (0 until list.length()).map { list.getString(it) } }.toMutableList()
        expanded.optString("tapAction").takeIf { it.isNotEmpty() && it !in actionKeys }?.let(actionKeys::add)
        for (key in actionKeys) {
            val action = actionBundle.getParcelable(key, Notification.Action::class.java)
                ?: throw IllegalArgumentException("missing_action")
            require(action.actionIntent != null && action.actionIntent.creatorUid == sbn.uid)
            actions[key] = action
        }
        val service = expanded.optJSONObject("content")?.let {
            ComponentName.unflattenFromString(it.getString("service"))
                ?: throw IllegalArgumentException("invalid_service")
        }
        if (service != null) {
            require(service.packageName == sbn.packageName)
            val pm = context.createContextAsUser(UserHandle.of(sbn.userId), 0).packageManager
            val info = pm.getServiceInfo(service, PackageManager.ComponentInfoFlags.of(0))
            require(info.exported && info.applicationInfo.uid == sbn.uid && info.permission != null)
            val permission = pm.getPermissionInfo(info.permission, 0)
            require(permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_SIGNATURE)
        }
        return IslandActivity(sbn.key, sbn.packageName, sbn.uid, sbn.userId, payload, pics.toMap(),
            actions.toMap(), sbn.notification.contentIntent?.takeIf { it.creatorUid == sbn.uid }, service, null,
            source = IslandActivity.SOURCE_CUSTOM)
    }

    /**
     * Android Live Update (or call/alarm/stopwatch adapter) mapped onto the canonical payload:
     * A = large icon, else the small icon; B = short critical text, else chronometer, else `when`.
     */
    private fun baseline(sbn: StatusBarNotification, category: String?, fallback: String?): IslandActivity {
        val n = sbn.notification
        val extras = n.extras
        val pics = mutableMapOf<String, Icon>()
        n.smallIcon?.let { if (validIcon(it, sbn.packageName)) pics[APP_PIC] = it }
        n.getLargeIcon()?.let { if (validIcon(it, sbn.packageName)) pics[LARGE_PIC] = it }
        val critical = extras.getCharSequence(Notification.EXTRA_SHORT_CRITICAL_TEXT)?.toString()?.take(16)
        val b = when {
            !critical.isNullOrBlank() -> JSONObject().put("type", "text").put("text", critical)
            n.`when` > 0 && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) -> JSONObject()
                .put("type", if (extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) "countdown" else "timer")
                .put(if (extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) "endEpochMs" else "startEpochMs", n.`when`)
            n.`when` > 0 -> JSONObject().put("type", "text").put("text",
                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(n.`when`)).take(16))
            else -> null
        }
        val compact = JSONObject()
        when {
            LARGE_PIC in pics -> compact.put("a", JSONObject().put("type", "icon").put("pic", LARGE_PIC))
            APP_PIC in pics -> compact.put("a", JSONObject().put("type", "icon").put("pic", APP_PIC).put("tint", true))
        }
        if (b != null) compact.put("b", b)
        val actions = linkedMapOf<String, Notification.Action>()
        n.actions?.take(IslandPayload.MAX_ACTIONS)?.forEachIndexed { index, action ->
            if (action.actionIntent != null && action.actionIntent.creatorUid == sbn.uid) actions["action_$index"] = action
        }
        if (category == "call") {
            listOf(Triple("answer", "Answer", Notification.EXTRA_ANSWER_INTENT),
                Triple("decline", "Decline", Notification.EXTRA_DECLINE_INTENT),
                Triple("hangup", "Hang up", Notification.EXTRA_HANG_UP_INTENT)).forEach { (id, label, key) ->
                extras.getParcelable(key, android.app.PendingIntent::class.java)?.takeIf { it.creatorUid == sbn.uid }?.let { intent ->
                    // Replace the same CallStyle action instead of rendering it twice.
                    actions.entries.removeAll { it.value.actionIntent == intent }
                    actions[id] = Notification.Action.Builder(null as Icon?, label, intent).build()
                }
            }
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(80)
            ?.takeIf { it.isNotBlank() } ?: sbn.packageName.take(80)
        val expanded = JSONObject().put("template", "basic").put("title", title)
            .put("body", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(2000).orEmpty())
            .put("actions", JSONArray(actions.keys.toList()))
        progress(n)?.let { expanded.put("template", "progress").put("progress", it) }
        val payload = JSONObject().put("version", 2).put("expanded", expanded)
            .put("alert", category == "alarm" || (category == "call" && extras.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_INCOMING))
        if (compact.length() > 0) payload.put("compact", compact)
        // setTimeoutAfter is the Live Update's declared end; otherwise the island default applies.
        // Calls, alarms and stopwatches recognised by SystemUI end with their source (12 h cap).
        val declared = n.timeoutAfter.takeIf { it > 0 }?.let { ((it + 999) / 1000).coerceIn(1, IslandStore.MAX_LIFETIME_S.toLong()) }
        when {
            declared != null -> payload.put("timeoutS", declared)
            category != null -> payload.put("timeoutS", IslandStore.MAX_LIFETIME_S)
        }
        val tier = when (category) {
            "call" -> IslandStore.TIER_CALL
            "alarm" -> IslandStore.TIER_ALARM
            else -> IslandStore.TIER_ACTIVITY
        }
        return IslandActivity(sbn.key, sbn.packageName, sbn.uid, sbn.userId, payload, pics,
            actions, n.contentIntent?.takeIf { it.creatorUid == sbn.uid } ?: n.fullScreenIntent?.takeIf { it.creatorUid == sbn.uid },
            null, fallback, source = if (category != null) IslandActivity.SOURCE_SYSTEM else IslandActivity.SOURCE_LIVE_UPDATE, tier = tier)
    }

    /** Progress from Android 16 ProgressStyle (rebuilt from extras) or the legacy progress extras. */
    private fun progress(n: Notification): JSONObject? {
        val extras = n.extras
        if (extras.getString(Notification.EXTRA_TEMPLATE) == Notification.ProgressStyle::class.java.name) {
            val style = runCatching { Notification.Builder.recoverBuilder(context, n).style as? Notification.ProgressStyle }.getOrNull()
            if (style != null) {
                val result = JSONObject()
                if (!style.isProgressIndeterminate && style.progressMax > 0) {
                    result.put("value", (style.progress.toDouble() / style.progressMax).coerceIn(0.0, 1.0))
                }
                return result
            }
        }
        if (!extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) && extras.getInt(Notification.EXTRA_PROGRESS_MAX) <= 0) return null
        val result = JSONObject()
        val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
        if (!extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) && maximum > 0) {
            result.put("value", (extras.getInt(Notification.EXTRA_PROGRESS).toDouble() / maximum).coerceIn(0.0, 1.0))
        }
        return result
    }

    private fun validIcon(icon: Icon, postingPackage: String): Boolean = when (icon.type) {
        Icon.TYPE_RESOURCE -> icon.resPackage == postingPackage || icon.resPackage == "android"
        Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> icon.bitmap?.let {
            it.width <= 512 && it.height <= 512 && it.allocationByteCount <= 1024 * 1024
        } == true
        else -> false // URI/data loading is not part of the island transport.
    }

    companion object {
        const val EXTRA_V2 = "taku.island.v2"
        const val APP_PIC = IslandActivity.APP_PIC
        const val LARGE_PIC = IslandActivity.LARGE_PIC
    }
}
