/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONObject

/**
 * Public Agent interface. Publication policy is separate from reading/operating visible state.
 * Every call is authorized against the caller before it reaches the engine ([IslandAccessPolicy]).
 */
class IslandProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.taku.island+json"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val method = uri.pathSegments.firstOrNull() ?: "capabilities"
        val key = uri.getQueryParameter("key") ?: uri.getQueryParameter("package") ?: uri.pathSegments.getOrNull(1)
        val result = if (method in setOf("capabilities", "canShowIsland", "state", "list", "get", "settings")) {
            operate(method, key, null)
        } else IslandRuntime.error("unsupported_query")
        return MatrixCursor(arrayOf("json")).apply {
            addRow(arrayOf(result.toString()))
            context?.let { setNotificationUri(it.contentResolver, Uri.parse("content://com.android.systemui.island")) }
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = Bundle().apply {
        putString("json", operate(method, arg, extras?.getString("value")).toString())
    }

    private fun operate(method: String, arg: String?, extra: String?): JSONObject {
        val uid = Binder.getCallingUid()
        val identity = Binder.clearCallingIdentity()
        return try {
            val context = context ?: return IslandRuntime.error("systemui_starting")
            val trusted = trusted(context, uid)
            // canShowIsland without a package answers for the caller's own (single) package.
            val packages = if (trusted) emptyList() else context.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
            val effectiveArg = if (method == "canShowIsland" && arg == null && packages.size == 1) packages[0] else arg
            val own = effectiveArg != null && effectiveArg in packages
            val granted = IslandAccessPolicy.needsGrant(method, trusted) && controlGranted(context, uid)
            IslandAccessPolicy.decide(method, trusted, granted, own)?.let { return IslandRuntime.error(it) }
            IslandRuntime.call(method, effectiveArg, extra)
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun trusted(context: Context, uid: Int): Boolean {
        if (IslandAccessPolicy.trustedUid(uid, Process.myUid())) return true
        return context.packageManager.checkSignatures(uid, Process.SYSTEM_UID) == PackageManager.SIGNATURE_MATCH
    }

    /** Default deny: an unavailable or malformed App Permission Center answer is a refusal. */
    private fun controlGranted(context: Context, uid: Int): Boolean = try {
        val request = JSONObject().put("uid", uid).put("scope", IslandAccessPolicy.CONTROL_SCOPE)
        val response = context.contentResolver.call(PERMISSIONS, "check", request.toString(), null)
        val verdict = JSONObject(response?.getString("result") ?: "{}")
        verdict.optBoolean("ok") && verdict.optBoolean("allowed")
    } catch (unavailable: Exception) { false }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("use_call")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("use_call")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("use_call")

    private companion object {
        val PERMISSIONS: Uri = Uri.parse("content://org.agentos.assistant.permissions")
    }
}
