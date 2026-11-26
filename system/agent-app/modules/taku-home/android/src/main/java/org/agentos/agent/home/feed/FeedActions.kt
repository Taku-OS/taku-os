package org.agentos.agent.home.feed

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent as AndroidIntent
import android.content.pm.PackageManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.agentos.agent.home.MainThread
import org.agentos.agent.home.assistant.AgentGateway
import org.agentos.agent.home.assistant.AssistantSession
import org.agentos.agent.home.assistant.GatewayException
import org.agentos.agent.home.assistant.HomeComposer
import org.json.JSONObject
import java.util.UUID

object FeedActions {
  private const val CONVERSATION = "org.agentos.agent.MainActivity"

  suspend fun dispatch(context: Context, cardId: String, actionId: String, values: JSONObject, origin: FeedOrigin): JSONObject {
    val repo = FeedRepository.get(context)
    val captured = FeedSelectionValues.capture(values)
    val accepted = repo.acceptAction(FeedActionRequest.Declared(cardId, actionId, captured), origin)
    val card = accepted.card
    val action = accepted.action
    val selected = captured.json()
    val intent = action.intent
    val result = when (intent) {
      is Intent.AskTaku -> askTaku(context, draft(intent.prompt, card, selected), accepted.cursor)
      is Intent.OpenApp -> openApp(context, intent)
      is Intent.OpenUrl -> start(context, AndroidIntent(AndroidIntent.ACTION_VIEW, Uri.parse(intent.url)), "open-url")
      is Intent.ShareText -> shareText(context, intent)
      Intent.ConfirmDiningShare -> JSONObject().put("effect", Intent.ConfirmDiningShare.ID)
      Intent.Dismiss -> repo.remove(cardId, origin).put("effect", "dismiss")
      Intent.None -> JSONObject().put("effect", "none").put("note", "This card has no connected integration; nothing was executed.")
    }
    repo.recordDispatch(accepted, origin)?.let { result.put("dispatchCursor", it.toString()) }
    result.put("eventCursor", accepted.cursor.toString())
    return result.put("cardId", cardId).put("actionId", actionId)
  }

  /** Long-press "Ask Taku": opens the conversation with the card as context. */
  suspend fun askAbout(context: Context, cardId: String, origin: FeedOrigin): JSONObject {
    val accepted = FeedRepository.get(context).acceptAction(FeedActionRequest.AskAbout(cardId), origin)
    val result = askTaku(context, (accepted.action.intent as Intent.AskTaku).prompt, accepted.cursor)
    return result.put("cardId", cardId).put("eventCursor", accepted.cursor.toString())
  }

  private fun draft(prompt: String, card: FeedCard, values: JSONObject): String {
    if (values.length() == 0) return prompt
    val chosen = values.keys().asSequence().joinToString(", ") { key -> "${key.substringAfterLast('/')}=${values.opt(key)}" }
    return "$prompt\n\n(${card.app} card selections: $chosen)"
  }

  private suspend fun askTaku(context: Context, draft: String, cursor: FeedCursor): JSONObject {
    if (draft.length > AssistantSession.MAX_TEXT) throw FeedException("INVALID_TEXT", "The Taku draft exceeds ${AssistantSession.MAX_TEXT} characters")
    val threadId = try {
      withContext(Dispatchers.IO) {
        val gateway = AgentGateway(context.contentResolver)
        val target = HomeComposer.createThread(gateway)
        val handoffId = UUID.nameUUIDFromBytes("feed:$cursor".toByteArray()).toString()
        HomeComposer(context, gateway, Dispatchers.IO).handoff(target, handoffId, draft)
        target
      }
    } catch (failure: GatewayException) {
      throw FeedException(failure.code, failure.message ?: "Taku could not save the draft")
    }
    val intent = AndroidIntent().setComponent(ComponentName(context.packageName, CONVERSATION))
      .addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK or AndroidIntent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra("threadId", threadId)
    return start(context, intent, "ask-taku").put("draft", draft).put("threadId", threadId)
  }

  private fun openApp(context: Context, target: Intent.OpenApp): JSONObject {
    val intent = if (target.uri != null) AndroidIntent(AndroidIntent.ACTION_VIEW, Uri.parse(target.uri)).setPackage(target.packageName)
    else context.packageManager.getLaunchIntentForPackage(target.packageName)
      ?: throw FeedException("APP_NOT_INSTALLED", "${target.packageName} is not installed")
    return start(context, intent, "open-app").put("package", target.packageName)
  }

  private fun shareText(context: Context, target: Intent.ShareText): JSONObject {
    val manager = context.packageManager
    try {
      manager.getApplicationInfo(target.packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
      throw FeedException("APP_NOT_INSTALLED", "${target.packageName} is not installed")
    }
    val intent = AndroidIntent(AndroidIntent.ACTION_SEND)
      .setType("text/plain")
      .setPackage(target.packageName)
      .putExtra(AndroidIntent.EXTRA_TEXT, target.text)
    if (manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
      throw FeedException("NO_ACTIVITY", "${target.packageName} cannot handle text sharing")
    }
    val result = try {
      start(context, intent, "share-text")
    } catch (_: SecurityException) {
      throw FeedException("PERMISSION_DENIED", "${target.packageName} does not allow text sharing")
    }
    return result.put("package", target.packageName).put("mimeType", "text/plain")
      .put("requiresUserConfirmation", true)
  }

  private fun start(context: Context, intent: AndroidIntent, effect: String): JSONObject {
    try {
      MainThread.call(onTimeout = { FeedException("TIMEOUT", "main thread busy") }) {
        context.startActivity(intent.addFlags(AndroidIntent.FLAG_ACTIVITY_NEW_TASK))
      }
    } catch (e: ActivityNotFoundException) {
      throw FeedException("NO_ACTIVITY", "nothing can handle $effect: ${e.message}")
    }
    return JSONObject().put("effect", effect)
  }
}
