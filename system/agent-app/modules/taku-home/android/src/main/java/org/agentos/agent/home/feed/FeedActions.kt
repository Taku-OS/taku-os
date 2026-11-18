package org.agentos.agent.home.feed

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent as AndroidIntent
import android.net.Uri
import org.json.JSONObject

object FeedActions {
  /** Extra read by the conversation screen (`TakuAgentModule.launchDraft`). */
  const val EXTRA_DRAFT = "org.agentos.agent.extra.DRAFT"
  private const val CONVERSATION = "org.agentos.agent.MainActivity"

  fun dispatch(context: Context, cardId: String, actionId: String, values: JSONObject, origin: FeedOrigin): JSONObject {
    val repo = FeedRepository.get(context)
    val captured = FeedSelectionValues.capture(values)
    val accepted = repo.acceptAction(FeedActionRequest.Declared(cardId, actionId, captured), origin)
    val card = accepted.card
    val action = accepted.action
    val selected = captured.json()
    val result = when (val intent = action.intent) {
      is Intent.AskTaku -> askTaku(context, draft(intent.prompt, card, selected))
      is Intent.OpenApp -> openApp(context, intent)
      is Intent.OpenUrl -> start(context, AndroidIntent(AndroidIntent.ACTION_VIEW, Uri.parse(intent.url)), "open-url")
      Intent.Dismiss -> repo.remove(cardId, origin).put("effect", "dismiss")
      Intent.None -> JSONObject().put("effect", "none").put("note", "This card has no connected integration; nothing was executed.")
    }
    result.put("eventCursor", accepted.cursor.toString())
    return result.put("cardId", cardId).put("actionId", actionId)
  }

  /** Long-press "Ask Taku": opens the conversation with the card as context. */
  fun askAbout(context: Context, cardId: String, origin: FeedOrigin): JSONObject {
    val accepted = FeedRepository.get(context).acceptAction(FeedActionRequest.AskAbout(cardId), origin)
    val result = askTaku(context, (accepted.action.intent as Intent.AskTaku).prompt)
    return result.put("cardId", cardId).put("eventCursor", accepted.cursor.toString())
  }

  private fun draft(prompt: String, card: FeedCard, values: JSONObject): String {
    if (values.length() == 0) return prompt
    val chosen = values.keys().asSequence().joinToString(", ") { key -> "${key.substringAfterLast('/')}=${values.opt(key)}" }
    return "$prompt\n\n(${card.app} card selections: $chosen)"
  }

  private fun askTaku(context: Context, draft: String): JSONObject {
    val intent = AndroidIntent().setComponent(ComponentName(context.packageName, CONVERSATION))
      .addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK or AndroidIntent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra(EXTRA_DRAFT, draft)
    return start(context, intent, "ask-taku").put("draft", draft)
  }

  private fun openApp(context: Context, target: Intent.OpenApp): JSONObject {
    val intent = if (target.uri != null) AndroidIntent(AndroidIntent.ACTION_VIEW, Uri.parse(target.uri)).setPackage(target.packageName)
    else context.packageManager.getLaunchIntentForPackage(target.packageName)
      ?: throw FeedException("APP_NOT_INSTALLED", "${target.packageName} is not installed")
    return start(context, intent, "open-app").put("package", target.packageName)
  }

  private fun start(context: Context, intent: AndroidIntent, effect: String): JSONObject {
    try {
      context.startActivity(intent.addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
      throw FeedException("NO_ACTIVITY", "nothing can handle $effect: ${e.message}")
    }
    return JSONObject().put("effect", effect)
  }
}
