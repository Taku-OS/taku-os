package com.android.systemui.taku.lockscreen

import android.graphics.Path
import androidx.core.graphics.PathParser

/**
 * The tall lock-screen clock's glyphs (Figma 979:470, "09:30 · uniform 14px strokes").
 *
 * Every digit sits in a 68 × 320 cell drawn with uniform 14 px strokes; digits of a pair are 6 px
 * apart and the colon fills a 30 px gap with two 16 px dots. The design draws 0, 3 and 9 only: their
 * outlines below are the Figma paths, moved to the cell origin. The other digits are placeholders in
 * the same construction (a centreline stroked 14 px wide) until design supplies them.
 */
internal object TakuClockGlyphs {
    const val CELL_WIDTH = 68f
    const val CELL_HEIGHT = 320f
    const val DIGIT_GAP = 6f
    const val COLON_GAP = 30f
    const val STROKE = 14f
    /** Colon dots: 16 px, centred 18 px into the gap, at y 111 and 219 (Figma 979:470). */
    const val COLON_CENTER_X = 18f
    const val COLON_RADIUS = 8f
    val COLON_CENTERS_Y = floatArrayOf(111f, 219f)

    /** Filled outlines from the design. */
    private val outlines = mapOf(
        '0' to "M34 0C44.87 0 53.806 4.033 59.808 11.868C65.588 19.414 68 29.644 68 41V279C68 290.356 65.588 300.586 59.808 308.132C53.806 315.967 44.87 320 34 320C23.13 320 14.194 315.967 8.192 308.132C2.412 300.586 0 290.356 0 279V41C0 29.644 2.412 19.414 8.192 11.868C14.194 4.033 23.13 0 34 0ZM34 14C26.87 14 22.306 16.467 19.308 20.382C16.088 24.586 14 31.356 14 41V279C14 288.644 16.088 295.414 19.308 299.618C22.306 303.533 26.87 306 34 306C41.13 306 45.694 303.533 48.692 299.618C51.912 295.414 54 288.644 54 279V41C54 31.356 51.912 24.586 48.692 20.382C45.694 16.467 41.13 14 34 14Z",
        '3' to "M61 0C63.228 0 65.324 1.061 66.643 2.857C67.961 4.654 68.346 6.971 67.679 9.097L27.318 137.685C38.351 139.359 47.7 144.073 54.696 151.425C63.542 160.72 68 173.572 68 188V279C68 290.356 65.588 300.586 59.808 308.132C53.806 315.967 44.87 320 34 320C23.13 320 14.194 315.967 8.192 308.132C2.412 300.586 0 290.356 0 279V262H14V279C14 288.644 16.088 295.414 19.308 299.618C22.306 303.533 26.87 306 34 306C41.13 306 45.694 303.533 48.692 299.618C51.912 295.414 54 288.644 54 279V188C54 176.429 50.458 167.28 44.554 161.075C38.694 154.918 29.884 151 18 151C15.772 151 13.676 149.939 12.357 148.143C11.039 146.346 10.654 144.029 11.321 141.903L51.466 14H7V0H61Z",
        '9' to "M34 0C44.87 0 53.806 4.033 59.808 11.868C65.588 19.414 68 29.644 68 41V279C68 290.356 65.588 300.586 59.808 308.132C53.806 315.967 44.87 320 34 320C23.13 320 14.194 315.967 8.192 308.132C2.412 300.586 0 290.356 0 279V262H14V279C14 288.644 16.088 295.414 19.308 299.618C22.306 303.533 26.87 306 34 306C41.13 306 45.694 303.533 48.692 299.618C51.912 295.414 54 288.644 54 279V154.885C48.568 158.932 41.78 161 34 161C23.13 161 14.194 156.967 8.192 149.132C2.412 141.586 0 131.356 0 120V41C0 29.644 2.412 19.414 8.192 11.868C14.194 4.033 23.13 0 34 0ZM34 14C26.87 14 22.306 16.467 19.308 20.382C16.088 24.586 14 31.356 14 41V120C14 129.644 16.088 136.414 19.308 140.618C22.306 144.533 26.87 147 34 147C41.13 147 45.694 144.533 48.692 140.618C51.912 136.414 54 129.644 54 120V41C54 31.356 51.912 24.586 48.692 20.382C45.694 16.467 41.13 14 34 14Z",
    )

    /** Placeholder centrelines (stroked [STROKE] px, round caps) for digits the design has not drawn. */
    private val centerlines = mapOf(
        '1' to "M20 34L34 7V313",
        '2' to "M7 41C7 20 18 7 34 7C50 7 61 20 61 41V118C61 152 7 214 7 262V313H61",
        '4' to "M48 7L7 222H61M48 118V313",
        '5' to "M61 7H7V150C18 142 28 140 34 140C50 140 61 153 61 174V279C61 300 50 313 34 313C18 313 7 300 7 279",
        '6' to "M61 41C61 20 50 7 34 7C18 7 7 20 7 41V279C7 300 18 313 34 313C50 313 61 300 61 279V190C61 169 50 156 34 156C18 156 7 169 7 190",
        '7' to "M7 7H61L26 313",
        '8' to "M34 7C18 7 7 20 7 41V121C7 140 18 153 34 153C50 153 61 140 61 121V41C61 20 50 7 34 7ZM34 153C18 153 7 168 7 188V279C7 300 18 313 34 313C50 313 61 300 61 279V188C61 168 50 153 34 153Z",
    )

    private val cache = HashMap<Char, Glyph>()

    /** A digit's path and whether it is filled (design outline) or stroked (placeholder centreline). */
    class Glyph(val path: Path, val filled: Boolean)

    fun glyph(digit: Char): Glyph? = cache.getOrPut(digit) {
        outlines[digit]?.let { return@getOrPut Glyph(PathParser.createPathFromPathData(it), filled = true) }
        val line = centerlines[digit] ?: return null
        Glyph(PathParser.createPathFromPathData(line), filled = false)
    }

    /** Unscaled width of [text] (digits and ':'), in design pixels. */
    fun width(text: String): Float {
        var width = 0f
        var previousDigit = false
        for (character in text) {
            if (character == ':') { width += COLON_GAP; previousDigit = false; continue }
            if (previousDigit) width += DIGIT_GAP
            width += CELL_WIDTH
            previousDigit = true
        }
        return width
    }
}
