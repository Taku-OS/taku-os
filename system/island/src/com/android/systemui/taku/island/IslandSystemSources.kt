/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.Context
import android.media.projection.StopReason
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.screenrecord.ScreenRecordUxController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.CastController
import com.android.systemui.statusbar.policy.CastDevice
import java.util.concurrent.Executor
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject

/** Native controller callbacks. No notification publisher, launcher, JS or AI is required. */
class IslandSystemSources @Inject constructor(
    private val context: Context,
    private val engine: IslandEngine,
    private val recording: ScreenRecordUxController,
    private val casting: CastController,
    private val users: UserTracker,
    @Main private val handler: Handler,
    @Main private val executor: Executor,
) {
    private val published = mutableSetOf<String>()
    private val started = mutableMapOf<String, Long>()
    fun start() {
        recording.addCallback(object : ScreenRecordUxController.StateChangeCallback {
            override fun onRecordingStart() { handler.post(::refresh) }
            override fun onRecordingEnd() { handler.post(::refresh) }
            override fun onCountdownEnd() { handler.post(::refresh) }
        })
        casting.addCallback(object : CastController.Callback {
            override fun onCastDevicesChanged() { handler.post(::refresh) }
        })
        users.addCallback(object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) { refresh() }
            override fun onProfilesChanged(profiles: List<android.content.pm.UserInfo>) { refresh() }
        }, executor)
        refresh()
    }
    private fun refresh() {
        val current = mutableSetOf<String>()
        if (recording.isRecording) {
            val key = "taku:system:recording:${users.userId}"
            current += key
            publish(key, "Screen recording", "Recording your screen", "recording") {
                check(recording.isRecording) { "source_ended" }
                recording.stopRecording(StopReason.STOP_QS_TILE)
            }
        }
        casting.castDevices.filter { it.isCasting }.forEach { device ->
            // Recording is also a projection into SystemUI; show it only once.
            if (recording.isRecording && device.origin == CastDevice.CastOrigin.MediaProjection && device.id == context.packageName) return@forEach
            val key = "taku:system:cast:${users.userId}:${device.origin}:${device.id}"
            current += key
            publish(key, "Screen sharing", device.name ?: device.description ?: "Casting", if (device.origin == CastDevice.CastOrigin.MediaProjection) "projection" else "cast") {
                val active = casting.castDevices.find { it.id == device.id && it.origin == device.origin && it.isCasting }
                    ?: throw IllegalStateException("source_ended")
                casting.stopCasting(active, StopReason.STOP_QS_TILE)
            }
        }
        (published - current).forEach { engine.removeSystem(it); started.remove(it) }
        engine.reconcileSystemKeys(current)
        published.clear(); published.addAll(current)
    }
    private fun publish(key: String, title: String, body: String, source: String, stop: () -> Unit) {
        val beginning = started.getOrPut(key) { System.currentTimeMillis() }
        val payload = JSONObject().put("version", 2)
            .put("compact", JSONObject().put("b", JSONObject().put("type", "timer").put("startEpochMs", beginning)))
            .put("expanded", JSONObject().put("template", "basic").put("title", title).put("body", body.take(2000))
                .put("actions", JSONArray(listOf("stop"))))
            .put("systemSource", source)
        engine.putSystem(IslandActivity(key, context.packageName, Process.myUid(), users.userId, payload,
            emptyMap(), emptyMap(), null, null, null, mapOf("stop" to IslandNativeAction("Stop", stop)),
            source = IslandActivity.SOURCE_SYSTEM, tier = IslandStore.TIER_RECORDING),
            SystemClock.elapsedRealtime())
    }
}
