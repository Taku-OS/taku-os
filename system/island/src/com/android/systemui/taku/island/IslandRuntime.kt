/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.os.Handler
import android.os.Looper
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Provider is created by Android before Dagger startup; it never creates another repository. */
object IslandRuntime {
    @Volatile var engine: IslandEngine? = null
    @Volatile var producerStatus = "starting"
    private val handler = Handler(Looper.getMainLooper())

    fun call(method: String, arg: String?, extra: String?): JSONObject {
        if (Looper.myLooper() == Looper.getMainLooper()) return invoke(method, arg, extra)
        val task = FutureTask { invoke(method, arg, extra) }
        if (!handler.post(task)) return error("systemui_unavailable")
        return try { task.get(5, TimeUnit.SECONDS) }
        catch (failure: Exception) {
            task.cancel(false)
            error("systemui_timeout")
        }
    }

    private fun invoke(method: String, arg: String?, extra: String?): JSONObject = try {
        engine?.command(method, arg, extra) ?: error("systemui_starting")
    } catch (failure: Exception) { error(failure.message ?: "operation_failed") }

    fun error(code: String): JSONObject = JSONObject().put("ok", false).put("error", code.take(160))
}
