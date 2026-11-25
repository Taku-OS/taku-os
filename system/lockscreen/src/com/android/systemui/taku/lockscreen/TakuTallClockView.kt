package com.android.systemui.taku.lockscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View

/**
 * The locked-state clock (Figma 979:470): "HH:MM" in [TakuClockGlyphs], 320 dp tall, white at 92 %.
 * The glyphs scale with the view's height; the width follows the time shown, centred.
 */
class TakuTallClockView(context: Context) : View(context) {
    private val glyphHeightPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TakuClockGlyphs.CELL_HEIGHT,
        resources.displayMetrics)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = TakuClockGlyphs.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** The time to draw, digits and ':' only (e.g. "09:30" or "9:30"). */
    var time: String = ""
        set(value) {
            if (field == value) return
            val resize = TakuClockGlyphs.width(field) != TakuClockGlyphs.width(value)
            field = value
            contentDescription = value
            if (resize) requestLayout()
            invalidate()
        }

    init { setColor(Color.argb(0.92f, 1f, 1f, 1f)) }

    fun setColor(color: Int) {
        fill.color = color
        stroke.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val scale = glyphHeightPx / TakuClockGlyphs.CELL_HEIGHT
        val width = (TakuClockGlyphs.width(time) * scale).toInt() + paddingLeft + paddingRight
        val height = glyphHeightPx.toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val available = (height - paddingTop - paddingBottom).toFloat()
        if (available <= 0f || time.isEmpty()) return
        val scale = available / TakuClockGlyphs.CELL_HEIGHT
        val contentWidth = TakuClockGlyphs.width(time) * scale
        canvas.save()
        canvas.translate(paddingLeft + (width - paddingLeft - paddingRight - contentWidth) / 2f, paddingTop.toFloat())
        canvas.scale(scale, scale)
        var x = 0f
        var previousDigit = false
        for (character in time) {
            if (character == ':') {
                for (y in TakuClockGlyphs.COLON_CENTERS_Y) {
                    canvas.drawCircle(x + TakuClockGlyphs.COLON_CENTER_X, y, TakuClockGlyphs.COLON_RADIUS, fill)
                }
                x += TakuClockGlyphs.COLON_GAP
                previousDigit = false
                continue
            }
            if (previousDigit) x += TakuClockGlyphs.DIGIT_GAP
            TakuClockGlyphs.glyph(character)?.let { glyph ->
                canvas.save()
                canvas.translate(x, 0f)
                canvas.drawPath(glyph.path, if (glyph.filled) fill else stroke)
                canvas.restore()
            }
            x += TakuClockGlyphs.CELL_WIDTH
            previousDigit = true
        }
        canvas.restore()
    }
}
