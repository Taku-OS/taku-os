package org.agentos.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Restore durable owners after unlock/reboot/time changes. */
class MatterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread({
            try {
                val unlockManager = context.getSystemService(android.os.UserManager::class.java)
                if (unlockManager == null || unlockManager.isUserUnlocked) {
                    TakuAccountTokenServer.ensureStarted(context)
                    TakuAuthManager.get(context).onUserUnlocked()
                }
                val runtime = MatterRuntime.get(context)
                runtime.migrateCards()
                runtime.recoverPanelUpdates()
                runtime.recover()
            } catch (_: Exception) {
            } finally {
                if (!Handler(Looper.getMainLooper()).post {
                    try { BackgroundScreenTasks.restore(context) }
                    finally { pending.finish() }
                }) pending.finish()
            }
        }, "matter-recovery").start()
    }
}
