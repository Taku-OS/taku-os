package org.agentos.assistant

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

internal object ComposerState {
    class RevisionConflict(val revision: String) : IOException("DRAFT_REVISION_CONFLICT")

    private val monitor = ComposerPreferences::class.java

    @JvmStatic
    fun userError(context: Context, callerUid: Int): String? {
        val owner = UserHandle.getUserHandleForUid(0)
        if (UserHandle.getUserHandleForUid(callerUid) != owner || Process.myUserHandle() != owner)
            return "USER_UNAVAILABLE"
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return "USER_LOCKED"
        return null
    }

    @JvmStatic
    @Throws(IOException::class)
    fun read(prefs: SharedPreferences, thread: String): JSONObject = synchronized(monitor) {
        recoverLegacyDelivery(prefs, thread)
        recoverLegacyHome(prefs, thread)
        snapshot(prefs, thread)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun write(prefs: SharedPreferences, thread: String, expected: String, text: String): JSONObject = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        if (text != ComposerPreferences.draftText(prefs, thread)) {
            val editor = prefs.edit()
            ComposerPreferences.putDraftText(editor, thread, text)
            persist(prefs, editor, ComposerPreferences.draftTextKey(thread), ComposerPreferences.draftRevisionKey(thread))
        }
        snapshot(prefs, thread)
    }

    fun importLegacy(prefs: SharedPreferences, thread: String, text: String): JSONObject = synchronized(monitor) {
        val marker = ComposerPreferences.key(thread, "expoDraftImported.${PanelProtocol.hash(text)}")
        if (text.isNotEmpty() && !prefs.contains(marker)) {
            val current = ComposerPreferences.draft(prefs, thread)
            val editor = prefs.edit().putString(marker, "1")
            when {
                current.text == text -> persist(prefs, editor, marker)
                current.text.isEmpty() && current.attachments == "[]" && current.plugins == "[]" -> {
                    ComposerPreferences.putDraftText(editor, thread, text)
                    persist(prefs, editor, marker, ComposerPreferences.draftTextKey(thread), ComposerPreferences.draftRevisionKey(thread))
                }
                else -> {
                    ComposerPreferences.stashDraft(prefs, editor, ComposerDraft(thread, "", text, "[]"))
                    persist(prefs, editor, marker, recoveryKey(thread))
                }
            }
        }
        snapshot(prefs, thread)
    }

