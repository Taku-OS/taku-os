package org.agentos.agent.home.island

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Requests presentation from SystemUI; this process never owns a second island presenter. */
object SystemIslandBridge {
  val URI: Uri = Uri.parse("content://com.android.systemui.island")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
  @Volatile private var current: String? = null
  private var observing = false
  private val requestSequence = java.util.concurrent.atomic.AtomicLong()
  private val refreshRequests = Channel<Context>(Channel.CONFLATED)
  init { scope.launch { for (context in refreshRequests) current = read(context)?.toString() } }

  @Synchronized fun observe(context: Context) {
    if (observing) return
    val app = context.applicationContext
    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
      override fun onChange(selfChange: Boolean) { refresh(app) }
    }
    runCatching { app.contentResolver.registerContentObserver(URI, true, observer); observing = true }
    refresh(app)
  }

  private fun refresh(context: Context) { refreshRequests.trySend(context.applicationContext) }
  private fun read(context: Context): JSONObject? = runCatching {
    context.contentResolver.call(URI, "state", null, null)?.getString("json")?.let(::JSONObject)
      ?.takeIf { it.optBoolean("ok") }
  }.getOrNull()

  fun state(): JSONObject? = current?.let { runCatching { JSONObject(it) }.getOrNull() }

  fun ownKey(context: Context, snapshot: JSONObject,
    expectedToken: String? = context.getSharedPreferences("system_island_session", Context.MODE_PRIVATE).getString("token", null)): String? {
    val list = snapshot.optJSONArray("activities") ?: return null
    for (index in 0 until list.length()) {
      val item = list.getJSONObject(index)
      val assistant = item.optJSONObject("payload")?.optJSONObject("expanded")?.optJSONObject("assistant")
      if (item.optString("package") == context.packageName && assistant != null &&
        (expectedToken == null || assistant.optString("token") == expectedToken)) return item.getString("key")
    }
    return null
  }

  fun presentation(context: Context): String {
    val snapshot = state() ?: return "unavailable"
    val key = ownKey(context, snapshot) ?: return "hidden"
    val presentation = snapshot.optJSONObject("presentation") ?: return "unavailable"
    return when {
      presentation.optJSONObject("card")?.optString("key") == key -> "expanded"
      presentation.optString("selected") == key && presentation.optBoolean("compactVisible") -> "collapsed"
      else -> "hidden"
    }
  }

  /**
   * Asks SystemUI to [method] Taku's card, retrying an expansion briefly while the notification
   * lands. [onResult] reports whether SystemUI accepted it; a newer request supersedes this one
   * without a result.
   */
  fun request(context: Context, method: String, onResult: ((Boolean) -> Unit)? = null) {
    val sequence = requestSequence.incrementAndGet()
    val app = context.applicationContext
    scope.launch {
      var accepted = false
      try { repeat(if (method == "expand") 10 else 1) {
        if (sequence != requestSequence.get()) return@launch
        val snapshot = read(app)
        val key = snapshot?.let { ownKey(app, it) }
        if (snapshot != null && key != null) {
          if (method == "collapse" && snapshot.optJSONObject("presentation")?.optJSONObject("card")?.optString("key") != key) return@launch
          val success = runCatching {
            app.contentResolver.call(URI, method, if (method == "collapse") null else key, null)
              ?.getString("json")?.let { JSONObject(it).optBoolean("ok") } == true
          }.getOrDefault(false)
          if (success) { refresh(app); accepted = true; return@launch }
        }
        delay(150)
      } } finally {
        if (sequence == requestSequence.get() || accepted) onResult?.invoke(accepted)
      }
    }
  }
}
