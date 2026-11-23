/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.drawable.Icon
import org.json.JSONObject

data class IslandNativeAction(val title: String, val run: () -> Unit)

/**
 * Extracted immutable envelope around the canonical (v2-shaped) payload, whichever wire format or
 * Live Update it came from. JSON and Android objects are never exposed for caller mutation.
 */
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
    /** `custom-v2`, `custom-v1`, `live-update` or `system`. */
    val source: String = SOURCE_V2,
    /** [IslandStore] tier, derived from Android roles and SystemUI controllers, never from JSON. */
    val tier: Int = IslandStore.TIER_ACTIVITY,
) {
    /** Capsule content (groups A and B); null means this activity never shows a capsule. */
    val compact: JSONObject? get() = payload.optJSONObject("compact")
    val compactVisible: Boolean get() = compact != null
    val expanded: JSONObject get() = payload.getJSONObject("expanded")
    val template: String get() = expanded.getString("template")
    val title: String get() = expanded.getString("title")
    val body: String get() = expanded.optString("body")
    /** Capsule tap: `expand` opens the card, `open` sends the content intent. */
    val tap: String get() = payload.optString("tap", "expand")
    val cardPosition: String get() = payload.optString("cardPosition", "cover")
    /** Taku Agent's native conversation card (the parser reserves the template to Taku Agent). */
    val nativeAssistant: Boolean get() = template == "assistant"
    /** Transitional: Taku onboarding's progress chrome until it becomes the public basic template. */
    val nativeProgressCard: Boolean get() = packageName == IslandPayload.TAKU_PACKAGE && template == "basic" &&
        !expanded.has("content") && !expanded.has("pic") && !expanded.has("steps") && !expanded.has("progress") &&
        (expanded.optJSONArray("actions")?.length() ?: 0) <= 1
    /** A host-rendered clock in the capsule needs a tick every second. */
    val timed: Boolean get() = listOf("a", "b").any { compact?.optJSONObject(it)?.optString("type") in setOf("timer", "countdown") }

    companion object {
        const val SOURCE_V2 = "custom-v2"
        const val SOURCE_V1 = "custom-v1"
        const val SOURCE_LIVE_UPDATE = "live-update"
        const val SOURCE_SYSTEM = "system"
    }
}
