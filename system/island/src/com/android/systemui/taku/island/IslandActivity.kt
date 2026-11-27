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
    /** `custom` (Taku Island extras), `live-update`, `system` or `status`. */
    val source: String = SOURCE_CUSTOM,
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
        // A styled card is an ordinary template card: the onboarding chrome would drop its colours.
        backgroundColor == null && accentColor == null &&
        (expanded.optJSONArray("actions")?.length() ?: 0) <= 1
    /** A host-rendered clock in the capsule needs a tick every second. */
    val timed: Boolean get() = listOf("a", "b").any { compact?.optJSONObject(it)?.optString("type") in setOf("timer", "countdown") }

    /** The validated `#RRGGBB` template colours as opaque ARGB, parsed once per payload. */
    val backgroundColor: Int? by lazy(LazyThreadSafetyMode.NONE) { color("backgroundColor") }
    val accentColor: Int? by lazy(LazyThreadSafetyMode.NONE) { color("accentColor") }
    private fun color(field: String): Int? = payload.optJSONObject("expanded")?.optString(field)
        ?.takeIf { it.length == 7 && it[0] == '#' }?.substring(1)?.toIntOrNull(16)?.let { 0xff000000.toInt() or it }

    companion object {
        const val SOURCE_CUSTOM = "custom"
        const val SOURCE_LIVE_UPDATE = "live-update"
        const val SOURCE_SYSTEM = "system"
        /** A glyph-only system status ([IslandLockStatus]): no card, no actions, never in the store. */
        const val SOURCE_STATUS = "status"
        /** A status activity's `status` payload field. */
        const val STATUS_LOCKED = "locked"
        const val STATUS_UNLOCKED = "unlocked"
        /** Reserved picture keys for the notification's own icons (publisher keys cannot start with `_`). */
        const val APP_PIC = "_app"
        const val LARGE_PIC = "_large"
    }
}
