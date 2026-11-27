package com.android.systemui.taku.lockscreen

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import java.util.concurrent.Executor
import java.io.IOException

/**
 * The lock screen's background layer (Figma 979:430 and 979:451), drawn above the wallpaper window:
 *
 * - the lock wallpaper again, blurred 24 dp, under a `rgba(0,0,0,0.48)` → `rgba(0,0,0,0.08)` gradient;
 * - a 176 dp bottom bar blurred a further 12 dp with the "Taku · Edge Refraction" shader
 *   (dispersion 0.4, flare 0.4), ported line for line from the Figma WGSL to AGSL.
 *
 * It is the keyguard root's background drawable, not a child view: the keyguard's burn-in logic moves
 * the large clock down by however far the highest visible child starts above the status-bar inset
 * (`AodBurnInViewModel`: `max(topInset - minViewY, burnIn)`), and a full-screen child at y = 0 would
 * always trigger that.
 *
 * The wallpaper is static, so its blurred copy is a set of render nodes rebuilt only when the wallpaper
 * or the bounds change. A live wallpaper has no bitmap: then only the gradient is drawn over it.
 *
 * [style] selects the layer: the lock screen's page ([Style.LOCK_SCREEN]) or the bouncer's lighter
 * one ([Style.BOUNCER]: the same wallpaper, less blur, a flat dark overlay and no bottom bar).
 */
