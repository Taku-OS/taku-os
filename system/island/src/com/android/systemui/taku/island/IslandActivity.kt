/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.drawable.Icon
import org.json.JSONObject

data class IslandNativeAction(val title: String, val run: () -> Unit)

/** Extracted immutable envelope. JSON and Android objects are never exposed for caller mutation. */
data class IslandActivity(
    val key: String,
    val packageName: String,
    val uid: Int,
    val userId: Int,
    val payload: JSONObject,
    val pics: Map<String, Icon>,
    val actions: Map<String, Notification.Action>,
    val openIntent: PendingIntent?,
    val contentService: ComponentName?,
    val fallback: String?,
    val nativeActions: Map<String, IslandNativeAction> = emptyMap(),
) {
    val category: String get() = payload.getString("category")
    // Assistant notifications from older publishers must not resurrect listening/error pills.
    val building: Boolean get() = category == "assistant" && payload.optString("compact") == "building"
    val compactVisible: Boolean get() = if (category == "assistant") building else payload.optString("compact") != "hidden"
    val priority: Int get() = CATEGORIES.indexOf(category).let { if (it < 0) 8 else it }
    val title: String get() = payload.getJSONObject("expanded").getString("title")
    val body: String get() = payload.getJSONObject("expanded").optString("body")

    companion object {
        val CATEGORIES = listOf("call", "alarm", "recording", "navigation", "timer", "assistant", "media", "progress", "other")
    }
}
