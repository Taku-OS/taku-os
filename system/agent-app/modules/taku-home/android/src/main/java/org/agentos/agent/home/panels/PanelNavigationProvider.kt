package org.agentos.agent.home.panels

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.json.JSONObject

/**
 * Agent access to the desktop's own navigation state, without launching it:
 * `content call --uri content://org.agentos.agent.panel-navigation --method navigation`.
 * The answer is the live pager projection in [PanelNavigation], not a directory read: the
 * authoritative list stays in the Assistant's PanelRegistry.
 */
class PanelNavigationProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    val callerUid = Binder.getCallingUid()
    val appCtx = context
    if (appCtx != null && !org.agentos.agent.home.TakuCallerAccess.isAllowed(appCtx, callerUid)) {
      return Bundle().apply {
        putString("result", JSONObject().put("ok", false).put("code", "PERMISSION_DENIED").put("message", "Panel navigation access denied by App Permission Center").toString())
      }
    }
    val identity = Binder.clearCallingIdentity()
    try {
      val result = when (method) {
        "navigation" -> JSONObject().put("ok", true).put("result", PanelNavigation.state.value.toJson())
        else -> JSONObject().put("ok", false).put("code", "UNKNOWN_METHOD").put("message", "unknown method $method")
      }
      return Bundle().apply { putString("result", result.toString()) }
    } finally {
      Binder.restoreCallingIdentity(identity)
    }
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
    throw UnsupportedOperationException("Use content call; see navigation")
  override fun getType(uri: Uri) = "application/json"
  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("The panel desktop is read-only")
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
