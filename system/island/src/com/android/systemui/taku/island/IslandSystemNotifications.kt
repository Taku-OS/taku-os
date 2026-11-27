/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.Manifest
import android.app.Notification
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import javax.inject.Inject

/** Derive critical priority from Android notification/system roles, never from custom JSON. */
class IslandSystemNotifications @Inject constructor(private val context: Context) {
    fun category(sbn: StatusBarNotification): String? {
        val n = sbn.notification
        if (!candidate(n)) return null
        val user = context.createContextAsUser(UserHandle.of(sbn.userId), 0)
        val pm = user.packageManager
        val system = runCatching { pm.getApplicationInfo(sbn.packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)
        val dialer = runCatching { user.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == sbn.packageName }.getOrDefault(false)
        val ownCalls = pm.checkPermission(Manifest.permission.MANAGE_OWN_CALLS, sbn.packageName) == PackageManager.PERMISSION_GRANTED
        return IslandSourcePolicy.category(n.category, n.isStyle(Notification.CallStyle::class.java),
            system, dialer, ownCalls, n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            n.fullScreenIntent != null, n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
    }

    companion object {
        /** Now Playing: a media-session notification while it is ongoing, i.e. while it plays. */
        fun media(n: Notification) = n.isStyle(Notification.MediaStyle::class.java) &&
            n.flags and Notification.FLAG_ONGOING_EVENT != 0

        fun candidate(n: Notification) = n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_ALARM,
            Notification.CATEGORY_STOPWATCH) || n.isStyle(Notification.CallStyle::class.java)
    }
}
