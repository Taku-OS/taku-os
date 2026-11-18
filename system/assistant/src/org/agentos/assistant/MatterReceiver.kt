package org.agentos.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restore durable owners after unlock/reboot/time changes. */
class MatterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        Thread({
            try {
                val runtime = MatterRuntime.get(context)
                runtime.migrateCards()
                runtime.recoverPanelUpdates()
                runtime.recover()
                BackgroundScreenTasks.restore(context)
            } catch (_: Exception) {
            } finally { pending.finish() }
        }, "matter-recovery").start()
    }
}
