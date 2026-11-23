/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.TaskStackChangeListener
import com.android.systemui.shared.system.TaskStackChangeListeners
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Tracks the app in front so an activity's capsule is hidden while its own app shows that
 * information, and appears when the user leaves it. The default home app is exempt: Taku Agent
 * publishes from Home, where its capsule must stay visible.
 */
@SysUISingleton
class IslandForeground @Inject constructor(
    private val context: Context,
    @Main private val handler: Handler,
    @Background private val worker: Executor,
) {
    /** Package of the top task's top activity; main-thread state. */
    var foregroundPackage: String? = null
        private set
    private var homePackage: String? = null
    private var changed: () -> Unit = {}

    private val listener = object : TaskStackChangeListener {
        override fun onTaskStackChanged() = refresh()
        override fun onTaskMovedToFront(taskInfo: ActivityManager.RunningTaskInfo) {
            handler.post { update(taskInfo.topActivity?.packageName, homePackage) }
        }
    }

    fun start(onChanged: () -> Unit) {
        changed = onChanged
        TaskStackChangeListeners.getInstance().registerTaskStackListener(listener)
        refresh()
    }

    /** Binder lookups stay off the main thread; the result is applied there. */
    private fun refresh() = worker.execute {
        val top = runCatching { ActivityManagerWrapper.getInstance().runningTask?.topActivity?.packageName }.getOrNull()
        val home = runCatching {
            context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        handler.post { update(top, home) }
    }

    private fun update(top: String?, home: String?) {
        if (top == foregroundPackage && home == homePackage) return
        foregroundPackage = top
        homePackage = home
        changed()
    }

    /** SystemUI's own sources (recording, casting) and the home app are never suppressed. */
    fun suppresses(activity: IslandActivity): Boolean = activity.packageName != context.packageName &&
        activity.packageName == foregroundPackage && activity.packageName != homePackage
}
