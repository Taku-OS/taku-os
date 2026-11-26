/* Copyright 2026 Taku OS. SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.taku.island

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * SystemUI owns geometry and touch; bounded remote cards may supply their own chrome. Figma 297:415:
 * a 40 dp-radius card, 8 dp from the screen edges, filled with content/900 (black at 92 %) over a
 * 24 dp backdrop blur (Backdrop Blur/md) so the screen behind reads as a soft tone rather than
 * legible detail. A template's `backgroundColor` replaces the fill. The card covers the camera
 * cutout or sits below it only as the presenter decided ([coverRequested]); the keyboard never
 * changes that, it only bounds the card's height.
 */
internal class IslandCardWindow(context: Context, initial: IslandActivity, coverRequested: Boolean,
    private val command: (String, String?, String?) -> Unit, private val collapse: (String) -> Unit,
    private val covering: (Boolean) -> Unit,
    private val changed: () -> Unit) {
    private val windowContext = context.createWindowContext(context.display,
        WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL, null)
    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private val density = windowContext.resources.displayMetrics.density
    private val metrics = wm.currentWindowMetrics
    private val topInset = metrics.windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
    private val cover = coverRequested
    private var activity = initial
    private val nativeAssistant = initial.nativeAssistant
    private val nativeView = if (nativeAssistant) IslandAssistantCard(windowContext, command, collapse) else null
    private val nativeProgress = initial.nativeProgressCard
    private val progressView = if (nativeProgress) IslandProgressCard(windowContext, command, collapse) else null
    private val nativeContent = nativeAssistant || nativeProgress
    /** Every other activity is drawn by the native template card. */
    private val templateView = if (nativeContent) null else IslandTemplateCard(windowContext, command, collapse)
    private val cardWidth = minOf(dp(420), metrics.bounds.width() - dp(16))
    private val fullCard = nativeContent || initial.payload.getJSONObject("expanded").optJSONObject("content")?.optString("layout") == "card"
    /** A ready full-card remote surface draws its own chrome; the shell then stays transparent. */
    private var remoteChrome = false
    private val root = FrameLayout(windowContext).apply {
        background = GradientDrawable().apply { setColor(IslandStyle.opaqueFill(initial)); cornerRadius = dp(IslandStyle.CORNER_DP).toFloat() }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(IslandStyle.CORNER_DP).toFloat())
            }
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private var blurListening = false
    private val blurListener = java.util.function.Consumer<Boolean> { if (attached) updateBackdrop() }
    private var host: IslandContentConnection? = null
    private var hostView: SurfaceView? = null
    private var hostIdentity: String? = null
    private var attached = false
    private var richReady = false
    private var templateGeneration = 0
    private var animation: ValueAnimator? = null
    private val region = Region()
    private var finalHeight = desiredHeight(initial)
    private var availableHeight = Int.MAX_VALUE
    private var heightCap = finalHeight
    private val params = WindowManager.LayoutParams(
        cardWidth, if (nativeContent) finalHeight else dp(48),
        WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL, flags(), PixelFormat.TRANSLUCENT,
    ).apply {
        title = "TakuIsland"
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = if (cover) dp(8) else topInset + dp(8)
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
    }
    private val insetsListener = ViewTreeObserver.OnComputeInternalInsetsListener { info ->
        val path = Path().apply { addRoundRect(RectF(0f, 0f, root.width.toFloat(), root.height.toFloat()),
            dp(40).toFloat(), dp(40).toFloat(), Path.Direction.CW) }
        region.setPath(path, Region(0, 0, root.width, root.height))
        info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION)
        info.touchableRegion.set(region)
    }

    init {
        root.addView(nativeView ?: progressView ?: templateView, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            // The keyboard bounds the card's height; it never moves the card off the cutout.
            if (attached) {
                val available = metrics.bounds.height() - insets.getInsets(WindowInsets.Type.ime()).bottom - params.y - dp(8)
                availableHeight = available
                if (available < dp(120)) root.post { collapse("ime_no_space") }
                else {
                    heightCap = minOf(finalHeight, available)
                    params.height = minOf(params.height, heightCap)
                    wm.updateViewLayout(root, params)
                    changed()
                }
            }
            insets
        }
        update(initial)
    }

    private fun flags(): Int = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    fun show() {
        root.viewTreeObserver.addOnComputeInternalInsetsListener(insetsListener)
        wm.addView(root, params)
        attached = true
        covering(cover)
        updateBackdrop()
        wm.addCrossWindowBlurEnabledListener(windowContext.mainExecutor, blurListener)
        blurListening = true
        // Native content is complete before addView: no template/remote-surface transition.
        if (nativeContent) return
        animation = ValueAnimator.ofInt(dp(48), finalHeight).apply {
            duration = 220
            addUpdateListener {
                if (attached) {
                    params.height = minOf(it.animatedValue as Int, heightCap)
                    wm.updateViewLayout(root, params)
                    changed()
                }
            }
            start()
        }
    }

    /** Content snapshots update views; they do not restart the shell's geometry animation. */
    fun update(next: IslandActivity) {
        val fillChanged = IslandStyle.cardFill(next) != IslandStyle.cardFill(activity)
        activity = next
        if (fillChanged && attached) updateBackdrop()
        val template = templateView
        when {
            nativeView != null -> nativeView.update(next)
            progressView != null -> progressView.update(next)
            else -> template!!.update(next)
        }
        val wantedHeight = desiredHeight(next)
        if (wantedHeight != finalHeight) {
            finalHeight = wantedHeight
            heightCap = minOf(finalHeight, availableHeight)
            if (attached) {
                animation?.cancel()
                params.height = heightCap
                wm.updateViewLayout(root, params)
                changed()
            }
        }
        if (template == null) return
        val expanded = next.payload.getJSONObject("expanded")
        val identity = next.contentService?.flattenToString()?.plus(":" + expanded.optJSONObject("content")?.optString("token"))
        if (identity != hostIdentity) {
            host?.close()
            host = null
            hostView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
            hostView = null
            hostIdentity = identity
            richReady = false
            template.showTemplateText(true)
            template.visibility = View.VISIBLE
            if (identity != null) {
                val surface = SurfaceView(windowContext).apply {
                    alpha = 0f
                    holder.setFormat(PixelFormat.TRANSLUCENT)
                }
                (if (fullCard) root else template.bodyHost).addView(surface, FrameLayout.LayoutParams(-1, -1))
                hostView = surface
                host = IslandContentConnection(windowContext, next, surface) { ready ->
                    richReady = ready
                    if (fullCard) { remoteChrome = ready; updateBackdrop() }
                    if (fullCard) template.visibility = if (ready) View.INVISIBLE else View.VISIBLE
                    else template.showTemplateText(!ready)
                    surface.alpha = if (ready) 1f else 0f
                }.also { it.start() }
            }
        }
    }

    /**
     * The card's fill over a 24 dp backdrop blur of whatever is behind it; an opaque fill when
     * compositor blur is off, so text and widgets behind never show through sharply.
     */
    private fun updateBackdrop() {
        val corner = dp(IslandStyle.CORNER_DP).toFloat()
        if (remoteChrome) { root.background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = corner }; return }
        val blurred = if (wm.isCrossWindowBlurEnabled) runCatching {
            root.viewRootImpl.createBackgroundBlurDrawable().apply {
                setBlurRadius(dp(IslandStyle.BLUR_DP)); setCornerRadius(corner); setColor(IslandStyle.cardFill(activity))
            }
        }.getOrNull() else null
        root.background = blurred ?: GradientDrawable().apply { setColor(IslandStyle.opaqueFill(activity)); cornerRadius = corner }
        backdropBlurred = blurred != null
    }
    private var backdropBlurred = false

    fun state(): JSONObject = JSONObject().put("key", activity.key).put("windowType", params.type)
        .put("cover", cover).put("backdropBlur", backdropBlurred)
        .put("fill", "#%08X".format(IslandStyle.cardFill(activity))).put("focusable", params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0).put("layout", if (nativeAssistant) "native-assistant" else if (nativeProgress) "native-progress" else if (fullCard) "card" else "body").put("richReady", richReady)
        .put("renderer", if (richReady) "remote-surface" else "systemui-native").put("nativeReady", attached && !richReady)
        .put("assistant", nativeView?.state() ?: JSONObject.NULL)
        .put("template", templateView?.state() ?: JSONObject.NULL)
        .put("progress", progressView?.state() ?: JSONObject.NULL)
        .put("width", root.width).put("height", root.height).put("top", params.y)
        .put("touchRegion", region.toString()).put("outsideTapCollapses", false)

    fun close() {
        if (blurListening) { wm.removeCrossWindowBlurEnabledListener(blurListener); blurListening = false }
        covering(false)
        templateGeneration++
        animation?.cancel()
        animation = null
        host?.close()
        host = null
        root.viewTreeObserver.removeOnComputeInternalInsetsListener(insetsListener)
        if (attached) { attached = false; wm.removeViewImmediate(root) }
    }

    private fun desiredHeight(item: IslandActivity): Int {
        val cap = metrics.bounds.height() / 3
        if (progressView != null) return minOf(progressView.desiredHeight(item, cardWidth), cap)
        if (nativeView != null) return minOf(nativeView.desiredHeight(item, cardWidth), cap)
        // A remote full card asks for its height; everything else follows its native content.
        val requested = if (fullCard) item.payload.getJSONObject("expanded").optJSONObject("content")?.optInt("heightDp", 300) else null
        return minOf(requested?.let { dp(it.coerceIn(96, 340)) } ?: templateView!!.heightFor(cardWidth), cap)
    }
    private fun dp(value: Int) = (value * density).toInt()
}
