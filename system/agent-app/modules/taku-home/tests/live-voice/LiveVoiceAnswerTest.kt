package org.agentos.agent.home

import org.agentos.agent.home.assistant.LiveVoiceAnswer
import org.json.JSONArray
import org.json.JSONObject

private fun check(condition: Boolean, label: String) { if (!condition) throw AssertionError(label) }
private fun answered(d: LiveVoiceAnswer.Decision) = (d as? LiveVoiceAnswer.Answer)?.response
private fun refused(d: LiveVoiceAnswer.Decision) = d is LiveVoiceAnswer.Refuse

fun main() {
  val ask = JSONObject().put("question", "Which flight?").put("options", JSONArray(listOf("Morning", "Evening")))
  check(answered(LiveVoiceAnswer.decide("phone/askUser", ask, 1, "the evening one"))?.getInt("optionIndex") == 1, "option chosen by voice")
  check(refused(LiveVoiceAnswer.decide("phone/askUser", ask, null, "evening")), "an answer needs an option index")
  check(refused(LiveVoiceAnswer.decide("phone/askUser", ask, 2, "third")), "index must be offered")
  check(refused(LiveVoiceAnswer.decide("phone/askUser", ask, -1, "none")), "negative index refused")
  check(refused(LiveVoiceAnswer.decide("phone/askUser", JSONObject().put("question", "Name?"), 0, "Ann")), "free text stays on screen")
  check(refused(LiveVoiceAnswer.decide("phone/askUser", JSONObject(ask.toString()).put("isSecret", true), 0, "Morning")), "secret answers stay on screen")

  val confirm = JSONObject().put("title", "Pay NT$1,200?").put("confirmLabel", "Pay")
  check(refused(LiveVoiceAnswer.decide("phone/confirmAction", confirm, 0, "yes pay it")), "voice never confirms a high-risk action")
  check(refused(LiveVoiceAnswer.decide("phone/confirmAction", confirm, null, "confirm")), "spoken confirm refused")
  check(refused(LiveVoiceAnswer.decide("phone/confirmAction", confirm, null, "don't cancel, go ahead")), "only an explicit cancellation counts")
  check(answered(LiveVoiceAnswer.decide("phone/confirmAction", confirm, null, "Cancel."))?.getString("decision") == "cancel", "explicit cancel")
  check(answered(LiveVoiceAnswer.decide("phone/confirmAction", confirm, null, "取消。"))?.getString("decision") == "cancel", "Chinese cancel")

  check(refused(LiveVoiceAnswer.decide("phone/action/requestApproval", JSONObject(), 0, "allow")), "approvals stay on screen")
  check(refused(LiveVoiceAnswer.decide("item/tool/requestUserInput", JSONObject(), 0, "x")), "other requests stay on screen")
  // Keyguard Action Gate decisions and locked status (R1-B2).
  check(LiveVoiceAnswer.lockedGate(false, "submit") == null, "unlocked proceeds")
  for (action in listOf("submit", "steer", "correct", "cancel", "confirm"))
    check(LiveVoiceAnswer.lockedGate(true, action) == "UNLOCK_REQUIRED", "locked $action needs unlock")
  check(LiveVoiceAnswer.lockedGate(true, "status") == "status", "locked status answers without details")
  val lockedStatus = LiveVoiceAnswer.lockedStatus(2, 1)
  check(lockedStatus.contains("2 task(s) running") && lockedStatus.contains("1 finished") && !Regex("[0-9]{3,}").containsMatchIn(lockedStatus), "counts only, no task ids")
  check(LiveVoiceAnswer.lockedStatus(0, 0).startsWith("No task"), "no tasks")
  println("PASS LiveVoiceAnswerTest: askUser option answers, confirmAction cancel-only, approvals and secrets stay on screen")
}
