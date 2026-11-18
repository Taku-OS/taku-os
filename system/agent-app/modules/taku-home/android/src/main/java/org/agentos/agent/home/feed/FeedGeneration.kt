package org.agentos.agent.home.feed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.JsonReader
import android.util.JsonToken
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.interfaces.TaskInterface
import com.facebook.react.jstasks.HeadlessJsTaskConfig
import com.facebook.react.jstasks.HeadlessJsTaskContext
import com.facebook.react.jstasks.HeadlessJsTaskEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.StringReader
import java.util.UUID
import java.util.concurrent.TimeUnit

object FeedGeneration {
  private const val MAX_REQUEST_BYTES = 96_000
  private const val MAX_RECEIPT_BYTES = 128_000
  private const val STARTUP_MS = 10_000L
  private const val OPERATION_MS = 60_000L
  private const val SOURCE_WAIT_MS = 5_000L
  private const val TASK_NAME = "TakuFeedGeneration"
  private val ERROR_CODE = Regex("^[A-Z][A-Z0-9_]{0,63}$")
  private val lock = Object()
  private val main by lazy { Handler(Looper.getMainLooper()) }
  private var active: Attempt? = null

  private sealed interface Input {
    data class Prompt(val prompt: String) : Input
    data class Card(val card: JSONObject) : Input
  }

  private sealed interface State {
    data object Starting : State
    data object Running : State
    data class Prepared(val receipt: String) : State
    data object Committing : State
    data class Failed(val error: FeedException) : State
    data object Closed : State
  }

  private class Attempt(val input: Input, now: Long) {
    val attemptId = UUID.randomUUID().toString()
    val cardId = "agent-generated:$attemptId"
    val createdAt = System.currentTimeMillis()
    val startupDeadline = now + STARTUP_MS
    val operationDeadline = now + OPERATION_MS
    var state: State = State.Starting
    var host: ReactHost? = null
    var startup: TaskInterface<Void>? = null
    var context: ReactContext? = null
    // Only the main thread accesses the native task record.
    var task: TaskRecord? = null
  }

  private class TaskRecord(
    val manager: HeadlessJsTaskContext,
    val listener: HeadlessJsTaskEventListener,
    var id: Int? = null,
  )

  private data class PreparedCard(val card: JSONObject, val model: String? = null)

