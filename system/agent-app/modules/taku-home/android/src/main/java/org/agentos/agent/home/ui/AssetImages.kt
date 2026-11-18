package org.agentos.agent.home.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bitmaps for native chrome (pins, header). Only bundled `asset:feed/…` raster images. */
object AssetImages {
  private val cache = LruCache<String, ImageBitmap>(24)

  fun cached(ref: String): ImageBitmap? = cache.get(ref)

  fun load(context: Context, ref: String, maxPx: Int): ImageBitmap? {
    cache.get(ref)?.let { return it }
    if (!ref.startsWith("asset:feed/") || ref.contains("..") || ref.endsWith(".svg")) return null
    val path = ref.removePrefix("asset:")
    return try {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
      var sample = 1
      while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
      val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
      bitmap?.asImageBitmap()?.also { cache.put(ref, it) }
    } catch (_: java.io.IOException) {
      null
    }
  }
}

@Composable
fun rememberAssetImage(ref: String?, maxPx: Int = 192): ImageBitmap? {
  val context = LocalContext.current
  var image by remember(ref) { mutableStateOf(ref?.let(AssetImages::cached)) }
  LaunchedEffect(ref) {
    if (ref != null && image == null) image = withContext(Dispatchers.IO) { AssetImages.load(context, ref, maxPx) }
  }
  return image
}
