/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionFilter
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType

/**
 * An ordinary island activity does not also raise a stock heads-up while the island can present
 * it; its `alert` expands the island card instead. Locked, shade open, landscape, hidden
 * activities, calls and alarms keep Android's normal heads-up.
 */
internal class IslandHeadsUpFilter(private val presenter: IslandPresenter, private val engine: IslandEngine) :
    VisualInterruptionFilter(setOf(VisualInterruptionType.PEEK), "taku island presents this activity") {
    override fun shouldSuppress(entry: NotificationEntry): Boolean =
        presenter.replacesHeadsUp(entry.key) { engine.headsUpCandidate(entry.sbn) }
}
