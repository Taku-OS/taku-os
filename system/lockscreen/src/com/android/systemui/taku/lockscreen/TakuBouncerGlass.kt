package com.android.systemui.taku.lockscreen

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Liquid-glass controls for the unlock screen (bouncer): each shape shows the Taku wallpaper behind it
 * through a lens (blurred, magnified toward the rim, with a little colour dispersion), under a faint
 * white fill and a light rim. The wallpaper comes from the bouncer's own background
 * ([TakuLockscreenBackgroundDrawable.backdrop]), so the glass refracts exactly what the user sees.
 *
 * Without a static wallpaper (a live one) or off the hardware renderer, a shape is the fill and the rim
 * only, which stays legible over the dark overlay.
 */
class TakuGlass(private val backdrop: () -> Backdrop?) {
    /** The recorded page and the view it is drawn in, for positioning the lens. */
    class Backdrop(val page: RenderNode, val host: View)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val lenses = HashMap<Int, Lens>()
    private val hostAt = IntArray(2)
    private val backdropAt = IntArray(2)

    private class Lens(val node: RenderNode, var width: Int = 0, var height: Int = 0)

    /**
     * Draws one glass shape at [rect] (in [view]'s coordinates, which [canvas] uses): a circle when
     * [corner] is null, else a rounded rectangle. [slot] keeps one lens per shape of a drawable.
     */
    fun draw(canvas: Canvas, view: View, rect: RectF, corner: Float?, pressed: Boolean, slot: Int = 0) {
        if (rect.isEmpty) return
        path.rewind()
        if (corner == null) path.addCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) / 2f, Path.Direction.CW)
        else path.addRoundRect(rect, corner, corner, Path.Direction.CW)
        val density = view.resources.displayMetrics.density
        val source = backdrop()
        if (source != null && canvas.isHardwareAccelerated) {
            val w = rect.width().toInt().coerceAtLeast(1)
            val h = rect.height().toInt().coerceAtLeast(1)
            val lens = lenses.getOrPut(slot) { Lens(RenderNode("TakuGlass$slot")) }
            if (lens.width != w || lens.height != h) {
                lens.width = w
                lens.height = h
                lens.node.setPosition(0, 0, w, h)
                lens.node.setRenderEffect(effect(w, h, BLUR_DP * density))
            }
            // Where this shape sits over the backdrop, wherever the bouncer has moved it.
            view.getLocationInWindow(hostAt)
            source.host.getLocationInWindow(backdropAt)
            val x = hostAt[0] - backdropAt[0] + rect.left
            val y = hostAt[1] - backdropAt[1] + rect.top
            lens.node.beginRecording(w, h).also { recording ->
                recording.translate(-x, -y)
                recording.drawRenderNode(source.page)
            }
            lens.node.endRecording()
            canvas.save()
            canvas.clipPath(path)
            canvas.translate(rect.left, rect.top)
            canvas.drawRenderNode(lens.node)
            canvas.restore()
        }
        fill.color = if (pressed) PRESSED_FILL else FILL
        canvas.drawPath(path, fill)
        rim.strokeWidth = RIM_DP * density
        rim.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, RIM_LIGHT, RIM_DARK, Shader.TileMode.CLAMP)
        canvas.drawPath(path, rim)
    }

    /** Blur first, then the lens: createChainEffect applies the inner effect first. */
    private fun effect(width: Int, height: Int, blur: Float): RenderEffect {
        val lens = RuntimeShader(LENS).apply {
            setFloatUniform("size", width.toFloat(), height.toFloat())
            setFloatUniform("strength", LENS_STRENGTH)
            setFloatUniform("dispersion", LENS_DISPERSION)
        }
        return RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(lens, "content"),
            RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP),
        )
    }

    companion object {
        /** Faint white glass; brighter while pressed. */
        val FILL = Color.argb(0.10f, 1f, 1f, 1f)
        val PRESSED_FILL = Color.argb(0.32f, 1f, 1f, 1f)
        /** Rim: light from the top left, fading toward the bottom right. */
        val RIM_LIGHT = Color.argb(0.60f, 1f, 1f, 1f)
        val RIM_DARK = Color.argb(0.14f, 1f, 1f, 1f)
        const val RIM_DP = 1f
        const val BLUR_DP = 3f
        const val LENS_STRENGTH = 0.28f
        const val LENS_DISPERSION = 0.012f

        /**
         * Magnifies toward the rim (the edge samples move inward, as through a thick lens) and splits
         * the channels slightly along the radius there.
         */
        const val LENS = """
            uniform shader content;
            uniform float2 size;
            uniform float strength;
            uniform float dispersion;

            half4 main(float2 p) {
                float2 c = size * 0.5;
                float2 d = (p - c) / c;
                float l = clamp(length(d), 0.0, 1.0);
                float edge = l * l * l;
                float2 q = c + (p - c) * (1.0 - strength * edge);
                float2 dir = l > 0.0 ? normalize(p - c) : float2(0.0);
                float shift = dispersion * min(size.x, size.y) * edge;
                half4 cr = content.eval(q + dir * shift);
                half4 cg = content.eval(q);
                half4 cb = content.eval(q - dir * shift);
                return half4(cr.r, cg.g, cb.b, max(cr.a, max(cg.a, cb.a)));
            }
        """
    }
}

/**
 * One glass shape filling the view's bounds: a circle (PIN keys) or a rounded rectangle with
 * [cornerDp] (the password field). It brightens while the view is pressed.
 */
class TakuGlassDrawable(private val glass: TakuGlass, private val cornerDp: Float? = null) : Drawable() {
    private val rect = RectF()
    private var pressed = false

    override fun draw(canvas: Canvas) {
        val view = callback as? View ?: return
        rect.set(bounds)
        val inset = TakuGlass.RIM_DP * view.resources.displayMetrics.density / 2f
        rect.inset(inset, inset)
        if (cornerDp == null) {
            // The key's circle, centred in its cell.
            val radius = minOf(rect.width(), rect.height()) / 2f
            rect.set(rect.centerX() - radius, rect.centerY() - radius, rect.centerX() + radius, rect.centerY() + radius)
        }
        glass.draw(canvas, view, rect, cornerDp?.let { it * view.resources.displayMetrics.density }, pressed)
    }

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_pressed in state
        if (now == pressed) return false
        pressed = now
        invalidateSelf()
        return true
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The pattern grid's background: a glass circle under each of the 3 × 3 dots, at the same centres the
 * pattern view uses (its padding split into equal squares).
 */
class TakuGlassGridDrawable(private val glass: TakuGlass) : Drawable() {
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val view = callback as? View ?: return
        val density = view.resources.displayMetrics.density
        val square = (view.width - view.paddingLeft - view.paddingRight) / SIZE.toFloat()
        val squareHeight = (view.height - view.paddingTop - view.paddingBottom) / SIZE.toFloat()
        val radius = minOf(square * CELL_FRACTION, squareHeight * CELL_FRACTION, CELL_RADIUS_DP * density)
        if (radius <= 0f) return
        for (row in 0 until SIZE) for (column in 0 until SIZE) {
            val cx = view.paddingLeft + column * square + square / 2f
            val cy = view.paddingTop + row * squareHeight + squareHeight / 2f
            rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
            glass.draw(canvas, view, rect, null, pressed = false, slot = row * SIZE + column)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val SIZE = 3
        /** Each circle is at most 64 dp across, and never more than 80 % of its square. */
        const val CELL_RADIUS_DP = 32f
        const val CELL_FRACTION = 0.4f
    }
}
