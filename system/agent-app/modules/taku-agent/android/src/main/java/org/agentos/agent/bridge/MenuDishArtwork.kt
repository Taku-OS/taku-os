package org.agentos.agent.bridge

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.json.JSONObject

internal class MenuDishArtwork(private val directory: File) {
  data class Request(val name: String, val ingredients: String)

  fun request(dish: JSONObject): Request {
    val evidence = dish.optJSONObject("evidence")
    val ingredients = listOf(
      dish.optString("description"),
      evidence?.optJSONObject("ingredients")?.optString("en"),
      evidence?.optJSONObject("facts")?.optString("en"),
    ).filterNotNull().filter(String::isNotBlank).distinct().joinToString(". ")
    return Request(dish.getString("name").trim().take(160), ingredients.take(700))
  }

  fun cached(request: Request): File? = file(request).takeIf { it.isFile }

  fun generate(resolver: ContentResolver, request: Request): File {
    cached(request)?.let { return it }
    if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
      throw IOException("Unable to create menu artwork cache")
    }
    val uri = Uri.Builder().scheme("content").authority(TakuAgentModule.GATEWAY_AUTHORITY)
      .appendPath("menu-dish-artwork")
      .appendQueryParameter("name", request.name)
      .appendQueryParameter("ingredients", request.ingredients)
      .build()
    val temporary = File.createTempFile("artwork-", ".tmp", directory)
    try {
      val descriptor = resolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Menu artwork stream unavailable")
      ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
        temporary.outputStream().use { output ->
          val buffer = ByteArray(16 * 1024)
          val readable = StructPollfd().apply { fd = descriptor.fileDescriptor; events = OsConstants.POLLIN.toShort() }
          var remaining = 2 * 1024 * 1024
          val deadline = SystemClock.elapsedRealtime() + 240_000
          while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (SystemClock.elapsedRealtime() >= deadline) throw IOException("Menu artwork timed out")
            if (Os.poll(arrayOf(readable), 300) == 0) continue
            val count = input.read(buffer)
            if (count == -1) break
            remaining -= count
            if (remaining < 0) throw IOException("Menu artwork stream too large")
            output.write(buffer, 0, count)
          }
          descriptor.checkError()
        }
      }
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeFile(temporary.path, bounds)
      if (bounds.outMimeType != "image/png" || bounds.outWidth !in 256..2048 || bounds.outWidth != bounds.outHeight) {
        throw IOException("Invalid menu artwork")
      }
      val target = file(request)
      if (!temporary.renameTo(target)) throw IOException("Unable to store menu artwork")
      return target
    } finally {
      temporary.delete()
    }
  }

  fun prune() {
    val expiry = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    directory.listFiles { file -> file.extension == "png" }?.sortedByDescending(File::lastModified)
      ?.forEachIndexed { index, file ->
        if (index >= 96 || file.lastModified() < expiry) file.delete()
      }
  }

  private fun file(request: Request): File {
    val key = MessageDigest.getInstance("SHA-256")
      .digest("plate-v1\u0000${request.name}\u0000${request.ingredients}".toByteArray())
      .joinToString("") { "%02x".format(it) }
    return File(directory, "$key.png")
  }
}
