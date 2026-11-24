/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** Privileged Agent interface. Publication policy is separate from reading/operating visible state. */
class IslandProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.taku.island+json"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        enforceTrustedCaller(Binder.getCallingUid())
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

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        enforceTrustedCaller(Binder.getCallingUid())
        return Bundle().apply {
            putString("json", operate(method, arg, extras?.getString("value")).toString())
        }
    }

    private fun enforceTrustedCaller(uid: Int) {
        if (uid == Process.ROOT_UID || uid == Process.SYSTEM_UID || uid == Process.SHELL_UID) return
        if (context?.packageManager?.checkSignatures(uid, Process.SYSTEM_UID) ==
            PackageManager.SIGNATURE_MATCH) return
        throw SecurityException("Island access requires a trusted system caller")
    }

    private fun operate(method: String, arg: String?, extra: String?): org.json.JSONObject {
        val identity = Binder.clearCallingIdentity()
        return try { IslandRuntime.call(method, arg, extra) }
        finally { Binder.restoreCallingIdentity(identity) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("use_call")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("use_call")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("use_call")
}
