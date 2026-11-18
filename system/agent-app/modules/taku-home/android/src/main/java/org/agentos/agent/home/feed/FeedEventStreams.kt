package org.agentos.agent.home.feed

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object FeedEventStreams {
  const val LEASE_MILLIS = 300_000L
  private const val NO_PROGRESS_MILLIS = 5_000L
  private val permits = Semaphore(4)
  private val leases = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "feed-event-leases").apply { isDaemon = true } }.apply {
    removeOnCancelPolicy = true
  }
  private val newline = byteArrayOf('\n'.code.toByte())

  fun open(repo: FeedRepository, request: FeedEventRequest): ParcelFileDescriptor {
    if (!permits.tryAcquire()) throw FeedException("STREAM_BUSY", "at most four Feed event streams may be open")
    var reader: FeedRepository.EventReader? = null
    var pipe: Array<ParcelFileDescriptor>? = null
    var stream: Stream? = null
    try {
      val opened = repo.openEventReader(request)
      reader = opened
      val descriptors = ParcelFileDescriptor.createReliablePipe()
      pipe = descriptors
      val flags = Os.fcntlInt(descriptors[1].fileDescriptor, OsConstants.F_GETFL, 0)
      Os.fcntlInt(descriptors[1].fileDescriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
      val active = Stream(opened, descriptors[1])
      stream = active
      active.lease(leases.schedule({ active.close("STREAM_LEASE_EXPIRED") }, LEASE_MILLIS, TimeUnit.MILLISECONDS))
      Thread({
        try {
          active.frame(opened.ready)
          while (!active.closed.get()) {
            when (val next = opened.next()) {
              is FeedRepository.EventRead.Event -> {
                var offset = 0L
                while (offset < next.stored.byteCount) {
                  val bytes = opened.chunk(next.stored, offset)
                  if (bytes.isEmpty() || bytes.size > next.stored.byteCount - offset) throw IOException("STATE_READ_FAILED")
                  active.write(bytes)
                  offset += bytes.size
                }
                active.write(newline)
              }
              is FeedRepository.EventRead.Checkpoint -> active.frame(JSONObject().put("type", "checkpoint").put("through", next.through.toString()))
              FeedRepository.EventRead.End -> break
            }
          }
        } catch (e: Exception) {
          active.close(if (e is FeedException) e.code else if (e is IOException) e.message ?: "STREAM_FAILED" else "STREAM_FAILED")
        } finally {
          active.close()
          permits.release()
        }
      }, "feed-event-writer").apply { isDaemon = true }.start()
      return descriptors[0]
    } catch (e: Throwable) {
      stream?.close("STREAM_OPEN_FAILED")
      reader?.close()
      pipe?.forEach { runCatching { it.close() } }
      permits.release()
      throw e
    }
  }

  private class Stream(val reader: FeedRepository.EventReader, private val output: ParcelFileDescriptor) {
    val closed = AtomicBoolean(false)
    private var expiry: ScheduledFuture<*>? = null
    private val poll = arrayOf(StructPollfd().apply {
      fd = output.fileDescriptor
      events = OsConstants.POLLOUT.toShort()
    })

    fun lease(task: ScheduledFuture<*>) = synchronized(this) {
      if (closed.get()) task.cancel(false) else expiry = task
    }

    fun frame(frame: JSONObject) {
      write(frame.toString().toByteArray(Charsets.UTF_8))
      write(newline)
    }

    fun write(bytes: ByteArray) {
      var offset = 0
      var deadline = SystemClock.elapsedRealtime() + NO_PROGRESS_MILLIS
      while (offset < bytes.size) {
        if (closed.get()) throw IOException("STREAM_CLOSED")
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw IOException("STREAM_SLOW_CONSUMER")
        try {
          val count = synchronized(this) {
            if (closed.get()) throw IOException("STREAM_CLOSED")
            Os.write(output.fileDescriptor, bytes, offset, bytes.size - offset)
          }
          if (count > 0) {
            offset += count
            deadline = SystemClock.elapsedRealtime() + NO_PROGRESS_MILLIS
            continue
          }
        } catch (e: ErrnoException) {
          if (e.errno == OsConstants.EINTR) continue
          if (e.errno != OsConstants.EAGAIN) throw e
        }
        try {
          Os.poll(poll, minOf(250L, remaining).toInt())
        } catch (e: ErrnoException) {
          if (e.errno != OsConstants.EINTR) throw e
        }
        if ((poll[0].revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) {
          throw IOException("STREAM_DISCONNECTED")
        }
      }
    }

    fun close(error: String? = null) {
      synchronized(this) {
        if (!closed.compareAndSet(false, true)) return
        expiry?.cancel(false)
        expiry = null
        try {
          if (error == null) output.close() else output.closeWithError(error.take(128))
        } catch (_: IOException) {
          runCatching { output.close() }
        }
      }
      reader.close()
    }
  }
}
