package org.agentos.agent.bridge

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
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
import androidx.core.content.FileProvider
import expo.modules.interfaces.permissions.Permissions
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.CodedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class ComposerCameraProvider : FileProvider()

internal class ComposerPicker(
  private val main: Handler,
  private val background: CoroutineScope,
  private val callGateway: (ContentResolver, String, String, Bundle) -> String,
) {
  companion object {
    private const val CAMERA_CACHE = "composer-camera"
    private const val CAMERA_GRANT = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    private var cachePrepared = false

    fun prepareCameraCache(context: Context) {
      if (cachePrepared) return
      cachePrepared = true
      File(context.cacheDir, CAMERA_CACHE).listFiles()?.forEach { it.delete() }
    }
  }

  private enum class Kind(val mime: String) { FILE("*/*"), IMAGE("image/*"), CAMERA("image/jpeg") }
  private data class CameraOutput(val file: File, val uri: Uri)
  private class Pending(
    val activity: Activity,
    val kind: Kind,
    val threadId: String,
    val expectedRevision: String,
    val promise: Promise,
    var launcher: ActivityResultLauncher<Intent>? = null,
    var camera: CameraOutput? = null,
    var transfer: CancellationSignal? = null,
  )

  private var pending: Pending? = null
  @Volatile private var closed = false

  fun pick(
    activity: Activity?, kind: String, threadId: String, expectedRevision: String,
    permissions: Permissions?, promise: Promise,
  ) {
    if (closed || activity == null) {
      promise.reject("PICKER_UNAVAILABLE", "Open the conversation before attaching a file", null)
      return
    }
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
    val request = Pending(activity, source, threadId, expectedRevision, promise)
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
            if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
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
    if (activity.isDestroyed || activity.isFinishing) {
      finish(request) { resolve() }
      return
    }
    try {
      val intent = if (request.kind == Kind.CAMERA) {
        prepareCameraCache(activity)
        val directory = File(activity.cacheDir, CAMERA_CACHE).apply { mkdirs() }
        val file = File.createTempFile("photo-", ".jpg", directory)
        val uri = try {
          FileProvider.getUriForFile(activity, "${activity.packageName}.composer-camera", file)
        } catch (error: Exception) {
          file.delete()
          throw error
        }
        request.camera = CameraOutput(file, uri)
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
          putExtra(MediaStore.EXTRA_OUTPUT, uri)
          clipData = ClipData.newRawUri("Photo", uri)
          addFlags(CAMERA_GRANT)
        }
      } else {
        Intent(Intent.ACTION_OPEN_DOCUMENT).setType(request.kind.mime).addCategory(Intent.CATEGORY_OPENABLE)
      }
      val registry = (activity as ActivityResultRegistryOwner).activityResultRegistry
      val launcher = registry.register("taku-composer-${UUID.randomUUID()}", ActivityResultContracts.StartActivityForResult()) { result ->
        // Unregister only after the registry has marked this launch complete.
        main.post { onActivityResult(request, result) }
      }
      request.launcher = launcher
      launcher.launch(intent)
    } catch (_: Exception) {
      unavailable(request)
    }
  }

  private fun onActivityResult(request: Pending, result: ActivityResult) {
    if (!owns(request) || request.transfer != null) return
    val activity = request.activity
    if (result.resultCode == Activity.RESULT_CANCELED || activity.isDestroyed || activity.isFinishing) {
      finish(request) { resolve() }
      return
    }
    val uri = request.camera?.uri ?: result.data?.data ?: result.data?.clipData?.let { clip ->
      if (clip.itemCount == 1) clip.getItemAt(0).uri else null
    }
    if (result.resultCode != Activity.RESULT_OK || uri?.scheme != ContentResolver.SCHEME_CONTENT) {
      finish(request) { reject("ATTACHMENT_UNREADABLE", "The selected attachment could not be read", null) }
      return
    }
    val signal = CancellationSignal()
    request.transfer = signal
    background.launch {
      try {
        val envelope = attach(activity.contentResolver, request, uri, signal)
        main.post { finish(request) { resolve(envelope) } }
      } catch (error: CodedException) {
        main.post { finish(request) { reject(error) } }
      } catch (_: Exception) {
        main.post { finish(request) { reject("GATEWAY_UNAVAILABLE", "Taku Agent gateway is unavailable", null) } }
      }
    }
  }

  private fun attach(resolver: ContentResolver, request: Pending, uri: Uri, signal: CancellationSignal): String {
    val descriptor = try {
      resolver.openFileDescriptor(uri, "r", signal) ?: throw IOException()
    } catch (_: Exception) {
      throw unreadable()
    }
    descriptor.use {
      val input = try {
        val flags = Os.fcntlInt(descriptor.fileDescriptor, OsConstants.F_GETFL, 0)
        if ((flags and OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) throw IOException()
        val camera = request.kind == Kind.CAMERA
        val name = if (camera) "Photo.jpg" else resolver.query(
          uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, signal,
        )?.use { cursor ->
          val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }?.takeIf { it.isNotBlank() } ?: "Attachment"
        val mime = if (camera) request.kind.mime else resolver.getType(uri) ?: "application/octet-stream"
        JSONObject().put("threadId", request.threadId).put("expectedRevision", request.expectedRevision)
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

  fun onActivityDestroyed(activity: Activity) {
    pending?.takeIf { it.activity === activity }?.let { finish(it) { resolve() } }
  }

  fun close() {
    closed = true
    main.post { pending?.let(::clear) }
  }

  private fun owns(request: Pending) = !closed && pending === request

  private fun finish(request: Pending, complete: Promise.() -> Unit) {
    if (!owns(request)) return
    clear(request)
    request.promise.complete()
  }

  private fun clear(request: Pending) {
    pending = null
    request.launcher?.unregister()
    request.transfer?.cancel()
    request.camera?.let {
      try {
        request.activity.revokeUriPermission(it.uri, CAMERA_GRANT)
      } catch (_: SecurityException) {
      } finally {
        it.file.delete()
      }
    }
  }

  private fun unavailable(request: Pending) = finish(request) {
    reject("PICKER_UNAVAILABLE", "Android could not open the attachment picker", null)
  }

  private fun unreadable() = CodedException("ATTACHMENT_UNREADABLE", "The selected attachment could not be read", null)
}