class TakuLockscreenBackgroundDrawable(
    context: Context,
    private val userId: () -> Int,
    private val background: Executor,
    private val style: Style = Style.LOCK_SCREEN,
) : Drawable() {
    /** Blur radius, the overlay gradient (top to bottom) and whether the refracted bottom bar is drawn. */
    data class Style(val blurDp: Float, val overlayTop: Int, val overlayBottom: Int, val bar: Boolean) {
        companion object {
            val LOCK_SCREEN = Style(PAGE_BLUR_DP, OVERLAY_TOP, OVERLAY_BOTTOM, bar = true)
            /** The unlock screen: the wallpaper stays recognisable under a subtle dark overlay. */
            val BOUNCER = Style(12f, Color.argb(0.36f, 0f, 0f, 0f), Color.argb(0.36f, 0f, 0f, 0f), bar = false)
        }
    }
    private val metrics = context.resources.displayMetrics
    private val wallpaperManager: WallpaperManager? = context.getSystemService(WallpaperManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gradientPaint = Paint()

    /** Blurred wallpaper. */
    private val blurred = RenderNode("TakuLockscreenBlur")
    /** Blurred wallpaper plus the gradient: the whole page. */
    private val page = RenderNode("TakuLockscreenPage")
    /** The bottom slice of [page], blurred again and refracted. */
    private val bar = RenderNode("TakuLockscreenBar")

    private var wallpaper: Bitmap? = null
    private var dirty = true
    private var generation = 0
    private var attached = false
    private var opacity = 255

    private val colorsListener = WallpaperManager.OnColorsChangedListener { _: WallpaperColors?, which: Int ->
        if (which and (WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM) != 0) reload()
    }

    /** Starts following the wallpaper; call when the keyguard root is bound. */
    fun attach() {
        if (attached) return
        attached = true
        wallpaperManager?.addOnColorsChangedListener(colorsListener, main)
        reload()
    }

    fun detach() {
        if (!attached) return
        attached = false
        wallpaperManager?.removeOnColorsChangedListener(colorsListener)
        generation++
        wallpaper = null
        bar.discardDisplayList()
        page.discardDisplayList()
        blurred.discardDisplayList()
        dirty = true
    }

    override fun onBoundsChange(bounds: Rect) {
        gradientPaint.shader = LinearGradient(0f, 0f, 0f, bounds.height().toFloat(), style.overlayTop, style.overlayBottom,
            Shader.TileMode.CLAMP)
        dirty = true
    }

    /** Reads the lock wallpaper (falling back to the home wallpaper) off the main thread. */
    fun reload() {
        val manager = wallpaperManager ?: return
        val ticket = ++generation
        val user = userId()
        background.execute {
            val bitmap = try {
                loadWallpaper(manager, user)
            } catch (error: IOException) {
                Log.w(TAG, "Lock wallpaper could not be decoded", error)
                null
            } catch (error: RuntimeException) {
                Log.w(TAG, "Lock wallpaper unavailable", error)
                null
            }
            main.post {
                if (ticket != generation) return@post
                wallpaper = bitmap
                dirty = true
                invalidateSelf()
            }
        }
    }

    private fun loadWallpaper(manager: WallpaperManager, user: Int): Bitmap? {
        // WallpaperManager's bitmap cache is shared with ImageWallpaper. Its renderer can recycle
        // that bitmap while our RenderNode still references it, aborting SystemUI in native code.
        // Decode our own pixels from a descriptor instead; never recycle them while RenderThread
        // may still hold a display list from the previous frame. Normal GC owns their lifetime.
        for (which in intArrayOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM)) {
            // A separate live lock wallpaper must not be covered by the static home wallpaper.
            if (manager.getWallpaperInfo(which, user) != null) return null
            manager.getWallpaperFile(which, user)?.use { file ->
                return BitmapFactory.decodeFileDescriptor(file.fileDescriptor)
            }
        }
        // No custom static file: this API decodes a fresh default drawable, outside the shared cache.
        return (manager.builtInDrawable as? BitmapDrawable)?.bitmap
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || opacity == 0) return
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        if (!canvas.isHardwareAccelerated) {
            gradientPaint.alpha = opacity
            canvas.drawRect(0f, 0f, bounds.width().toFloat(), bounds.height().toFloat(), gradientPaint)
        } else {
            if (dirty) rebuild()
            val alpha = opacity / 255f
            page.setAlpha(alpha)
            bar.setAlpha(alpha)
            canvas.drawRenderNode(page)
            if (style.bar) {
                canvas.translate(0f, (bounds.height() - barHeight()).toFloat())
                canvas.drawRenderNode(bar)
            }
        }
        canvas.restore()
    }

    /** The always-on display stays black: the section sets 1 − doze amount. */
    override fun setAlpha(alpha: Int) {
        if (opacity == alpha) return
        opacity = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = opacity

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun rebuild() {
        dirty = false
        val w = bounds.width()
        val h = bounds.height()

        blurred.setPosition(0, 0, w, h)
        blurred.setRenderEffect(RenderEffect.createBlurEffect(dp(style.blurDp), dp(style.blurDp), Shader.TileMode.CLAMP))
        blurred.beginRecording().also { recording ->
            wallpaper?.let { recording.drawBitmap(it, centerCrop(it, w, h), bitmapPaint) }
        }
        blurred.endRecording()

        page.setPosition(0, 0, w, h)
        page.beginRecording().also { recording ->
            recording.drawRenderNode(blurred)
            gradientPaint.alpha = 255
            recording.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradientPaint)
        }
        page.endRecording()

        if (!style.bar) return
        val barHeight = barHeight()
        bar.setPosition(0, 0, w, barHeight)
        bar.setRenderEffect(barEffect(w, barHeight, dp(BAR_BLUR_DP)))
        bar.beginRecording().also { recording ->
            recording.translate(0f, -(h - barHeight).toFloat())
            recording.drawRenderNode(page)
        }
        bar.endRecording()
    }

    /**
     * The page as last recorded (blurred wallpaper and overlay), for glass controls that refract what
     * is behind them; null before the first hardware draw or without a static wallpaper.
     */
    fun backdrop(): RenderNode? = page.takeIf { !dirty && wallpaper != null && page.hasDisplayList() }

    private fun centerCrop(bitmap: Bitmap, width: Int, height: Int): Matrix {
        val scale = maxOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
        }
    }

    private fun barHeight() = dp(BAR_HEIGHT_DP).toInt().coerceAtMost(bounds.height())

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)

    companion object {
        private const val TAG = "TakuLockscreenBg"
        private val OVERLAY_TOP = Color.argb(0.48f, 0f, 0f, 0f)
        private val OVERLAY_BOTTOM = Color.argb(0.08f, 0f, 0f, 0f)
        const val PAGE_BLUR_DP = 24f
        const val BAR_BLUR_DP = 12f
        const val BAR_HEIGHT_DP = 176f
        const val DISPERSION = 0.4f
        const val FLARE = 0.4f

        /**
         * The bottom bar's effect for a [width] × [height] slice: blur first, then refract the blurred
         * slice (createChainEffect applies the inner effect first). Shared with the notification list,
         * so cards scrolling under the bar get the same treatment as the wallpaper.
         */
        fun barEffect(width: Int, height: Int, blurRadius: Float): RenderEffect {
            val refraction = RuntimeShader(EDGE_REFRACTION).apply {
                setFloatUniform("size", width.toFloat(), height.toFloat())
                setFloatUniform("dispersion", DISPERSION)
                setFloatUniform("flare", FLARE)
            }
            return RenderEffect.createChainEffect(
                RenderEffect.createRuntimeShaderEffect(refraction, "content"),
                RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP),
            )
        }

        /**
         * Figma "Taku · Edge Refraction" (WGSL on a 90°-rotated node) in AGSL. In the rotated node uv.x
         * runs down the bar and uv.y across it; the WGSL's `tint.z` is the node's output height, which
         * is the bar's on-screen width.
         */
        const val EDGE_REFRACTION = """
            uniform shader content;
            uniform float2 size;
            uniform float dispersion;
            uniform float flare;

            float2 toPixels(float2 uv) { return float2((1.0 - uv.y) * size.x, uv.x * size.y); }

            half4 main(float2 p) {
                float2 uv = float2(p.y / size.y, 1.0 - p.x / size.x);
                float t = smoothstep(0.0, 1.0, uv.x);
                float stretch = 1.0 + flare * t * t;
                float2 q = float2(uv.x, 0.5 + (uv.y - 0.5) / stretch);
                float shift = dispersion * t * t / max(size.x, 1.0);
                half4 cr = content.eval(toPixels(clamp(q + float2(0.0, shift), float2(0.0), float2(1.0))));
                half4 cg = content.eval(toPixels(q));
                half4 cb = content.eval(toPixels(clamp(q - float2(0.0, shift), float2(0.0), float2(1.0))));
                half a = max(cr.a, max(cg.a, cb.a));
                return half4(cr.r, cg.g, cb.b, a);
            }
        """
    }
}