  fun generate(context: Context, raw: String?): JSONObject {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      throw FeedException("INVALID_THREAD", "Feed generation must run on a worker thread")
    }
    val input = parseRequest(raw)
    val attempt = synchronized(lock) {
      if (active != null) throw FeedException("BUSY", "Another Feed generation is active")
      Attempt(input, SystemClock.elapsedRealtime()).also { active = it }
    }
    val application = context.applicationContext
    val beforeDestroy: () -> Unit = { fail(attempt, "RUNTIME_LOST", "Feed generation runtime was lost") }
    val dispatch = Runnable { dispatch(attempt) }
    // Providers can receive Binder calls before Application.onCreate initializes native libraries.
    val bootstrap = Runnable {
      try {
        synchronized(lock) { requireActive(attempt) }
        val runtimeHost = (application as? ReactApplication)?.reactHost
          ?: throw FeedException("RUNTIME_UNAVAILABLE", "Feed generation runtime is unavailable")
        synchronized(lock) {
          requireActive(attempt)
          attempt.host = runtimeHost
        }
        runtimeHost.addBeforeDestroyListener(beforeDestroy)
        val startup = runtimeHost.start()
        synchronized(lock) {
          if (canContinue(attempt)) attempt.startup = startup
          lock.notifyAll()
        }
      } catch (e: FeedException) {
        fail(attempt, e.code, e.message ?: "Feed generation runtime could not start")
      } catch (_: Exception) {
        fail(attempt, "RUNTIME_UNAVAILABLE", "Feed generation runtime could not start")
      }
    }
    try {
      val repo = FeedRepository.get(application)
      repo.ensureLoaded()
      if (!main.post(bootstrap)) throw FeedException("RUNTIME_UNAVAILABLE", "Feed generation runtime could not be dispatched")
      val startup = synchronized(lock) {
        while (attempt.startup == null) {
          requireActive(attempt)
          lock.wait((attempt.startupDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
        requireActive(attempt)
        checkNotNull(attempt.startup)
      }
      val runtimeHost = synchronized(lock) { checkNotNull(attempt.host) }
      while (!startup.isCompleted()) {
        val remaining = synchronized(lock) {
          requireActive(attempt)
          attempt.startupDeadline - SystemClock.elapsedRealtime()
        }
        startup.waitForCompletion(minOf(remaining.coerceAtLeast(1), 100), TimeUnit.MILLISECONDS)
      }
      if (startup.isCancelled() || startup.isFaulted()) {
        // Observe the public task error without exposing a runtime exception to the caller.
        startup.getError()
        throw FeedException("RUNTIME_UNAVAILABLE", "Feed generation runtime could not start")
      }
      synchronized(lock) {
        requireActive(attempt)
        val reactContext = runtimeHost.currentReactContext
          ?: throw FeedException("RUNTIME_UNAVAILABLE", "Feed generation runtime has no context")
        if (!reactContext.hasActiveReactInstance()) {
          throw FeedException("RUNTIME_UNAVAILABLE", "Feed generation runtime is not active")
        }
        attempt.context = reactContext
      }
      if (!main.post(dispatch)) throw FeedException("RUNTIME_UNAVAILABLE", "Feed generation could not be dispatched")
      val prepared = acceptReceipt(attempt, awaitReceipt(attempt))
      awaitSources(attempt, repo)
      synchronized(lock) {
        requireActive(attempt)
        check(attempt.state is State.Prepared)
        attempt.state = State.Committing
      }
      // The precommit deadline ends here. Persistence must return its actual result.
      return repo.publishGeneratedCard(prepared.card, attempt.cardId, FeedOrigin.AGENT).also { result ->
        prepared.model?.let { result.put("model", it) }
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
      throw FeedException("CANCELLED", "Feed generation was interrupted")
    } catch (e: FeedException) {
      throw e
    } catch (_: Exception) {
      throw FeedException("GENERATION_FAILED", "Feed generation could not complete")
    } finally {
      val runtimeHost = synchronized(lock) {
        attempt.state = State.Closed
        if (active === attempt) active = null
        lock.notifyAll()
        attempt.startup = null
        attempt.host
      }
      main.removeCallbacks(bootstrap)
      main.removeCallbacks(dispatch)
      main.post {
        runtimeHost?.removeBeforeDestroyListener(beforeDestroy)
        attempt.task?.let { task ->
          task.manager.removeTaskEventListener(task.listener)
          task.id?.let { if (task.manager.isTaskRunning(it)) task.manager.finishTask(it) }
        }
        attempt.task = null
      }
    }
  }

  fun begin(attemptId: String, context: ReactContext): Boolean = synchronized(lock) {
    val attempt = active ?: return false
    if (attempt.attemptId != attemptId || attempt.state !== State.Starting || attempt.context !== context) return false
    if (!canContinue(attempt)) return false
    attempt.state = State.Running
    lock.notifyAll()
    true
  }

  fun complete(attemptId: String, receiptJson: String, context: ReactContext): Boolean = synchronized(lock) {
    val attempt = active ?: return false
    if (attempt.attemptId != attemptId || attempt.state !== State.Running || attempt.context !== context) return false
    if (!canContinue(attempt)) return false
    if (!fitsUtf8(receiptJson, MAX_RECEIPT_BYTES)) {
      failLocked(attempt, "INVALID_MODEL_OUTPUT", "Feed generation receipt exceeds its size limit")
      return false
    }
    if (!canContinue(attempt)) return false
    attempt.state = State.Prepared(receiptJson)
    lock.notifyAll()
    true
  }

  private fun dispatch(attempt: Attempt) {
    try {
      val context = synchronized(lock) {
        requireActive(attempt)
        check(attempt.state === State.Starting)
        checkNotNull(attempt.context)
      }
      val manager = HeadlessJsTaskContext.getInstance(context)
      val listener = object : HeadlessJsTaskEventListener {
        override fun onHeadlessJsTaskStart(taskId: Int) = Unit

        override fun onHeadlessJsTaskFinish(taskId: Int) {
          if (attempt.task?.id != taskId) return
          synchronized(lock) {
            if ((attempt.state === State.Starting || attempt.state === State.Running) && canContinue(attempt)) {
              failLocked(attempt, "RUNTIME_UNAVAILABLE", "Feed generation task finished without a receipt")
            }
          }
        }
      }
      val record = TaskRecord(manager, listener)
      attempt.task = record
      manager.addTaskEventListener(listener)
      val data = Arguments.createMap().apply {
        putString("attemptId", attempt.attemptId)
        putString("id", attempt.cardId)
        putDouble("createdAt", attempt.createdAt.toDouble())
        putMap("input", Arguments.createMap().apply {
          when (val input = attempt.input) {
            is Input.Prompt -> { putString("kind", "prompt"); putString("prompt", input.prompt) }
            is Input.Card -> { putString("kind", "card"); putMap("card", nativeObject(input.card)) }
          }
        })
      }
      val remaining = synchronized(lock) {
        requireActive(attempt)
        attempt.operationDeadline - SystemClock.elapsedRealtime()
      }
      record.id = manager.startTask(HeadlessJsTaskConfig(TASK_NAME, data, remaining.coerceAtLeast(1), true))
    } catch (e: FeedException) {
      fail(attempt, e.code, e.message ?: "Feed generation could not start")
    } catch (_: Exception) {
      fail(attempt, "RUNTIME_UNAVAILABLE", "Feed generation task could not start")
    }
  }

  private fun awaitReceipt(attempt: Attempt): String = synchronized(lock) {
    while (attempt.state !is State.Prepared) {
      requireActive(attempt)
      val deadline = if (attempt.state === State.Starting) attempt.startupDeadline else attempt.operationDeadline
      lock.wait((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
    }
    requireActive(attempt)
    (attempt.state as State.Prepared).receipt
  }

  private fun awaitSources(attempt: Attempt, repo: FeedRepository) {
    val deadline = minOf(attempt.operationDeadline, SystemClock.elapsedRealtime() + SOURCE_WAIT_MS)
    synchronized(lock) {
      while (true) {
        requireActive(attempt)
        val state = repo.state.value
        if (state.error != null) throw FeedException("FEED_UNAVAILABLE", "Feed sources could not load")
        if (!state.loading) return
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) throw FeedException("FEED_UNAVAILABLE", "Feed sources did not become ready")
        lock.wait(minOf(remaining, 50))
      }
    }
  }

  private fun requireActive(attempt: Attempt) {
    if (!canContinue(attempt)) {
      throw (attempt.state as? State.Failed)?.error
        ?: FeedException("CANCELLED", "Feed generation is no longer active")
    }
  }

  private fun canContinue(attempt: Attempt): Boolean {
    if (active !== attempt || attempt.state is State.Failed || attempt.state === State.Closed) return false
    if (attempt.state === State.Committing) return true
    val now = SystemClock.elapsedRealtime()
    if (now >= attempt.operationDeadline || (attempt.state === State.Starting && now >= attempt.startupDeadline)) {
      failLocked(attempt, "RUNTIME_TIMEOUT", "Feed generation exceeded its deadline")
      return false
    }
    val context = attempt.context
    if (context != null && (attempt.host?.currentReactContext !== context || !context.hasActiveReactInstance())) {
      failLocked(attempt, "RUNTIME_LOST", "Feed generation runtime was lost")
      return false
    }
    return true
  }

  private fun fail(attempt: Attempt, code: String, message: String) = synchronized(lock) {
    failLocked(attempt, code, message)
  }

  private fun failLocked(attempt: Attempt, code: String, message: String) {
    if (active !== attempt || attempt.state is State.Failed || attempt.state === State.Closed || attempt.state === State.Committing) return
    attempt.state = State.Failed(FeedException(code, message))
    lock.notifyAll()
  }

  private fun parseRequest(raw: String?): Input {
    if (raw == null) throw FeedException("INVALID_ARGUMENT", "Provide exactly one prompt or card")
    if (!fitsUtf8(raw, MAX_REQUEST_BYTES)) throw FeedException("REQUEST_TOO_LARGE", "Feed generation request exceeds 96000 UTF-8 bytes")
    val request = parseObject(raw, "INVALID_ARGUMENT")
    if (request.length() != 1) throw FeedException("INVALID_ARGUMENT", "Provide exactly one prompt or card")
    if (request.has("prompt")) {
      val prompt = request.opt("prompt") as? String
        ?: throw FeedException("INVALID_ARGUMENT", "prompt must be a nonblank string")
      if (prompt.isBlank() || prompt.length > 16_000) throw FeedException("INVALID_ARGUMENT", "prompt must contain 1 to 16000 characters")
      return Input.Prompt(prompt)
    }
    val card = request.opt("card") as? JSONObject
      ?: throw FeedException("INVALID_ARGUMENT", "card must be a full JSON object")
    try { FeedCard.parseGenerated(card) } catch (_: Exception) {
      throw FeedException("INVALID_CARD", "The supplied card envelope is invalid")
    }
    return Input.Card(card)
  }

  private fun acceptReceipt(attempt: Attempt, raw: String): PreparedCard {
    val receipt = parseObject(raw, "GENERATION_FAILED")
    when (receipt.opt("ok")) {
      false -> {
        if (!hasKeys(receipt, "ok", "code", "message")) invalidReceipt()
        val code = receipt.opt("code") as? String ?: invalidReceipt()
        val message = receipt.opt("message") as? String ?: invalidReceipt()
        if (!ERROR_CODE.matches(code) || message.length > 2_048) invalidReceipt()
        val diagnostic = when (code) {
          "INVALID_MODEL_OUTPUT" -> "The model did not return a valid Feed card"
          "INVALID_SPEC" -> "The card body does not satisfy the Feed catalog"
          "INVALID_CARD" -> "The card envelope is invalid"
          else -> "Feed generation failed ($code)"
        }
        throw FeedException(code, diagnostic)
      }
      true -> return when (val input = attempt.input) {
        is Input.Card -> {
          if (!hasKeys(receipt, "ok")) invalidReceipt()
          PreparedCard(input.card)
        }
        is Input.Prompt -> {
          if (!hasKeys(receipt, "ok", "card", "model")) invalidReceipt()
          val card = receipt.opt("card") as? JSONObject ?: invalidReceipt()
          val model = receipt.opt("model") as? String ?: invalidReceipt()
          if (model.isBlank()) invalidReceipt()
          try { FeedCard.parseGenerated(card) } catch (_: Exception) {
            throw FeedException("INVALID_MODEL_OUTPUT", "The generated card envelope is invalid")
          }
          PreparedCard(card, model)
        }
      }
      else -> invalidReceipt()
    }
  }

  private fun hasKeys(value: JSONObject, vararg keys: String): Boolean =
    value.length() == keys.size && keys.all(value::has)

  private fun invalidReceipt(): Nothing = throw FeedException("GENERATION_FAILED", "Feed generation returned an invalid receipt")

  private fun parseObject(raw: String, code: String): JSONObject {
    try {
      JsonReader(StringReader(raw)).use { reader ->
        reader.isLenient = false
        val value = readJson(reader, 0)
        if (value !is JSONObject || reader.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException()
        return value
      }
    } catch (_: Exception) {
      throw FeedException(code, "Expected one complete JSON object with unique keys")
    }
  }

  private fun readJson(reader: JsonReader, depth: Int): Any {
    require(depth <= 64)
    return when (reader.peek()) {
      JsonToken.BEGIN_OBJECT -> JSONObject().apply {
        reader.beginObject()
        while (reader.hasNext()) {
          val key = reader.nextName()
          require(!has(key))
          put(key, readJson(reader, depth + 1))
        }
        reader.endObject()
      }
      JsonToken.BEGIN_ARRAY -> JSONArray().apply {
        reader.beginArray()
        while (reader.hasNext()) put(readJson(reader, depth + 1))
        reader.endArray()
      }
      JsonToken.STRING -> reader.nextString()
      JsonToken.NUMBER -> {
        val number = reader.nextString()
        number.toLongOrNull() ?: number.toDouble().also { require(it.isFinite()) }
      }
      JsonToken.BOOLEAN -> reader.nextBoolean()
      JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
      else -> throw IllegalArgumentException()
    }
  }

  private fun fitsUtf8(value: String, limit: Int): Boolean {
    if (value.length > limit) return false
    var bytes = 0
    var index = 0
    while (index < value.length) {
      val char = value[index++]
      bytes += when {
        char.code < 0x80 -> 1
        char.code < 0x800 -> 2
        char.isHighSurrogate() && index < value.length && value[index].isLowSurrogate() -> { index++; 4 }
        else -> 3
      }
      if (bytes > limit) return false
    }
    return true
  }

  private fun nativeObject(value: JSONObject): WritableMap = Arguments.createMap().apply {
    for (key in value.keys()) {
      when (val entry = value.get(key)) {
        JSONObject.NULL -> putNull(key)
        is JSONObject -> putMap(key, nativeObject(entry))
        is JSONArray -> putArray(key, nativeArray(entry))
        is String -> putString(key, entry)
        is Boolean -> putBoolean(key, entry)
        is Number -> putDouble(key, entry.toDouble())
      }
    }
  }

  private fun nativeArray(value: JSONArray): WritableArray = Arguments.createArray().apply {
    for (index in 0 until value.length()) {
      when (val entry = value.get(index)) {
        JSONObject.NULL -> pushNull()
        is JSONObject -> pushMap(nativeObject(entry))
        is JSONArray -> pushArray(nativeArray(entry))
        is String -> pushString(entry)
        is Boolean -> pushBoolean(entry)
        is Number -> pushDouble(entry.toDouble())
      }
    }
  }
}
