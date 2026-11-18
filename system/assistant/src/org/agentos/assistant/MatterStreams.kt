package org.agentos.assistant

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.HashMap
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** UID-bound single-use multiplex leases; a slow reader cannot block source commits. */
internal class MatterStreams(private val runtime: MatterRuntime) {
    private val leases = HashMap<String, Lease>()
    private val expiry = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "matter-lease-expiry").apply { isDaemon = true }
    }

    private inner class Lease(val uid: Int, val ids: JSONArray) : MatterRuntime.Listener {
        val id = UUID.randomUUID().toString()
        val filter = HashSet<String>()
        val expires = SystemClock.elapsedRealtime() + 120_000
        val queue: BlockingQueue<ByteArray> = ArrayBlockingQueue(64)
        @Volatile var closed = false
        var opened = false
        var output: ParcelFileDescriptor? = null
        var bytes = 0

        init {
            for (i in 0 until ids.length()) filter.add(ids.getString(i))
        }

        @Synchronized
        override fun event(event: JSONObject) {
            if (closed || event.has("matterId") && !filter.contains(event.optString("matterId"))) return
            val value = (event.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            if (value.size > 256_000 || bytes + value.size > 4 * 1024 * 1024 || !queue.offer(value)) {
                close()
                return
            }
            bytes += value.size
        }

        fun close() {
            closed = true
            try { output?.close() } catch (_: IOException) { }
            synchronized(this@MatterStreams) { leases.remove(id) }
            runtime.unwatch(id, this)
        }
    }

    @Throws(Exception::class)
    fun subscribe(ids: JSONArray, uid: Int): JSONObject {
        // Accessibility is checked at issuance; open registers baselines with future updates.
        for (i in 0 until ids.length()) runtime.read(ids.getString(i))
        val lease = Lease(uid, ids)
        synchronized(this) {
            if (leases.size >= 2) throw IOException("STREAM_BUSY")
            leases[lease.id] = lease
        }
        expiry.schedule({ lease.close() }, 120, TimeUnit.SECONDS)
        return JSONObject().put("subscriptionId", lease.id)
            .put("streamUri", Uri.Builder().scheme("content").authority(AgentGatewayProtocol.AUTHORITY)
                .appendPath("matter-events").appendPath(lease.id).build().toString())
            .put("leaseExpiresAt", System.currentTimeMillis() + 120_000)
    }

    @Throws(Exception::class)
    fun unsubscribe(id: String, uid: Int): JSONObject {
        val lease: Lease?
        synchronized(this) {
            lease = leases[id]
            if (lease != null && lease.uid != uid) throw IOException("STREAM_IDENTITY_MISMATCH")
        }
        lease?.close()
        return JSONObject().put("closed", true)
    }

    @Throws(Exception::class)
    fun open(id: String, uid: Int): ParcelFileDescriptor {
        val lease: Lease
        synchronized(this) {
            val current = leases[id]
            if (current == null || current.closed || current.opened || current.uid != uid ||
                current.expires <= SystemClock.elapsedRealtime()) throw IOException("STREAM_EXPIRED")
            lease = current
            lease.opened = true
        }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        lease.output = pipe[1]
        Os.fcntlInt(pipe[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        try {
            runtime.watch(id, lease.ids, lease)
        } catch (failure: Exception) {
            lease.close()
            pipe[0].close()
            throw failure
        }
        val worker = Thread({
            try {
                while (!lease.closed) {
                    val queued = lease.queue.poll(10, TimeUnit.SECONDS)
                    val bytes = if (queued == null) {
                        (runtime.heartbeat().toString() + "\n").toByteArray(StandardCharsets.UTF_8)
                    } else {
                        synchronized(lease) { lease.bytes -= queued.size }
                        queued
                    }
                    write(lease, bytes)
                }
            } catch (_: Exception) {
                // EOF requires a new baseline.
            } finally {
                lease.close()
            }
        }, "matter-events")
        worker.isDaemon = true
        worker.start()
        return pipe[0]
    }

    private fun write(lease: Lease, bytes: ByteArray) {
        val descriptor = lease.output!!.fileDescriptor
        val poll = StructPollfd()
        poll.fd = descriptor
        poll.events = OsConstants.POLLOUT.toShort()
        var offset = 0
        while (offset < bytes.size && !lease.closed) {
            try {
                offset += Os.write(descriptor, bytes, offset, bytes.size - offset)
            } catch (busy: ErrnoException) {
                if (busy.errno != OsConstants.EAGAIN) throw busy
                Os.poll(arrayOf(poll), 1000)
            }
        }
    }
}
