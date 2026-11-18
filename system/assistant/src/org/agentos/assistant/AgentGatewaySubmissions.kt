package org.agentos.assistant

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HashMap
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import java.util.regex.Pattern
import org.json.JSONObject

/** Durable duplicate fence. An ambiguous publication is inspected, never automatically replayed. */
internal class AgentGatewaySubmissions(private val root: File) {
    /** Read-only checks only. A failure here proves the mutating callback was never invoked. */
    fun interface Preflight {
        @Throws(Exception::class)
        fun check()
    }
    fun interface Publish {
        @Throws(Exception::class)
        fun publish(before: TaskRequests.BeforePublish): String
    }
    fun interface Mutation {
        @Throws(Exception::class)
        fun run(): JSONObject
    }
    /** Only an explicit native rejection proving steer was not submitted may use this. */
    class SteerNotSubmitted : IOException("TURN_CHANGED")
    interface Fallback {
        @Throws(Exception::class)
        fun check()
        @Throws(Exception::class)
        fun publish(before: TaskRequests.BeforePublish): String
        fun published(taskId: String): Boolean
    }

    private class ThreadGate {
        val lock = ReentrantLock(true)
        var users = 0
    }
    private val threads = HashMap<String, ThreadGate>()

    private fun sequence(threadId: String): ThreadGate {
        val gate: ThreadGate
        synchronized(this) {
            gate = threads.computeIfAbsent(threadId) { ThreadGate() }
            gate.users++
        }
        // Waiting and callbacks never hold the submissions monitor. MatterRuntime releases its
        // monitor before Submit, so a sequenced preflight can safely read the current matter.
        gate.lock.lock()
        return gate
    }

    private fun release(threadId: String, gate: ThreadGate) {
        gate.lock.unlock()
        synchronized(this) {
            if (--gate.users == 0) threads.remove(threadId, gate)
        }
    }

    @Throws(Exception::class)
    fun steer(requestId: String, threadId: String, turnId: String, text: String, preflight: Preflight, operation: Mutation): JSONObject =
        steer(requestId, threadId, turnId, text, preflight, operation, null)

    @Throws(Exception::class)
    fun steer(requestId: String, threadId: String, turnId: String, text: String, preflight: Preflight, operation: Mutation, fallback: Fallback?): JSONObject {
        val gate = sequence(threadId)
        try {
            val receipt = File(root, "steer-$requestId.json")
            val hash = digest("steer\n$threadId\n$turnId\n$text")
            val state = reserve(receipt, requestId, threadId, hash)
            if (state.has("replayed")) {
                val prior = read(receipt)
                if (fallback != null && prior.optString("state") == "notSent" && prior.optString("code") == "TURN_CHANGED")
                    return fallback(receipt, prior, fallback, true)
                if (prior.optString("state") == "fallbackPublishing") {
                    if (fallback != null && fallback.published(prior.optString("taskId"))) {
                        prior.put("state", "accepted").put("result", JSONObject().put("queuedAfterTurn", true))
                        saveSteer(receipt, prior)
                    } else prior.put("state", "deliveryUnknown")
                }
                if (prior.optString("state") == "publishing") prior.put("state", "deliveryUnknown")
                return publicReceipt(prior).put("replayed", true)
            }
            if (!preflight(receipt, state, preflight, true)) {
                if (fallback != null && state.optString("code") == "TURN_CHANGED") return fallback(receipt, state, fallback, false)
                return publicReceipt(state).put("replayed", false)
            }
            try {
                val result = operation.run()
                state.put("state", "accepted").put("result", result)
                saveSteer(receipt, state)
            } catch (rejected: SteerNotSubmitted) {
                state.put("state", "notSent").put("code", "TURN_CHANGED")
                saveSteer(receipt, state)
                if (fallback != null) return fallback(receipt, state, fallback, false)
            } catch (unknown: Exception) {
                state.put("state", "deliveryUnknown").remove("result")
                try { saveSteer(receipt, state) } catch (ignored: Exception) { }
            }
            return publicReceipt(state).put("replayed", false)
        } finally { release(threadId, gate) }
    }

    @Throws(Exception::class)
    private fun fallback(receipt: File, state: JSONObject, fallback: Fallback, replayed: Boolean): JSONObject {
        if (!preflight(receipt, state, Preflight { fallback.check() }, true)) return publicReceipt(state).put("replayed", replayed)
        state.put("state", "fallbackPublishing").remove("code")
        saveSteer(receipt, state)
        try {
            val task = fallback.publish { id -> state.put("taskId", id); saveSteer(receipt, state) }
            state.put("taskId", task).put("state", "accepted").put("result", JSONObject().put("queuedAfterTurn", true))
            saveSteer(receipt, state)
        } catch (unknown: Exception) {
            val published = fallback.published(state.optString("taskId"))
            state.put("state", if (published) "accepted" else "deliveryUnknown")
            if (published) state.put("result", JSONObject().put("queuedAfterTurn", true))
            try { saveSteer(receipt, state) } catch (ignored: Exception) { }
        }
        return publicReceipt(state).put("replayed", replayed)
    }

