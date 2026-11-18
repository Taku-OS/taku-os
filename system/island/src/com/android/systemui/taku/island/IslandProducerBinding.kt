/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import java.util.concurrent.Executor
import javax.inject.Inject

/** Keeps the notification projection alive independently of home/assistant Activities and rich UI. */
@SysUISingleton
class IslandProducerBinding @Inject constructor(private val context: Context, private val users: UserTracker,
    @Main private val handler: Handler, @Main private val executor: Executor) : ServiceConnection {
    private var bound = false
    private var retryMs = 1000L
    private val retry = Runnable { bind() }

    fun start() {
        users.addCallback(object : UserTracker.Callback {
            override fun onUserChanged(newUser: Int, userContext: Context) { rebind() }
        }, executor)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { rebind() }
        }, IntentFilter(Intent.ACTION_USER_UNLOCKED), Context.RECEIVER_NOT_EXPORTED)
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == PACKAGE) rebind()
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED); addDataScheme("package")
        }, Context.RECEIVER_NOT_EXPORTED)
        bind()
    }

    private fun rebind() {
        handler.removeCallbacks(retry)
        if (bound) { context.unbindService(this); bound = false }
        retryMs = 1000
        bind()
    }

    private fun bind() {
        if (bound) return
        try {
            bound = context.bindServiceAsUser(Intent().setComponent(ComponentName(PACKAGE, SERVICE)),
                this, Context.BIND_AUTO_CREATE, UserHandle.of(users.userId))
            IslandRuntime.producerStatus = if (bound) "binding" else "unavailable"
        } catch (failure: RuntimeException) { IslandRuntime.producerStatus = "unavailable:${failure.javaClass.simpleName}" }
        if (!bound) {
            handler.removeCallbacks(retry)
            handler.postDelayed(retry, retryMs)
            retryMs = (retryMs * 2).coerceAtMost(30_000)
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        retryMs = 1000
        IslandRuntime.producerStatus = "connected"
    }
    override fun onServiceDisconnected(name: ComponentName) { IslandRuntime.producerStatus = "reconnecting" }
    override fun onBindingDied(name: ComponentName) { rebind() }
    override fun onNullBinding(name: ComponentName) {
        if (bound) { context.unbindService(this); bound = false }
        IslandRuntime.producerStatus = "null_binding"
        handler.postDelayed(retry, 30_000)
    }
    companion object {
        const val PACKAGE = "org.agentos.agent"
        const val SERVICE = "org.agentos.agent.home.island.IslandPublisherService"
    }
}
