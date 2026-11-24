package org.agentos.assistant

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

internal class PanelRegistry(private val context: Context, private val runtime: MatterRuntime, private val store: MatterStore) {
    private val services get() = runtime.services
    @Throws(Exception::class)
    fun call(operation: String, raw: JSONObject): JSONObject {
        val input = PanelProtocol.parse("panels/$operation", PanelProtocol.copy(raw))
        if (operation == "describe") return PanelProtocol.describe().put("migration", runtime.migrationReport())
        if (operation == "navigation") return services.navigation()
        if (operation == "open") {
            runtime.state { panel(input.getString("panelId")) }
            services.open(input.getString("panelId"))
            return JSONObject().put("accepted", true).put("panelId", input.getString("panelId"))
        }
        if (operation == "get") {
            val record = runtime.state { panel(input.getString("panelId")) }
            val availability = services.threadState(record.getString("originThreadId"))
            return runtime.state {
                val current = panel(record.getString("panelId"))
                val matter = store.get("matters", current.getString("matterId"))
                val sourceState = JSONObject().put("state", if (matter == null) "unavailable" else availability)
                if (matter != null) sourceState.put("observedAt", matter.getJSONObject("meta").optLong("observedAt"))
                if (availability == "thread-unavailable") sourceState.put("code", "SOURCE_THREAD_NOT_FOUND")
                if (matter == null) sourceState.put("code", "SOURCE_UNAVAILABLE")
                JSONObject().put("panel", current).put("sourceState", sourceState).put("updateReceipt", latestUpdate(current.getString("panelId")) ?: JSONObject.NULL)
                    .put("capabilities", JSONObject().put("canOpen", true).put("canContinue", availability != "thread-unavailable").put("canUnpin", true))
                    .put("listRevision", revision())
            }
        }
        if (operation == "continue") return continuePanel(input)
        if (operation == "list") return runtime.state { list(input) }
        val request = input.getString("requestId")
        val hash = PanelProtocol.hash(operation + "\n" + PanelProtocol.canonical(input))
        val prior = runtime.state { receipt(request, operation, hash) }
        if (prior != null) return prior.put("replayed", true)
        val prepared = if (operation == "pin") runtime.preparePin(input.getJSONObject("source")) else null
        val result = runtime.state(true) {
            val replay = receipt(request, operation, hash)
            if (replay != null) replay.put("replayed", true)
            else {
                val result = when (operation) {
                    "pin" -> pin(prepared!!)
                    "unpin" -> {
                        expect(revision(), input.getLong("expectedListRevision"))
                        val old = panel(input.getString("panelId"))
                        store.remove("panel_records", old.getString("panelId"))
                        val revision = advance()
                        runtime.invalidateMatter(old.getString("matterId"))
                        JSONObject().put("removed", true).put("listRevision", revision)
                    }
                    "rename" -> {
                        val record = panel(input.getString("panelId"))
                        expect(record.getLong("metadataRevision"), input.getLong("expectedMetadataRevision"))
                        if (record.getString("title") != input.getString("title")) {
                            record.put("title", input.getString("title")).put("metadataRevision", record.getLong("metadataRevision") + 1).put("updatedAt", System.currentTimeMillis())
                            store.put("panel_records", record.getString("panelId"), record); advance()
                        }
                        JSONObject().put("panel", record).put("listRevision", revision())
                    }
                    "move" -> move(input)
                    else -> throw IOException("UNKNOWN_METHOD")
                }.put("replayed", false)
                store.put("panel_receipts", request, JSONObject().put("operation", operation).put("hash", hash).put("result", result))
                result
            }
        }
        services.notifyDirectory()
        return result
    }

    @Throws(Exception::class)
    fun cardView(operation: String, raw: JSONObject): JSONObject {
        val input = PanelProtocol.parse("card-view/$operation", PanelProtocol.copy(raw))
        val result = runtime.state(operation == "apply") {
            if (operation == "read") {
                val matter = store.get("matters", input.getString("matterId"))
                if (matter == null) store.get("card_view_states", input.getString("matterId")) ?: throw IOException("SOURCE_UNAVAILABLE") else runtime.cardViews.read(matter)
            } else {
                runtime.cardViews.apply(input).also { if (!it.optBoolean("replayed")) runtime.invalidateMatter(input.getString("matterId")) }
            }
        }
        if (operation == "apply") services.notifyMatter(input.getString("matterId"))
        return result
    }

