package org.agentos.agent.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.FileNotFoundException
import org.json.JSONException
import org.json.JSONObject

class MenuScanProvider : ContentProvider() {
  companion object {
    const val AUTHORITY = "org.agentos.agent.menu-scan"
    private const val PERMISSION = "org.agentos.permission.AGENT_CLIENT"
  }

  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
    enforceCaller()
    val identity = Binder.clearCallingIdentity()
    val envelope = try {
      val input = if (arg.isNullOrBlank()) JSONObject() else JSONObject(arg)
      val result = if (method == "read") {
        MenuScanSession.read()
      } else {
        MenuScanSession.action(
          method,
          input.opt("scanId") as? String ?: "",
          input.opt("dishId") as? String ?: "",
          input.opt("quantity") as? Number,
        )
      }
      JSONObject().put("ok", true).put("result", result ?: JSONObject.NULL)
    } catch (error: MenuScanException) {
      failure(error.code, error.message ?: error.code)
    } catch (_: JSONException) {
      failure("INVALID_ARGUMENT", "Expected a JSON object")
    } finally {
      Binder.restoreCallingIdentity(identity)
    }
    return Bundle().apply { putString("result", envelope.toString()) }
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    enforceCaller()
    if (mode != "r") throw FileNotFoundException("Menu scan images are read-only")
    photoId(uri)?.let { return MenuScanSession.openPhoto(it) }
    artworkId(uri)?.let { (scanId, dishId) -> return MenuScanSession.openArtwork(scanId, dishId) }
    throw FileNotFoundException("Unknown menu scan image URI")
  }

  private fun enforceCaller() {
    val uid = Binder.getCallingUid()
    if (uid == Process.myUid() || uid == 0 || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID) return
    requireNotNull(context).enforceCallingPermission(PERMISSION, "Menu Scan requires Agent client permission")
  }

  private fun failure(code: String, message: String): JSONObject =
    JSONObject().put("ok", false).put("code", code).put("message", message)

  private fun photoId(uri: Uri): String? {
    val parts = uri.pathSegments
    return if (parts.size == 2 && parts[0] == "photo") parts[1] else null
  }

  private fun artworkId(uri: Uri): Pair<String, String>? {
    val parts = uri.pathSegments
    return if (parts.size == 3 && parts[0] == "artwork") parts[1] to parts[2] else null
  }

  override fun getType(uri: Uri): String? = when {
    photoId(uri) != null -> "image/jpeg"
    artworkId(uri) != null -> "image/png"
    else -> null
  }

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException()
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException()
}
