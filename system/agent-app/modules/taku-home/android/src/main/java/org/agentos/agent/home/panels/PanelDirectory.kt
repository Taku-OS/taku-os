package org.agentos.agent.home.panels

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A directory read that produced no usable page list; [code] is the native error code. */
internal class PanelReadException(val code: String, message: String?) : Exception(message ?: code)

/**
 * The desktop's projection of the one native Panel directory. It reads every page through the
 * public Assistant Interface, restarts the walk when the revision moves, and rereads on the
 * committed `content://org.agentos.assistant.open-access/panels` invalidation, which is only a
 * hint that the revision changed. [State.entries] is a disposable cache of native records, never
 * a second directory: nothing is written here and a failed read keeps the pages it already had.
 */
class PanelDirectory private constructor(context: Context) {
  enum class Status {
    /** The Assistant speaks this build's contract; [State.entries] is the current projection. */
    Ready,

    /** The installed Assistant serves another (or no) Panel contract: panels are unavailable. */
    UpdateRequired,
  }

  /** One directory row, as published by the native registry. */
  data class Entry(val panelId: String, val title: String, val position: Long)

  data class State(
    val entries: List<Entry> = emptyList(),
    val listRevision: Long = -1,
    /** How many reads completed; a repeated failure still counts, so observers can retry. */
    val read: Long = 0,
    val status: Status = Status.Ready,
    val code: String? = null,
  ) {
    val ids: List<String> get() = entries.map { it.panelId }
    val updateRequired: Boolean get() = status == Status.UpdateRequired
  }

  private val appContext = context.applicationContext
  private val lock = Any()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val flow = MutableStateFlow(State())
  private var job: Job? = null
  private var pending = false
  private var observing = false
  private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
    override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
  }
  private val interfaceCall = PanelCall { method, arg -> exchange(method, arg) }

  val state: StateFlow<State> = flow.asStateFlow()

  /** Registers the committed invalidation observer once and reads the directory now. */
  fun start() {
    synchronized(lock) {
      if (observing) return
      appContext.contentResolver.registerContentObserver(INVALIDATION, false, observer)
      observing = true
    }
    refresh()
  }

  /** Reads the directory; a read already in flight is followed by exactly one more. */
  fun refresh() {
    synchronized(lock) {
      if (job?.isActive == true) {
        pending = true
        return
      }
      job = scope.launch { runReads() }
    }
  }

  private suspend fun runReads() {
    while (true) {
      val result = try {
        PanelDirectoryPages.read(interfaceCall).let { State(it.entries, it.listRevision) }
      } catch (e: PanelReadException) {
        failed(e)
      } catch (e: Exception) {
        failed(PanelReadException("DIRECTORY_UNAVAILABLE", e.message ?: e.javaClass.simpleName))
      }
      flow.value = result.copy(read = flow.value.read + 1)
      synchronized(lock) {
        if (!pending) {
          job = null
          return
        }
        pending = false
      }
    }
  }

  private fun failed(e: PanelReadException): State {
    val previous = flow.value
    return previous.copy(
      status = if (e.code == PanelDirectoryPages.UPDATE_REQUIRED) Status.UpdateRequired else previous.status,
      code = e.code,
    )
  }

  /** Calls the public Assistant Interface and returns the unwrapped result of a successful reply. */
  private fun exchange(method: String, arg: JSONObject): JSONObject {
    val bundle = try {
      appContext.contentResolver.call(OPEN_ACCESS, method, arg.toString(), null)
    } catch (e: IllegalArgumentException) {
      throw PanelReadException(PanelDirectoryPages.UPDATE_REQUIRED, e.message)
    } catch (e: SecurityException) {
      throw PanelReadException(PanelDirectoryPages.UPDATE_REQUIRED, e.message)
    } ?: throw PanelReadException(PanelDirectoryPages.ASSISTANT_UNAVAILABLE, "the TakuOS Assistant is not installed or not running")
    val raw = bundle.getString("result")
      ?: throw PanelReadException(PanelDirectoryPages.ASSISTANT_UNAVAILABLE, "the TakuOS Assistant returned no result")
    val envelope = try {
      JSONObject(raw)
    } catch (e: Exception) {
      throw PanelReadException(PanelDirectoryPages.ASSISTANT_UNAVAILABLE, "the TakuOS Assistant returned an unreadable result")
    }
    if (!envelope.optBoolean("ok")) {
      throw PanelReadException(envelope.optString("code").ifBlank { "UNKNOWN_METHOD" }, envelope.optString("message"))
    }
    return envelope.optJSONObject("result") ?: JSONObject()
  }

  companion object {
    private val OPEN_ACCESS = Uri.parse("content://org.agentos.assistant.open-access")
    private val INVALIDATION = Uri.parse("content://org.agentos.assistant.open-access/panels")

    @Volatile private var instance: PanelDirectory? = null

    fun get(context: Context): PanelDirectory =
      instance ?: synchronized(this) { instance ?: PanelDirectory(context).also { instance = it } }
  }
}
