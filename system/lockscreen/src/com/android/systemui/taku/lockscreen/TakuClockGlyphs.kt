package com.android.systemui.taku.lockscreen

import android.graphics.Path

/** Native counterpart of the designer's clockPath(k): 0 tall, 1 compact. */
internal object TakuClockGlyphs {
    const val CELL_HEIGHT = 320f
    const val COMPACT_HEIGHT = 87.36f
    data class Contour(val compact: FloatArray, val tall: FloatArray)
    data class Glyph(val compactAdvance: Float, val contours: List<Contour>)

    fun advance(char: Char, progress: Float): Float {
        val glyph = TakuClockContours.glyphs[char] ?: return 0f
        val tall = when (char) { ':' -> 24f; '3' -> 76f; else -> 74f }
        return tall + (glyph.compactAdvance - tall) * progress
    }

    fun width(text: String, progress: Float = 0f): Float =
        if (text.isEmpty()) 0f else text.sumOf { advance(it, progress).toDouble() }.toFloat() +
            (-6f + 9.6f * progress)

    /** Reuses one Path; no SVG parser or font lookup on animation frames. */
    fun path(char: Char, progress: Float, out: Path) {
        out.rewind()
        out.fillType = Path.FillType.EVEN_ODD
        val glyph = TakuClockContours.glyphs[char] ?: return
        for (contour in glyph.contours) {
            val a = contour.compact
            val b = contour.tall
            fun at(i: Int) = b[i] + (a[i] - b[i]) * progress
            out.moveTo(at(0), at(1))
            val count = a.size / 6
            for (segment in 0 until count) {
                val offset = 2 + segment * 6
                val end = if (segment == count - 1) 0 else offset + 4
                out.cubicTo(at(offset), at(offset+1), at(offset+2), at(offset+3), at(end), at(end+1))
            }
            out.close()
        }
    }
}
