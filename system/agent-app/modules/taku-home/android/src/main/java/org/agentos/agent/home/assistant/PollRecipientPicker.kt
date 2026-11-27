package org.agentos.agent.home.assistant

import android.app.Activity
import android.content.ComponentName
import android.content.Intent

/** Result-only WhatsApp chooser hosted by the already declared assistant overlay. */
@Suppress("DEPRECATION")
internal object PollRecipientPicker {
  const val REQUEST = 7301
  fun open(activity: Activity, session: AssistantSession, token: String) {
    if (session.state.value.share?.pollPickerId != token || token.isEmpty()) return
    try {
      // The shortcut result includes both the group title and JID. Intersect
      // with ACTION_PICK to select the chat chooser rather than the separate
      // camera shortcut activity. ACTION_SEND would attach ordinary content.
      val pick = Intent(Intent.ACTION_CREATE_SHORTCUT).setPackage("com.whatsapp")
      val chatPickers = activity.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_PICK).setPackage("com.whatsapp").addCategory("com.whatsapp"), 0)
        .map { it.activityInfo.name }.toSet()
      val targets = activity.packageManager.queryIntentActivities(pick, 0).map { it.activityInfo }
        .filter { it.packageName == "com.whatsapp" && it.exported && it.enabled && it.name in chatPickers }
        .distinctBy { it.name }
      if (targets.size != 1) throw AssistantException("WHATSAPP_PICKER_UNAVAILABLE", "WhatsApp group selection is unavailable. The poll has not been sent.")
      pick.component = ComponentName("com.whatsapp", targets.single().name)
      activity.startActivityForResult(pick, REQUEST)
    } catch (e: Exception) {
      session.completePollRecipient(token, null, (e as? AssistantException)?.message ?: "WhatsApp could not open. The poll has not been sent.")
    }
  }
  fun result(session: AssistantSession, token: String, resultCode: Int, data: Intent?) {
    try {
      if (resultCode != Activity.RESULT_OK) session.completePollRecipient(token, null)
      else {
        val result = data ?: throw AssistantException("POLL_RECIPIENT_UNAVAILABLE", "The selected group could not be read. Choose it again. The poll has not been sent.")
        val chat = result.getParcelableExtra<Intent>(Intent.EXTRA_SHORTCUT_INTENT)
        // Only read data from the selected app. Do not execute a nested Intent.
        if (chat?.component?.packageName != "com.whatsapp" ||
          (chat.`package` != null && chat.`package` != "com.whatsapp"))
          throw AssistantException("POLL_RECIPIENT_UNAVAILABLE", "The selected group could not be read. Choose it again. The poll has not been sent.")
        val recipient = PollRecipient.selected(chat.getStringExtra("jid"), result.getStringExtra(Intent.EXTRA_SHORTCUT_NAME))
        session.completePollRecipient(token, recipient)
      }
    } catch (e: Exception) {
      session.completePollRecipient(token, null, (e as? AssistantException)?.message ?: "The selected group could not be read. Choose it again. The poll has not been sent.")
    }
  }
}
