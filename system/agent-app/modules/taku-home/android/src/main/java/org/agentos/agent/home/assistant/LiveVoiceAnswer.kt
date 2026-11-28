package org.agentos.agent.home.assistant

import org.json.JSONObject

/**
 * What a spoken `confirm` may answer. By voice the user can pick one of a `phone/askUser` question's
 * options, or cancel a `phone/confirmAction` with an explicit cancellation. Confirming a high-risk
 * action, approvals, free-text and secret answers always stay on screen.
 */
internal object LiveVoiceAnswer {
  sealed interface Decision
  data class Answer(val response: JSONObject) : Decision
  data class Refuse(val message: String) : Decision

  val EXPLICIT_CANCEL = setOf("cancel", "cancel it", "cancel that", "cancel the task", "cancel my task", "stop the task",
    "取消", "取消任务", "取消这个任务", "取消刚才的任务", "停止任务")

  /** Keyguard Action Gate for a tool call: what a locked phone answers instead, or null to proceed. */
  fun lockedGate(locked: Boolean, action: String): String? = when {
    !locked -> null
    action == "status" -> "status"
    else -> "UNLOCK_REQUIRED"
  }

  /** Progress while locked: counts only, never task ids or content. */
  fun lockedStatus(running: Int, finished: Int): String =
    if (running + finished == 0) "No task has been delegated in this Live Talk session."
    else "$running task(s) running and $finished finished. The phone is locked; details are available after unlocking."

  fun explicitCancel(text: String): Boolean = text.trim().lowercase().trimEnd('.', '!', '。', '！') in EXPLICIT_CANCEL

  fun decide(method: String, arguments: JSONObject, optionIndex: Int?, spoken: String): Decision = when (method) {
    "phone/askUser" -> {
      val options = arguments.optJSONArray("options")
      when {
        arguments.optBoolean("isSecret") -> Refuse("This answer is private. Ask the user to answer on screen.")
        options == null || optionIndex == null || optionIndex !in 0 until options.length() ->
          Refuse("Give optionIndex for one of the offered options, or ask the user to answer on screen.")
        else -> Answer(JSONObject().put("optionIndex", optionIndex))
      }
    }
    "phone/confirmAction" -> if (explicitCancel(spoken)) Answer(JSONObject().put("decision", "cancel"))
      else Refuse("This action must be confirmed on screen; by voice it can only be cancelled.")
    else -> Refuse("This request must be answered on screen.")
  }
}
