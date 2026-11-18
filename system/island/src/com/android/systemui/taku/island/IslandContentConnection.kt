/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.UserHandle
import android.view.SurfaceControlViewHost
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import org.agentos.island.IslandContentProtocol as Wire

/** Never blocks SystemUI on the producer; late replies and binder death leave the template usable. */
internal class IslandContentConnection(
    private val context: Context,
    private val activity: IslandActivity,
    private val view: SurfaceView,
    private val onReady: (Boolean) -> Unit,
) : ServiceConnection, SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private var bound = false
    private var closed = false
    private var remote: Messenger? = null
    private var binder: IBinder? = null
    private var deathLinked = false
    private var generation = 0
    private var surface: SurfaceControlViewHost.SurfacePackage? = null
    private val death = IBinder.DeathRecipient { handler.post { fail() } }
    private val timeout = Runnable { fail() }
    private val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val candidate = try { message.data.getParcelable(Wire.SURFACE, SurfaceControlViewHost.SurfacePackage::class.java) }
                catch (invalid: RuntimeException) { null }
            if (closed || message.arg1 != generation || message.what != Wire.READY || candidate == null) {
                candidate?.release()
                if (!closed && message.arg1 == generation && message.what == Wire.FAILED) fail()
                return
            }
            try {
                view.setChildSurfacePackage(candidate)
                surface?.release()
                surface = candidate
                handler.removeCallbacks(timeout)
                onReady(true)
            } catch (invalid: RuntimeException) {
                candidate.release()
                fail()
            }
        }
    })

    fun start() {
        val component = activity.contentService ?: return
        // Embedded SCVH views only receive touch when above their host window.
        // The child is translucent; SystemUI's fallback is removed only after READY.
        view.setZOrderOnTop(true)
        view.holder.addCallback(this)
        try {
            bound = context.bindServiceAsUser(Intent().setComponent(component), this,
                Context.BIND_AUTO_CREATE, UserHandle.of(activity.userId))
            if (!bound) fail() else handler.postDelayed(timeout, 2500)
        } catch (failure: RuntimeException) { fail() }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        if (closed) return
        binder = service
        try {
            service.linkToDeath(death, 0)
            deathLinked = true
            remote = Messenger(service)
            attach()
        } catch (failure: Exception) { fail() }
    }
    override fun onServiceDisconnected(name: ComponentName) = fail()
    override fun onBindingDied(name: ComponentName) = fail()
    override fun onNullBinding(name: ComponentName) = fail()
    override fun surfaceCreated(holder: SurfaceHolder) = attach()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = attach()
    override fun surfaceDestroyed(holder: SurfaceHolder) { releaseRemote(); onReady(false) }

    private fun attach() {
        val service = remote ?: return
        if (closed || !view.holder.surface.isValid || view.width <= 0 || view.height <= 0) return
        releaseRemote()
        val message = Message.obtain(null, Wire.ATTACH).apply {
            arg1 = ++generation
            replyTo = receiver
            data = Bundle().apply {
                putInt(Wire.VERSION_KEY, Wire.VERSION)
                putBinder(Wire.HOST_TOKEN, view.hostToken)
                putInt(Wire.WIDTH, view.width.coerceAtMost(Wire.MAX_DIMENSION))
                putInt(Wire.HEIGHT, view.height.coerceAtMost(Wire.MAX_DIMENSION))
                putInt(Wire.DISPLAY, view.display.displayId)
                putString(Wire.TOKEN, activity.payload.getJSONObject("expanded").optJSONObject("content")?.optString("token").orEmpty())
            }
        }
        try {
            service.send(message)
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, 2500)
        } catch (failure: Exception) { fail() }
    }

    private fun releaseRemote() {
        try { remote?.send(Message.obtain(null, Wire.RELEASE).apply { arg1 = generation; replyTo = receiver }) }
        catch (ignored: Exception) { /* Binder death uses the same template fallback. */ }
        surface?.release()
        surface = null
    }

    private fun fail() {
        close()
        onReady(false)
    }

    fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(timeout)
        releaseRemote()
        if (deathLinked) {
            try { binder?.unlinkToDeath(death, 0) } catch (ignored: RuntimeException) {}
            deathLinked = false
        }
        binder = null
        remote = null
        view.holder.removeCallback(this)
        view.visibility = View.GONE
        if (bound) {
            bound = false
            context.unbindService(this)
        }
    }
}
