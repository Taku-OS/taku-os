// SPDX-License-Identifier: Apache-2.0
package org.agentos.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

class AudioOpenAccessProvider : ContentProvider() {
    override fun onCreate() = true

    private fun isTrustedCaller(ctx: Context, uid: Int): Boolean {
        if (uid == 0 || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID || uid == Process.myUid()) return true
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid) ?: return false
        return pkgs.any { pm.checkSignatures("android", it) == PackageManager.SIGNATURE_MATCH }
    }

    private fun isControlGranted(ctx: Context, uid: Int): Boolean {
        return try {
            val res = ctx.contentResolver.call(
                Uri.parse("content://org.agentos.assistant.permissions"),
                "check",
                JSONObject().put("uid", uid).put("scope", "taku.control").toString(),
                null,
            ) ?: return false
            val raw = res.getString("result") ?: return false
            val verdict = JSONObject(raw)
            verdict.optBoolean("ok", false) && verdict.optBoolean("allowed", false)
        } catch (_: Exception) {
            false
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callerUid = Binder.getCallingUid()
        val appContext = context?.applicationContext
            ?: return envelope(JSONObject().put("ok", false).put("code", "CONFIG_UNAVAILABLE").put("message", "Voice configuration is temporarily unavailable"))
        val identity = Binder.clearCallingIdentity()
        try {
            if (method != "settings") throw AudioSettings.failure("UNSUPPORTED_OPERATION")
            if (extras != null && !extras.isEmpty) throw AudioSettings.failure("INVALID_ARGUMENT")
            val command = AudioSettings.decode(arg)
            if (!isTrustedCaller(appContext, callerUid)) {
                if (command is AudioSettingsCommand.SaveFalKey || command is AudioSettingsCommand.RemoveFalKey) {
                    return envelope(JSONObject().put("ok", false).put("code", "PERMISSION_DENIED").put("message", "Voice credential changes require Taku Audio settings"))
                }
                if (!isControlGranted(appContext, callerUid)) {
                    return envelope(JSONObject().put("ok", false).put("code", "PERMISSION_DENIED").put("message", "Voice access denied by App Permission Center"))
                }
            }
            val result = AudioSettings.execute(appContext, command)
            return envelope(JSONObject().put("ok", true).put("result", result.toJson()))
        } catch (error: AudioSettingsFailure) {
            return envelope(JSONObject().put("ok", false).put("code", error.code).put("message", error.message))
        } catch (_: Exception) {
            val error = AudioSettings.failure("CONFIG_UNAVAILABLE")
            return envelope(JSONObject().put("ok", false).put("code", error.code).put("message", error.message))
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun envelope(value: JSONObject) = Bundle().apply { putString("result", value.toString()) }
    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Use the settings method")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Use the settings method")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use the settings method")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use the settings method")
}
