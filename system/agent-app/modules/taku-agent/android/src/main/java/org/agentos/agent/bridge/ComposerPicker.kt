package org.agentos.agent.bridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException

/**
 * The composer's file, image and camera picker.
 *
 * Android may destroy the conversation Activity, or the whole process, while the picker or
 * camera is in front. The open request is therefore kept on disk and registered under one
 * stable result key: the recreated Activity collects the result and the selection is still
 * attached to the draft the system gateway holds. [onAttached] then tells React which
 * conversation's draft changed, since the promise that asked may no longer have a listener.
 */
internal class ComposerPicker(
  private val main: Handler,
  private val background: CoroutineScope,
  private val callGateway: (ContentResolver, String, String, Bundle?) -> String,
  private val onAttached: (threadId: String) -> Unit,
) {
  private companion object {
    const val RESULT_KEY = "taku-composer-picker"
    const val STORE = "composer-picker"
    const val CAMERA_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    /** A picker left open longer than this is not coming back. */
    const val STALE_MS = 60 * 60 * 1000L
  }

  private enum class Kind(val mime: String) { FILE("*/*"), IMAGE("image/*"), CAMERA("image/jpeg") }
  private class Pending(
    val context: Context,
    var activity: Activity?,
    val kind: Kind,
    val threadId: String,
    val expectedRevision: String,
    /** Null once the request was restored after its process died. */
    val promise: Promise?,
    var launcher: ActivityResultLauncher<Intent>? = null,
    /** The gallery entry the camera writes to. */
    var camera: Uri? = null,
    var captured: Boolean = false,
    /** The Activity that asked was recreated, so React may have missed the answer. */
    var recreated: Boolean = false,
    var transfer: CancellationSignal? = null,
  )

  private var pending: Pending? = null
  @Volatile private var closed = false

  /** A picker the chat opened is still waiting for its result (main thread). */
  val isOpen: Boolean get() = pending != null

  fun pick(
    activity: Activity?, kind: String, threadId: String, expectedRevision: String,
    permissions: Permissions?, promise: Promise,
  ) {
    if (closed || activity == null) {
      promise.reject("PICKER_UNAVAILABLE", "Open the conversation before attaching a file", null)
      return
    }
    // A restored request whose result never arrived must not block a new one.
    pending?.takeIf { it.promise == null && it.transfer == null }?.let(::clear)
    if (pending != null) {
      promise.reject("PICKER_BUSY", "An attachment picker is already open", null)
      return
    }
    val source = when (kind) {
      "file" -> Kind.FILE
      "image" -> Kind.IMAGE
      "camera" -> Kind.CAMERA
      else -> {
        promise.reject("INVALID_ARGUMENT", "Unknown attachment picker kind", null)
        return
      }
    }
    val request = Pending(activity.applicationContext, activity, source, threadId, expectedRevision, promise)
    pending = request
    try {
      if (source == Kind.CAMERA && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        if (permissions == null) {
          finish(request) { reject("CAMERA_PERMISSION_DENIED", "Camera access is required to take a photo", null) }
          return
        }
        permissions.askForPermissions({
          main.post {
            if (!owns(request)) return@post
            if (request.context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
              launch(request)
            } else {
              finish(request) { reject("CAMERA_PERMISSION_DENIED", "Camera access is required to take a photo", null) }
            }
          }
        }, Manifest.permission.CAMERA)
      } else {
        launch(request)
      }
    } catch (_: Exception) {
      unavailable(request)
    }
  }

  private fun launch(request: Pending) {
    val activity = request.activity
    if (activity == null || activity.isDestroyed || activity.isFinishing) {
      finish(request) { resolve() }
      return
    }
    try {
      val intent = if (request.kind == Kind.CAMERA) {
        // The photo is saved to the gallery, like any photo the user takes.
        val values = ContentValues().apply {
          put(MediaStore.Images.Media.DISPLAY_NAME, "Taku-${System.currentTimeMillis()}.jpg")
          put(MediaStore.Images.Media.MIME_TYPE, request.kind.mime)
          put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Taku")
        }
        val uri = request.context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException()
        request.camera = uri
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
          putExtra(MediaStore.EXTRA_OUTPUT, uri)
          clipData = ClipData.newRawUri("Photo", uri)
          addFlags(CAMERA_GRANT)
        }
      } else {
        Intent(Intent.ACTION_OPEN_DOCUMENT).setType(request.kind.mime).addCategory(Intent.CATEGORY_OPENABLE)
          .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
      }
      save(request)
      register(activity, request).launch(intent)
    } catch (_: Exception) {
      unavailable(request)
    }
  }

  private fun register(activity: Activity, request: Pending): ActivityResultLauncher<Intent> {
    val registry = (activity as ActivityResultRegistryOwner).activityResultRegistry
    // One stable key: a recreated Activity registering it again receives the parked result.
    return registry.register(RESULT_KEY, ActivityResultContracts.StartActivityForResult()) { result ->
      // Unregister only after the registry has marked this launch complete.
      main.post { onActivityResult(request, result) }
    }.also { request.launcher = it }
  }

  private fun onActivityResult(request: Pending, result: ActivityResult) {
    if (!owns(request) || request.transfer != null) return
    if (result.resultCode == Activity.RESULT_CANCELED) {
      finish(request) { resolve() }
      return
    }
    val camera = request.camera
    val selected = when {
      camera != null -> listOf(camera)
      else -> result.data?.clipData?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } }
        ?: listOfNotNull(result.data?.data)
    }
    if (result.resultCode != Activity.RESULT_OK || selected.isEmpty() || selected.any { it?.scheme != ContentResolver.SCHEME_CONTENT }) {
      finish(request) { reject("ATTACHMENT_UNREADABLE", "The selected attachment could not be read", null) }
      return
    }
    request.captured = camera != null
    val signal = CancellationSignal()
    request.transfer = signal
    background.launch {
      try {
        val envelope = attachAll(request.context.contentResolver, request, selected, signal)
        main.post { finish(request) { resolve(envelope) } }
      } catch (error: CodedException) {
        main.post { finish(request) { reject(error) } }
      } catch (_: Exception) {
        main.post { finish(request) { reject("GATEWAY_UNAVAILABLE", "Taku Agent gateway is unavailable", null) } }
      }
    }
  }

  /**
   * Attaches each selection in turn, each against the draft revision the previous one produced.
   * Returns the last accepted draft; when a later selection is refused (too large, over the
   * attachment limit, unreadable) the envelope also carries that code as `partial`.
   */
  private fun attachAll(resolver: ContentResolver, request: Pending, selected: List<Uri>, signal: CancellationSignal): String {
    var revision = request.expectedRevision
    if (request.recreated) currentRevision(resolver, request.threadId)?.let { revision = it }
    var accepted: JSONObject? = null
    for (uri in selected) {
      val envelope = try {
        JSONObject(attach(resolver, request, uri, revision, signal))
      } catch (error: CodedException) {
        val draft = accepted ?: throw error
        return draft.put("partial", error.code).toString()
      }
      if (!envelope.optBoolean("ok")) {
        val draft = accepted ?: return envelope.toString()
        return draft.put("partial", envelope.optString("code", "GATEWAY_UNAVAILABLE")).toString()
      }
      accepted = envelope
      revision = envelope.getJSONObject("result").getString("revision")
    }
    return accepted?.toString() ?: throw unreadable()
  }

  /** The draft may have been saved again while the picker was open and the Activity was recreated. */
  private fun currentRevision(resolver: ContentResolver, threadId: String): String? = try {
    val envelope = JSONObject(callGateway(resolver, "composer/read", JSONObject().put("threadId", threadId).toString(), null))
    if (envelope.optBoolean("ok")) envelope.getJSONObject("result").getString("revision") else null
  } catch (_: Exception) {
    null
  }

  private fun attach(resolver: ContentResolver, request: Pending, uri: Uri, revision: String, signal: CancellationSignal): String {
    val descriptor = try {
      resolver.openFileDescriptor(uri, "r", signal) ?: throw IOException()
    } catch (_: Exception) {
      throw unreadable()
    }
    descriptor.use {
      val input = try {
        val flags = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
        if ((flags and OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) throw IOException()
        val name = resolver.query(
          uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal,
        )?.use { cursor ->
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }?.takeIf { it.isNotBlank() } ?: "Attachment"
        val mime = resolver.getType(uri) ?: if (request.kind == Kind.CAMERA) request.kind.mime else "application/octet-stream"
        JSONObject().put("threadId", request.threadId).put("expectedRevision", revision)
          .put("name", name).put("mime", mime).toString()
      } catch (_: Exception) {
        throw unreadable()
      }
      val extras = Bundle().apply { putParcelable("attachment", descriptor) }
      signal.throwIfCanceled()
      if (closed) throw IOException()
      return callGateway(resolver, "composer/attach", input, extras)
    }
  }

  /**
   * A conversation Activity exists again. If a picker was open when the previous one (or the
   * process) went away, collect its result here.
   */
  fun onActivityAvailable(activity: Activity) {
    if (closed || activity !is ActivityResultRegistryOwner) return
    val request = pending ?: restore(activity.applicationContext)?.also { pending = it } ?: return
    if (request.activity != null || request.transfer != null) return
    request.activity = activity
    request.recreated = true
    try { register(activity, request) } catch (_: Exception) { clear(request) }
  }

  fun onActivityDestroyed(activity: Activity) {
    val request = pending?.takeIf { it.activity === activity } ?: return
    // A finishing Activity is gone for good; one being recreated will come back for the result.
    if (activity.isFinishing && request.transfer == null) {
      finish(request) { resolve() }
      return
    }
    // Its result registry dies with it; the saved state carries the launch to the next one.
    request.activity = null
    request.launcher = null
  }

  fun close() {
    closed = true
    main.post { pending?.let(::clear) }
  }

  private fun owns(request: Pending) = !closed && pending === request

  private fun finish(request: Pending, complete: Promise.() -> Unit) {
    if (!owns(request)) return
    val attached = request.transfer != null
    clear(request)
    request.promise?.complete()
    if (attached && (request.recreated || request.promise == null)) onAttached(request.threadId)
  }

  private fun clear(request: Pending) {
    pending = null
    request.launcher?.unregister()
    request.transfer?.cancel()
    request.context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().clear().apply()
    // An entry the camera never filled would show as a broken photo in the gallery.
    request.camera?.takeIf { !request.captured && !filled(request.context, it) }?.let { uri ->
      try { request.context.contentResolver.delete(uri, null, null) } catch (_: Exception) { }
    }
  }

  private fun save(request: Pending) {
    request.context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
      .putString("kind", request.kind.name).putString("threadId", request.threadId)
      .putString("expectedRevision", request.expectedRevision).putString("camera", request.camera?.toString())
      .putLong("openedAt", System.currentTimeMillis()).apply()
  }

  private fun restore(context: Context): Pending? {
    val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    val kind = store.getString("kind", null)?.let { name -> Kind.values().firstOrNull { it.name == name } } ?: return null
    val request = Pending(context, null, kind, store.getString("threadId", "").orEmpty(),
      store.getString("expectedRevision", "").orEmpty(), null, camera = store.getString("camera", null)?.let(Uri::parse))
    if (System.currentTimeMillis() - store.getLong("openedAt", 0) > STALE_MS) {
      clear(request)
      return null
    }
    return request
  }

  private fun filled(context: Context, uri: Uri): Boolean = try {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length > 0 } ?: false
  } catch (_: Exception) {
    false
  }

  private fun unavailable(request: Pending) = finish(request) {
    reject("PICKER_UNAVAILABLE", "Android could not open the attachment picker", null)
  }

  private fun unreadable() = CodedException("ATTACHMENT_UNREADABLE", "The selected attachment could not be read", null)
}
