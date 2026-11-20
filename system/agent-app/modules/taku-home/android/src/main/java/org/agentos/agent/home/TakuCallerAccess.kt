package org.agentos.agent.home

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import org.json.JSONObject

/**
 * Enforces Taku App Permission Center policy on exported Home providers.
 *
 * First-party platform-signed components, own UID, root, system, and ADB shell pass in-process
 * with zero IPC; external third-party UIDs are checked against `org.agentos.assistant.permissions`.
 */
internal object TakuCallerAccess {
  private val GATE_URI = Uri.parse("content://org.agentos.assistant.permissions")

  /**
   * Root, system, ADB shell, this app, and platform-signed components: the callers that may
   * handle credentials (a ChatGPT device code, an API key). A third-party app is never trusted,
   * whatever the Permission Center grants it.
   */
  fun isTrusted(context: Context, callerUid: Int): Boolean {
    if (callerUid == 0 || callerUid == Process.SYSTEM_UID || callerUid == Process.SHELL_UID || callerUid == Process.myUid()) {
      return true
    }
    val pm = context.packageManager
    val pkgs = pm.getPackagesForUid(callerUid) ?: return false
    return pkgs.any { pm.checkSignatures(context.packageName, it) == PackageManager.SIGNATURE_MATCH }
  }

  fun isAllowed(context: Context, callerUid: Int, scope: String = "taku.control"): Boolean {
    if (isTrusted(context, callerUid)) return true
    return try {
      val res = context.contentResolver.call(
        GATE_URI,
        "check",
        JSONObject().put("uid", callerUid).put("scope", scope).toString(),
        null,
      ) ?: return false
      val raw = res.getString("result") ?: return false
      val verdict = JSONObject(raw)
      verdict.optBoolean("ok", false) && verdict.optBoolean("allowed", false)
    } catch (_: Exception) {
      false
    }
  }
}
