package com.android.systemui.taku.lockscreen

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import java.util.concurrent.Executor

/**
 * The lock screen's background layer (Figma 979:430 and 979:451), drawn above the wallpaper window:
 *
 * - the lock wallpaper again, blurred 24 dp, under a `rgba(0,0,0,0.48)` → `rgba(0,0,0,0.08)` gradient;
 * - a 176 dp bottom bar blurred a further 12 dp with the "Taku · Edge Refraction" shader
 *   (dispersion 0.4, flare 0.4), ported line for line from the Figma WGSL to AGSL.
 *
 * The wallpaper is static, so its blurred copy is a set of render nodes rebuilt only when the wallpaper
 * or the view size changes. A live wallpaper has no bitmap: then only the gradient is drawn over it.
 */
class TakuLockscreenBackgroundView(
    context: Context,
    private val userId: () -> Int,
    private val background: Executor,
) : View(context) {
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

    private val colorsListener = WallpaperManager.OnColorsChangedListener { _: WallpaperColors?, which: Int ->
        if (which and (WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM) != 0) reload()
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wallpaperManager?.addOnColorsChangedListener(colorsListener, main)
        reload()
    }

    override fun onDetachedFromWindow() {
        wallpaperManager?.removeOnColorsChangedListener(colorsListener)
        generation++
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradientPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), OVERLAY_TOP, OVERLAY_BOTTOM, Shader.TileMode.CLAMP)
        dirty = true
    }

    /** Reads the lock wallpaper (falling back to the home wallpaper) off the main thread. */
    fun reload() {
        val manager = wallpaperManager ?: return
        val ticket = ++generation
        val user = userId()
        background.execute {
            val bitmap = try {
                manager.getBitmapAsUser(user, false, WallpaperManager.FLAG_LOCK)
                    ?: manager.getBitmapAsUser(user, false, WallpaperManager.FLAG_SYSTEM)
            } catch (error: RuntimeException) {
                Log.w(TAG, "Lock wallpaper unavailable", error)
                null
            }
            main.post {
                if (ticket != generation) return@post
                wallpaper = bitmap
                dirty = true
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        if (!canvas.isHardwareAccelerated) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
            return
        }
        if (dirty) rebuild()
        canvas.drawRenderNode(page)
        canvas.save()
        canvas.translate(0f, (height - barHeight()).toFloat())
        canvas.drawRenderNode(bar)
        canvas.restore()
    }

    private fun rebuild() {
        dirty = false
        val w = width
        val h = height

        blurred.setPosition(0, 0, w, h)
        blurred.setRenderEffect(RenderEffect.createBlurEffect(dp(PAGE_BLUR_DP), dp(PAGE_BLUR_DP), Shader.TileMode.CLAMP))
        blurred.beginRecording().also { recording ->
            wallpaper?.let { recording.drawBitmap(it, centerCrop(it), bitmapPaint) }
        }
        blurred.endRecording()

        page.setPosition(0, 0, w, h)
        page.beginRecording().also { recording ->
            recording.drawRenderNode(blurred)
            recording.drawRect(0f, 0f, w.toFloat(), h.toFloat(), gradientPaint)
        }
        page.endRecording()

        val barHeight = barHeight()
        val refraction = RuntimeShader(EDGE_REFRACTION).apply {
            setFloatUniform("size", w.toFloat(), barHeight.toFloat())
            setFloatUniform("dispersion", DISPERSION)
            setFloatUniform("flare", FLARE)
        }
        bar.setPosition(0, 0, w, barHeight)
        // Blur first, then refract the blurred slice (createChainEffect applies the inner effect first).
        bar.setRenderEffect(RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(refraction, "content"),
            RenderEffect.createBlurEffect(dp(BAR_BLUR_DP), dp(BAR_BLUR_DP), Shader.TileMode.CLAMP),
        ))
        bar.beginRecording().also { recording ->
            recording.translate(0f, -(h - barHeight).toFloat())
            recording.drawRenderNode(page)
        }
        bar.endRecording()
    }

    private fun centerCrop(bitmap: Bitmap): Matrix {
        val scale = maxOf(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - bitmap.width * scale) / 2f, (height - bitmap.height * scale) / 2f)
        }
    }

    private fun barHeight() = dp(BAR_HEIGHT_DP).toInt().coerceAtMost(height)

    private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

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
