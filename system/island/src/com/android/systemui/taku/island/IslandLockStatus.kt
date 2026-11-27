/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.os.Handler
import android.os.Process
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * The device's lock state as an island status: a padlock in the capsule's disc, with no pill, card
 * or actions. It is not a live activity, so it never enters [IslandStore] and neither the island
 * switch nor an activity's lifetime applies to it.
 *
 * Locked while the keyguard shows and cannot be dismissed without authenticating. Authenticating
 * (or the keyguard leaving) turns it into the open padlock for [UNLOCKED_MS], after which the
 * status ends. The lock screen draws it in [IslandStatusView]; once the keyguard has gone the
 * status-bar capsule shows the open padlock, and gets the full time again so an unlock that goes
 * straight home is still seen. A keyguard that never needs authentication has no status.
 */
@SysUISingleton
class IslandLockStatus @Inject constructor(
    private val context: Context,
    private val keyguard: KeyguardStateController,
    @Main private val handler: Handler,
) {
    enum class State(val wire: String?) { NONE(null), LOCKED(IslandActivity.STATUS_LOCKED), UNLOCKED(IslandActivity.STATUS_UNLOCKED) }

    private val mutable = MutableStateFlow(State.NONE)
    val state: StateFlow<State> = mutable.asStateFlow()
    private var wasShowing = false
    private val end = Runnable { mutable.value = State.NONE }
    private val activities = mapOf(State.LOCKED to build("Locked", State.LOCKED), State.UNLOCKED to build("Unlocked", State.UNLOCKED))

    init {
        keyguard.addCallback(object : KeyguardStateController.Callback {
            override fun onKeyguardShowingChanged() = update()
            override fun onUnlockedChanged() = update()
        })
        update()
    }

    private fun update() {
        val showing = keyguard.isShowing
        val locked = showing && !keyguard.canDismissLockScreen()
        when {
            locked -> {
                handler.removeCallbacks(end)
                mutable.value = State.LOCKED
            }
            mutable.value == State.LOCKED -> {
                mutable.value = State.UNLOCKED
                endLater()
            }
            // The keyguard has just gone: the open padlock moves to the status bar.
            mutable.value == State.UNLOCKED && wasShowing && !showing -> endLater()
        }
        wasShowing = showing
    }

    private fun endLater() {
        handler.removeCallbacks(end)
        handler.postDelayed(end, UNLOCKED_MS)
    }

    /** The capsule content for the current state, or null without a status. */
    fun activity(): IslandActivity? = activities[mutable.value]

    /** The open padlock only: what the status bar shows after the keyguard has gone. */
    fun unlocked(): IslandActivity? = activity().takeIf { mutable.value == State.UNLOCKED }

    /** Ends the open padlock early (a tap on it). The locked state cannot be dismissed. */
    fun dismiss() {
        if (mutable.value != State.UNLOCKED) return
        handler.removeCallbacks(end)
        mutable.value = State.NONE
    }

    // The status belongs to the device, not to a user's notifications: SystemUI's own user (0).
    private fun build(title: String, status: State) = IslandActivity(KEY, context.packageName, Process.myUid(),
        0, JSONObject().put("version", 2).put("status", status.wire).put("compact", JSONObject())
            .put("expanded", JSONObject().put("template", "basic").put("title", title)),
        emptyMap(), emptyMap(), null, null, null, source = IslandActivity.SOURCE_STATUS)

    companion object {
        const val KEY = "taku:status:lock"
        /** How long the open padlock stays before the island returns to normal. */
        const val UNLOCKED_MS = 1500L
    }
}
