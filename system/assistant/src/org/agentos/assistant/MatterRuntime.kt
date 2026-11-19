package org.agentos.assistant

import android.app.KeyguardManager
import android.content.Context
import android.os.UserManager
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom
import java.util.regex.Pattern
import org.agentos.lab.UiCardRequest
import org.json.JSONArray
import org.json.JSONObject

/** One native owner for projections, shared observations, rule claims and delivery receipt references. */
internal class MatterRuntime(
    private val context: Context,
    private val store: MatterStore,
    private val adapters: SourceAdapters,
    private val io: ExecutorService
) {
    fun interface Submit {
        @Throws(Exception::class)
        fun run(input: JSONObject): JSONObject
    }
    fun interface Listener {
        fun event(event: JSONObject)
    }

    internal val services = PanelNativeServices(context)
    internal val panels = PanelRegistry(context, this, store)
    internal val cardViews = CardViewStateStore(store)
    private var migrationReady = !File(context.filesDir, "agent-ui-panels").isDirectory
    private val retainedReads = HashMap<String, Int>()
    private val watches = HashMap<String, AutoCloseable>()
    private val pending = HashSet<String>()
    private val dirty = HashSet<String>()
    private val ownedSources = HashSet<String>()
    private val generations = HashMap<String, Long>()
    private val leases = HashMap<String, Set<String>>()
    private val listeners = HashSet<Listener>()
    private val deliveringOccurrences = HashSet<String>()
    private val epoch = UUID.randomUUID().toString()
    private var seq = 0L
    private var submit: Submit? = Submit { services.submit(it) }
    private var deferredEvents: MutableList<JSONObject>? = null
    private val primaryReplies = LinkedHashMap<String, JSONObject>()
    private var replyReader: AgentGatewayProtocol.NativeRead? = AgentGatewayProtocol.NativeRead { services.reply(it) }
    private val primaryKeyPattern = Pattern.compile("reply-[a-f0-9]{32}")

    private fun primaryKey(key: String): Boolean = primaryKeyPattern.matcher(key).matches()

    private fun primaryUnavailable(matter: JSONObject): Boolean {
        val provenance = matter.getJSONObject("meta").getJSONObject("provenance")
        val primary = provenance.optString("kind") == "primary-assistant-reply"
        return (primary || primaryKey(matter.getString("key"))) && (!primary || provenance.optString("replyPhase", "completed") != "completed")
    }

    @Synchronized
    fun setReplyReader(reader: AgentGatewayProtocol.NativeRead) { replyReader = reader }


    private constructor(context: Context) : this(context, MatterStore(context), SourceAdapters(context),
        Executors.newFixedThreadPool(4) { runnable ->
            val thread = Thread(runnable, "matter-sources")
            thread.isDaemon = true
            thread
        })

    @Throws(Exception::class)
    private fun <T> transaction(work: MatterStore.Work<T>): T {
        if (deferredEvents != null) return work.run()
        val events = ArrayList<JSONObject>()
        deferredEvents = events
        val result: T
        try { result = store.transaction(work) } finally { deferredEvents = null }
        for (event in events) publish(event)
        return result
    }

    @Synchronized
    fun setSubmit(value: Submit?) { submit = value; recover() }

    @Throws(Exception::class)
    private fun reclaim(matter: String?, source: String?, reserveMatters: Int, reserveSources: Int) {
        // Below both limits there is nothing to evict. Ordinary draft saves never scan durable history.
        if (!migrationReady) throw IOException("CARD_STATE_MIGRATION_PENDING")
        if (store.count("matters") + reserveMatters <= 200 &&
            store.count("sources") + reserveSources <= MatterStore.MAX_SOURCES) return
        val retained = panels.retained()
        retained.addAll(cardViews.retained())
        retained.addAll(retainedReads.keys)
        for (ids in leases.values) retained.addAll(ids)
        if (matter != null) retained.add(matter)
        val busy = HashSet(pending)
        busy.addAll(ownedSources)
        busy.addAll(watches.keys)
        if (source != null) busy.add(source)
        transaction {
            MatterRetention.reclaim(store, retained, busy, MatterStore.MAX_MATTERS, MatterStore.MAX_SOURCES, reserveMatters, reserveSources)
            null
        }
        generations.keys.removeIf { id -> try { store.get("sources", id) == null } catch (unavailable: Exception) { false } }
    }

    @Synchronized
    @Throws(Exception::class)
    fun retain(id: String): AutoCloseable {
        unlocked()
        require(id)
        retainedReads[id] = retainedReads.getOrDefault(id, 0) + 1
        return object : AutoCloseable {
            private var closed = false
            override fun close() {
                synchronized(this@MatterRuntime) {
                    if (closed) return
                    closed = true
                    val count = retainedReads.getOrDefault(id, 1)
                    if (count <= 1) retainedReads.remove(id) else retainedReads[id] = count - 1
                }
            }
        }
    }


    @Throws(IOException::class)
    private fun credentialUnlocked() {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) throw IOException("USER_LOCKED")
    }

    @Throws(IOException::class)
    private fun unlocked() {
        credentialUnlocked()
        if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) throw IOException("DEVICE_LOCKED")
    }

    @Throws(Exception::class)
    internal fun requireMatter(id: String): JSONObject = require(id)

    private fun require(id: String): JSONObject {
        AgentGatewayProtocol.matterIdentity(id)
        return store.get("matters", id) ?: throw IOException("MATTER_UNAVAILABLE")
    }

    internal fun <T> state(write: Boolean = false, background: Boolean = false, work: () -> T): T {
        val events = ArrayList<JSONObject>()
        val result: T
        synchronized(this) {
            if (background) credentialUnlocked() else unlocked()
            if (!write) return work()
            check(deferredEvents == null)
            val replies = LinkedHashMap(primaryReplies)
            val sourceGenerations = HashMap(generations)
            deferredEvents = events
            try {
                result = store.transaction {
                    val value = work()
                    val ids = LinkedHashSet<String>()
                    for (event in events) if (event.optString("method") == "matter/snapshot") ids.add(event.getString("matterId"))
                    events.removeAll { it.optString("method") == "matter/snapshot" }
                    for (id in ids) store.get("matters", id)?.let { events.add(JSONObject().put("method", "matter/snapshot").put("matterId", id).put("snapshot", snapshot(it))) }
                    value
                }
            } catch (failure: Exception) {
                primaryReplies.clear(); primaryReplies.putAll(replies)
                generations.clear(); generations.putAll(sourceGenerations)
                throw failure
            }
            finally { deferredEvents = null }
        }
        for (event in events) {
            val recipients: List<Listener>
            synchronized(this) {
                event.put("schema", "taku.matter-events/1").put("runtimeEpoch", epoch).put("seq", ++seq)
                recipients = ArrayList(listeners)
            }
            for (listener in recipients) try { listener.event(copy(event)) } catch (_: Exception) { }
        }
        return result
    }

    internal fun invalidateMatter(id: String) {
        val matter = store.get("matters", id) ?: return
        val event = JSONObject().put("method", "matter/snapshot").put("matterId", id).put("snapshot", snapshot(matter))
        val deferred = deferredEvents
        if (deferred != null) deferred.add(event) else publish(event)
    }

    internal fun supersede(matter: JSONObject, requestId: String) {
        store.put("display_supersessions", matter.getString("matterId"), JSONObject().put("requestId", requestId)
            .put("sourceId", matter.getString("presentationSourceId")).put("at", System.currentTimeMillis()))
        rebuild(matter, false)
        commit(matter)
    }

    internal fun preparePin(source: JSONObject): JSONObject {
        if (source.getString("kind") == "matter") {
            val matter = state { copy(require(source.getString("matterId"))) }
            val provenance = matter.getJSONObject("meta").getJSONObject("provenance")
            if (provenance.optString("kind") != "primary-assistant-reply")
                return JSONObject().put("matterId", matter.getString("matterId")).put("sourceId", matter.getString("presentationSourceId"))
            return preparePin(JSONObject().put("kind", "primary").put("threadId", matter.getString("threadId"))
                .put("turnId", provenance.getString("turnId")).put("replyId", provenance.getString("replyId"))
                .put("revision", provenance.getLong("replyRevision")).put("contractHash", provenance.getString("contractHash")))
        }
        val thread = source.getString("threadId")
        val turn = source.getString("turnId")
        val result = AgentGatewayProtocol.replyRead(JSONObject().put("threadId", thread).put("turnId", turn).put("replyId", source.getString("replyId")),
            synchronized(this) { replyReader } ?: throw IOException("SOURCE_UNAVAILABLE"))
        val reply = if (result.optBoolean("ok")) result.getJSONObject("reply") else state {
            val snapshots = store.all("primary_snapshots")
            var saved: JSONObject? = null
            for (i in 0 until snapshots.length()) {
                val candidate = snapshots.getJSONObject(i).getJSONObject("reply")
                if (candidate.optString("id") == source.getString("replyId") && candidate.getJSONObject("provenance").optString("threadId") == thread) saved = candidate
            }
            saved ?: throw IOException("SOURCE_UNAVAILABLE")
        }
        validatePrimary(reply, thread, turn)
        val provenance = reply.getJSONObject("provenance")
        if (reply.getString("id") != source.getString("replyId") || reply.getLong("revision") != source.getLong("revision") ||
            provenance.getString("contractHash") != source.getString("contractHash")) throw IOException("SOURCE_CHANGED")
        return JSONObject().put("matterId", thread + "/" + reply.getString("key")).put("reply", copy(reply))
    }

    private fun validatePrimary(reply: JSONObject, thread: String, turn: String) {
        AgentGatewayProtocol.mainReply(reply, thread, turn)
        if (reply.getJSONObject("lifecycle").getString("phase") != "completed" || reply.optJSONObject("card") == null) throw IOException("REPLY_INCOMPLETE")
        if (reply.getJSONObject("provenance").getString("contractHash") != services.contractHash()) throw IOException("SOURCE_CHANGED")
        val request = reply.getJSONObject("card").getJSONObject("request")
        UiCardRequest.validate(request)
        if (request.getString("key") != reply.getString("key")) throw IOException("SOURCE_CHANGED")
        val actions = request.getJSONArray("actions")
        for (i in 0 until actions.length()) if (actions.getJSONObject(i).has("actionRef")) throw IOException("SOURCE_CHANGED")
        reply.getJSONObject("card").getJSONObject("view").getJSONObject("spec")
    }

    internal fun admitPin(prepared: JSONObject): JSONObject {
        val id = prepared.getString("matterId")
        val reply = prepared.optJSONObject("reply")
        if (reply == null) {
            val matter = require(id)
            if (matter.getString("presentationSourceId") != prepared.getString("sourceId")) throw IOException("SOURCE_CHANGED")
            panels.pinCode(matter)?.let { throw IOException(it) }
            return matter
        }
        val provenance = copy(reply.getJSONObject("provenance")).put("replyId", reply.getString("id"))
            .put("replyRevision", reply.getLong("revision")).put("replyPhase", reply.getJSONObject("lifecycle").getString("phase"))
        val candidate = JSONObject().put("matterId", id).put("threadId", provenance.getString("threadId")).put("meta", JSONObject().put("provenance", provenance))
        panels.pinCode(candidate)?.let { throw IOException(it) }
        val prior = store.get("matters", id)?.getJSONObject("meta")?.getJSONObject("provenance")
        if (prior != null && (prior.optLong("replyRevision") > reply.getLong("revision") || prior.optLong("replyRevision") == reply.getLong("revision") && prior.optString("replyPhase", "completed") != "completed")) throw IOException("SOURCE_CHANGED")
        observeReply(provenance.getString("threadId"), provenance.getString("turnId"), reply)
        return require(id)
    }

    internal fun knownReplyIds(thread: String): JSONArray {
        val ids = HashSet<String>()
        val matters = store.all("matters")
        for (i in 0 until matters.length()) {
            val matter = matters.getJSONObject(i)
            if (matter.getString("threadId") == thread) matter.getJSONObject("meta").getJSONObject("provenance").optString("replyId")
                .takeIf { it.isNotEmpty() }?.let { ids.add(it) }
        }
        for ((id, reply) in primaryReplies) if (id.startsWith("$thread/")) ids.add(reply.getString("id"))
        return JSONArray(ids)
    }

    internal fun reservePanelTask(update: JSONObject) {
        val task = update.getString("taskId")
        val id = "task-$task"
        if (store.get("sources", id) == null) reclaim(update.getString("matterId"), id, 0, 1)
        store.put("sources", id, JSONObject().put("id", id).put("kind", "task-receipt").put("resourceId", task)
            .put("threadId", update.getString("originThreadId")).put("panelRequestId", update.getString("requestId")))
    }

    private fun pendingPanelUpdates(): Boolean = store.hasPendingPanelUpdates()

    internal fun observePanelTasks() { synchronized(this) { reconcile(); MatterJob.schedule(context, nextDue(), pendingPanelUpdates()) } }

    internal fun recoverPanelUpdates() {
        val updates = state(background = true) { store.pendingPanelUpdates() }
        for (i in 0 until updates.length()) {
            val update = updates.getJSONObject(i)
            val receipt = AgentGatewaySubmissions.existing(context.filesDir, update.getString("requestId"))
            if (receipt == null && update.optString("taskId").isEmpty() && !update.has("text")) {
                state(true, true) {
                    val current = store.get("panel_updates", update.getString("requestId"))!!
                    current.put("submission", JSONObject().put("requestId", current.getString("requestId"))
                        .put("threadId", current.getString("originThreadId")).put("state", "notSent").put("code", "SUBMISSION_NOT_STARTED"))
                    panels.settle(current, "not-sent", "SUBMISSION_NOT_STARTED")
                }
            }
            val binding = if (receipt?.optString("taskId")?.isNotEmpty() == true) try {
                SourceAdapters.readObject(File(File(TaskRuntime.TASKS, receipt.getString("taskId")), "request.json")).optJSONObject("panelUpdate")
            } catch (_: Exception) { null } else null
            if (receipt != null) state(true, true) {
                val current = store.get("panel_updates", update.getString("requestId"))!!
                if (!current.optBoolean("settled")) {
                    if (current.optString("taskId").isEmpty() && receipt.optString("taskId").isNotEmpty()) {
                        val task = receipt.getString("taskId")
                        if (binding != null && arrayOf("requestId", "panelId", "originThreadId", "matterId", "sourceVersion").all { canonical(binding.opt(it)) == canonical(current.opt(it)) }) {
                            current.put("taskId", task); reservePanelTask(current)
                        }
                    }
                    current.put("submission", receipt)
                    if (receipt.optString("state") == "notSent") panels.settle(current, "not-sent", receipt.optString("code"))
                    else store.put("panel_updates", current.getString("requestId"), current)
                }
            }
            val task = state(background = true) { store.get("panel_updates", update.getString("requestId"))!!.optString("taskId") }
            if (task.isNotEmpty()) taskChanged(task)
        }
        observePanelTasks()
    }

    @Throws(Exception::class)
    fun taskChanged(taskId: String) {
        if (!taskId.matches(Regex("[0-9]{1,20}"))) return
        val bound = state(background = true) { store.panelUpdateForTask(taskId) } ?: return
        val directory = File(TaskRuntime.TASKS, taskId)
        var evidenceFailure: String? = null
        fun evidence(file: File, maximum: Int = 128_000): JSONObject? = try { SourceAdapters.readObject(file, maximum) }
            catch (invalid: Exception) { evidenceFailure = if (invalid is org.json.JSONException) "SOURCE_CHANGED" else "SOURCE_UNAVAILABLE"; null }
        val request = evidence(File(directory, "request.json")) ?: JSONObject()
        val info = File(directory, "runtime-info.json").let { if (it.isFile) evidence(it) ?: JSONObject() else JSONObject() }
        val terminal = File(directory, "done").isFile
        val manifestFile = File(directory, "panel-completion.json")
        val manifest = if (manifestFile.isFile) evidence(manifestFile, 8 * 1024 * 1024) else null
        val taskState = if (terminal) evidence(File(directory, "state.json")) else null
        val done = if (terminal) File(directory, "done").readText().trim() else ""
        var verified: JSONObject? = null
        var failure: String? = null
        if (terminal) try {
            if (evidenceFailure != null) throw IOException(evidenceFailure)
            if (!File(directory, "ready").isFile) throw IOException("SOURCE_CHANGED")
            if (bound.optString("turnId").isNotEmpty() && bound.getString("turnId") != info.optString("turnId")) throw IOException("SOURCE_CHANGED")
            val target = request.getJSONObject("panelUpdate")
            PanelProtocol.exact(target, setOf("panelId", "originThreadId", "matterId", "sourceVersion", "requestId"))
            for (key in arrayOf("panelId", "originThreadId", "matterId", "sourceVersion", "requestId"))
                if (canonical(target.get(key)) != canonical(bound.get(key))) throw IOException("SOURCE_CHANGED")
            if (request.optString("id") != taskId || request.optString("conversationId") != bound.getString("originThreadId") || taskState!!.optString("id") != taskId || taskState.optString("state") != done) throw IOException("SOURCE_CHANGED")
            val completion = manifest ?: throw IOException("SOURCE_UNAVAILABLE")
            PanelProtocol.exact(completion, setOf("schema", "taskId", "threadId", "turnId", "status", "candidates", "reply", "code"))
            if (completion.optString("schema") != "taku.panel-completion/1" || completion.optString("taskId") != taskId || completion.optString("threadId") != bound.getString("originThreadId") || completion.optString("status") != done) throw IOException("SOURCE_CHANGED")
            if (completion.optString("turnId") != info.optString("turnId") || info.optString("threadId").isNotEmpty() && info.getString("threadId") != bound.getString("originThreadId")) throw IOException("SOURCE_CHANGED")
            if (done == "completed") {
                if (info.optString("threadId") != completion.getString("threadId") || info.optString("turnId").isEmpty() || info.getString("turnId") != completion.getString("turnId")) throw IOException("SOURCE_CHANGED")
                val candidates = completion.getJSONArray("candidates")
                val identities = HashSet<String>()
                for (i in 0 until candidates.length()) {
                    val candidate = candidates.getJSONObject(i)
                    PanelProtocol.exact(candidate, setOf("replyId", "key", "revision", "contractHash", "anchorOrdinal"))
                    if (!identities.add(candidate.getString("replyId")) || !candidate.getString("replyId").matches(Regex("reply-[a-f0-9]{32}")) ||
                        !candidate.getString("key").matches(Regex("[a-z0-9-]{1,64}")) || candidate.getString("contractHash") != services.contractHash()) throw IOException("SOURCE_CHANGED")
                    PanelProtocol.number(candidate, "revision"); PanelProtocol.number(candidate, "anchorOrdinal")
                }
                if (candidates.length() != 1) {
                    val expected = if (candidates.length() == 0) "REPLY_INCOMPLETE" else "PANEL_UPDATE_AMBIGUOUS"
                    if (!completion.isNull("reply") || completion.optString("code") != expected) throw IOException("SOURCE_CHANGED")
                    throw IOException(expected)
                }
                if (completion.has("code")) throw IOException("SOURCE_CHANGED")
                val reply = completion.getJSONObject("reply")
                validatePrimary(reply, completion.getString("threadId"), completion.getString("turnId"))
                val candidate = candidates.getJSONObject(0)
                if (candidate.getString("replyId") != reply.getString("id") || candidate.getString("key") != reply.getString("key") || candidate.getLong("revision") != reply.getLong("revision") ||
                    candidate.getString("contractHash") != reply.getJSONObject("provenance").getString("contractHash") || candidate.getLong("anchorOrdinal") != reply.getJSONObject("provenance").getLong("anchorOrdinal")) throw IOException("SOURCE_CHANGED")
                verified = reply
            } else if (completion.getJSONArray("candidates").length() != 0 || !completion.isNull("reply")) throw IOException("SOURCE_CHANGED")
        } catch (invalid: Exception) { failure = invalid.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "SOURCE_CHANGED" }
        state(true, true) {
            val update = store.get("panel_updates", bound.getString("requestId")) ?: return@state
            if (update.optBoolean("settled")) return@state
            if (info.optString("threadId") == update.getString("originThreadId") && info.optString("turnId").isNotEmpty() &&
                (update.optString("turnId").isEmpty() || update.getString("turnId") == info.getString("turnId"))) update.put("turnId", info.getString("turnId"))
            if (!terminal) { store.put("panel_updates", update.getString("requestId"), update); return@state }
            if (manifest?.optJSONArray("candidates") != null) update.put("candidates", manifest.getJSONArray("candidates"))
            if (failure != null) panels.settle(update, "rejected", failure)
            else if (done != "completed") panels.settle(update, if (done == "cancelled") "interrupted" else "failed", done.uppercase(java.util.Locale.ROOT))
            else {
                val current = store.get("panel_records", update.getString("panelId"))
                val nextId = update.getString("originThreadId") + "/" + verified!!.getString("key")
                val other = store.panelForMatter(nextId)
                when {
                    current == null -> panels.settle(update, "unpinned", "PANEL_NOT_FOUND")
                    current.getLong("sourceVersion") != update.getLong("sourceVersion") || current.getString("matterId") != update.getString("matterId") -> panels.settle(update, "stale", "STALE_SOURCE")
                    other != null && other.getString("panelId") != current.getString("panelId") -> panels.settle(update, "rejected", "SOURCE_ALREADY_PINNED")
                    else -> {
                        publishingPanelReply = true
                        try {
                            observeReply(update.getString("originThreadId"), info.getString("turnId"), verified!!)
                            panels.publish(update, require(nextId))
                        } finally { publishingPanelReply = false }
                    }
                }
            }
        }
        if (terminal) services.notifyDirectory()
        observePanelTasks()
    }

    private var publishingPanelReply = false

    internal fun migrationReport(): JSONArray = state { store.all("card_state_migrations") }

    internal fun migrateCards() {
        val root = File(context.filesDir, "agent-ui-panels")
        val files = root.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        var ready = true
        for (file in files) {
            val identity = "1/" + file.name
            val marker = state(background = true) { store.get("card_state_migrations", identity) }
            if (marker?.optBoolean("complete") == true) continue
            try {
                // The old limit counted UTF-16 code units; a BMP character can occupy three UTF-8 bytes.
                val record = SourceAdapters.readObject(file, 3 * 128_000)
                val thread = PanelProtocol.uuid(record, "threadId")
                val id = thread + "/" + record.getString("key")
                AgentGatewayProtocol.matterIdentity(id)
                val source = record.optJSONObject("source")
                var prepared: JSONObject? = null
                var disposition = "archived-unverified-source"
                var retry = false
                if (source?.optString("kind") == "primary") {
                    try {
                        val native = AgentGatewayProtocol.replyRead(JSONObject().put("threadId", thread).put("turnId", source.getString("turnId")).put("replyId", source.getString("replyId")), replyReader!!)
                        if (!native.optBoolean("ok")) throw IOException("SOURCE_UNAVAILABLE")
                        val reply = native.getJSONObject("reply")
                        validatePrimary(reply, thread, source.getString("turnId"))
                        if (reply.getString("key") != record.getString("key") || reply.getString("id") != source.getString("replyId") || reply.getLong("revision") < source.getLong("revision") || reply.getJSONObject("provenance").getString("contractHash") != source.getString("contractHash")) throw IOException("SOURCE_CHANGED")
                        prepared = reply; disposition = "verified-native-snapshot"
                    } catch (failure: Exception) { retry = failure.message == "SOURCE_UNAVAILABLE"; disposition = if (retry) "pending-source-verification" else "archived-source-mismatch" }
                }
                state(true, true) {
                    if (prepared != null) {
                        val reply = prepared!!
                        val current = store.get("matters", id)?.optJSONObject("meta")?.optJSONObject("provenance")
                        if (current == null || current.optLong("replyRevision") <= reply.getLong("revision")) {
                            store.put("primary_snapshots", id, JSONObject().put("reply", copy(reply)).put("observedAt", System.currentTimeMillis()))
                            if (current != null) observeReply(thread, reply.getJSONObject("provenance").getString("turnId"), reply)
                        }
                    }
                    val previous = store.get("card_state_migrations", identity)
                    val uiDisposition = if (previous?.optBoolean("stateImported") == true) previous.getString("uiDisposition")
                        else cardViews.importLegacy(store.get("matters", id), id, record)
                    store.put("card_state_migrations", identity, JSONObject().put("version", 1).put("file", file.name).put("matterId", id)
                        .put("stateImported", true).put("uiDisposition", uiDisposition).put("snapshotDisposition", disposition).put("complete", !retry).put("observedAt", System.currentTimeMillis()))
                }
                file.setReadOnly()
            } catch (failure: Exception) {
                val archived = failure is org.json.JSONException || failure.message in setOf("INVALID_ARGUMENT", "INVALID_MATTER_ID", "INVALID_key")
                if (!archived) ready = false
                state(true, true) { store.put("card_state_migrations", identity, JSONObject().put("version", 1).put("file", file.name).put("complete", archived)
                    .put("snapshotDisposition", if (archived) "archived-invalid-record" else "retry-required").put("code", failure.message ?: "STORAGE_ERROR")) }
                if (archived) file.setReadOnly()
            }
        }
        synchronized(this) { migrationReady = ready }
    }

    @Synchronized
    @Throws(Exception::class)
    fun cursor(): JSONObject = JSONObject().put("runtimeEpoch", epoch).put("afterSeq", seq)

    @Synchronized
    @Throws(Exception::class)
    fun read(id: String): JSONObject {
        unlocked()
        val matter = require(id)
        val before = canonical(matter.getJSONObject("request")) + canonical(matter.getJSONObject("meta"))
        rebuild(matter, false)
        if (before != canonical(matter.getJSONObject("request")) + canonical(matter.getJSONObject("meta"))) commit(matter)
        return JSONObject().put("snapshot", snapshot(matter)).put("cursor", cursor())
    }

    @Throws(Exception::class)
    private fun snapshot(matter: JSONObject): JSONObject {
        val id = matter.getString("matterId")
        val saved = store.get("primary_snapshots", id)?.optJSONObject("reply")
        val presentation = if (saved != null) JSONObject().put("kind", "primary").put("reply", copy(saved))
            else JSONObject().put("kind", "matter").put("request", copy(matter.getJSONObject("request"))).put("sourceId", matter.getString("presentationSourceId"))
        return JSONObject().put("schema", "taku.matter/1").put("matterId", id)
            .put("threadId", matter.getString("threadId")).put("key", matter.getString("key"))
            .put("revision", matter.getLong("revision")).put("request", copy(matter.getJSONObject("request")))
            .put("meta", copy(matter.getJSONObject("meta"))).put("commands", copy(matter.getJSONObject("commands")))
            .put("pin", panels.pinState(matter)).put("cardView", cardViews.read(matter)).put("presentation", presentation)
    }

    @Synchronized
    @Throws(Exception::class)
    fun observe(thread: String, key: String, request: JSONObject, sourceId: String, provenance: JSONObject) {
        credentialUnlocked()
        AgentGatewayProtocol.matterIdentity("$thread/$key")
        val id = "$thread/$key"
        val previous = store.get("matters", id)
        val ownsPrimary = primaryKey(key) || primaryReplies.containsKey(id) || provenance.optString("kind") == "primary-assistant-reply" ||
            previous?.optJSONObject("meta")?.optJSONObject("provenance")?.optString("kind") == "primary-assistant-reply"
        val presentation: JSONObject
        if (ownsPrimary) {
            if (provenance.optString("kind") != "primary-assistant-reply") throw IOException("PRIMARY_REPLY_SOURCE_OWNED")
            val accepted = primaryReplies[id] ?: throw IOException("INVALID_PRIMARY_SOURCE")
            val source = accepted.getJSONObject("provenance")
            if (accepted.getJSONObject("lifecycle").optString("phase") != "completed" ||
                accepted.getString("id") != provenance.optString("replyId") || accepted.getLong("revision") != provenance.optLong("replyRevision") ||
                source.getString("turnId") != provenance.optString("turnId") || source.getString("contractHash") != provenance.optString("contractHash"))
                throw IOException("INVALID_PRIMARY_SOURCE")
            presentation = accepted.getJSONObject("card").getJSONObject("request")
        } else presentation = request
        UiCardRequest.validate(presentation)
        if (key != presentation.getString("key")) throw IOException("MATTER_KEY_MISMATCH")
        if (previous == null) reclaim(id, null, 1, 0)
        // Preference writes and repeated native snapshots cannot renew capabilities.
        if (previous != null && sourceId == previous.optString("presentationSourceId")) return
        val matter = previous ?: JSONObject().put("matterId", id).put("threadId", thread).put("key", key)
            .put("revision", 0).put("meta", JSONObject()).put("commands", JSONObject())
        if (previous == null) {
            val history = store.related("commands", "matter", id)
            for (i in 0 until history.length()) {
                val command = history.getJSONObject(i)
                matter.getJSONObject("commands").put(command.getString("id"), command.getJSONObject("receipt"))
            }
        }
        matter.put("presentationSourceId", sourceId).put("baseRequest", copy(presentation))
        if (provenance.has("presentationAt")) matter.put("presentationAt", provenance.getLong("presentationAt"))
        val meta = matter.getJSONObject("meta")
        meta.put("provenance", provenance).put("quality", if (provenance.optString("kind") == "primary-assistant-reply") "primary-assistant-reply" else "derived-assistant-reply")
            .put("observedAt", System.currentTimeMillis())
        cardViews.sourceChanged(matter)
        rebuild(matter, true)
        commit(matter)
    }


    @Synchronized
    @Throws(Exception::class)
    fun observeThread(thread: JSONObject) {
        val turns = thread.optJSONArray("turns") ?: return
        for (i in 0 until turns.length()) {
            val turn = turns.optJSONObject(i) ?: continue
            val replies = turn.optJSONObject("mainReplies") ?: continue
            val ids = replies.keys()
            while (ids.hasNext()) {
                val id = ids.next()
                val reply = replies.getJSONObject(id)
                if (reply.optString("id") != id) throw IOException("INVALID_MAIN_REPLY")
                observeReply(thread.getString("id"), turn.getString("id"), reply)
            }
        }
    }

    @Throws(Exception::class)
    fun observeReply(thread: String, turn: String, incoming: JSONObject) {
        AgentGatewayProtocol.mainReply(incoming, thread, turn)
        if (incoming.getJSONObject("lifecycle").optString("phase") == "completed") validatePrimary(incoming, thread, turn)
        synchronized(this) { observeReplyLocked(thread, turn, incoming) }
    }

    private fun observeReplyLocked(thread: String, turn: String, incoming: JSONObject) {
        credentialUnlocked()
        val key = incoming.getString("key")
        val identity = "$thread/$key"
        val previous = store.get("matters", identity)
        val previousSource = previous?.optJSONObject("meta")?.optJSONObject("provenance")
        val phase = incoming.getJSONObject("lifecycle").getString("phase")
        if (previousSource?.optString("kind") == "primary-assistant-reply") {
            if (previousSource.optString("replyId") != incoming.getString("id")) throw IOException("SOURCE_CHANGED")
            if (previousSource.optLong("replyRevision") > incoming.getLong("revision")) return
            if (previousSource.optLong("replyRevision") == incoming.getLong("revision") && previousSource.optString("replyPhase", "completed") != phase)
                throw IOException("SOURCE_CHANGED")
        }
        val known = primaryReplies[identity]
        if (known != null && known.getString("id") != incoming.getString("id")) throw IOException("SOURCE_CHANGED")
        if (known != null && known.getLong("revision") > incoming.getLong("revision")) return
        if (known != null && known.getLong("revision") == incoming.getLong("revision") && known.getJSONObject("lifecycle").optString("phase") != phase)
            throw IOException("SOURCE_CHANGED")
        val reply = if (known != null && known.getLong("revision") == incoming.getLong("revision")) known else copy(incoming)
        if (previousSource?.optString("kind") == "primary-assistant-reply" && previousSource.optLong("replyRevision") == reply.getLong("revision") &&
            phase == "completed" && store.get("primary_snapshots", identity) == null) {
            val source = incoming.getJSONObject("provenance")
            if (source.keys().asSequence().any { canonical(previousSource.opt(it)) != canonical(source.get(it)) } ||
                canonical(previous!!.getJSONObject("baseRequest")) != canonical(incoming.getJSONObject("card").getJSONObject("request")) ||
                known != null && canonical(known) != canonical(incoming)) throw IOException("SOURCE_CHANGED")
            store.put("primary_snapshots", identity, JSONObject().put("reply", copy(reply)).put("observedAt", System.currentTimeMillis()))
        }
        primaryReplies[identity] = reply
        while (primaryReplies.size > 200) primaryReplies.remove(primaryReplies.keys.first())
        if (previousSource?.optString("kind") == "primary-assistant-reply" && previousSource.optLong("replyRevision") >= reply.getLong("revision")) return
        val provenance = copy(reply.getJSONObject("provenance")).put("replyId", reply.getString("id"))
            .put("replyRevision", reply.getLong("revision")).put("replyPhase", phase)
        val lifecycle = reply.getJSONObject("lifecycle")
        if (lifecycle.has("code")) provenance.put("replyCode", lifecycle.getString("code"))
        val sourceId = "primary:" + reply.getString("id") + ":" + reply.getLong("revision")
        if (phase != "completed") {
            if (previous != null) {
                previous.put("presentationSourceId", sourceId)
                previous.getJSONObject("meta").put("provenance", provenance).put("quality", "primary-assistant-reply").put("observedAt", System.currentTimeMillis())
                rebuild(previous, true)
                commit(previous)
            }
            return
        }
        if (!publishingPanelReply) {
            val candidate = JSONObject().put("matterId", identity).put("threadId", thread).put("meta", JSONObject().put("provenance", provenance))
            if (panels.pinCode(candidate) == "PANEL_UPDATE_PENDING") return
        }
        store.put("primary_snapshots", identity, JSONObject().put("reply", copy(reply)).put("observedAt", System.currentTimeMillis()))
        observe(thread, key, reply.getJSONObject("card").getJSONObject("request"), sourceId, provenance)
    }

    @Throws(Exception::class)
    private fun rebuild(matter: JSONObject, renew: Boolean) {
        val request = copy(matter.getJSONObject("baseRequest"))
        val primary = matter.getJSONObject("meta").getJSONObject("provenance").optString("kind") == "primary-assistant-reply"
        val unavailable = primaryUnavailable(matter) || store.get("display_supersessions", matter.getString("matterId")) != null
        val caps = (if (renew || unavailable) JSONObject() else matter.optJSONObject("capabilities")) ?: JSONObject()
        val actions = request.getJSONArray("actions")
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            if (unavailable) { action.remove("actionRef"); continue }
            val id = action.getString("id")
            var cap = caps.optJSONObject(id)
            if (cap == null || action.getString("reply") != cap.optString("reply"))
                cap = JSONObject().put("ref", UUID.randomUUID().toString()).put("kind", "reply").put("reply", action.getString("reply"))
            caps.put(id, cap)
            action.put("actionRef", cap.getString("ref"))
        }
        val sources = store.bindings("matter", matter.getString("matterId"))
        val sourceMeta = JSONObject()
        for (i in 0 until sources.length()) {
            val binding = sources.getJSONObject(i)
            val source = store.get("sources", binding.getString("sourceId")) ?: continue
            val observation = source.optJSONObject("observation") ?: continue
            sourceMeta.put(source.getString("id"), observation)
            if (!primary && !unavailable) {
                MatterProjection.source(request, binding, observation)
                if (PublicSourceAdapter.supports(source.getString("kind"))) addSourceControl(actions, caps, source)
            }
        }
        matter.getJSONObject("meta").put("sources", sourceMeta)
        val rules = store.related("rules", "matter", matter.getString("matterId"))
        val ruleMeta = JSONArray()
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            ruleMeta.put(rule)
            if (!primary && !unavailable) {
                addControl(actions, caps, rule, "toggle", if (rule.optBoolean("paused")) "Resume rule" else "Pause rule")
                if ("price-below" != rule.optString("kind")) addControl(actions, caps, rule, "skip", "Skip today")
            }
        }
        matter.getJSONObject("meta").put("rules", ruleMeta)
        matter.put("capabilities", caps).put("request", request)
        UiCardRequest.validate(request)
    }

    @Throws(Exception::class)
    private fun addSourceControl(actions: JSONArray, caps: JSONObject, source: JSONObject) {
        val id = "refresh-" + source.getString("id")
        for (i in 0 until actions.length()) if (id == actions.getJSONObject(i).getString("id")) return
        if (actions.length() >= 32) return
        var cap = caps.optJSONObject(id)
        if (cap == null || "refresh" != cap.optString("kind")) {
            cap = JSONObject().put("ref", UUID.randomUUID().toString()).put("kind", "refresh").put("sourceId", source.getString("id"))
            caps.put(id, cap)
        }
        val label = if (source.getString("kind") == "weather") "Refresh weather" else "Refresh Figma"
        actions.put(JSONObject().put("id", id).put("label", label).put("reply", label).put("style", "secondary").put("actionRef", cap.getString("ref")))
    }

    @Throws(Exception::class)
    private fun addControl(actions: JSONArray, caps: JSONObject, rule: JSONObject, operation: String, suppliedLabel: String) {
        val label = (if (operation == "toggle") (if (rule.optBoolean("paused")) "Resume " else "Pause ") else "Skip ") + rule.getString("id")
        val id = "rule-" + rule.getString("id") + "-" + operation
        var cap = caps.optJSONObject(id)
        val date = if (operation == "skip") LocalDate.now(ZoneId.of(rule.getString("timeZone"))).toString() else ""
        if (cap == null || operation == "toggle" && cap.optBoolean("paused") != rule.optBoolean("paused") || operation == "skip" && date != cap.optString("date")) {
            cap = JSONObject().put("ref", UUID.randomUUID().toString()).put("kind", operation).put("ruleId", rule.getString("id"))
                .put("paused", rule.optBoolean("paused")).put("date", date)
            caps.put(id, cap)
        }
        actions.put(JSONObject().put("id", id).put("label", label).put("reply", label).put("style", "secondary").put("actionRef", cap.getString("ref")))
    }

    @Throws(Exception::class)
    private fun commit(matter: JSONObject) {
        matter.put("revision", matter.getLong("revision") + 1).put("retainedAt", System.currentTimeMillis())
        store.put("matters", matter.getString("matterId"), matter)
        val event = JSONObject().put("method", "matter/snapshot").put("matterId", matter.getString("matterId")).put("snapshot", snapshot(matter))
        val deferred = deferredEvents
        if (deferred != null) deferred.add(event) else publish(event)
    }

    @Throws(Exception::class)
    private fun publish(event: JSONObject) {
        event.put("schema", "taku.matter-events/1").put("runtimeEpoch", epoch).put("seq", ++seq)
        for (listener in ArrayList(listeners)) listener.event(copy(event))
    }

    @Synchronized
    @Throws(Exception::class)
    fun watch(lease: String, ids: JSONArray, listener: Listener) {
        unlocked()
        val requested = HashSet<String>()
        for (i in 0 until ids.length()) { require(ids.getString(i)); requested.add(ids.getString(i)) }
        leases[lease] = requested
        listeners.add(listener)
        // Registration and complete baselines share the same commit lock with no read/watch gap.
        for (id in requested) listener.event(JSONObject().put("schema", "taku.matter-events/1").put("runtimeEpoch", epoch)
            .put("seq", ++seq).put("method", "matter/snapshot").put("matterId", id).put("snapshot", snapshot(require(id))))
        reconcile()
    }

    @Synchronized
    fun unwatch(lease: String, listener: Listener) {
        leases.remove(lease)
        listeners.remove(listener)
        reconcile()
        try { reclaim(null, null, 0, 0) } catch (unavailable: Exception) { /* A later creation retries bounded reclamation. */ }
    }

    @Synchronized
    @Throws(Exception::class)
    fun heartbeat(): JSONObject {
        unlocked()
        return JSONObject().put("schema", "taku.matter-events/1").put("runtimeEpoch", epoch).put("seq", ++seq).put("method", "matter/heartbeat")
    }

    @Throws(Exception::class)
    fun nativeAction(input: JSONObject): JSONObject {
        if ("dispatch" == input.optString("action")) {
            val allowed = java.util.Set.of("threadId", "key", "action", "command")
            val keys = input.keys()
            while (keys.hasNext()) if (!allowed.contains(keys.next())) throw IOException("UNSUPPORTED_PARAMETER")
            val command = copy(input.getJSONObject("command"))
            command.put("matterId", input.getString("threadId") + "/" + input.getString("key"))
            AgentGatewayProtocol.request("dispatchMatter", command.toString())
            return dispatch(command)
        }
        synchronized(this) { return nativeActionLocked(input) }
    }

    @Throws(Exception::class)
    private fun nativeActionLocked(input: JSONObject): JSONObject {
        unlocked()
        val thread = input.getString("threadId")
        val key = input.getString("key")
        val id = "$thread/$key"
        AgentGatewayProtocol.matterIdentity(id)
        val action = input.getString("action")
        val allowedFields = when (action) {
            "present" -> java.util.Set.of("threadId", "key", "action", "request", "source")
            "attachSource" -> java.util.Set.of("threadId", "key", "action", "source")
            "putRule" -> java.util.Set.of("threadId", "key", "action", "rule")
            "dispatch" -> java.util.Set.of("threadId", "key", "action", "command")
            else -> java.util.Set.of("threadId", "key", "action")
        }
        val keys = input.keys()
        while (keys.hasNext()) if (!allowedFields.contains(keys.next())) throw IOException("UNSUPPORTED_PARAMETER")
        if (action == "present") {
            val request = input.getJSONObject("request")
            UiCardRequest.validate(request)
            val sourceId = "native-tool:" + UUID.nameUUIDFromBytes(canonical(request).toByteArray(StandardCharsets.UTF_8))
            observe(thread, key, request, sourceId, JSONObject().put("kind", "derived-assistant-reply").put("threadId", thread).put("tool", "matter"))
            if (input.has("source")) nativeAction(JSONObject().put("threadId", thread).put("key", key).put("action", "attachSource").put("source", input.getJSONObject("source")))
            return read(id)
        }
        val matter = require(id)
        when (action) {
            "read" -> return read(id)
            "attachSource" -> {
                val spec = input.getJSONObject("source")
                val kind = spec.getString("kind")
                if (spec.length() != 3) throw IOException("UNSUPPORTED_SOURCE_PARAMETER")
                if (!java.util.Set.of("memory", "task-receipt", "weather", "feed").contains(kind)) throw IOException("SOURCE_UNAVAILABLE")
                var resource = AgentGatewayProtocol.text(spec, "resourceId", 160)
                val block = AgentGatewayProtocol.text(spec, "blockId", 64)
                if (PublicSourceAdapter.supports(kind)) resource = PublicSourceAdapter.normalize(kind, resource)
                val target = findBlock(matter.getJSONObject("baseRequest"), block)
                if (!PublicSourceAdapter.supports(kind) && "text" != target.getString("kind")) throw IOException("SOURCE_BLOCK_REQUIRES_TEXT")
                if (kind == "weather" && matter.getJSONObject("baseRequest").getJSONArray("blocks").length() > 6) throw IOException("WEATHER_REQUIRES_THREE_BLOCKS")
                val identity = adapters.identity(kind)
                val sourceId = if (kind == "task-receipt") "task-$resource" else UUID.nameUUIDFromBytes((kind + "\n" + resource).toByteArray(StandardCharsets.UTF_8)).toString()
                var source = store.get("sources", sourceId)
                if (source != null && kind == "task-receipt" && thread != source.getString("threadId")) throw IOException("TASK_THREAD_MISMATCH")
                if (source == null) reclaim(id, sourceId, 0, 1)
                if (source == null) source = JSONObject().put("id", sourceId).put("kind", kind).put("resourceId", resource).put("threadId", thread).put("identity", identity)
                else if (canonical(identity) != canonical(source.optJSONObject("identity"))) {
                    source.put("identity", identity).remove("observation")
                    generations[sourceId] = generations.getOrDefault(sourceId, 0L) + 1
                }
                store.put("sources", sourceId, source)
                store.bind(id, sourceId, block)
                refresh(sourceId)
                reconcile()
                return JSONObject().put("sourceId", sourceId).put("state", "reading")
            }
            "putRule" -> return putRule(matter, input.getJSONObject("rule"))
            else -> throw IOException("UNSUPPORTED_MATTER_ACTION")
        }
    }

    @Throws(Exception::class)
    private fun putRule(matter: JSONObject, supplied: JSONObject): JSONObject {
        val rule = copy(supplied)
        val shortId = rule.getString("id")
        if (!Pattern.matches("[a-z0-9-]{1,32}", shortId)) throw IOException("INVALID_RULE_ID")
        val id = matter.getString("matterId") + "/" + shortId
        val prior = store.get("rules", id)
        if (prior == null && store.count("rules") >= MatterStore.MAX_RULES) throw IOException("RULE_LIMIT")
        val kind = rule.getString("kind")
        if (!java.util.Set.of("daily", "weekdays", "price-below").contains(kind)) throw IOException("UNSUPPORTED_RULE")
        AgentGatewayProtocol.text(rule, "reply", 8000)
        val allowed = if (kind == "price-below") java.util.Set.of("id", "kind", "reply", "sourceId", "threshold") else java.util.Set.of("id", "kind", "reply", "slotId", "localTime", "timeZone")
        val keys = rule.keys()
        while (keys.hasNext()) if (!allowed.contains(keys.next())) throw IOException("UNSUPPORTED_RULE_PARAMETER")
        if (prior == null && store.related("rules", "matter", matter.getString("matterId")).length() >= 12) throw IOException("RULE_ACTION_LIMIT")
        if (prior != null && prior.optString("kind") != kind) throw IOException("RULE_KIND_CONFLICT")
        if (prior != null && kind != "price-below" && prior.getString("slotId") != rule.getString("slotId")) throw IOException("RULE_SLOT_CONFLICT")
        if (prior != null) {
            var same = true
            for (field in allowed) if (canonical(rule.opt(field)) != canonical(prior.opt(field))) same = false
            if (same) return JSONObject().put("rule", prior).put("snapshot", snapshot(matter))
        }
        rule.put("id", shortId).put("storageId", id).put("matterId", matter.getString("matterId"))
            .put("revision", if (prior == null) 1L else prior.optLong("revision") + 1).put("paused", prior != null && prior.optBoolean("paused"))
        rule.put("createdAt", if (prior == null) System.currentTimeMillis() else prior.optLong("createdAt"))
        if (prior != null) for (field in arrayOf("lastCheckedAt", "lastOccurrence", "downtimeGap")) if (prior.has(field)) rule.put(field, prior.get(field))
        if (kind == "price-below") {
            val threshold = rule.getJSONObject("threshold")
            money(threshold)
            val sourceId = rule.getString("sourceId")
            val source = store.get("sources", sourceId)
            var bound = false
            val bindings = store.bindings("matter", matter.getString("matterId"))
            for (i in 0 until bindings.length()) if (sourceId == bindings.getJSONObject(i).getString("sourceId")) bound = true
            if (source == null || !bound) throw IOException("SOURCE_UNAVAILABLE")
            val observation = source.optJSONObject("observation")
            rule.put("availability", if (observation != null && observation.optJSONObject("capabilities") != null && observation.getJSONObject("capabilities").optBoolean("typedPrice")) "available" else "unavailable:typed-price-source-required")
            rule.put("armed", prior == null || prior.optBoolean("armed", true)).put("episode", if (prior == null) 0L else prior.optLong("episode"))
        } else {
            LocalTime.parse(rule.getString("localTime"))
            ZoneId.of(rule.getString("timeZone"))
            if (!Pattern.matches("[0-2][0-9]:[0-5][0-9]", rule.getString("localTime"))) throw IOException("INVALID_SCHEDULE")
            if (!Pattern.matches("[a-z0-9-]{1,32}", rule.getString("slotId"))) throw IOException("INVALID_SLOT_ID")
            rule.put("lateWindowMs", 1800_000).put("availability", "android-job-may-be-delayed")
            if (prior != null) rule.put("skipDate", prior.optString("skipDate"))
        }
        transaction { store.put("rules", id, rule); rebuild(matter, false); commit(matter); null }
        reconcile()
        MatterJob.schedule(context, nextDue(), pendingPanelUpdates())
        return JSONObject().put("rule", rule).put("snapshot", snapshot(matter))
    }

    @Throws(Exception::class)
    private fun values(request: JSONObject, supplied: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = supplied.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val block = findBlock(request, id)
            val config = block.getJSONObject("config")
            val value = supplied.get(id)
            if (block.getString("kind") == "choices") {
                if (value !is JSONArray) throw IOException("INVALID_SELECTION")
                val items = block.getJSONArray("items")
                val labels = JSONArray()
                val seen = HashSet<String>()
                if (!config.optBoolean("multi") && value.length() > 1) throw IOException("INVALID_SELECTION")
                for (i in 0 until value.length()) {
                    val item = value.getString(i)
                    var found = false
                    if (!seen.add(item)) throw IOException("INVALID_SELECTION")
                    for (j in 0 until items.length()) if (item == items.getJSONObject(j).getString("id")) {
                        labels.put(items.getJSONObject(j).getString("label")); found = true; break
                    }
                    if (!found) throw IOException("STALE_SELECTION")
                }
                result.put(id, JSONObject().put("selectedItemIds", value).put("selectedLabels", labels))
            } else if (block.getString("kind") == "input") {
                val editor = config.optString("editor", "text")
                if (if (editor == "toggle") value !is Boolean else if (java.util.Set.of("number", "range").contains(editor)) value !is Number else value !is String)
                    throw IOException("INVALID_DRAFT")
                if (value is Number) {
                    val number = value.toDouble()
                    if (!java.lang.Double.isFinite(number) || number < config.optDouble("min", -Double.MAX_VALUE) || number > config.optDouble("max", Double.MAX_VALUE)) throw IOException("INVALID_DRAFT")
                    if (config.has("step") && config.getDouble("step") > 0) {
                        val steps = (number - config.optDouble("min", 0.0)) / config.getDouble("step")
                        if (Math.abs(steps - Math.rint(steps)) > 0.0000001) throw IOException("INVALID_DRAFT")
                    }
                }
                if (value is String && value.length > 4000) throw IOException("INVALID_DRAFT")
                if (editor == "time") {
                    val time = value as String
                    try { if (Pattern.matches("[0-2][0-9]:[0-5][0-9]", time)) LocalTime.parse(time) else OffsetDateTime.parse(time) }
                    catch (invalid: DateTimeParseException) { throw IOException("INVALID_DRAFT") }
                }
                result.put(id, JSONObject().put("draft", value))
            } else throw IOException("READ_ONLY_FIELD")
        }
        return result
    }

    private class Dispatch {
        val receipt: JSONObject?
        val publication: JSONObject?
        val target: Submit?
        constructor(receipt: JSONObject) { this.receipt = receipt; publication = null; target = null }
        constructor(publication: JSONObject, target: Submit?) { receipt = null; this.publication = publication; this.target = target }
    }

    @Throws(Exception::class)
    fun dispatch(input: JSONObject): JSONObject {
        val prepared: Dispatch
        synchronized(this) { prepared = prepareDispatch(input) }
        if (prepared.receipt != null) return prepared.receipt
        // The persisted deliveryUnknown command is the exclusive publication reservation.
        val receipt = prepared.target!!.run(prepared.publication!!)
        receipt.put("effect", "conversational-followup")
        synchronized(this) { return finishDispatch(input.getString("requestId"), receipt) }
    }

    @Throws(Exception::class)
    private fun prepareDispatch(input: JSONObject): Dispatch {
        unlocked()
        val id = input.getString("matterId")
        val requestId = input.getString("requestId")
        AgentGatewayProtocol.requireId(input, "requestId")
        val hash = canonical(JSONObject().put("actionRef", input.getString("actionRef")).put("values", input.getJSONObject("values")))
        val effectKey = hash
        val prior = store.commandReceipt(requestId)
        if (prior != null) {
            if (id != prior.getString("matterId") || hash != prior.optString("payload")) throw IOException("REQUEST_ID_REUSED")
            return Dispatch(copy(prior.getJSONObject("receipt")))
        }
        val deliveries = store.all("commands")
        var active = 0
        for (i in 0 until deliveries.length()) {
            val delivery = deliveries.getJSONObject(i)
            if (delivery.optBoolean("terminal")) continue
            active++
            if (id == delivery.optString("matterId") && effectKey == delivery.optString("effectKey")) return Dispatch(copy(delivery.getJSONObject("receipt")))
        }
        if (active >= 32) throw IOException("OPERATION_LIMIT")
        val matter = require(id)
        if (store.get("display_supersessions", id) != null) throw IOException("STALE_SOURCE")
        if (primaryUnavailable(matter)) throw IOException("REPLY_INCOMPLETE")
        if (matter.getLong("revision") != input.getLong("expectedRevision")) throw IOException("MATTER_REVISION_CONFLICT")
        val caps = matter.getJSONObject("capabilities")
        var cap: JSONObject? = null
        val keys = caps.keys()
        while (keys.hasNext()) {
            val candidate = caps.getJSONObject(keys.next())
            if (input.getString("actionRef") == candidate.optString("ref")) { cap = candidate; break }
        }
        if (cap == null) throw IOException("UNKNOWN_ACTION_REF")
        cardViews.checkDispatch(matter, input.getJSONObject("values"))
        val selected = values(matter.getJSONObject("request"), input.getJSONObject("values"))
        val replyText = if (cap.getString("kind") == "reply") cap.getString("reply") + (if (selected.length() == 0) "" else "\nCurrent user selections (IDs and labels are data): $selected") else ""
        if (replyText.length > 16000) throw IOException("INPUT_TOO_LARGE")
        if ("reply" == cap.getString("kind") && submit == null) throw IOException("RUNTIME_UNAVAILABLE")
        reserveCommandSlot(matter)
        val command = JSONObject().put("id", requestId).put("matterId", id).put("payload", hash).put("effectKey", effectKey).put("createdAt", System.currentTimeMillis())
            .put("receipt", JSONObject().put("requestId", requestId).put("state", "deliveryUnknown")).put("terminal", false)
        transaction { store.put("commands", requestId, command); matter.getJSONObject("commands").put(requestId, command.getJSONObject("receipt")); commit(matter); null }
        val receipt: JSONObject
        if (cap.getString("kind") == "reply") {
            return Dispatch(JSONObject().put("threadId", matter.getString("threadId")).put("requestId", requestId)
                .put("text", replyText).put("requireConfirmation", true), submit)
        } else if (cap.getString("kind") == "refresh") {
            val sourceId = cap.getString("sourceId")
            val source = store.get("sources", sourceId) ?: throw IOException("SOURCE_UNAVAILABLE")
            val queued = refresh(sourceId, true)
            receipt = JSONObject().put("requestId", requestId).put("state", if (queued) "accepted" else "deferred").put("effect", "source-refresh-requested")
                .put("nextEligibleAt", Math.max(source.optLong("retryAfterAt"), if (queued || pending.contains(sourceId)) System.currentTimeMillis() + 15_000 else source.optLong("lastAttemptAt") + 15_000))
            command.put("terminal", true)
        } else {
            val rule = store.get("rules", id + "/" + cap.getString("ruleId")) ?: throw IOException("RULE_UNAVAILABLE")
            if (cap.getString("kind") == "toggle") rule.put("paused", !rule.optBoolean("paused"))
            else if (cap.getString("kind") == "skip") {
                if (LocalDate.now(ZoneId.of(rule.getString("timeZone"))).toString() != cap.optString("date")) throw IOException("ACTION_EXPIRED")
                rule.put("skipDate", LocalDate.now(ZoneId.of(rule.getString("timeZone"))).toString())
            } else throw IOException("UNKNOWN_ACTION_REF")
            store.put("rules", rule.getString("storageId"), rule)
            if (cap.getString("kind") == "skip") {
                val occurrenceId = rule.getString("storageId") + "/" + rule.getString("skipDate") + "/" + rule.getString("slotId")
                val occurrence = store.get("occurrences", occurrenceId)
                if (occurrence != null && "claimed" == occurrence.optString("state")) {
                    occurrence.put("state", "skipped").put("reason", "user-skipped").put("terminal", true)
                    store.put("occurrences", occurrenceId, occurrence)
                    rule.put("lastOccurrence", JSONObject().put("id", occurrenceId).put("state", "skipped").put("reason", "user-skipped"))
                    store.put("rules", rule.getString("storageId"), rule)
                }
            }
            receipt = JSONObject().put("requestId", requestId).put("state", "confirmed").put("effect", "local-rule-change")
            command.put("terminal", true)
        }
        return Dispatch(finishDispatch(requestId, receipt))
    }

    @Throws(Exception::class)
    private fun finishDispatch(requestId: String, suppliedReceipt: JSONObject): JSONObject {
        // Publication can overlap observations, source updates and other commands. Never commit a stale matter.
        val command = store.get("commands", requestId) ?: throw IOException("COMMAND_UNAVAILABLE")
        val matter = require(command.getString("matterId"))
        val current = command.getJSONObject("receipt")
        val receipt = if (current.has("taskState") && current.optString("taskId") == suppliedReceipt.optString("taskId")) current else suppliedReceipt
        if (java.util.Set.of("notSent", "failed", "cancelled", "taskCompleted").contains(receipt.optString("state")) ||
            java.util.Set.of("local-rule-change", "source-refresh-requested").contains(receipt.optString("effect"))) command.put("terminal", true)
        command.put("receipt", receipt)
        store.put("commands", requestId, command)
        matter.getJSONObject("commands").put(requestId, receipt)
        rebuild(matter, false)
        commit(matter)
        if (Pattern.matches("[0-9]{1,20}", receipt.optString("taskId"))) attachReceipt(matter, receipt.getString("taskId"), requestId)
        reconcile()
        MatterJob.schedule(context, nextDue(), pendingPanelUpdates())
        return copy(receipt)
    }

    @Throws(Exception::class)
    private fun reserveCommandSlot(matter: JSONObject) {
        val history = store.all("commands")
        if (history.length() >= MatterStore.MAX_COMMAND_HISTORY) {
            val terminal = ArrayList<JSONObject>()
            for (i in 0 until history.length()) if (history.getJSONObject(i).optBoolean("terminal")) terminal.add(history.getJSONObject(i))
            terminal.sortWith(Comparator.comparingLong { it.optLong("createdAt") })
            var remaining = history.length()
            for (command in terminal) {
                if (remaining < MatterStore.MAX_COMMAND_HISTORY) break
                archiveCommand(command, matter)
                remaining--
            }
            if (remaining >= MatterStore.MAX_COMMAND_HISTORY) throw IOException("COMMAND_HISTORY_FULL")
        }
        val commands = store.related("commands", "matter", matter.getString("matterId"))
        if (commands.length() < MatterStore.MAX_COMMANDS) return
        var oldest: JSONObject? = null
        for (i in 0 until commands.length()) {
            val command = commands.getJSONObject(i)
            if (command.optBoolean("terminal") && (oldest == null || command.optLong("createdAt") < oldest.optLong("createdAt"))) oldest = command
        }
        if (oldest == null) throw IOException("COMMAND_HISTORY_FULL")
        archiveCommand(oldest, matter)
    }

    @Throws(Exception::class)
    private fun archiveCommand(command: JSONObject, currentMatter: JSONObject) {
        store.archiveCommand(command)
        val matter = if (currentMatter.getString("matterId") == command.getString("matterId")) currentMatter else store.get("matters", command.getString("matterId"))
        if (matter != null) {
            matter.getJSONObject("commands").remove(command.getString("id"))
            if (matter !== currentMatter) commit(matter)
        }
        val task = command.getJSONObject("receipt").optString("taskId")
        val sourceId = "task-$task"
        if (Pattern.matches("[0-9]{1,20}", task) && store.bindings("source", sourceId).length() == 0 && !pending.contains(sourceId) && !ownedSources.contains(sourceId) && !watches.containsKey(sourceId)) {
            val source = store.get("sources", sourceId)
            val observation = source?.optJSONObject("observation")
            if (observation != null && observation.optBoolean("terminal")) store.removeSource(sourceId)
        }
    }

    @Throws(Exception::class)
    private fun attachReceipt(matter: JSONObject, task: String, command: String) {
        val sourceId = "task-$task"
        var resource = store.get("sources", sourceId)
        if (resource == null) reclaim(matter.getString("matterId"), sourceId, 0, 1)
        if (resource == null) resource = JSONObject().put("id", sourceId).put("kind", "task-receipt").put("resourceId", task).put("threadId", matter.getString("threadId"))
        resource.put("commandId", command).put("matterId", matter.getString("matterId"))
        store.put("sources", sourceId, resource)
        refresh(sourceId)
    }

    @Synchronized
    @Throws(Exception::class)
    fun registerAudio(matterId: String, observation: JSONObject) {
        val matter = require(matterId)
        val resource = observation.getString("resourceId")
        AgentGatewayProtocol.requireId(JSONObject().put("resourceId", resource), "resourceId")
        if (matterId != observation.getString("matterId") || matter.getString("threadId") != observation.getString("threadId")) throw IOException("AUDIO_MATTER_MISMATCH")
        val sourceId = "audio-$resource"
        var source = store.get("sources", sourceId)
        if (source == null) reclaim(matterId, sourceId, 0, 1)
        if (source == null) source = JSONObject().put("id", sourceId).put("kind", "audio-session").put("resourceId", resource).put("threadId", matter.getString("threadId")).put("matterId", matterId).put("identity", adapters.identity("audio-session"))
        val attached = source
        transaction { store.bind(matterId, sourceId, "@audio-$resource"); applySource(attached, observation); null }
        reconcile()
    }

    @Synchronized
    private fun refresh(sourceId: String): Boolean = refresh(sourceId, false)

    @Synchronized
    private fun refresh(sourceId: String, manual: Boolean): Boolean {
        try {
            val source = store.get("sources", sourceId) ?: return false
            if (PublicSourceAdapter.supports(source.getString("kind"))) {
                val now = System.currentTimeMillis()
                if (if (manual) now < Math.max(source.optLong("retryAfterAt"), source.optLong("lastAttemptAt") + 15_000) else now < source.optLong("nextEligibleAt")) return false
            }
        } catch (unavailable: Exception) { return false }
        if (!pending.add(sourceId)) { dirty.add(sourceId); return false }
        io.execute {
            val source: JSONObject?
            val generation: Long
            synchronized(this@MatterRuntime) {
                try {
                    source = store.get("sources", sourceId)
                    generation = generations.getOrDefault(sourceId, 0L)
                    if (source != null && PublicSourceAdapter.supports(source.getString("kind"))) {
                        source.put("lastAttemptAt", System.currentTimeMillis()).put("readAttempts", source.optLong("readAttempts") + 1)
                        store.put("sources", sourceId, source)
                    }
                } catch (failure: Exception) { pending.remove(sourceId); return@execute }
            }
            if (source == null) { synchronized(this@MatterRuntime) { pending.remove(sourceId) }; return@execute }
            var observation: JSONObject
            var retryAfter = 0L
            try { observation = adapters.resolve(source.getString("kind")).read(source) }
            catch (failure: Exception) {
                observation = JSONObject()
                if (failure is PublicSourceAdapter.Failure) retryAfter = failure.retryAfterAt
                try { observation.put("state", if (failure is SecurityException) "denied" else "unavailable").put("code", failure.message).put("observedAt", System.currentTimeMillis()) } catch (ignored: Exception) { }
            }
            synchronized(this@MatterRuntime) {
                pending.remove(sourceId)
                try {
                    val currentSource = store.get("sources", sourceId)
                    if (currentSource != null && generation == generations.getOrDefault(sourceId, 0L)) {
                        if (PublicSourceAdapter.supports(currentSource.getString("kind"))) {
                            val available = "available" == observation.optString("state")
                            val failures = if (available) 0 else Math.min(5, currentSource.optInt("failures") + 1)
                            val backoff = Math.min(6 * 60 * 60_000L, 15 * 60_000L * (1L shl Math.max(0, failures - 1)))
                            val due = if (available) observation.getLong("validUntil") else System.currentTimeMillis() + backoff + ThreadLocalRandom.current().nextLong(30_001)
                            currentSource.put("failures", failures).put("retryAfterAt", retryAfter).put("nextEligibleAt", Math.max(due, retryAfter))
                            observation.put("nextEligibleAt", currentSource.getLong("nextEligibleAt")).put("readAttempts", currentSource.getLong("readAttempts"))
                        }
                        applySource(currentSource, observation)
                    }
                } catch (failure: Exception) { /* Last committed snapshot remains readable. */ }
                if (dirty.remove(sourceId)) refresh(sourceId)
            }
        }
        return true
    }

    @Throws(Exception::class)
    private fun applySource(source: JSONObject, observation: JSONObject) {
        if (observation.optString("state") == "available" && source.has("identity") && canonical(source.getJSONObject("identity")) != canonical(observation.optJSONObject("provenance"))) {
            val keys = observation.keys()
            while (keys.hasNext()) { keys.next(); keys.remove() }
            observation.put("state", "denied").put("code", "SOURCE_IDENTITY_CHANGED").put("observedAt", System.currentTimeMillis())
        }
        val old = source.optJSONObject("observation")
        if (old != null && "unavailable" == observation.optString("state")) {
            val keys = old.keys()
            while (keys.hasNext()) { val field = keys.next(); if (!observation.has(field)) observation.put(field, old.get(field)) }
            observation.put("lastSuccessfulAt", old.optLong("lastSuccessfulAt", old.optLong("observedAt")))
        }
        if (old != null && observation.optString("state") == "available" && observation.optLong("sourceVersion") > 0 && old.optLong("sourceVersion") > observation.optLong("sourceVersion")) return
        val a = copy(observation)
        val b = if (old == null) JSONObject() else copy(old)
        a.remove("observedAt")
        b.remove("observedAt")
        if (canonical(a) == canonical(b)) return
        transaction {
            source.put("observation", observation)
            store.put("sources", source.getString("id"), source)
            val bindings = store.bindings("source", source.getString("id"))
            for (i in 0 until bindings.length()) {
                val matter = require(bindings.getJSONObject(i).getString("matterId"))
                rebuild(matter, false)
                commit(matter)
            }
            if (source.has("commandId")) {
                val command = store.get("commands", source.getString("commandId"))
                if (command != null) {
                    val receipt = command.getJSONObject("receipt")
                    receipt.put("taskState", observation.optString("taskState", "unknown"))
                        .put("state", observation.optString("deliveryState", "deliveryUnknown")).put("effect", "conversational-followup")
                    command.put("terminal", observation.optBoolean("terminal"))
                    store.put("commands", command.getString("id"), command)
                    val matter = require(command.getString("matterId"))
                    matter.getJSONObject("commands").put(command.getString("id"), receipt)
                    commit(matter)
                }
            }
            evaluatePrices(source)
            null
        }
        reconcile()
    }

    @Throws(Exception::class)
    private fun evaluatePrices(source: JSONObject) {
        val observation = source.getJSONObject("observation")
        // No supported adapter supplies typed landed-price evidence. Assistant/memory text cannot supply prices.
        if ("available" != observation.optString("state") || "verified-landed-price" != observation.optString("quality") ||
            observation.optLong("expiresAt") <= System.currentTimeMillis() || !observation.has("price")) return
        val rules = store.all("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optBoolean("paused") || "price-below" != rule.optString("kind") || source.getString("id") != rule.optString("sourceId")) continue
            val threshold = rule.getJSONObject("threshold")
            val price = observation.getJSONObject("price")
            if (threshold.getString("currency") != price.optString("currency") || threshold.getInt("scale") != price.optInt("scale", -1)) continue
            val comparison = money(price).compareTo(money(threshold))
            if (comparison >= 0) rule.put("armed", true)
            else if (rule.optBoolean("armed", true)) {
                val episode = rule.optLong("episode") + 1
                rule.put("episode", episode).put("armed", false)
                claim(rule, "price-$episode", System.currentTimeMillis(), false)
            }
            store.put("rules", rule.getString("storageId"), rule)
        }
    }

    @Throws(Exception::class)
    private fun claim(rule: JSONObject, slot: String, expected: Long, missed: Boolean) {
        val occurrenceId = rule.getString("storageId") + "/" + slot
        if (store.get("occurrences", occurrenceId) != null) return
        if (store.count("occurrences") >= 2048) {
            store.prune()
            if (store.count("occurrences") >= 2048) throw IOException("OCCURRENCE_HISTORY_FULL")
        }
        val requestId = UUID.nameUUIDFromBytes(occurrenceId.toByteArray(StandardCharsets.UTF_8)).toString()
        val occurrence = JSONObject().put("id", occurrenceId).put("ruleId", rule.getString("storageId"))
            .put("matterId", rule.getString("matterId")).put("requestId", requestId).put("payload", copy(rule)).put("createdAt", System.currentTimeMillis())
            .put("expectedAt", expected).put("eligibleAt", System.currentTimeMillis()).put("state", if (missed) "missed" else "claimed").put("terminal", missed)
        if (missed) occurrence.put("reason", "outside-late-window")
        store.put("occurrences", occurrenceId, occurrence)
        rule.put("lastOccurrence", JSONObject().put("id", occurrenceId).put("expectedAt", expected).put("eligibleAt", System.currentTimeMillis()).put("state", if (missed) "missed" else "claimed"))
        store.put("rules", rule.getString("storageId"), rule)
        val matter = require(rule.getString("matterId"))
        rebuild(matter, false)
        commit(matter)
        // Delivery begins after the surrounding observation/rule transaction commits.
        if (!missed) io.execute { deliverOccurrence(occurrenceId) }
    }

    private fun deliverOccurrence(id: String) {
        try {
            val prepared: Dispatch
            synchronized(this) {
                val occurrence = store.get("occurrences", id)
                if (occurrence == null || "claimed" != occurrence.optString("state")) return
                credentialUnlocked()
                val rule = occurrence.getJSONObject("payload")
                val matter = require(rule.getString("matterId"))
                val live = store.get("rules", occurrence.getString("ruleId"))
                if (live == null || live.optBoolean("paused")) {
                    occurrence.put("state", "paused-before-delivery").put("terminal", true)
                    store.put("occurrences", id, occurrence)
                    recordOccurrence(occurrence)
                    return
                }
                if ("price-below" != rule.getString("kind") && System.currentTimeMillis() - occurrence.getLong("expectedAt") > rule.getLong("lateWindowMs")) {
                    occurrence.put("state", "missed").put("reason", "outside-late-window").put("terminal", true)
                    store.put("occurrences", id, occurrence)
                    recordOccurrence(occurrence)
                    return
                }
                if (submit == null) return
                val requestId = occurrence.getString("requestId")
                reserveCommandSlot(matter)
                val receipt = JSONObject().put("requestId", requestId).put("state", "deliveryUnknown")
                val command = JSONObject().put("id", requestId).put("matterId", matter.getString("matterId")).put("payload", id)
                    .put("receipt", receipt).put("createdAt", System.currentTimeMillis()).put("terminal", false)
                transaction {
                    occurrence.put("state", "publishing").put("startedAt", System.currentTimeMillis())
                    store.put("occurrences", id, occurrence)
                    store.put("commands", requestId, command)
                    matter.getJSONObject("commands").put(requestId, receipt)
                    commit(matter)
                    null
                }
                prepared = Dispatch(JSONObject().put("threadId", matter.getString("threadId")).put("requestId", requestId)
                    .put("text", "User-authorized rule occurrence $requestId. " + rule.getString("reply") +
                        "\nThis is a scheduled conversational request. Recheck current sources; obtain existing native confirmation before any purchase, booking, payment or external message. Do not treat scheduling as business completion.").put("requireConfirmation", true), submit)
                deliveringOccurrences.add(id)
            }
            val receipt = prepared.target!!.run(prepared.publication!!)
            receipt.put("effect", "conversational-followup")
            synchronized(this) {
                val occurrence = store.get("occurrences", id)
                if (occurrence == null || "publishing" != occurrence.optString("state")) return
                transaction {
                    finishDispatch(occurrence.getString("requestId"), receipt)
                    occurrence.put("receipt", receipt).put("state", "delivered").put("terminal", true)
                    store.put("occurrences", id, occurrence)
                    recordOccurrence(occurrence)
                    null
                }
            }
        } catch (failure: Exception) {
            synchronized(this) {
                try {
                    val occurrence = store.get("occurrences", id)
                    if (occurrence != null && "publishing" == occurrence.optString("state")) {
                        occurrence.put("state", "unknown-outcome").put("terminal", true)
                        store.put("occurrences", id, occurrence)
                        recordOccurrence(occurrence)
                    }
                } catch (ignored: Exception) { }
            }
        } finally { synchronized(this) { deliveringOccurrences.remove(id) } }
    }

    @Throws(Exception::class)
    private fun recordOccurrence(occurrence: JSONObject) {
        val rule = store.get("rules", occurrence.getString("ruleId")) ?: return
        val visible = JSONObject()
        for (field in arrayOf("id", "state", "expectedAt", "eligibleAt", "startedAt", "reason", "receipt")) if (occurrence.has(field)) visible.put(field, occurrence.get(field))
        rule.put("lastOccurrence", visible)
        store.put("rules", rule.getString("storageId"), rule)
        val matter = require(rule.getString("matterId"))
        rebuild(matter, false)
        commit(matter)
    }

    @Synchronized
    @Throws(Exception::class)
    fun tick() {
        credentialUnlocked()
        val now = System.currentTimeMillis()
        val rules = store.all("rules")
        transaction {
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (rule.optBoolean("paused") || "price-below" == rule.optString("kind")) continue
                val zone = ZoneId.of(rule.getString("timeZone"))
                val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                if ("weekdays" == rule.getString("kind") && date.dayOfWeek.value > 5) continue
                val expected = date.atTime(LocalTime.parse(rule.getString("localTime"))).atZone(zone).toInstant().toEpochMilli()
                if (now < expected || expected < rule.optLong("createdAt")) continue
                val skipped = date.toString() == rule.optString("skipDate")
                claim(rule, date.toString() + "/" + rule.getString("slotId"), expected, skipped || now - expected > rule.getLong("lateWindowMs"))
                if (skipped) {
                    val occurrence = store.get("occurrences", rule.getString("storageId") + "/" + date + "/" + rule.getString("slotId"))
                    if (occurrence != null && "missed" == occurrence.optString("state")) {
                        occurrence.put("state", "skipped").put("reason", "user-skipped")
                        store.put("occurrences", occurrence.getString("id"), occurrence)
                        recordOccurrence(occurrence)
                    }
                }
            }
            store.prune()
            null
        }
        reconcile()
        for (source in ArrayList(watches.keys)) refresh(source)
        MatterJob.schedule(context, nextDue(), pendingPanelUpdates())
    }

    @Throws(Exception::class)
    private fun nextDue(): Long {
        var next = System.currentTimeMillis() + 86400_000
        var persistent = false
        val rules = store.all("rules")
        for (i in 0 until rules.length()) {
            val rule = rules.getJSONObject(i)
            if (rule.optBoolean("paused")) continue
            if ("price-below" == rule.optString("kind")) {
                if ("available" == rule.optString("availability")) persistent = true
                continue
            }
            persistent = true
            val zone = ZoneId.of(rule.getString("timeZone"))
            val today = LocalDate.now(zone)
            for (d in 0 until 8) {
                val day = today.plusDays(d.toLong())
                if (rule.getString("kind") == "weekdays" && day.dayOfWeek.value > 5) continue
                val due = day.atTime(LocalTime.parse(rule.getString("localTime"))).atZone(zone).toInstant().toEpochMilli()
                if (due > System.currentTimeMillis() && store.get("occurrences", rule.getString("storageId") + "/" + day + "/" + rule.getString("slotId")) == null) {
                    next = Math.min(next, due)
                    break
                }
            }
        }
        val sources = store.all("sources")
        for (i in 0 until sources.length()) {
            val source = sources.getJSONObject(i)
            val observation = source.optJSONObject("observation")
            if (source.getString("kind") == "task-receipt" && (observation == null || !observation.optBoolean("terminal"))) persistent = true
        }
        return if (persistent) next else -1L
    }

    @Throws(Exception::class)
    private fun deliveryReceipt(requestId: String, thread: String): JSONObject? {
        val file = File(File(context.filesDir, "agent-ui-receipts"), "$requestId.json")
        if (!file.isFile) return null
        val raw = SourceAdapters.readObject(file)
        if (requestId != raw.optString("requestId") || thread != raw.optString("threadId")) throw IOException("RECEIPT_THREAD_MISMATCH")
        val receipt = JSONObject()
        for (field in arrayOf("requestId", "threadId", "taskId", "state", "code", "createdAt")) if (raw.has(field)) receipt.put(field, raw.get(field))
        if ("publishing" == receipt.optString("state")) {
            val task = receipt.optString("taskId")
            val published = Pattern.matches("[0-9]{1,20}", task) && File(File(TaskRuntime.TASKS, task), "ready").isFile
            receipt.put("state", if (published) "queued" else "deliveryUnknown")
        }
        return receipt.put("effect", "conversational-followup")
    }

    @Throws(Exception::class)
    private fun reconcileCommands() {
        val commands = store.all("commands")
        for (i in 0 until commands.length()) {
            val command = commands.getJSONObject(i)
            if (command.optBoolean("terminal")) continue
            val matter = require(command.getString("matterId"))
            var receipt = deliveryReceipt(command.getString("id"), matter.getString("threadId")) ?: continue
            val current = command.getJSONObject("receipt")
            if (current.has("taskState") && current.optString("taskId") == receipt.optString("taskId")) receipt = current
            if (java.util.Set.of("notSent", "failed", "cancelled", "taskCompleted").contains(receipt.optString("state"))) command.put("terminal", true)
            command.put("receipt", receipt)
            store.put("commands", command.getString("id"), command)
            if (canonical(receipt) != canonical(matter.getJSONObject("commands").optJSONObject(command.getString("id")))) {
                matter.getJSONObject("commands").put(command.getString("id"), receipt)
                commit(matter)
            }
            if (Pattern.matches("[0-9]{1,20}", receipt.optString("taskId"))) attachReceipt(matter, receipt.getString("taskId"), command.getString("id"))
        }
    }

    @Synchronized
    fun recover() {
        io.execute {
            synchronized(this@MatterRuntime) {
                try {
                    credentialUnlocked()
                    // Claimed is pre-publication and safe to deliver. Publishing is reconciled conservatively.
                    val occurrences = store.all("occurrences")
                    for (i in 0 until occurrences.length()) {
                        val occurrence = occurrences.getJSONObject(i)
                        if ("claimed" == occurrence.optString("state")) io.execute { deliverOccurrence(occurrence.optString("id")) }
                        else if ("publishing" == occurrence.optString("state") && !deliveringOccurrences.contains(occurrence.optString("id"))) {
                            val matter = require(occurrence.getString("matterId"))
                            val receipt = deliveryReceipt(occurrence.getString("requestId"), matter.getString("threadId"))
                            occurrence.put("state", if (receipt == null) "unknown-outcome" else "delivered").put("terminal", true)
                            if (receipt != null) {
                                occurrence.put("receipt", receipt)
                                val requestId = occurrence.getString("requestId")
                                reserveCommandSlot(matter)
                                store.put("commands", requestId, JSONObject().put("id", requestId).put("matterId", matter.getString("matterId")).put("payload", occurrence.getString("id")).put("receipt", receipt).put("createdAt", occurrence.getLong("createdAt")).put("terminal", false))
                            }
                            store.put("occurrences", occurrence.getString("id"), occurrence)
                            recordOccurrence(occurrence)
                        }
                    }
                    reconcileCommands()
                    tick()
                } catch (lockedOrUnavailable: Exception) { /* Unlock/job/next foreground retries the same persisted owners. */ }
            }
            try { migrateCards(); recoverPanelUpdates() } catch (_: Exception) { }
        }
    }

    private fun reconcile() {
        try {
            val owned = HashSet<String>()
            val updates = store.pendingPanelUpdates()
            for (i in 0 until updates.length()) {
                val update = updates.getJSONObject(i)
                if (!update.optBoolean("settled") && update.optString("taskId").isNotEmpty()) owned.add("task-" + update.getString("taskId"))
            }
            val visibleSources = HashSet<String>()
            val visible = HashSet<String>()
            for (ids in leases.values) visible.addAll(ids)
            for (id in visible) {
                val bindings = store.bindings("matter", id)
                for (i in 0 until bindings.length()) {
                    val source = bindings.getJSONObject(i).getString("sourceId")
                    owned.add(source)
                    visibleSources.add(source)
                }
            }
            val rules = store.all("rules")
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                if (rule.optBoolean("paused")) continue
                if (rule.has("sourceId") && "available" == rule.optString("availability")) owned.add(rule.getString("sourceId"))
                if ("price-below" != rule.optString("kind")) {
                    val bindings = store.bindings("matter", rule.getString("matterId"))
                    for (j in 0 until bindings.length()) {
                        val id = bindings.getJSONObject(j).getString("sourceId")
                        val source = store.get("sources", id)
                        if (source != null && PublicSourceAdapter.supports(source.getString("kind"))) owned.add(id)
                    }
                }
            }
            val sources = store.all("sources")
            var activeTasks = 0
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val observation = source.optJSONObject("observation")
                if (source.getString("kind") == "task-receipt" && (observation == null || !observation.optBoolean("terminal"))) {
                    if (activeTasks++ < 32) owned.add(source.getString("id"))
                }
                if (source.getString("kind") == "audio-session" && observation != null && !java.util.Set.of("stopped", "failed", "interrupted").contains(observation.optString("status"))) owned.add(source.getString("id"))
            }
            for (id in ownedSources) if (!owned.contains(id)) generations[id] = generations.getOrDefault(id, 0L) + 1
            ownedSources.clear()
            ownedSources.addAll(owned)
            for (id in ArrayList(watches.keys)) {
                val source = store.get("sources", id)
                if (!owned.contains(id) || source != null && PublicSourceAdapter.supports(source.getString("kind")) && !visibleSources.contains(id)) {
                    try { watches.remove(id)!!.close() } catch (ignored: Exception) { }
                }
            }
            for (id in owned) if (!watches.containsKey(id)) {
                val source = store.get("sources", id) ?: continue
                if (PublicSourceAdapter.supports(source.getString("kind")) && !visibleSources.contains(id)) { refresh(id); continue }
                try { watches[id] = adapters.resolve(source.getString("kind")).watch(source) {
                    refresh(id)
                    if (source.has("panelRequestId")) io.execute { try { taskChanged(source.getString("resourceId")) } catch (_: Exception) { } }
                } }
                catch (denied: Exception) { refresh(id); continue }
                refresh(id)
            }
        } catch (unavailable: Exception) { /* Bounded job recovery retains persisted owners. */ }
    }

    companion object {
        private var instance: MatterRuntime? = null

        @JvmStatic
        fun get(context: Context): MatterRuntime = synchronized(MatterRuntime::class.java) {
            var runtime = instance
            if (runtime == null) {
                runtime = MatterRuntime(context.applicationContext)
                instance = runtime
                try { runtime.services.contractHash(); runtime.migrateCards() } catch (_: Exception) { }
                runtime.recover()
            }
            runtime
        }

        @Throws(Exception::class)
        private fun copy(value: JSONObject): JSONObject = JSONObject(value.toString())

        @Throws(Exception::class)
        private fun canonical(value: Any?): String {
            if (value is JSONObject) {
                val keys = ArrayList<String>()
                value.keys().forEachRemaining { keys.add(it) }
                Collections.sort(keys)
                val text = StringBuilder("{")
                for (key in keys) {
                    if (text.length > 1) text.append(',')
                    text.append(JSONObject.quote(key)).append(':').append(canonical(value.get(key)))
                }
                return text.append('}').toString()
            }
            if (value is JSONArray) {
                val text = StringBuilder("[")
                for (i in 0 until value.length()) {
                    if (i > 0) text.append(',')
                    text.append(canonical(value.get(i)))
                }
                return text.append(']').toString()
            }
            return if (value is String) JSONObject.quote(value) else java.lang.String.valueOf(value)
        }

        @Throws(Exception::class)
        private fun money(value: JSONObject): BigInteger {
            val scale = value.get("scale")
            if (scale !is Number || scale.toDouble() != scale.toInt().toDouble()) throw IOException("INVALID_PRICE")
            if ("money" != value.getString("type") || value.get("value") !is String || !Pattern.matches("[0-9]{1,24}", value.getString("value")) ||
                !Pattern.matches("[A-Z]{3}", value.getString("currency")) || value.getInt("scale") < 0 || value.getInt("scale") > 6) throw IOException("INVALID_PRICE")
            return BigInteger(value.getString("value"))
        }

        @Throws(Exception::class)
        private fun findBlock(request: JSONObject, id: String): JSONObject {
            val blocks = request.getJSONArray("blocks")
            for (i in 0 until blocks.length()) if (id == blocks.getJSONObject(i).getString("id")) return blocks.getJSONObject(i)
            throw IOException("UNKNOWN_FIELD_ID")
        }
    }
}
