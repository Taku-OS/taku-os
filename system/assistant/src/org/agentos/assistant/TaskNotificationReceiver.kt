package org.agentos.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.util.concurrent.Executors
import org.json.JSONObject

class TaskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (DiningPollIsland.receive(this, context, intent)) return
        try {
            if (intent.action == "org.agentos.assistant.WORKSPACE_REVOKED") {
                showRevocation(context, JSONObject(intent.getStringExtra("revocation")))
                return
            }
            val id = intent.getStringExtra(EXTRA_TASK_ID) ?: return
            if (!TaskDetails.validTaskId(id)) return
            val pending = goAsync()
            PANEL_COMPLETIONS.execute {
                try {
                    MatterRuntime.get(context).taskChanged(id)
                } catch (failure: Exception) {
                    Log.w("PanelCompletion", "Durable completion will retry during recovery", failure)
                } finally {
                    pending.finish()
                }
            }
            if (BackgroundScreenTasks.ownsTask(context, id)) {
                BackgroundScreenTasks.restore(context)
                return // Progress, questions and results are owned by the system surface.
            }
            val state = TaskDetails.notificationState(context.filesDir, id)
            val running = state.optString("state") == "running"
            val manager = taskNotifications(context)
            // Keep the Activity target for old notifications. Data distinguishes each task's PendingIntent.
            val open = Intent(context, PanelActivity::class.java)
                .setData(Uri.fromParts("taku-task", id, null))
                .putExtra(EXTRA_TASK_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val link = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE)
            val title = when (state.optString("state")) {
                "cancelled" -> "Taku stopped"
                "needs_user" -> "Taku needs your attention"
                "running" -> "Taku is running in the background"
                "failed" -> "Taku execution failed"
                else -> "Taku completed"
            }
            val message = if (running) state.optString("prompt") else state.optString("message")
            val notice = Notification.Builder(context, "codex_tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(link)
                .setOnlyAlertOnce(true)
                .setOngoing(running)
                .setAutoCancel(!running)
                .build()
            manager.notify(42, notice)
        } catch (failure: Exception) {
            Log.e("AgentOSNotify", "Cannot update task notification", failure)
        }
    }

    companion object {
        const val EXTRA_TASK_ID = "task_id"
        private val PANEL_COMPLETIONS = Executors.newSingleThreadExecutor()

        private fun taskNotifications(context: Context): NotificationManager {
            val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
            manager.createNotificationChannel(NotificationChannel(
                "codex_tasks", "Taku phone task", NotificationManager.IMPORTANCE_DEFAULT))
            return manager
        }

        private fun showRevocation(context: Context, receipt: JSONObject) {
            val copy = when (receipt.getString("status")) {
                "released" -> R.string.workspace_revocation_released
                "retained" -> R.string.workspace_revocation_retained
                "failed" -> R.string.workspace_revocation_failed
                else -> throw IllegalArgumentException("Unexpected workspace revocation status")
            }
            val labels = ArrayList<String>()
            val packages = if (receipt.getString("status") == "released" && receipt.has("released")) {
                receipt.getJSONArray("released")
            } else {
                receipt.getJSONArray("packageNames")
            }
            val manager = context.packageManager
            for (i in 0 until packages.length()) {
                val pkg = packages.getString(i)
                try {
                    labels.add(manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString())
                } catch (_: PackageManager.NameNotFoundException) {
                    labels.add(pkg)
                }
            }
            val task = receipt.optString("taskTitle").ifBlank { context.getString(R.string.workspace_task_fallback) }
            val message = context.getString(copy, labels.joinToString(", "), task) + " " +
                context.getString(if (receipt.optBoolean("screenRetained")) {
                    R.string.workspace_revocation_screen_retained
                } else {
                    R.string.workspace_revocation_screen_released
                })
            val open = Intent(context, BackgroundWorkspacesActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val link = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE)
            val notice = Notification.Builder(context, "codex_tasks")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.workspace_revocation_title))
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(link)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            val tag = "workspace-revocation-${receipt.getString("screenKey")}-${receipt.getString("threadId")}"
            taskNotifications(context).notify(tag, 1, notice)
        }
    }
}
