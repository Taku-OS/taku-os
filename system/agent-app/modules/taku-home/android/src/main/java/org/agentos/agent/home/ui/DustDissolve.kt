package org.agentos.agent.home.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.geometry.Rect
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 0-4 "remove card" dissolve: the card snapshot erodes from its edges and through
 * low-frequency noise holes into 1–2 dp grains that scatter outward with an upward bias
 * while the whole cloud drifts ~27 dp up and spreads ~15 %. One drawVertices call per frame.
 */
class DustDissolve(private val bitmap: Bitmap, val bounds: Rect, density: Float) {
  private val cell = 2.5f * density
  private val cols = (bitmap.width / cell).toInt().coerceAtLeast(1)
  private val rows = (bitmap.height / cell).toInt().coerceAtLeast(1)
  private val count = cols * rows
  private val start = FloatArray(count)
  private val vx = FloatArray(count)
  private val vy = FloatArray(count)
  // Non-indexed triangles: 6 vertices (12 floats) per grain, so any card size works.
  private val verts = FloatArray(count * 12)
  private val texs = FloatArray(count * 12)
  private val origin = FloatArray(count * 2)
  private val colors = IntArray(count * 6)
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
  private val drift = 27f * density
  private val scale = bitmap.width / bounds.width

  init {
    val maxEdge = min(bitmap.width, bitmap.height) * 0.5f
    val noiseScale = 1f / (90f * density)
    var i = 0
    for (r in 0 until rows) for (c in 0 until cols) {
      val x = c * cell; val y = r * cell
      val edge = min(min(x, bitmap.width - x), min(y, bitmap.height - y)) / maxEdge
      val n = 0.55f * valueNoise(x * noiseScale, y * noiseScale) + 0.25f * hash(c, r) + 0.35f * sqrt(edge.coerceIn(0f, 1f))
      start[i] = (n * 0.62f - 0.08f).coerceIn(0f, 0.7f)
      val angle = (hash(r * 7 + 3, c * 13 + 1) * 6.2832f)
      val speed = (40f + 80f * hash(c * 3 + 11, r * 5 + 17)) * density
      val outward = if (x < bitmap.width / 2f) -1f else 1f
      vx[i] = (cos(angle) * 0.7f + outward * 0.3f) * speed
      vy[i] = (sin(angle) * 0.6f - 0.55f) * speed
      origin[i * 2] = x; origin[i * 2 + 1] = y
      quad(texs, i * 12, x, y, cell)
      i++
    }
  }

  private fun quad(a: FloatArray, o: Int, x: Float, y: Float, s: Float) {
    a[o] = x; a[o + 1] = y; a[o + 2] = x + s; a[o + 3] = y; a[o + 4] = x + s; a[o + 5] = y + s
    a[o + 6] = x; a[o + 7] = y; a[o + 8] = x + s; a[o + 9] = y + s; a[o + 10] = x; a[o + 11] = y + s
  }

  /** Draws the dissolve at [progress] ∈ [0, 1] in the card's own coordinates (bitmap pixels scaled to bounds). */
  fun draw(canvas: Canvas, progress: Float) {
    val p = progress.coerceIn(0f, 1f)
    val lift = drift * easeOut(p)
    val spread = 1f + 0.15f * easeOut(p)
    val cx = bitmap.width / 2f; val cy = bitmap.height / 2f
    var visible = 0
    for (i in 0 until count) {
      val local = ((p - start[i]) / (1f - start[i])).coerceIn(0f, 1f)
      val alpha = if (p < start[i]) 1f else (1f - smooth(local * 1.35f)).coerceIn(0f, 1f)
      val grain = if (p < start[i]) 1f else 0.55f + 0.45f * (1f - local)
      val dx = vx[i] * local * scale; val dy = vy[i] * local * scale
      val x0 = cx + (origin[i * 2] - cx) * spread + dx
      val y0 = cy + (origin[i * 2 + 1] - cy) * spread + dy - lift * scale
      quad(verts, i * 12, x0, y0, cell * grain)
      val a = (alpha * 255).toInt()
      val argb = (a shl 24) or (a shl 16) or (a shl 8) or a
      colors.fill(argb, i * 6, i * 6 + 6)
      if (a > 0) visible++
    }
    if (visible == 0) return
    canvas.save()
    canvas.translate(bounds.left, bounds.top)
    canvas.scale(1f / scale, 1f / scale)
    canvas.drawVertices(Canvas.VertexMode.TRIANGLES, verts.size, verts, 0, texs, 0, colors, 0, null, 0, 0, paint)
    canvas.restore()
  }

  private fun easeOut(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
  private fun smooth(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

  private fun hash(x: Int, y: Int): Float {
    var h = x * 374761393 + y * 668265263
    h = (h xor (h ushr 13)) * 1274126177
    return ((h xor (h ushr 16)) and 0xFFFFFF) / 16777215f
  }

  private fun valueNoise(x: Float, y: Float): Float {
    val xi = floor(x).toInt(); val yi = floor(y).toInt()
    val fx = x - xi; val fy = y - yi
    val u = fx * fx * (3 - 2 * fx); val v = fy * fy * (3 - 2 * fy)
    val a = hash(xi, yi); val b = hash(xi + 1, yi); val c = hash(xi, yi + 1); val d = hash(xi + 1, yi + 1)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
  }
}
