package org.agentos.agent.home.menuscan

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import android.os.SystemClock
import android.util.Log
import org.agentos.agent.home.TakuCallerAccess
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MenuCaptureProvider : ContentProvider() {
  override fun onCreate() = true

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
    val app = context ?: return null
    if (method != "scan") return settings(app, method, arg)
    if (Build.VERSION.SDK_INT < 33 || !isCamera(app) ||
      app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return null
    val callback = extras?.getParcelable("result", ResultReceiver::class.java) ?: return null
    val photo = extras.getParcelable("photo", ParcelFileDescriptor::class.java) ?: return null
    val captureId = MenuCapture.begin(app) ?: run {
      photo.close()
      callback.send(0, null)
      return Bundle.EMPTY
    }
    worker.execute {
      val scan = runCatching { scan(app, captureId, photo) }
        .onFailure { Log.w("MenuCapture", "Could not read this camera capture", it) }
      val review = scan.getOrNull()
      if (review == null) MenuCapture.finishWithoutMenu(captureId, scan.isFailure)
      if (review == null) {
        callback.send(0, null)
      } else {
        val intent = Intent(app, MenuReviewActivity::class.java)
          .setData(Uri.parse("taku-menu-review://capture/$captureId"))
        val reviewActivity = PendingIntent.getActivity(
          app, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        callback.send(1, Bundle().apply { putParcelable("review", reviewActivity) })
      }
    }
    return Bundle.EMPTY
  }

  private fun settings(app: Context, method: String, arg: String?): Bundle {
    fun response(value: JSONObject) = Bundle().apply { putString("result", value.toString()) }
    fun error(code: String) = response(JSONObject().put("ok", false).put("code", code))
    if (method != "status" && method != "setEnabled") return error("UNKNOWN_METHOD")
    if (!TakuCallerAccess.isAllowed(app, Binder.getCallingUid())) return error("PERMISSION_DENIED")
    val input = try { if (arg == null) JSONObject() else JSONObject(arg) }
      catch (_: org.json.JSONException) { return error("INVALID_ARGUMENT") }
    val snapshot = when (method) {
      "status" -> {
        if (input.length() != 0) return error("INVALID_ARGUMENT")
        MenuCapture.status(app)
      }
      else -> {
        val enabled = input.opt("enabled") as? Boolean ?: return error("INVALID_ARGUMENT")
        if (input.length() != 1) return error("INVALID_ARGUMENT")
        MenuCapture.setEnabled(app, enabled) ?: return error("SETTINGS_UNAVAILABLE")
      }
    }
    return response(JSONObject().put("ok", true).put("result", snapshot))
  }

  private fun isCamera(context: Context): Boolean {
    if (callingPackage != CAMERA) return false
    val app = try { context.packageManager.getApplicationInfo(CAMERA, 0) }
      catch (_: PackageManager.NameNotFoundException) { return false }
    return app.uid == Binder.getCallingUid() && app.flags and ApplicationInfo.FLAG_SYSTEM != 0
  }

  @androidx.annotation.RequiresApi(28)
  private fun scan(context: Context, id: String, photo: ParcelFileDescriptor): MenuCapture.Review? {
    if (!MenuCapture.isCurrent(id)) {
      photo.close()
      return null
    }
    val directory = File(context.cacheDir, "camera-menu").apply { mkdirs() }
    val expiry = System.currentTimeMillis() - MenuCapture.REVIEW_MS
    directory.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.delete() }
    val file = try { File.createTempFile("camera-", ".jpg", directory) }
      catch (error: IOException) { photo.close(); throw error }
    var saved = false
    try {
      ParcelFileDescriptor.AutoCloseInputStream(photo).use { input ->
        file.outputStream().use { output ->
          val buffer = ByteArray(32 * 1024)
          var size = 0L
          while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            size += read
            if (size > MAX_PHOTO_BYTES) throw IOException("Camera photo is too large")
            output.write(buffer, 0, read)
          }
        }
      }
      if (!MenuCapture.isCurrent(id)) return null
      val bitmap = decodeMenuPhoto(file, 1_920)
      val menu = try { MenuRecognition.read(bitmap) } finally { bitmap.recycle() }
      if (menu == null) return null
      val result = MenuCapture.Review(id, file, menu, SystemClock.elapsedRealtime())
      saved = MenuCapture.complete(result)
      return result.takeIf { saved }
    } finally {
      if (!saved) file.delete()
    }
  }

  override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
  override fun getType(uri: Uri): String? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

  private companion object {
    const val CAMERA = "org.lineageos.aperture"
    const val MAX_PHOTO_BYTES = 20 * 1024 * 1024
    val worker = Executors.newSingleThreadExecutor()
  }
}

