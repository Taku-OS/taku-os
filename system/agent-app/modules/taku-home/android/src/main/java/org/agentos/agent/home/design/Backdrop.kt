package org.agentos.agent.home.design

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The home wallpaper, drawn inside the app (not the system wallpaper window) so every
 * glass surface can sample it. [blurred] is pre-blurred once with Backdrop Blur/lg
 * (σ 16 dp): cards and pills sit directly on the wallpaper, so their backdrop blur is that
 * bitmap at their screen position.
 */
@Immutable
class Backdrop(val sharp: ImageBitmap, val blurred: ImageBitmap, val screen: IntSize) {
  /** Draws the blurred wallpaper under an element whose top-left is at [origin] on screen. */
  fun DrawScope.drawBlurredAt(origin: Offset, size: Size = this.size) {
    val sx = blurred.width / screen.width.toFloat()
    val sy = blurred.height / screen.height.toFloat()
    drawImage(
      blurred,
      srcOffset = IntOffset((origin.x * sx).roundToInt().coerceIn(0, blurred.width - 1), (origin.y * sy).roundToInt().coerceIn(0, blurred.height - 1)),
      srcSize = IntSize((size.width * sx).roundToInt().coerceAtLeast(1), (size.height * sy).roundToInt().coerceAtLeast(1)),
      dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
    )
  }

  companion object {
    /** Loads the bundled Taku wallpaper, centre-cropped to the screen (Figma FILL). */
    fun load(context: Context, screen: IntSize, blurSigmaPx: Float): Backdrop {
      val source = context.assets.open("feed/home/wallpaper.jpg").use { BitmapFactory.decodeStream(it) }
      val target = screen.width.toFloat() / screen.height
      val (cw, ch) = if (source.width.toFloat() / source.height > target) (source.height * target).roundToInt() to source.height
      else source.width to (source.width / target).roundToInt()
      val cropped = Bitmap.createBitmap(source, (source.width - cw) / 2, (source.height - ch) / 2, cw, ch)
      val sharp = Bitmap.createScaledBitmap(cropped, screen.width, screen.height, true)
      // Blur at 1/8 scale: σ scales with it and the result is upsampled smoothly.
      val scale = 8
      val small = Bitmap.createScaledBitmap(sharp, (screen.width / scale).coerceAtLeast(1), (screen.height / scale).coerceAtLeast(1), true)
      val blurred = boxBlur3(small, (blurSigmaPx / scale).coerceAtLeast(0.5f))
      return Backdrop(sharp.asImageBitmap(), blurred.asImageBitmap(), screen)
    }

    /** Three box passes approximate a Gaussian of [sigma]. */
    private fun boxBlur3(src: Bitmap, sigma: Float): Bitmap {
      val w = src.width; val h = src.height
      val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
      val tmp = IntArray(w * h)
      val r = ((kotlin.math.sqrt(12.0 * sigma * sigma / 3 + 1) - 1) / 2).roundToInt().coerceAtLeast(1)
      repeat(3) {
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
      }
      return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
      val lines = if (horizontal) h else w
      val len = if (horizontal) w else h
      val div = 2 * r + 1
      for (line in 0 until lines) {
        fun at(i: Int): Int {
          val c = i.coerceIn(0, len - 1)
          return if (horizontal) src[line * w + c] else src[c * w + line]
        }
        var ra = 0; var rr = 0; var rg = 0; var rb = 0
        for (i in -r..r) { val c = at(i); ra += c ushr 24; rr += (c shr 16) and 255; rg += (c shr 8) and 255; rb += c and 255 }
        for (i in 0 until len) {
          val out = ((ra / div) shl 24) or ((rr / div) shl 16) or ((rg / div) shl 8) or (rb / div)
          if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
          val add = at(i + r + 1); val sub = at(i - r)
          ra += (add ushr 24) - (sub ushr 24); rr += ((add shr 16) and 255) - ((sub shr 16) and 255)
          rg += ((add shr 8) and 255) - ((sub shr 8) and 255); rb += (add and 255) - (sub and 255)
        }
      }
    }
  }
}