    private fun directory(): JSONObject = store.get("panel_directory", "directory") ?: throw IOException("STORAGE_ERROR")
    fun revision(): Long = directory().getLong("listRevision")
    private fun advance(): Long {
        val row = directory()
        val next = row.getLong("listRevision") + 1
        if (next > PanelProtocol.MAX_INTEGER) throw IOException("MATTER_STORAGE_FULL")
        store.put("panel_directory", "directory", row.put("listRevision", next))
        return next
    }
    private fun panel(id: String) = store.get("panel_records", id) ?: throw IOException("PANEL_NOT_FOUND")
    private fun expect(actual: Long, expected: Long) { if (actual != expected) throw IOException("VERSION_CONFLICT") }
    private fun receipt(id: String, operation: String, hash: String): JSONObject? {
        if (store.get("panel_updates", id) != null || store.hasCardViewReceipt(id)) throw IOException("IDEMPOTENCY_CONFLICT")
        val previous = store.get("panel_receipts", id) ?: return null
        if (previous.getString("operation") != operation || previous.getString("hash") != hash) throw IOException("IDEMPOTENCY_CONFLICT")
        return PanelProtocol.copy(previous.getJSONObject("result"))
    }
    private fun pin(prepared: JSONObject): JSONObject {
        val matter = runtime.admitPin(prepared)
        val matterId = matter.getString("matterId")
        val existing = store.panelForMatter(matterId)
        if (existing != null) return JSONObject().put("panel", existing).put("created", false).put("listRevision", revision())
        pinCode(matter)?.let { throw IOException(it) }
        val rows = store.all("panel_records")
        var position = 0L
        for (i in 0 until rows.length()) position = maxOf(position, rows.getJSONObject(i).getLong("position") + 1)
        val now = System.currentTimeMillis()
        val title = matter.getJSONObject("baseRequest").optString("title").trim().ifEmpty { "Panel" }
        val record = JSONObject().put("panelId", UUID.randomUUID().toString()).put("originThreadId", matter.getString("threadId"))
            .put("matterId", matterId).put("sourceVersion", 0).put("metadataRevision", 0)
            .put("title", title.substring(0, title.offsetByCodePoints(0, minOf(120, title.codePointCount(0, title.length))))).put("position", position).put("createdAt", now).put("updatedAt", now)
        store.put("panel_records", record.getString("panelId"), record)
        val revision = advance()
        runtime.invalidateMatter(matterId)
        return JSONObject().put("panel", record).put("created", true).put("listRevision", revision)
    }
    private fun move(input: JSONObject): JSONObject {
        expect(revision(), input.getLong("expectedListRevision"))
        val id = panel(input.getString("panelId")).getString("panelId")
        val before = if (input.isNull("beforePanelId")) null else panel(input.getString("beforePanelId")).getString("panelId")
        if (id == before) return JSONObject().put("listRevision", revision())
        val ordered = store.panelPage(-1, "", Int.MAX_VALUE)
        val rows = (0 until ordered.length()).map { ordered.getJSONObject(it) }.toMutableList()
        val previous = rows.map { it.getString("panelId") }
        val moving = rows.first { it.getString("panelId") == id }
        rows.remove(moving)
        rows.add(if (before == null) rows.size else rows.indexOfFirst { it.getString("panelId") == before }, moving)
        if (previous != rows.map { it.getString("panelId") }) {
            for ((index, record) in rows.withIndex()) if (record.getLong("position") != index.toLong())
                store.put("panel_records", record.getString("panelId"), record.put("position", index).put("updatedAt", System.currentTimeMillis()))
            advance()
        }
        return JSONObject().put("listRevision", revision())
    }
    private fun list(input: JSONObject): JSONObject {
        val revision = revision()
        var position = -1L
        var id = ""
        if (input.has("cursor") && !input.isNull("cursor")) {
            val cursor = decodeCursor(input.getString("cursor"))
            if (cursor.getLong("revision") != revision) throw IOException("LIST_CHANGED")
            position = cursor.getLong("position"); id = cursor.getString("id")
        }
        val limit = input.optInt("limit", 50)
        val rows = store.panelPage(position, id, limit + 1)
        val items = JSONArray()
        for (i in 0 until minOf(limit, rows.length())) items.put(rows.get(i))
        val next = if (rows.length() > limit) items.getJSONObject(items.length() - 1).let {
            encodeCursor(JSONObject().put("revision", revision).put("position", it.getLong("position")).put("id", it.getString("panelId")))
        } else JSONObject.NULL
        return JSONObject().put("items", items).put("nextCursor", next).put("listRevision", revision)
    }
    private fun signature(value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(directory().getString("cursorSecret").toByteArray(Charsets.UTF_8), "HmacSHA256")); doFinal(value.toByteArray(Charsets.UTF_8))
    }
    private fun encodeCursor(value: JSONObject): String {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toString().toByteArray(Charsets.UTF_8))
        return body + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(body))
    }
    private fun decodeCursor(value: String): JSONObject = try {
        val parts = value.split('.')
        if (parts.size != 2 || !MessageDigest.isEqual(signature(parts[0]), Base64.getUrlDecoder().decode(parts[1]))) throw IOException("INVALID_ARGUMENT")
        JSONObject(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
    } catch (failure: Exception) { throw IOException("INVALID_ARGUMENT", failure) }

    fun retained(): MutableSet<String> {
        val result = HashSet<String>()
        for (rows in arrayOf(store.all("panel_records"), store.pendingPanelUpdates())) {
            for (i in 0 until rows.length()) result.add(rows.getJSONObject(i).getString("matterId"))
        }
        return result
    }
    fun pinState(matter: JSONObject): JSONObject {
        val id = matter.getString("matterId")
        val existing = store.panelForMatter(id)
        val code = pinCode(matter)
        val provenance = matter.getJSONObject("meta").getJSONObject("provenance")
        val source = if (provenance.optString("kind") == "primary-assistant-reply") JSONObject().put("kind", "primary")
            .put("threadId", matter.getString("threadId")).put("turnId", provenance.getString("turnId")).put("replyId", provenance.getString("replyId"))
            .put("revision", provenance.getLong("replyRevision")).put("contractHash", provenance.getString("contractHash"))
            else JSONObject().put("kind", "matter").put("matterId", id)
        return JSONObject().put("canPin", code == null).put("panelId", existing?.getString("panelId") ?: JSONObject.NULL)
            .put("source", if (code == null) source else JSONObject.NULL).put("listRevision", revision()).also { if (code != null) it.put("code", code) }
    }
    fun pinCode(matter: JSONObject, mode: String? = matter.optJSONObject("baseRequest")?.optString("mode")): String? {
        if (store.get("display_supersessions", matter.getString("matterId")) != null) return "STALE_SOURCE"
        val provenance = matter.getJSONObject("meta").getJSONObject("provenance")
        if (provenance.optString("kind") != "primary-assistant-reply") return null
        if (provenance.optString("replyPhase", "completed") != "completed") return "REPLY_INCOMPLETE"
        if (provenance.optString("contractHash") != services.contractHash()) return "SOURCE_CHANGED"
        val rows = store.pendingPanelUpdates()
        for (i in 0 until rows.length()) {
            val update = rows.getJSONObject(i)
            if (update.getString("originThreadId") != matter.getString("threadId")) continue
            if (update.optString("taskId").isEmpty()) continue
            if (update.has("snapshot") && mode != "panel") continue
            if (update.optString("turnId").isNotEmpty()) {
                if (update.getString("turnId") == provenance.optString("turnId")) return "PANEL_UPDATE_PENDING"
            } else if (!(update.optJSONArray("knownReplies") ?: JSONArray()).let { known -> (0 until known.length()).any { known.optString(it) == provenance.optString("replyId") } }) return "PANEL_UPDATE_PENDING"
        }
        return null
    }

    fun composerReceipt(input: JSONObject): JSONObject {
        val requestId = input.getString("requestId")
        val thread = input.getString("threadId")
        val action = input.getString("action")
        if (action != "continue") {
            if (action == "sendMessage" && runtime.state {
                    store.get("panel_updates", requestId) != null || store.get("panel_receipts", requestId)?.optString("operation") == "continue"
                }) throw IOException("REQUEST_ID_REUSED")
            return services.submissions.lookup(requestId, thread, input.getString("text"), input.optString("expectedTurnId").takeIf { it.isNotEmpty() }, composer = input.has("options"))
        }
        val args = JSONObject().put("requestId", requestId).put("panelId", input.getString("panelId"))
            .put("expectedSourceVersion", input.getLong("expectedSourceVersion")).put("text", input.getString("text"))
        val hash = continueHash(args)
        val target = runtime.state { store.get("panel_updates", requestId) }
        if (target == null) return runtime.state {
            val denial = store.get("panel_receipts", requestId)?.takeIf { it.optString("operation") == "continue" }
                ?: return@state JSONObject().put("requestId", requestId).put("threadId", thread).put("state", "missing")
            denialReceipt(denial, hash, thread, "REQUEST_ID_REUSED")
        }
        if (target.getString("originThreadId") != thread || target.getString("hash") != hash)
            throw IOException("REQUEST_ID_REUSED")
        val receipt = services.submissions.lookup(requestId, thread, input.getString("text"), panel = target, composer = input.has("options"))
        return (if (receipt.getString("state") == "missing") target.getJSONObject("submission") else receipt)
            .put("panelId", args.getString("panelId"))
    }

    private fun continueHash(input: JSONObject): String = PanelProtocol.hash("continue\n" + PanelProtocol.canonical(input))

    private fun denialReceipt(stored: JSONObject, hash: String, thread: String?, conflict: String): JSONObject {
        if (stored.optString("operation") != "continue" || thread == null || stored.optString("threadId") != thread || stored.optString("hash") != hash)
            throw IOException(conflict)
        return stored.getJSONObject("result")
    }

    private sealed interface Admission {
        data class Update(val target: JSONObject, val created: Boolean) : Admission
        data class Denied(val receipt: JSONObject) : Admission
    }

    fun continueComposer(request: JSONObject): JSONObject {
        val args = JSONObject().put("requestId", request.getString("requestId")).put("panelId", request.getString("panelId"))
            .put("expectedSourceVersion", request.getLong("expectedSourceVersion")).put("text", request.getString("text"))
        return continuePanel(args, request)
    }

    private fun continuePanel(input: JSONObject, composerRequest: JSONObject? = null): JSONObject {
        val requestId = input.getString("requestId")
        val hash = continueHash(input)
        val existingSubmission = AgentGatewaySubmissions.existing(context.filesDir, requestId)
        val thread = composerRequest?.getString("threadId")
        val admission = runtime.state(true) {
            val old = store.get("panel_updates", requestId)
            if (old != null) {
                if (old.getString("hash") != hash || thread != null && old.getString("originThreadId") != thread) throw IOException("IDEMPOTENCY_CONFLICT")
                Admission.Update(old, false)
            } else {
                val prior = store.get("panel_receipts", requestId)
                if (prior != null) return@state Admission.Denied(denialReceipt(prior, hash, thread, "IDEMPOTENCY_CONFLICT").put("replayed", true))
                if (existingSubmission != null || store.hasCardViewReceipt(requestId)) throw IOException("IDEMPOTENCY_CONFLICT")
                val panelId = input.getString("panelId")
                val record = store.get("panel_records", panelId)
                val matter = record?.let { store.get("matters", it.getString("matterId")) }
                val denial = when {
                    record == null -> "PANEL_NOT_FOUND"
                    thread != null && thread != record.getString("originThreadId") -> "SOURCE_CHANGED"
                    record.getLong("sourceVersion") != input.getLong("expectedSourceVersion") -> "VERSION_CONFLICT"
                    matter == null -> "SOURCE_UNAVAILABLE"
                    else -> null
                }
                if (denial != null) {
                    if (thread == null) throw IOException(denial)
                    val receipt = JSONObject().put("requestId", requestId).put("threadId", thread).put("panelId", panelId)
                        .put("state", "notSent").put("code", denial)
                    store.put("panel_receipts", requestId, JSONObject().put("operation", "continue").put("hash", hash)
                        .put("threadId", thread).put("result", receipt))
                    return@state Admission.Denied(receipt.put("replayed", false))
                }
                checkNotNull(record)
                checkNotNull(matter)
                val snapshot = JSONObject().put("title", record.getString("title"))
                    .put("card", matter.getJSONObject("baseRequest")).put("inputs", runtime.cardViews.effectiveInputs(matter))
                val known = runtime.knownReplyIds(record.getString("originThreadId"))
                JSONObject().put("requestId", requestId).put("panelId", record.getString("panelId")).put("originThreadId", record.getString("originThreadId"))
                    .put("matterId", record.getString("matterId")).put("sourceVersion", record.getLong("sourceVersion")).put("hash", hash)
                    .put("text", input.getString("text")).put("snapshot", snapshot)
                    .put("knownReplies", known).put("settled", false).put("outcome", "pending").put("createdAt", System.currentTimeMillis())
                    .put("submission", JSONObject().put("requestId", requestId).put("threadId", record.getString("originThreadId")).put("state", "deliveryUnknown"))
                    .let { update ->
                        store.put("panel_updates", requestId, update)
                        Admission.Update(update, true)
                    }
            }
        }
        return when (admission) {
            is Admission.Denied -> admission.receipt
            is Admission.Update -> {
                if (admission.created) submitUpdate(admission.target, composerRequest)
                runtime.recoverPanelUpdates()
                runtime.state { submissionResult(store.get("panel_updates", requestId)!!, !admission.created) }
            }
        }
    }

    /** Submits a stored update again after an interruption, with the composer options it was sent with. */
    internal fun resubmitUpdate(target: JSONObject): JSONObject {
        val prefs = context.getSharedPreferences("agent-settings", android.content.Context.MODE_PRIVATE)
        return submitUpdate(target, ComposerState.submission(prefs, target.getString("requestId")))
    }

    private fun submitUpdate(target: JSONObject, composerRequest: JSONObject?): JSONObject {
        val requestId = target.getString("requestId")
        val publication = JSONObject().put("threadId", target.getString("originThreadId")).put("text", target.getString("text"))
            .put("requestId", requestId).put("panelUpdate", PanelProtocol.taskBinding(target))
        if (composerRequest != null) publication.put("options", composerRequest.getJSONObject("options"))
        val receipt = services.submissions.submit(requestId, target.getString("originThreadId"), target.getString("text"), {
            services.checkSubmit(target.getString("originThreadId"))
            services.checkScreenshot(publication)
            runtime.state(background = true) { targetFailure(target)?.let { throw IOException(it) } }
        }, { before -> services.publish(publication) { task ->
            before.persist(task)
            runtime.state(true, true) {
                targetFailure(target)?.let { throw AgentGatewaySubmissions.RejectedBeforeReady(it) }
                val update = store.get("panel_updates", requestId)!!
                update.put("taskId", task)
                runtime.reservePanelTask(update)
                store.put("panel_updates", requestId, update)
            }
            runtime.observePanelTasks()
        } })
        runtime.state(true, true) {
            val update = store.get("panel_updates", requestId)!!
            update.put("submission", receipt)
            if (receipt.optString("state") == "notSent") settle(update, "not-sent", receipt.optString("code"))
            store.put("panel_updates", requestId, update)
        }
        services.notifyDirectory()
        return receipt
    }
    private fun targetFailure(target: JSONObject): String? {
        val current = store.get("panel_records", target.getString("panelId")) ?: return "PANEL_NOT_FOUND"
        return if (current.getString("originThreadId") != target.getString("originThreadId") || current.getString("matterId") != target.getString("matterId") ||
            current.getLong("sourceVersion") != target.getLong("sourceVersion")) "STALE_SOURCE" else null
    }
    private fun submissionResult(update: JSONObject, replayed: Boolean): JSONObject = PanelProtocol.copy(update.getJSONObject("submission"))
        .put("panelId", update.getString("panelId")).put("update", updateReceipt(update)).put("replayed", replayed)
    private fun updateReceipt(update: JSONObject): JSONObject {
        val result = JSONObject()
        for (key in arrayOf("requestId", "panelId", "taskId", "originThreadId", "matterId", "sourceVersion", "turnId", "outcome", "code", "candidates", "publishedMatterId", "publishedSourceVersion", "settled", "createdAt", "updatedAt"))
            if (update.has(key)) result.put(key, update.get(key))
        return result
    }
    private fun latestUpdate(panelId: String): JSONObject? = store.latestPanelUpdate(panelId)?.let { updateReceipt(it) }

    fun publish(update: JSONObject, next: JSONObject) {
        val old = store.get("panel_records", update.getString("panelId"))
        if (old == null) { settle(update, "unpinned", "PANEL_NOT_FOUND"); return }
        if (old.getLong("sourceVersion") != update.getLong("sourceVersion") || old.getString("matterId") != update.getString("matterId")) {
            settle(update, "stale", "STALE_SOURCE"); return
        }
        val id = next.getString("matterId")
        val other = store.panelForMatter(id)
        if (other != null && other.getString("panelId") != old.getString("panelId")) { settle(update, "rejected", "SOURCE_ALREADY_PINNED"); return }
        val previous = runtime.requireMatter(old.getString("matterId"))
        if (previous.getString("matterId") != id) runtime.supersede(previous, update.getString("requestId"))
        runtime.cardViews.migrate(previous, next, update.optJSONObject("snapshot")?.optJSONObject("inputs"))
        old.put("matterId", id).put("sourceVersion", old.getLong("sourceVersion") + 1).put("updatedAt", System.currentTimeMillis())
        store.put("panel_records", old.getString("panelId"), old)
        update.put("publishedMatterId", id).put("publishedSourceVersion", old.getLong("sourceVersion"))
        settle(update, "published", null)
        advance()
        runtime.invalidateMatter(previous.getString("matterId")); runtime.invalidateMatter(id)
    }
    fun settle(update: JSONObject, outcome: String, code: String?) {
        update.put("settled", true).put("outcome", outcome).put("updatedAt", System.currentTimeMillis())
        if (code != null) update.put("code", code)
        store.put("panel_updates", update.getString("requestId"), update)
    }
    companion object {
        @JvmStatic fun get(context: Context): PanelRegistry = MatterRuntime.get(context).panels
    }
}