    @JvmStatic
    fun addAttachment(prefs: SharedPreferences, thread: String, expected: String, item: JSONObject): JSONObject = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        val items = ComposerPreferences.attachments(prefs, thread)
        if (items.length() >= 8) throw IOException("ATTACHMENT_LIMIT")
        replaceAttachments(prefs, thread, items.put(item))
    }

    @JvmStatic
    fun removeAttachment(prefs: SharedPreferences, thread: String, expected: String, id: String): JSONObject = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        val items = ComposerPreferences.attachments(prefs, thread)
        val index = (0 until items.length()).firstOrNull { attachmentId(items.getJSONObject(it)) == id }
            ?: throw IOException("ATTACHMENT_UNAVAILABLE")
        items.remove(index)
        replaceAttachments(prefs, thread, items)
    }

    private fun replaceAttachments(prefs: SharedPreferences, thread: String, items: JSONArray): JSONObject =
        replaceList(prefs, thread, "attachments", items)

    private fun replaceList(prefs: SharedPreferences, thread: String, name: String, items: JSONArray): JSONObject {
        val key = ComposerPreferences.key(thread, name)
        val editor = prefs.edit().putString(key, items.toString())
        ComposerPreferences.putDraftText(editor, thread, ComposerPreferences.draftText(prefs, thread))
        persist(prefs, editor, key, ComposerPreferences.draftRevisionKey(thread))
        return snapshot(prefs, thread)
    }

    /** Attaches a plugin [item] (`{id, name, tools}`) that God Mode reported enabled; repeating it changes nothing. */
    @JvmStatic
    fun addPlugin(prefs: SharedPreferences, thread: String, expected: String, item: JSONObject): JSONObject = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        val items = ComposerPreferences.plugins(prefs, thread)
        val id = item.getString("id")
        if ((0 until items.length()).any { items.optJSONObject(it)?.optString("id") == id }) return@synchronized snapshot(prefs, thread)
        if (items.length() >= ComposerPlugins.LIMIT) throw IOException("PLUGIN_LIMIT")
        replaceList(prefs, thread, "plugins", items.put(item))
    }

    @JvmStatic
    fun removePlugin(prefs: SharedPreferences, thread: String, expected: String, id: String): JSONObject = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        val items = ComposerPreferences.plugins(prefs, thread)
        val index = (0 until items.length()).firstOrNull { items.optJSONObject(it)?.optString("id") == id }
            ?: throw IOException("PLUGIN_UNAVAILABLE")
        items.remove(index)
        replaceList(prefs, thread, "plugins", items)
    }

    @JvmStatic
    fun attachmentId(item: JSONObject): String = File(item.getString("path")).name

    fun selectedAttachments(prefs: SharedPreferences, thread: String, expected: String, ids: JSONArray): JSONArray = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        val available = ComposerPreferences.attachments(prefs, thread)
        val selected = JSONArray()
        for (index in 0 until ids.length()) {
            val id = ids.getString(index)
            val item = (0 until available.length()).asSequence().map(available::getJSONObject).firstOrNull { attachmentId(it) == id }
                ?: throw IOException("ATTACHMENT_UNAVAILABLE")
            selected.put(item)
        }
        selected
    }

    private fun selectedPlugins(prefs: SharedPreferences, thread: String, ids: JSONArray): JSONArray {
        val available = ComposerPreferences.plugins(prefs, thread)
        val selected = JSONArray()
        for (index in 0 until ids.length()) {
            val id = ids.getString(index)
            selected.put((0 until available.length()).asSequence().mapNotNull(available::optJSONObject).firstOrNull { it.optString("id") == id }
                ?: throw IOException("PLUGIN_UNAVAILABLE"))
        }
        return selected
    }

    private val optionNames = arrayOf("model", "effort", "serviceTier", "multiAgentMode", "permissionMode", "responseStyle")

    private fun requestKey(id: String) = "composer-request-$id"
    private const val REQUEST_PREFIX = "composer-request-"
    private const val REQUEST_LEDGER = "composer-ledger.requests"
    private const val MARKER_LEDGER = "composer-ledger.markers"
    private val markerPrefixes = arrayOf("composer-handoff-", "composer-recovery-")
    private const val KEPT_RECORDS = 64
    private const val LEDGER_SLACK = 32

    /**
     * Notes a new per-submission record in an insertion-ordered ledger. Once the ledger outgrows
     * its limit, the oldest records of that kind are removed, together with any written before
     * the ledger existed. Otherwise every send would leave a record behind, and the settings
     * file, which is rewritten on each draft save, would grow without bound. A request that a
     * conversation is still waiting on is always kept.
     */
    private fun retire(prefs: SharedPreferences, ledgerKey: String, key: String, prefixes: Array<String>) {
        var ledger = try { JSONArray(prefs.getString(ledgerKey, "[]")) } catch (_: org.json.JSONException) { JSONArray() }
        ledger.put(key)
        val editor = prefs.edit()
        if (ledger.length() > KEPT_RECORDS + LEDGER_SLACK) {
            val all = prefs.all
            val kept = LinkedHashSet<String>()
            if (REQUEST_PREFIX in prefixes) for ((name, value) in all)
                if (name.startsWith("composer.") && name.endsWith(".pendingRequest")) kept.add(requestKey(value.toString()))
            for (index in ledger.length() - KEPT_RECORDS until ledger.length()) kept.add(ledger.getString(index))
            for (name in all.keys) if (prefixes.any(name::startsWith) && name !in kept) editor.remove(name)
            ledger = JSONArray(kept)
        }
        // Housekeeping only: a lost write here costs nothing but a later trim.
        editor.putString(ledgerKey, ledger.toString()).apply()
    }
    private fun pendingKey(thread: String) = ComposerPreferences.key(thread, "pendingRequest")

    /** Requests a conversation is still waiting on; an unresolved delivery may still be retried. */
    fun pendingRequestIds(prefs: SharedPreferences): Set<String> = synchronized(monitor) {
        prefs.all.mapNotNullTo(HashSet()) { (name, value) ->
            (value as? String)?.takeIf { name.startsWith("composer.") && name.endsWith(".pendingRequest") }
        }
    }

    fun submission(prefs: SharedPreferences, id: String): JSONObject? = synchronized(monitor) {
        prefs.getString(requestKey(id), null)?.let(::JSONObject)
    }

    fun reserveSubmission(prefs: SharedPreferences, input: JSONObject, optionsFor: (String, JSONObject) -> Unit): Pair<JSONObject, Boolean> = synchronized(monitor) {
        val id = input.getString("requestId")
        val thread = input.getString("threadId")
        val key = requestKey(id)
        val hash = PanelProtocol.hash(PanelProtocol.canonical(input))
        val existing = submission(prefs, id)
        if (existing != null) {
            if (existing.getString("hash") != hash) throw IOException("REQUEST_ID_REUSED")
            return@synchronized existing to false
        }
        val pending = pendingKey(thread)
        if (prefs.contains(pending)) throw IOException("PENDING_DIFFERENT")
        val revision = input.getString("revision")
        val attachments = selectedAttachments(prefs, thread, revision, input.getJSONArray("attachmentIds"))
        val plugins = selectedPlugins(prefs, thread, input.optJSONArray("pluginIds") ?: JSONArray())
        val text = ComposerPreferences.draftText(prefs, thread).trim()
        if (text.isEmpty() && attachments.length() == 0) throw IOException("INVALID_TEXT")
        val pollSendIntent = input.optString("pollSendIntentId")
        val options = JSONObject().put("includeScreenshot", pollSendIntent.isEmpty() && prefs.getBoolean("includeScreenshot", false))
        optionsFor(thread, options)
        options.put("attachments", attachments)
        options.put("plugins", plugins)
        if (pollSendIntent.isNotEmpty()) options.put("pollSendIntentId", pollSendIntent)
        val request = JSONObject(input.toString()).put("hash", hash).put("text", text).put("options", options)
        persist(prefs, prefs.edit().putString(key, request.toString()).putString(pending, id), key, pending)
        retire(prefs, REQUEST_LEDGER, key, arrayOf(REQUEST_PREFIX))
        request to true
    }

    fun finishSubmission(prefs: SharedPreferences, request: JSONObject, accepted: Boolean) = synchronized(monitor) {
        val thread = request.getString("threadId")
        val pending = pendingKey(thread)
        if (prefs.getString(pending, null) != request.getString("requestId")) return@synchronized
        val editor = prefs.edit().remove(pending)
        if (accepted && request.getString("revision") == ComposerPreferences.draftRevision(prefs, thread)) {
            val selected = request.getJSONArray("attachmentIds")
            val ids = (0 until selected.length()).map(selected::getString).toSet()
            val items = ComposerPreferences.attachments(prefs, thread)
            val kept = JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                if (attachmentId(item) !in ids) kept.put(item)
            }
            val key = ComposerPreferences.key(thread, "attachments")
            ComposerPreferences.putDraftText(editor, thread, "")
            editor.putString(key, kept.toString())
            val sentPlugins = request.optJSONArray("pluginIds") ?: JSONArray()
            val pluginIds = (0 until sentPlugins.length()).map(sentPlugins::getString).toSet()
            val plugins = ComposerPreferences.plugins(prefs, thread)
            val keptPlugins = JSONArray()
            for (index in 0 until plugins.length()) {
                val item = plugins.optJSONObject(index) ?: continue
                if (item.optString("id") !in pluginIds) keptPlugins.put(item)
            }
            val pluginKey = ComposerPreferences.key(thread, "plugins")
            if (prefs.contains(pluginKey)) editor.putString(pluginKey, keptPlugins.toString())
            persist(prefs, editor, pending, ComposerPreferences.draftRevisionKey(thread), ComposerPreferences.draftTextKey(thread), key, pluginKey)
        } else persist(prefs, editor, pending)
    }

    fun releaseSubmission(prefs: SharedPreferences, thread: String, id: String): JSONObject = synchronized(monitor) {
        val request = submission(prefs, id) ?: throw IOException("REQUEST_UNAVAILABLE")
        if (request.getString("threadId") != thread) throw IOException("REQUEST_ID_REUSED")
        val pending = pendingKey(thread)
        if (id == prefs.getString(pending, null)) {
            val editor = prefs.edit().remove(pending)
            val options = request.getJSONObject("options")
            val sent = ComposerDraft(thread, request.getString("revision"), request.getString("text"), options.getJSONArray("attachments").toString(),
                (options.optJSONArray("plugins") ?: JSONArray()).toString())
            // The request holds the trimmed text; surrounding whitespace alone is not a different draft.
            val current = ComposerPreferences.draft(prefs, thread)
            if (sent != current.copy(text = current.text.trim())) ComposerPreferences.stashDraft(prefs, editor, sent)
            persist(prefs, editor, pending, recoveryKey(thread))
        }
        snapshot(prefs, thread)
    }

    @JvmStatic
    fun handoff(prefs: SharedPreferences, thread: String, id: String, text: String): JSONObject = synchronized(monitor) {
        val key = "composer-handoff-$id"
        val hash = PanelProtocol.hash("$thread\n$text")
        val previous = prefs.getString(key, null)
        if (previous != null) {
            if (previous != hash) throw IOException("REQUEST_ID_REUSED")
            return@synchronized snapshot(prefs, thread)
        }
        val draft = ComposerPreferences.draft(prefs, thread)
        val editor = prefs.edit().putString(key, hash)
        if (draft.text != text || draft.attachments != "[]" || draft.plugins != "[]") {
            if (!draft.isEmpty) ComposerPreferences.stashDraft(prefs, editor, draft)
            ComposerPreferences.putDraftText(editor, thread, text)
            val attachments = ComposerPreferences.key(thread, "attachments")
            val plugins = ComposerPreferences.key(thread, "plugins")
            editor.remove(attachments).remove(plugins)
            persist(prefs, editor, key, recoveryKey(thread), ComposerPreferences.draftTextKey(thread), ComposerPreferences.draftRevisionKey(thread), attachments, plugins)
        } else persist(prefs, editor, key)
        retire(prefs, MARKER_LEDGER, key, markerPrefixes)
        snapshot(prefs, thread)
    }

    @JvmStatic
    fun preserveDraft(prefs: SharedPreferences, thread: String, id: String, text: String): JSONObject = synchronized(monitor) {
        val marker = "composer-recovery-$id"
        val hash = PanelProtocol.hash("$thread\n$text")
        val prior = prefs.getString(marker, null)
        if (prior != null && prior != hash) throw IOException("REQUEST_ID_REUSED")
        if (prior == null) {
            val key = recoveryKey(thread)
            val entries = JSONArray(prefs.getString(key, "[]"))
            entries.put(JSONObject().put("token", id).put("text", text).put("status", "saved-draft").put("attachments", JSONArray()))
            persist(prefs, prefs.edit().putString(marker, hash).putString(key, entries.toString()), marker, key)
            retire(prefs, MARKER_LEDGER, marker, markerPrefixes)
        }
        snapshot(prefs, thread)
    }

    @JvmStatic
    fun restore(prefs: SharedPreferences, thread: String, expected: String, id: String): JSONObject = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        val key = recoveryKey(thread)
        val entries = JSONArray(prefs.getString(key, "[]"))
        val index = (0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("token") == id }
            ?: throw IOException("RECOVERY_UNAVAILABLE")
        val entry = entries.getJSONObject(index)
        entries.remove(index)
        val editor = prefs.edit().putString(key, entries.toString())
        restoreDraft(prefs, editor, thread, entry.getString("text"), entry.optJSONArray("attachments") ?: JSONArray(),
            entry.optJSONArray("plugins") ?: JSONArray(), key)
        snapshot(prefs, thread)
    }

    fun restoreTask(prefs: SharedPreferences, thread: String, expected: String, request: JSONObject): JSONObject = synchronized(monitor) {
        checkRevision(prefs, thread, expected)
        restoreDraft(prefs, prefs.edit(), thread, request.getString("prompt"), request.optJSONArray("attachments") ?: JSONArray(),
            request.optJSONArray("plugins") ?: JSONArray())
        snapshot(prefs, thread)
    }

    private fun restoreDraft(prefs: SharedPreferences, editor: SharedPreferences.Editor, thread: String, text: String, items: JSONArray,
                             plugins: JSONArray, vararg extra: String) {
        if (!ComposerPreferences.draft(prefs, thread).isEmpty) throw IOException("DRAFT_NOT_EMPTY")
        ComposerPreferences.putDraftText(editor, thread, text)
        val attachmentKey = ComposerPreferences.key(thread, "attachments")
        val pluginKey = ComposerPreferences.key(thread, "plugins")
        editor.putString(attachmentKey, items.toString())
        if (plugins.length() > 0) editor.putString(pluginKey, plugins.toString()) else editor.remove(pluginKey)
        persist(prefs, editor, ComposerPreferences.draftTextKey(thread), ComposerPreferences.draftRevisionKey(thread), attachmentKey, pluginKey, *extra)
    }

    private fun recoveryMetadata(prefs: SharedPreferences, thread: String): JSONArray {
        val entries = JSONArray(prefs.getString(recoveryKey(thread), "[]"))
        val result = JSONArray()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            result.put(JSONObject().put("id", entry.optString("token")).put("text", entry.optString("text"))
                .put("status", entry.optString("status")).put("attachments", attachmentMetadata(entry.optJSONArray("attachments") ?: JSONArray()))
                .put("plugins", pluginMetadata(entry.optJSONArray("plugins") ?: JSONArray())))
        }
        return result
    }

    private fun pendingMetadata(prefs: SharedPreferences, thread: String): JSONArray {
        val id = prefs.getString(pendingKey(thread), null) ?: return JSONArray()
        val request = submission(prefs, id) ?: return JSONArray()
        val entry = JSONObject()
        for (key in arrayOf("requestId", "threadId", "text", "action", "revision", "attachmentIds", "pluginIds", "expectedTurnId", "panelId", "expectedSourceVersion"))
            if (request.has(key)) entry.put(key, request.get(key))
        return JSONArray().put(entry)
    }

    fun move(prefs: SharedPreferences, expected: String, target: String): JSONObject = synchronized(monitor) {
        val source = ""
        checkRevision(prefs, source, expected)
        val current = ComposerPreferences.draft(prefs, target)
        if (current.revision.isNotEmpty() || current.text.isNotEmpty() || current.attachments != "[]" || current.plugins != "[]" ||
            prefs.getString(recoveryKey(target), "[]") != "[]") throw IOException("DRAFT_TARGET_OCCUPIED")

        val draft = ComposerPreferences.draft(prefs, source)
        val saved = prefs.getString(recoveryKey(source), "[]")
        val editor = prefs.edit()
        val changed = ArrayList<String>()
        if (draft.text.isNotEmpty() || draft.attachments != "[]" || draft.plugins != "[]" || saved != "[]") {
            ComposerPreferences.putDraftText(editor, target, draft.text)
            ComposerPreferences.putDraftText(editor, source, "")
            changed.addAll(listOf(ComposerPreferences.draftTextKey(source), ComposerPreferences.draftRevisionKey(source),
                ComposerPreferences.draftTextKey(target), ComposerPreferences.draftRevisionKey(target)))
        }
        for (name in arrayOf("attachments", "plugins", "failedDeliveries", *optionNames)) {
            val from = ComposerPreferences.key(source, name)
            val to = ComposerPreferences.key(target, name)
            val value = prefs.getString(from, null) ?: continue
            if (prefs.contains(to)) throw IOException("DRAFT_TARGET_OCCUPIED")
            editor.putString(to, value).remove(from)
            changed.add(from)
            changed.add(to)
        }
        if (changed.isNotEmpty()) persist(prefs, editor, *changed.toTypedArray())
        snapshot(prefs, target)
    }

    private fun recoveryKey(thread: String) = ComposerPreferences.key(thread, "failedDeliveries")

    private fun checkRevision(prefs: SharedPreferences, thread: String, expected: String) {
        val actual = ComposerPreferences.draftRevision(prefs, thread)
        if (actual != expected) throw RevisionConflict(actual)
    }

    private fun recoverLegacyDelivery(prefs: SharedPreferences, thread: String) {
        val recovery = recoveryKey(thread)
        val keys = arrayOf(ComposerPreferences.key(thread, "delivery"), ComposerPreferences.key(thread, "steerDelivery"))
        if (keys.none(prefs::contains)) return
        val saved = JSONArray(prefs.getString(recovery, "[]"))
        val editor = prefs.edit()
        var changed = false
        for (key in keys) {
            val raw = prefs.getString(key, null) ?: continue
            val item = try { JSONObject(raw) } catch (_: org.json.JSONException) { continue }
            val id = item.optString("token")
            if (id.isEmpty()) continue
            if ((0 until saved.length()).none { saved.optJSONObject(it)?.optString("token") == id }) {
                if (item.optString("status") != "failed") item.put("status", "delivery-unconfirmed")
                saved.put(item)
            }
            editor.remove(key)
            changed = true
        }
        if (changed) persist(prefs, editor.putString(recovery, saved.toString()), recovery, *keys)
    }

    private fun recoverLegacyHome(prefs: SharedPreferences, thread: String) {
        val id = prefs.getString("home-pending-id", null)?.takeIf(String::isNotEmpty) ?: return
        val text = prefs.getString("home-pending-text", null)?.takeIf { it.isNotBlank() } ?: return
        val explicit = prefs.getString("home-pending-thread", "").orEmpty()
        val target = if (prefs.getBoolean("home-pending-new-thread", false)) ""
            else explicit.ifEmpty { prefs.getString("conversation", "").orEmpty() }
        if (thread != target) return
        val receiptsKey = "home-handoff-receipts"
        val receipts = JSONArray(prefs.getString(receiptsKey, "[]"))
        val already = (0 until receipts.length()).any { receipts.optString(it) == id }
        val editor = prefs.edit()
        val attachments = ComposerPreferences.key(thread, "attachments")
        val plugins = ComposerPreferences.key(thread, "plugins")
        if (!already && prefs.getString(ComposerPreferences.key(thread, "homeApplied"), null) != id) {
            val current = ComposerPreferences.draft(prefs, thread)
            if (current.text != text || current.attachments != "[]" || current.plugins != "[]") {
                if (!current.isEmpty) ComposerPreferences.stashDraft(prefs, editor, current)
                ComposerPreferences.putDraftText(editor, thread, text)
                editor.remove(attachments).remove(plugins)
            }
        }
        if (!already) {
            while (receipts.length() >= 40) receipts.remove(0)
            receipts.put(id)
        }
        // The old Boolean is inert without an ID. Keeping it avoids changing that key's type on rollback.
        editor.remove("home-pending-id").remove("home-pending-text").remove("home-pending-thread")
        editor.putString(receiptsKey, receipts.toString())
        persist(prefs, editor, "home-pending-id", "home-pending-text", "home-pending-thread", receiptsKey,
            ComposerPreferences.draftTextKey(thread), ComposerPreferences.draftRevisionKey(thread), attachments, plugins, recoveryKey(thread))
    }

    private fun snapshot(prefs: SharedPreferences, thread: String): JSONObject = JSONObject()
        .put("threadId", thread).put("revision", ComposerPreferences.draftRevision(prefs, thread))
        .put("text", ComposerPreferences.draftText(prefs, thread))
        .put("attachments", attachmentMetadata(ComposerPreferences.attachments(prefs, thread)))
        .put("plugins", pluginMetadata(ComposerPreferences.plugins(prefs, thread)))
        .put("recovery", recoveryMetadata(prefs, thread))
        .put("pendingReceipts", pendingMetadata(prefs, thread))

    private fun attachmentMetadata(items: JSONArray): JSONArray {
        val result = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val path = item.optString("path").takeIf { it.isNotEmpty() } ?: continue
            val file = File(path)
            result.put(JSONObject().put("id", file.name).put("name", item.optString("name"))
                .put("mime", item.optString("mime", "application/octet-stream"))
                .put("size", if (item.has("size")) item.getLong("size") else file.length()))
        }
        return result
    }

    private fun pluginMetadata(items: JSONArray): JSONArray {
        val result = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotEmpty() } ?: continue
            result.put(JSONObject().put("id", id).put("name", item.optString("name", id)))
        }
        return result
    }

    private fun persist(prefs: SharedPreferences, editor: SharedPreferences.Editor, vararg keys: String) {
        val previous = Array(keys.size) { index -> prefs.getString(keys[index], null) }
        if (editor.commit()) return
        // A failed Android commit can still change the in-process map.
        val rollback = prefs.edit()
        for (index in keys.indices) rollback.putString(keys[index], previous[index])
        rollback.apply()
        throw IOException("DRAFT_SAVE_FAILED")
    }
}
