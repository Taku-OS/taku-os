package com.android.systemui.taku.lockscreen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Both clock endpoints and every in-between frame use the same native Canvas view. */
class TakuTallClockView(context: Context, private val compact: Boolean = false) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.WHITE }
    private val path = Path()
    var progress: Float = if (compact) 1f else 0f
        set(value) { field = value; invalidate() }

    var time: String = ""
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value
            requestLayout()
            invalidate()
        }

    fun setColor(color: Int) { fill.color = color; invalidate() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Reserve both endpoints' width so narrow 12-hour times don't clip mid-morph.
        val width = maxOf(TakuClockGlyphs.width(time, 0f), TakuClockGlyphs.width(time, 1f))
        val height = if (compact) TakuClockGlyphs.COMPACT_HEIGHT else TakuClockGlyphs.CELL_HEIGHT
        setMeasuredDimension(resolveSize((width*density).toInt()+paddingLeft+paddingRight, widthMeasureSpec),
            resolveSize((height*density).toInt()+paddingTop+paddingBottom, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (time.isEmpty() || width <= 0) return
        val reservedWidth = maxOf(TakuClockGlyphs.width(time, 0f), TakuClockGlyphs.width(time, 1f))
        // Uniform fit for narrow screens. Never use scaleY to create the elongated numbers.
        val scale = minOf(density, (width-paddingLeft-paddingRight)/reservedWidth)
        if (scale <= 0f) return
        val contentWidth = TakuClockGlyphs.width(time, progress)
        fill.alpha = ((0.5888f + (0.64f-0.5888f)*progress.coerceIn(0f,1f))*255).toInt()
        canvas.save()
        canvas.translate(paddingLeft+(width-paddingLeft-paddingRight-contentWidth*scale)/2f,
            paddingTop.toFloat() + 1.68f*progress*scale)
        canvas.scale(scale, scale)
        for (character in time) {
            TakuClockGlyphs.path(character, progress, path)
            canvas.drawPath(path, fill)
            canvas.translate(TakuClockGlyphs.advance(character, progress), 0f)
        }
        canvas.restore()
    }
}
