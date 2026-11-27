package org.agentos.agent.home.island

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.AgentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject

class IslandActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val session = AssistantSession.get(context)
    val thread = intent.getStringExtra("thread") ?: return
    val activity = intent.getStringExtra("activity") ?: return
    if (session.state.value.threadId == null && thread != "entry" &&
        context.getSharedPreferences("system_island_session", Context.MODE_PRIVATE).getString("thread", null) == thread) {
      session.resume(thread)
      session.restoreIslandIdentity(context.getSharedPreferences("system_island_session", Context.MODE_PRIVATE).getString("activity", null))
    }
    if ((session.state.value.threadId ?: "entry") != thread || (session.state.value.activityId ?: "entry") != activity) return
    val pending = goAsync()
    scope.launch {
      try {
        withTimeout(5000) {
          session.state.first { state -> (state.threadId ?: "entry") != thread || (state.activityId ?: "entry") != activity || when (intent.action) {
            "stop" -> state.taskId != null || state.turn?.active == true
            "reply" -> state.turn?.suggestions?.any { it.id == intent.getStringExtra("suggestion") } == true
            "retry" -> state.retryText != null
            else -> true
          } }
        }
        if ((session.state.value.threadId ?: "entry") != thread || (session.state.value.activityId ?: "entry") != activity) return@launch
        val kind = intent.action
        if (kind == "pollContinue" || kind == "pollCheck") {
          val owner = PollSendOwner.from(session.state.value) ?: return@launch
          if (intent.data?.toString() != owner.actionIdentity(kind)) return@launch
          val observation = withContext(Dispatchers.IO) {
            PollSendObservation.from(owner, AgentGateway(context.contentResolver).call("diningPoll",
              JSONObject().put("action", "status").put("args", JSONObject().put("intentId", owner.intentId))))
          }
          if (!owner.owns(session.state.value) ||
            pollSendPresentation(session.state.value, observation)?.actions?.none { it.first == kind } != false) return@launch
          session.retryPollSend(kind == "pollCheck")
          return@launch
        }
        when (intent.action) {
          "open" -> session.openConversation()
          "options" -> session.showOptions(intent.getStringExtra("suggestion").orEmpty())
          "stop" -> session.stop()
          "reply" -> session.reply(intent.getStringExtra("suggestion").orEmpty())
          "retry" -> session.retry()
        }
      } catch (failure: Exception) { android.util.Log.w("TakuIsland", "Action rejected", failure) }
      finally { pending.finish() }
    }
  }
  companion object { private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
}
