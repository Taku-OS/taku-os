package org.agentos.agent.home.island

import android.content.Context
import android.app.NotificationManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
  private val enabled = MutableStateFlow<Boolean?>(null)
  val nativeEnabled = enabled.asStateFlow()
  init { scope.launch { for (context in refreshRequests) {
    val snapshot = read(context)
    current = snapshot?.toString()
    snapshot?.optJSONObject("settings")?.let { enabled.value = it.optBoolean("enabled", true) }
  } } }

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

  /** Official ROM v1 owns the native assistant template; other ROMs retain the input fallback. */
  fun nativeAssistantAvailable(context: Context, enabled: Boolean? = nativeEnabled.value): Boolean {
    if (context.packageManager.resolveContentProvider("com.android.systemui.island", 0)?.packageName != "com.android.systemui" ||
      !(enabled ?: (Settings.Global.getInt(context.contentResolver, "taku_island_enabled", 1) != 0))) return false
    val notifications = context.getSystemService(NotificationManager::class.java) ?: return false
    return notifications.areNotificationsEnabled() &&
      notifications.getNotificationChannel(IslandPublisherService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
  }

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

  fun request(context: Context, method: String, expectedToken: String? = null) {
    val sequence = requestSequence.incrementAndGet()
    val app = context.applicationContext
    scope.launch {
      repeat(if (method == "expand") 10 else 1) {
        if (sequence != requestSequence.get()) return@launch
        val snapshot = read(app)
        val key = snapshot?.let { if (expectedToken == null) ownKey(app, it) else ownKey(app, it, expectedToken) }
        if (snapshot != null && key != null) {
          if (method == "collapse" && snapshot.optJSONObject("presentation")?.optJSONObject("card")?.optString("key") != key) return@launch
          val success = runCatching {
            app.contentResolver.call(URI, method, if (method == "collapse") null else key, null)
              ?.getString("json")?.let { JSONObject(it).optBoolean("ok") } == true
          }.getOrDefault(false)
          if (success) { refresh(app); return@launch }
        }
        delay(150)
      }
    }
  }
}
