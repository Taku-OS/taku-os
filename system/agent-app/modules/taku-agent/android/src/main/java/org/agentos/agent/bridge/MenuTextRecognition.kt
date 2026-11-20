package org.agentos.agent.bridge

import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object MenuTextRecognition {
  suspend fun recognize(cacheDirectory: File, photoUri: String): Map<String, Any> = withContext(Dispatchers.IO) {
    val photo = cameraPhoto(cacheDirectory, photoUri)
    val bitmap = BitmapFactory.decodeFile(photo.path) ?: throw IOException("Could not decode the menu photo")
    try {
      // Expo Camera writes the capture orientation into the pixels with its default options.
      val image = InputImage.fromBitmap(bitmap, 0)
      val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).use { recognizer ->
        recognizer.process(image).awaitCompletion()
      }
      mapOf(
        "width" to bitmap.width,
        "height" to bitmap.height,
        "lines" to text.textBlocks.flatMap { block ->
          block.lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            mapOf(
              "text" to line.text,
              "box" to box.toCoordinates(),
              "words" to line.elements.mapNotNull { word ->
                word.boundingBox?.let { mapOf("text" to word.text, "box" to it.toCoordinates()) }
              },
            )
          }
        },
      )
    } finally {
      bitmap.recycle()
    }
  }

  fun cameraPhoto(cacheDirectory: File, photoUri: String): File {
    val uri = Uri.parse(photoUri)
    require(uri.scheme == "file" && uri.authority.isNullOrEmpty()) { "Expected a local camera photo URI" }
    val path = requireNotNull(uri.path) { "Missing camera photo path" }
    val photo = File(path).canonicalFile
    val cameraDirectory = File(cacheDirectory.canonicalFile, "Camera")
    require(photo.parentFile == cameraDirectory) { "Expected a photo from the camera cache" }
    if (!photo.isFile) throw IOException("Menu photo is no longer available")
    return photo
  }

  // The ML task may still read the bitmap after the Expo coroutine is canceled.
  private suspend fun Task<Text>.awaitCompletion(): Text = suspendCoroutine { continuation ->
    addOnCompleteListener { task ->
      val result = if (task.isSuccessful) {
        Result.success(task.result)
      } else {
        Result.failure(task.exception ?: IOException("Menu text recognition was canceled"))
      }
      continuation.resumeWith(result)
    }
  }

  private fun Rect.toCoordinates() = mapOf(
    "left" to left,
    "top" to top,
    "right" to right,
    "bottom" to bottom,
  )
}
