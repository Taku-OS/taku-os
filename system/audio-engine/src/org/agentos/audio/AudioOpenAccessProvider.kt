// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.json.JSONObject

class AudioOpenAccessProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // This is the deliberate device-local settings facade, not a trusted-caller claim.
        // Broker authenticates the engine transport; it does not authenticate the original app.
        // GUI and local callers share AudioSettings validation (docs/agent-open-access.md).
        val identity = Binder.clearCallingIdentity()
        try {
            if (method != "settings") throw AudioSettings.failure("UNSUPPORTED_OPERATION")
            if (extras != null && !extras.isEmpty) throw AudioSettings.failure("INVALID_ARGUMENT")
            val result = AudioSettings.execute(context?.applicationContext ?: throw AudioSettings.failure("CONFIG_UNAVAILABLE"), AudioSettings.decode(arg))
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
