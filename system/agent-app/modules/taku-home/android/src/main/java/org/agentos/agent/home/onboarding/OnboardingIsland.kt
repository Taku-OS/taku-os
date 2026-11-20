package org.agentos.agent.home.onboarding

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * The compact island while a ChatGPT sign-in has taken the user to the browser (Figma
 * 1155:1323 "4-connecting" / 1155:1453 "5-connected"): working dots and "Signing...", then a
 * brief check and "Connected". Published as an ordinary island notification (Home holds
 * POST_ISLAND); it never expands over the browser page by itself. Tapping returns to Home.
 */
internal class OnboardingIsland(private val context: Context) {
  private val manager = context.getSystemService(NotificationManager::class.java)
  private var shown: String? = null

  fun signingIn() = post("signing", JSONObject().put("type", "pic").put("pic", "taku").put("animated", "working"), "Signing...",
    "Signing in to ChatGPT", "Finish signing in on the ChatGPT page, then Taku continues setup.", timeoutS = 900)

  fun connected() = post("connected", JSONObject().put("type", "pic").put("pic", "check"), "Connected",
    "ChatGPT connected", "Taku is ready. Continue setup on the home screen.", timeoutS = 5)

  @Synchronized
  fun clear() {
    if (shown == null) return
    shown = null
    runCatching { manager?.cancel(TAG, ID) }
  }

  // The sign-in watcher (a background thread) and Home's foreground changes (main) both post and clear.
  @Synchronized
  private fun post(kind: String, leading: JSONObject, trailing: String, title: String, body: String, timeoutS: Int) {
    if (shown == kind || manager == null) return
    try {
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "Taku setup", NotificationManager.IMPORTANCE_LOW))
      val open = PendingIntent.getActivity(context, ID,
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.packageName)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      val payload = JSONObject().put("version", 1).put("category", "progress").put("relevance", 0.8)
        .put("bigIsland", JSONObject().put("leading", leading).put("trailing", JSONObject().put("type", "text").put("text", trailing)))
        .put("expanded", JSONObject().put("template", "text").put("title", title).put("body", body)
          .put("actions", JSONArray().put("open")).put("primaryAction", "open"))
        .put("islandTimeoutS", timeoutS).put("statusBar", "auto")
      val icon = Icon.createWithResource(context, context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
      val extras = Bundle().apply {
        putString("taku.island.v1", payload.toString())
        putBundle("taku.island.pics", Bundle().apply {
          putParcelable("taku", icon)
          putParcelable("check", Icon.createWithBitmap(check()))
        })
        putBundle("taku.island.actions", Bundle().apply {
          putParcelable("open", Notification.Action.Builder(null as Icon?, "Back to setup", open).build())
        })
      }
      val notification = Notification.Builder(context, CHANNEL).setSmallIcon(icon).setContentTitle(title).setContentText(body)
        .setContentIntent(open).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
        .setOngoing(kind == "signing").setTimeoutAfter(timeoutS * 1000L).addExtras(extras).build()
      manager.notify(TAG, ID, notification)
      shown = kind
    } catch (e: RuntimeException) {
      Log.w(TAG, "island unavailable", e)
    }
  }

  /** A 16 dp white checkmark (the island draws pics at its own size). */
  private fun check(): Bitmap {
    val px = (16 * context.resources.displayMetrics.density).toInt().coerceAtLeast(16)
    return Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
      val s = px / 16f
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.75f * s
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
      }
      Canvas(it).drawPath(Path().apply { moveTo(3.5f * s, 8.5f * s); lineTo(6.5f * s, 11.5f * s); lineTo(12.5f * s, 4.5f * s) }, paint)
    }
  }

  private companion object {
    const val TAG = "TakuOnboardingIsland"
    const val CHANNEL = "taku_setup"
    const val ID = 0x5e70
  }
}