internal object MenuCapture {
  data class Review(val id: String, val photo: File, val scan: MenuScan, val completedAt: Long)

  private var latest = ""
  private var result: Review? = null
  private var onReviewInvalidated: (() -> Unit)? = null
  private var lastScan = "idle"
  private val main = Handler(Looper.getMainLooper())
  val previewExecutor = Executors.newSingleThreadExecutor()
  const val REVIEW_MS = 180_000L

  private fun preferences(context: Context) = context.getSharedPreferences("menu-capture", Context.MODE_PRIVATE)
  private fun enabled(context: Context) = preferences(context).getBoolean("enabled", true)

  @Synchronized fun status(context: Context): JSONObject =
    JSONObject().put("enabled", enabled(context)).put("lastScan", lastScan)

  @Synchronized fun setEnabled(context: Context, value: Boolean): JSONObject? {
    if (value != enabled(context)) {
      if (!preferences(context).edit().putBoolean("enabled", value).commit()) return null
      if (!value && lastScan == "scanning") {
        latest = ""
        lastScan = "cancelled"
      }
    }
    return status(context)
  }

  @Synchronized fun begin(context: Context): String? {
    if (!enabled(context)) return null
    latest = UUID.randomUUID().toString()
    result?.let(::discard)
    lastScan = "scanning"
    return latest
  }

  @Synchronized fun isCurrent(id: String) = id == latest

  @Synchronized fun finishWithoutMenu(id: String, failed: Boolean) {
    if (isCurrent(id)) lastScan = if (failed) "failed" else "no_menu"
  }

  @Synchronized fun complete(review: Review): Boolean {
    if (!isCurrent(review.id)) return false
    result = review
    lastScan = "menu"
    main.postDelayed({ expire(review) }, REVIEW_MS)
    return true
  }

  @Synchronized private fun expire(review: Review) {
    if (result != review) return
    val remaining = REVIEW_MS - (SystemClock.elapsedRealtime() - review.completedAt)
    if (remaining > 0) main.postDelayed({ expire(review) }, remaining)
    else discard(review)
  }

  private fun discard(review: Review) {
    val notify = onReviewInvalidated
    onReviewInvalidated = null
    result = null
    if (notify != null) main.post { notify() }
    previewExecutor.execute { review.photo.delete() }
  }

  @Synchronized fun review(id: String): Review? {
    val active = result?.takeIf { it.id == id } ?: return null
    if (SystemClock.elapsedRealtime() - active.completedAt < REVIEW_MS && active.photo.isFile) return active
    discard(active)
    return null
  }

  @Synchronized fun show(id: String, onInvalidated: () -> Unit): Boolean {
    if (review(id) == null) return false
    onReviewInvalidated = onInvalidated
    return true
  }

  @Synchronized fun hide(onInvalidated: () -> Unit) {
    if (onReviewInvalidated === onInvalidated) onReviewInvalidated = null
  }

  @Synchronized fun currentReview(): Review? = if (onReviewInvalidated != null) review(latest) else null
}

@androidx.annotation.RequiresApi(28)
internal fun decodeMenuPhoto(file: File, maxSide: Int) =
  ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    require(info.mimeType == "image/jpeg") { "The camera selection is not a JPEG photo" }
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    val scale = minOf(1.0, maxSide.toDouble() / maxOf(info.size.width, info.size.height))
    decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
  }
