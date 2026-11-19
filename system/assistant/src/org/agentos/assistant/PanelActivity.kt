package org.agentos.assistant

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/** Existing notification and shortcut targets address this component by name. */
class PanelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val opened = ComposerState.userError(this, android.os.Process.myUid()) == null && runCatching {
            val args = intent.extras ?: Bundle.EMPTY
            when {
                args.containsKey(TaskNotificationReceiver.EXTRA_TASK_ID) -> openTask(args.getString(TaskNotificationReceiver.EXTRA_TASK_ID))
                args.containsKey("page") -> openPage(args.getString("page").orEmpty())
                args.containsKey("conversationId") -> args.getString("conversationId")?.let { AgentFrontendLauncher.openThread(this, it) } == true
                else -> AgentFrontendLauncher.openRoute(this, "/")
            }
        }.getOrDefault(false)
        if (!opened) Toast.makeText(this, "Taku couldn't open this link.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun openPage(page: String): Boolean {
        val route = when (page) {
            "modelConnection" -> "/system-ai?section=connection"
            "account" -> "/system-ai?section=account"
            "aiUsage" -> "/system-ai?section=usage"
            "jevConnection" -> "/system-ai?section=jev"
            else -> return false
        }
        return AgentFrontendLauncher.openRoute(this, route)
    }

    private fun openTask(id: String?): Boolean {
        if (id == null || !TaskDetails.validTaskId(id)) return AgentFrontendLauncher.openRoute(this, "/task-detail")
        val thread = TaskDetails.notificationThread(filesDir, id)
        return if (thread == null) AgentFrontendLauncher.openRoute(this, "/task-detail?taskId=$id")
            else AgentFrontendLauncher.openThread(this, thread)
    }
}
