package org.agentos.agent.home

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.agentos.agent.home.assistant.SystemAssistantEntry

/** App-side repair for boot/update/unlock. ROM role/voice policy owns the pre-Activity path. */
object HomeRole {
  private const val TAG = "TakuSystemEntry"
  private val LEGACY = setOf("org.agentos.assistant", "com.android.launcher3")

  fun claim(context: Context, complete: () -> Unit = {}) {
    val ctx = context.applicationContext
    val roles = ctx.getSystemService(RoleManager::class.java)
    if (roles == null) { complete(); return }
    val finished = AtomicBoolean()
    val pending = AtomicInteger(2)
    val handler = Handler(Looper.getMainLooper())
    val finish = { if (finished.compareAndSet(false, true)) complete() }
    val timeout = Runnable { Log.w(TAG, "Role repair callback timed out"); finish() }
    handler.postDelayed(timeout, 8_000)
    val done = { if (pending.decrementAndGet() == 0) { handler.removeCallbacks(timeout); finish() }; Unit }
    val fixedPolicy = runCatching {
      Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java)
        .invoke(null, "ro.taku.home.package", "") == ctx.packageName
    }.getOrDefault(false)
    for (role in listOf(RoleManager.ROLE_HOME, RoleManager.ROLE_ASSISTANT)) {
      try {
        if (!roles.isRoleAvailable(role)) { done(); continue }
        @Suppress("UNCHECKED_CAST")
        val holders = RoleManager::class.java.getMethod("getRoleHolders", String::class.java)
          .invoke(roles, role) as List<String>
        if (ctx.packageName in holders) {
          if (role == RoleManager.ROLE_ASSISTANT) repairVoiceBinding(ctx)
          done(); continue
        }
        if (!fixedPolicy && holders.any { it !in LEGACY }) { done(); continue }
        val callback = java.util.function.Consumer<Boolean> { ok ->
          if (ok && role == RoleManager.ROLE_ASSISTANT) repairVoiceBinding(ctx)
          Log.i(TAG, "$role repair: $ok")
          done()
        }
        RoleManager::class.java.getMethod("addRoleHolderAsUser", String::class.java, String::class.java,
          Int::class.javaPrimitiveType, android.os.UserHandle::class.java,
          java.util.concurrent.Executor::class.java, java.util.function.Consumer::class.java)
          .invoke(roles, role, ctx.packageName, 0, Process.myUserHandle(), ctx.mainExecutor, callback)
      } catch (failure: Exception) {
        Log.w(TAG, "$role repair failed", failure)
        done()
      }
    }
  }

  private fun repairVoiceBinding(context: Context) {
    try {
      val service = ComponentName(context.packageName, SystemAssistantEntry.SERVICE).flattenToShortString()
      for (key in listOf("assistant", "voice_interaction_service")) {
        if (Settings.Secure.getString(context.contentResolver, key) != service)
          Settings.Secure.putString(context.contentResolver, key, service)
      }
    } catch (failure: SecurityException) { Log.w(TAG, "Voice binding repair requires platform permission", failure) }
  }
}

class HomeRoleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    HomeRole.claim(context) { pending.finish() }
  }
}