    @Throws(Exception::class)
    fun submit(requestId: String, threadId: String, text: String, preflight: Preflight, publish: Publish): JSONObject {
        val receipt = File(root, "$requestId.json")
        val reservation = reserve(receipt, requestId, threadId, digest("$threadId\n$text"))
        if (reservation.has("replayed")) return reservation
        val gate = sequence(threadId)
        try {
            if (!preflight(receipt, reservation, preflight)) return publicReceipt(reservation).put("replayed", false)
            try {
                val task = publish.publish { id ->
                    reservation.put("taskId", id)
                    finish(receipt, reservation)
                }
                reservation.put("taskId", task).put("state", "queued")
                finish(receipt, reservation)
            } catch (failed: Exception) {
                val task = reservation.optString("taskId")
                val published = Pattern.matches("[0-9]+", task) && File(File(AssistantSession.TASKS, task), "ready").isFile
                reservation.put("state", if (published) "queued" else "deliveryUnknown")
                try { finish(receipt, reservation) } catch (ignored: Exception) { }
            }
            return publicReceipt(reservation).put("replayed", false)
        } finally { release(threadId, gate) }
    }

    @Synchronized
    @Throws(Exception::class)
    private fun reserve(receipt: File, requestId: String, threadId: String, hash: String): JSONObject {
        root.mkdirs()
        if (receipt.isFile) {
            val prior = read(receipt)
            if (hash != prior.optString("hash")) throw IOException("REQUEST_ID_REUSED")
            if ("publishing" == prior.optString("state")) prior.put("state", "deliveryUnknown")
            return publicReceipt(prior).put("replayed", true)
        }
        val state = JSONObject().put("requestId", requestId).put("threadId", threadId)
            .put("hash", hash).put("state", "publishing").put("createdAt", System.currentTimeMillis())
        TaskRequests.write(receipt, state.toString())
        return state
    }

    @Synchronized
    @Throws(Exception::class)
    private fun finish(receipt: File, state: JSONObject) {
        val current = read(receipt)
        if (state.getString("hash") != current.optString("hash")) throw IOException("REQUEST_ID_REUSED")
        if ("publishing" != current.optString("state")) throw IOException("DELIVERY_ALREADY_FINALIZED")
        TaskRequests.write(receipt, state.toString())
    }

    /** Steering transitions are serialized by the session gate; callbacks never hold this monitor. */
    @Synchronized
    @Throws(Exception::class)
    private fun saveSteer(receipt: File, state: JSONObject) {
        if (state.getString("hash") != read(receipt).optString("hash")) throw IOException("REQUEST_ID_REUSED")
        TaskRequests.write(receipt, state.toString())
    }

    @Throws(Exception::class)
    private fun preflight(receipt: File, state: JSONObject, check: Preflight): Boolean = preflight(receipt, state, check, false)

    @Throws(Exception::class)
    private fun preflight(receipt: File, state: JSONObject, check: Preflight, steering: Boolean): Boolean {
        try {
            check.check()
            return true
        } catch (denied: Exception) {
            val code = denied.message
            state.put("state", "notSent").put("code", if (code != null && Pattern.matches("[A-Z][A-Z_]{2,80}", code)) code else "RUNTIME_UNAVAILABLE")
            // If this write fails, the older durable publishing fence remains conservative.
            // The current response is still definitive because no mutation callback has run.
            try { if (steering) saveSteer(receipt, state) else finish(receipt, state) } catch (ignored: Exception) { }
            return false
        }
    }

    companion object {
        private val instances = HashMap<String, AgentGatewaySubmissions>()
        @JvmStatic
        fun get(filesDir: File): AgentGatewaySubmissions = synchronized(instances) {
            val root = File(filesDir, "agent-ui-receipts")
            instances.getOrPut(root.absolutePath) { AgentGatewaySubmissions(root) }
        }

        @JvmStatic
        fun existing(filesDir: File, requestId: String): JSONObject? {
            val file = File(File(filesDir, "agent-ui-receipts"), "$requestId.json")
            if (!file.isFile) return null
            val receipt = publicReceipt(read(file))
            if (receipt.optString("state") in setOf("publishing", "deliveryUnknown")) {
                val task = receipt.optString("taskId")
                val published = task.matches(Regex("[0-9]{1,20}")) && File(File(AssistantSession.TASKS, task), "ready").isFile
                receipt.put("state", if (published) "queued" else "deliveryUnknown")
            }
            return receipt
        }
        @Throws(Exception::class)
        private fun read(receipt: File): JSONObject = JSONObject(String(Files.readAllBytes(receipt.toPath()), StandardCharsets.UTF_8))

        @Throws(Exception::class)
        private fun publicReceipt(state: JSONObject): JSONObject {
            val result = JSONObject()
            for (key in arrayOf("requestId", "threadId", "taskId", "state", "code", "createdAt", "result"))
                if (state.has(key)) result.put(key, state.get(key))
            return result
        }

        @Throws(Exception::class)
        private fun digest(value: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            val result = StringBuilder()
            for (byte in hash) result.append(String.format(Locale.ROOT, "%02x", byte.toInt() and 255))
            return result.toString()
        }
    }
}
