package org.agentos.assistant

import android.app.Activity
import android.os.Bundle

/** Existing notification and shortcut targets address this component by name. */
class PanelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val opened = runCatching {
            val args = intent.extras ?: Bundle.EMPTY
            when {
                args.containsKey(TaskNotificationReceiver.EXTRA_TASK_ID) -> openTask(args.getString(TaskNotificationReceiver.EXTRA_TASK_ID))
                args.containsKey("page") -> openPage(args.getString("page").orEmpty())
                else -> openConversation(args.getString("conversationId"))
            }
        }.getOrDefault(false)
        if (!opened) AgentFrontendLauncher.showUnavailable(this, R.string.taku_link_unavailable)
        finish()
    }

    /** Every page an old link can name was a settings page; one this version no longer has opens settings. */
    private fun openPage(page: String): Boolean = AgentFrontendLauncher.openRoute(this, when (page) {
        "modelConnection" -> "/system-ai?section=connection"
        "account" -> "/system-ai?section=account"
        "aiUsage" -> "/system-ai?section=usage"
        "jevConnection" -> "/system-ai?section=jev"
        else -> "/system-ai"
    })

    /**
     * The conversation the link names, else the one the user last had open, else a new one. A
     * link whose conversation id is malformed still opens the chat.
     */
    private fun openConversation(requested: String?): Boolean {
        val last = getSharedPreferences("agent-settings", MODE_PRIVATE).getString("conversation", "").orEmpty()
        for (thread in listOf(requested.orEmpty(), last))
            if (AgentGatewayProtocol.isUuid(thread) && AgentFrontendLauncher.openThread(this, thread)) return true
        return AgentFrontendLauncher.openRoute(this, "/")
    }

    private fun openTask(id: String?): Boolean {
        if (id == null || !TaskDetails.validTaskId(id)) return AgentFrontendLauncher.openRoute(this, "/task-detail")
        val thread = TaskDetails.notificationThread(filesDir, id)
        return if (thread == null) AgentFrontendLauncher.openRoute(this, "/task-detail?taskId=$id")
            else AgentFrontendLauncher.openThread(this, thread)
    }
}
