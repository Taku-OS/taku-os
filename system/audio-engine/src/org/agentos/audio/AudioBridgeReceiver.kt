// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.*
import android.os.*

/** Shell/root bootstrap only; the manifest requires DUMP and the socket checks peer UID. */
class AudioBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) { AudioCliHost.start(context.applicationContext) }
}

/** The CLI binding lives only while a socket attaches or remains connected. Main thread only. */
internal object AudioCliHost {
    private val main=Handler(Looper.getMainLooper())
    private var context: Context?=null
    private var bound=false
    private var peers=0
    private val pending=java.util.ArrayDeque<Runnable>()
    private val connection=object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName,binder: IBinder) {}
        override fun onServiceDisconnected(name: ComponentName) { release() }
        override fun onBindingDied(name: ComponentName) { release() }
        override fun onNullBinding(name: ComponentName) { release() }
    }
    fun start(context: Context) {
        if(!bound) {
            this.context=context
            bound=context.bindService(Intent().setComponent(ComponentName(context,AudioEngineService::class.java)),connection,Context.BIND_AUTO_CREATE)
            if(!bound){this.context=null;return}
        }
        val expiry=object : Runnable { override fun run(){pending.remove(this);releaseIfIdle()} }
        pending.addLast(expiry);main.postDelayed(expiry,7000)
    }
    fun attached() {
        peers++
        pending.pollFirst()?.let{main.removeCallbacks(it)}
    }
    fun detached() { peers--;releaseIfIdle() }
    private fun releaseIfIdle() { if(peers==0 && pending.isEmpty())release() }
    private fun release() {
        pending.forEach{main.removeCallbacks(it)};pending.clear()
        if(bound)context?.let{runCatching{it.unbindService(connection)}}
        bound=false;context=null
    }
}
