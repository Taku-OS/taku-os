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
            if (notification.extras.containsKey("taku.island.v1")) {
                if (!customAllowed) error = "publisher_not_allowed"
                else {
                    val raw = notification.extras.get("taku.island.v1")
                    val result = IslandPayload.parse(raw as? String, sbn.packageName, false)
                    if (result.isValid) {
                        try {
                            return Extraction(custom(sbn, result.payload), null)
                        } catch (invalid: Exception) {
                            error = "invalid_references_or_host"
                        }
                    } else error = result.error
                }
            }
            if (notification.isPromotedOngoing || systemCategory != null) {
                return Extraction(baseline(sbn, systemCategory, error), error)
            }
        } catch (invalid: Exception) {
            error = "unreadable_notification"
        }
        return Extraction(null, error)
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
        val expanded = payload.getJSONObject("expanded")
        val actionKeys = expanded.optJSONArray("actions") ?: JSONArray()
        for (index in 0 until actionKeys.length()) {
            val key = actionKeys.getString(index)
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
            actions.toMap(), sbn.notification.contentIntent?.takeIf { it.creatorUid == sbn.uid }, service, null)
    }

    private fun baseline(sbn: StatusBarNotification, category: String?, fallback: String?): IslandActivity {
        val n = sbn.notification
        val extras = n.extras
        val pics = mutableMapOf<String, Icon>()
        n.smallIcon?.let { if (validIcon(it, sbn.packageName)) pics["app"] = it }
        val critical = extras.getCharSequence(Notification.EXTRA_SHORT_CRITICAL_TEXT)?.toString()?.take(16)
        val slot = when {
            !critical.isNullOrBlank() -> JSONObject().put("type", "text").put("text", critical)
            n.`when` > 0 && extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) -> JSONObject()
                .put("type", if (extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) "countdown" else "timer")
                .put(if (extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) "endEpochMs" else "startEpochMs", n.`when`)
            n.`when` > 0 -> JSONObject().put("type", "text").put("text",
                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(n.`when`)).take(16))
            pics.isNotEmpty() -> JSONObject().put("type", "pic").put("pic", "app")
            else -> JSONObject().put("type", "text").put("text", "●")
        }
        val actions = linkedMapOf<String, Notification.Action>()
        n.actions?.take(4)?.forEachIndexed { index, action ->
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
        val expanded = JSONObject().put("template", "text").put("title", title)
            .put("body", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(2000).orEmpty())
            .put("actions", JSONArray(actions.keys.toList()))
        if (extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) || extras.getInt(Notification.EXTRA_PROGRESS_MAX) > 0) {
            val progress = JSONObject().put("type", "progress")
            val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX)
            if (!extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) && maximum > 0) {
                progress.put("value", (extras.getInt(Notification.EXTRA_PROGRESS).toDouble() / maximum).coerceIn(0.0, 1.0))
            }
            expanded.put("template", "progress").put("progress", progress)
        }
        val payload = JSONObject().put("version", 1).put("category", category ?: when (n.category) {
            Notification.CATEGORY_NAVIGATION -> "navigation"
            Notification.CATEGORY_TRANSPORT -> "media"
            Notification.CATEGORY_PROGRESS -> "progress"
            else -> "other"
        }).put("bigIsland", JSONObject().put("leading", slot)).put("expanded", expanded)
            .put("alert", category == "alarm" || (category == "call" && extras.getInt(Notification.EXTRA_CALL_TYPE) == Notification.CallStyle.CALL_TYPE_INCOMING))
        return IslandActivity(sbn.key, sbn.packageName, sbn.uid, sbn.userId, payload, pics,
            actions, n.contentIntent?.takeIf { it.creatorUid == sbn.uid } ?: n.fullScreenIntent?.takeIf { it.creatorUid == sbn.uid }, null, fallback)
    }

    private fun validIcon(icon: Icon, postingPackage: String): Boolean = when (icon.type) {
        Icon.TYPE_RESOURCE -> icon.resPackage == postingPackage || icon.resPackage == "android"
        Icon.TYPE_BITMAP, Icon.TYPE_ADAPTIVE_BITMAP -> icon.bitmap?.let {
            it.width <= 512 && it.height <= 512 && it.allocationByteCount <= 1024 * 1024
        } == true
        else -> false // URI/data loading is not part of the island transport.
    }
}
