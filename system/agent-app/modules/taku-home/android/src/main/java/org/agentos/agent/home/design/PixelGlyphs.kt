package org.agentos.agent.home.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Dot-matrix icons in the style of the design's weather/sleep glyphs (32 dp box).
 * Named glyphs come from the Figma icons (sun, moon) or are drawn on the same 9×9 grid
 * (pitch 3.2, dot r 1.2). Unknown names get a deterministic, mirrored pattern so any card
 * can be pinned with a generated icon.
 */
object PixelGlyphs {
  data class Dot(val x: Float, val y: Float, val r: Float)

  private val sun: List<Dot> = buildList {
    // Core 5×5 disc on a 4-unit grid (r 1.6) and eight rays, transcribed from icon "Sun".
    val core = listOf(12 to 8, 16 to 8, 20 to 8, 8 to 12, 12 to 12, 16 to 12, 20 to 12, 24 to 12, 8 to 16, 12 to 16, 16 to 16,
      20 to 16, 24 to 16, 8 to 20, 12 to 20, 16 to 20, 20 to 20, 24 to 20, 12 to 24, 16 to 24, 20 to 24)
    core.forEach { (x, y) -> add(Dot(x.toFloat(), y.toFloat(), 1.6f)) }
    listOf(16f to 1.6f, 26.4f to 5.6f, 30.4f to 16f, 26.4f to 26.4f, 16f to 30.4f, 5.6f to 26.4f, 1.6f to 16f, 5.6f to 5.6f)
      .forEach { (x, y) -> add(Dot(x, y, 1.6f)) }
  }

  private val grids = mapOf(
    // Transcribed from icon "Moon" (crescent and star).
    "moon" to listOf("..XX..XXX", ".XX....X.", ".XX...XXX", "XXX......", "XXXX.....", "XXXXX...X", ".XXXXXXXX", ".XXXXXXX.", "...XXX..."),
    "car" to listOf(".........", "..XXXXX..", ".X..X..X.", ".X..X..X.", "XXXXXXXXX", "XXXXXXXXX", "X.XXXXX.X", ".X.....X.", "........."),
    "lock" to listOf("...XXX...", "..X...X..", "..X...X..", ".XXXXXXX.", ".XXXXXXX.", ".XXX.XXX.", ".XXX.XXX.", ".XXXXXXX.", ".XXXXXXX."),
    "list" to listOf(".........", "X.XXXXXXX", ".........", "X.XXXXXXX", ".........", "X.XXXXXXX", ".........", "X.XXXXX..", "........."),
    "chat" to listOf(".XXXXXXX.", "XXXXXXXXX", "XX.X.X.XX", "XXXXXXXXX", ".XXXXXXX.", "..XX.....", ".XX......", ".........", "........."),
    "cup" to listOf("..X.X.X..", ".X.X.X...", ".........", "XXXXXXXX.", "XXXXXXX.X", "XXXXXXX.X", ".XXXXXXX.", "..XXXXX..", ".XXXXXXX."),
    "bolt" to listOf(".....XX..", "....XX...", "...XX....", "..XXXXX..", "....XX...", "...XX....", "..XX.....", ".XX......", "........."),
    "music" to listOf("...XXXXXX", "...X....X", "...X....X", "...X....X", "...X....X", ".XXX..XXX", "XXXX.XXXX", ".XX...XX.", "........."),
    "photo" to listOf(".........", "XXXXXXXXX", "X.......X", "X.XX....X", "X.XX..X.X", "X....XXXX", "X..XXXXXX", "XXXXXXXXX", "........."),
    "pin" to listOf("..XXXXX..", ".XXXXXXX.", "XXX...XXX", "XXX...XXX", ".XXXXXXX.", "..XXXXX..", "...XXX...", "....X....", "........."),
    "home" to listOf("....X....", "...XXX...", "..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", ".XX...XX.", ".XX.X.XX.", ".XX.X.XX.", ".XXXXXXX."),
  )

  private val aliases = mapOf("checklist" to "list", "tasks" to "list", "coffee" to "cup", "message" to "chat", "location" to "pin")

  fun dots(glyph: String): List<Dot> {
    if (glyph == "sun") return sun
    val rows = grids[aliases[glyph] ?: glyph] ?: generated(glyph)
    return buildList {
      rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (c == 'X') add(Dot(2.8f + 3.2f * x, 2.8f + 3.2f * y, 1.2f)) } }
    }
  }

  /** Mirrored 9×9 pattern seeded by the glyph name (an identicon-like fallback). */
  private fun generated(seed: String): List<String> {
    var h = seed.fold(0x811C9DC5.toInt()) { acc, c -> (acc xor c.code) * 0x01000193 }
    return List(9) { y ->
      val half = CharArray(5) { x ->
        h = h * 1103515245 + 12345
        val centre = 2 - kotlin.math.abs(4 - y) / 2 - x / 3
        if (((h ushr 16) and 7) < 3 + centre.coerceAtLeast(0)) 'X' else '.'
      }
      String(half) + String(half.copyOfRange(0, 4).reversedArray())
    }
  }
}

@Composable
fun PixelGlyph(glyph: String, modifier: Modifier = Modifier, size: Dp = 32.dp, color: Color = TakuTheme.colors.icon) {
  val dots = remember(glyph) { PixelGlyphs.dots(glyph) }
  Canvas(modifier.size(size)) {
    val s = this.size.width / 32f
    for (d in dots) drawCircle(color, radius = d.r * s, center = Offset(d.x * s, d.y * s))
  }
}
