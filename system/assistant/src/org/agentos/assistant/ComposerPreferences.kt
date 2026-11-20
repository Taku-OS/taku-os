package org.agentos.assistant

import android.content.SharedPreferences
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object ComposerPreferences {
    fun key(thread: String, field: String) = "composer.$thread.$field"

    @JvmStatic
    fun get(prefs: SharedPreferences, thread: String, field: String, fallback: String): String =
        prefs.getString(key(thread, field), prefs.getString(field, fallback)) ?: fallback

    fun attachments(prefs: SharedPreferences, thread: String): JSONArray =
        readList(prefs, key(thread, "attachments"))

    fun draftTextKey(thread: String) = "draft-$thread"
    fun draftRevisionKey(thread: String) = key(thread, "draftRevision")
    fun draftText(prefs: SharedPreferences, thread: String) = prefs.getString(draftTextKey(thread), "").orEmpty()
    fun draftRevision(prefs: SharedPreferences, thread: String) = prefs.getString(draftRevisionKey(thread), "").orEmpty()

    fun putDraftText(editor: SharedPreferences.Editor, thread: String, text: String) {
        editor.putString(draftTextKey(thread), text).putString(draftRevisionKey(thread), UUID.randomUUID().toString())
    }

    fun draft(prefs: SharedPreferences, thread: String) = ComposerDraft(
        thread, draftRevision(prefs, thread), draftText(prefs, thread), attachments(prefs, thread).toString()
    )

    fun stashDraft(prefs: SharedPreferences, editor: SharedPreferences.Editor, draft: ComposerDraft) {
        val recovery = key(draft.thread, "failedDeliveries")
        val record = JSONObject().put("token", UUID.randomUUID().toString()).put("revision", draft.revision)
            .put("text", draft.text).put("attachments", JSONArray(draft.attachments)).put("status", "saved-draft")
        editor.putString(recovery, readList(prefs, recovery).put(record).toString())
    }

    private fun readList(prefs: SharedPreferences, key: String): JSONArray = try {
        JSONArray(prefs.getString(key, "[]") ?: "[]")
    } catch (_: JSONException) {
        JSONArray()
    }
}

internal data class ComposerDraft(val thread: String, val revision: String, val text: String, val attachments: String) {
    val isEmpty: Boolean get() = text.isBlank() && attachments == "[]"
}
